package com.fz.srttest

import android.util.Log
import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * SRT（live 模式）发送 MPEG-TS。
 *
 * 与 silu 一致：码率不随网络调，丢包靠 SRT 在 latency 窗口内重传。
 * 唯一的自保护：发送积压超过 [maxQueueMs] 时清空队列、等下一个关键帧再发（否则延迟无限增长），
 * 并通过 [onNeedKeyFrame] 让编码器马上出一个 IDR。
 */
class SrtSender(
    private val cfg: StreamConfig,
    private val onNeedKeyFrame: () -> Unit,
    private val maxQueueMs: Int = 2000,
) {
    companion object {
        private const val TAG = "SrtSender"
        /** live 模式单个 SRT 报文的最大载荷 = 7 个 TS 包 */
        private const val CHUNK = TsMuxer.PACKET_SIZE * 7
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private class Item(val data: ByteArray, val isKey: Boolean)

    private val queue = LinkedBlockingDeque<Item>()
    @Volatile private var queuedBytes = 0L
    @Volatile private var running = false
    @Volatile private var socket: SrtSocket? = null
    private var thread: Thread? = null
    @Volatile private var waitKey = true

    // —— 状态/统计（UI 线程读）——
    @Volatile var state: String = "未开始"
        private set
    @Volatile var droppedFrames = 0
        private set
    @Volatile var reconnects = 0
        private set
    @Volatile var sendMbps = 0.0
        private set
    @Volatile var rttMs = 0.0
        private set
    @Volatile var lossTotal = 0
        private set
    @Volatile var retransTotal = 0
        private set
    @Volatile var sndDropTotal = 0
        private set
    @Volatile var lastError: String = ""
        private set

    fun start() {
        running = true
        thread = Thread({ loop() }, "srt-send").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        closeSocket()
        queue.clear()
        queuedBytes = 0
        state = "已停止"
    }

    /** 编码线程调用：放入一帧 TS 数据（不阻塞） */
    fun offer(ts: ByteArray, isKey: Boolean) {
        if (!running) return
        if (waitKey && !isKey) {
            droppedFrames++
            return
        }
        waitKey = false
        val maxBytes = cfg.bitrateKbps.toLong() * 1000 / 8 * maxQueueMs / 1000
        if (queuedBytes > maxBytes) {
            // 网络跟不上：丢掉积压，从下一个关键帧重新开始
            droppedFrames += queue.size
            queue.clear()
            queuedBytes = 0
            waitKey = true
            onNeedKeyFrame()
            if (!isKey) {
                droppedFrames++
                return
            }
            waitKey = false
        }
        queue.offer(Item(ts, isKey))
        queuedBytes += ts.size
    }

    /** 当前积压（毫秒，按目标码率折算） */
    val queuedMs: Int
        get() = (queuedBytes * 8 / cfg.bitrateKbps.coerceAtLeast(1)).toInt()

    private fun loop() {
        try { Srt.startUp() } catch (t: Throwable) {
            state = "SRT 库加载失败"
            lastError = t.message ?: t.toString()
            return
        }
        var lastStatMs = 0L
        while (running) {
            val s = socket
            if (s == null) {
                if (!connect()) {
                    sleepQuietly(RECONNECT_DELAY_MS)
                }
                continue
            }
            val item = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
            if (item != null) {
                queuedBytes -= item.data.size
                try {
                    var off = 0
                    while (off < item.data.size) {
                        val n = minOf(CHUNK, item.data.size - off)
                        s.send(item.data, off, n)
                        off += n
                    }
                } catch (t: Throwable) {
                    lastError = t.message ?: t.toString()
                    Log.w(TAG, "发送失败，重连: $lastError")
                    state = "连接断开，重连中…"
                    closeSocket()
                    reconnects++
                    continue
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastStatMs >= 1000) {
                lastStatMs = now
                readStats(s)
            }
        }
    }

    private fun connect(): Boolean {
        state = "连接 ${cfg.host}:${cfg.port} …"
        return try {
            val s = SrtSocket()
            // TRANSTYPE 必须最先设：它会把其它选项重置为该模式的默认值
            s.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
            s.setSockFlag(SockOpt.PEERLATENCY, cfg.latencyMs)
            s.setSockFlag(SockOpt.RCVLATENCY, cfg.latencyMs)
            s.setSockFlag(SockOpt.PAYLOADSIZE, CHUNK)
            s.setSockFlag(SockOpt.CONNTIMEO, 5000)
            s.setSockFlag(SockOpt.STREAMID, cfg.publishStreamId)
            s.connect(cfg.host, cfg.port)
            socket = s
            // 新连接从关键帧开始，观看端立刻能解码
            queue.clear()
            queuedBytes = 0
            waitKey = true
            onNeedKeyFrame()
            state = "推流中"
            lastError = ""
            Log.i(TAG, "已连接 ${cfg.host}:${cfg.port} streamid=publish:${cfg.path}")
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
            state = "连接失败：$lastError"
            Log.w(TAG, "连接失败: $lastError")
            closeSocket()
            false
        }
    }

    private fun readStats(s: SrtSocket) {
        try {
            val st = s.bstats(false)
            sendMbps = st.mbpsSendRate
            rttMs = st.msRTT
            lossTotal = st.pktSndLossTotal
            retransTotal = st.pktRetransTotal
            sndDropTotal = st.pktSndDropTotal
        } catch (_: Throwable) {}
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        try { s?.close() } catch (_: Throwable) {}
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
