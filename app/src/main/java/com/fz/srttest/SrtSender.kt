package com.fz.srttest

import android.util.Log
import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket

/**
 * SRT（live 模式）发送 MPEG-TS —— 照竞品 silu 的 `SrtMpegTsPublisher`：
 *
 * - 编码器每出一帧就在**编码线程里同步发送**，不排队、不主动丢帧；网络跟不上时由 SRT 按 latency
 *   丢迟到包、由编码器输出背压自然限速（与 silu 相同，码率不随网络调）。
 * - 每 1316 字节先拷成独立小数组再 `send(byte[])`。不用 `send(array, offset, size)`：srtdroid 的 native
 *   实现每次都对**整个数组** Get/ReleaseByteArrayElements，一帧切 150 块就把整帧复制 300 遍。
 * - socket 选项同 silu：TRANSTYPE=LIVE、SENDER、TSBPDMODE、LATENCY/PEERLATENCY/RCVLATENCY、
 *   PAYLOADSIZE=1316、CONNTIMEO=max(1000, latency×20)。
 * - 连接/重连在独立线程，断线期间丢帧，重连成功后要一个 IDR 再继续。
 */
class SrtSender(
    private val cfg: StreamConfig,
    private val onNeedKeyFrame: () -> Unit,
) {
    companion object {
        private const val TAG = "SrtSender"
        /** live 模式单个 SRT 报文载荷 = 7 个 TS 包 */
        private const val CHUNK = TsMuxer.PACKET_SIZE * 7
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private val lock = Any()
    @Volatile private var socket: SrtSocket? = null
    @Volatile private var running = false
    @Volatile private var waitKey = true
    private var thread: Thread? = null

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
    /** SRT 发送缓冲里还没发出去的数据（毫秒）：持续变大 = 上行带宽不够 */
    @Volatile var sndBufMs = 0
        private set
    @Volatile var lastError: String = ""
        private set

    fun start() {
        running = true
        thread = Thread({ connectLoop() }, "srt-conn").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        synchronized(lock) { closeSocketLocked() }
        state = "已停止"
    }

    /** 编码线程调用：同步发送一帧 TS（断线中直接丢弃） */
    fun offer(ts: ByteArray, isKey: Boolean) {
        if (!running) return
        synchronized(lock) {
            val s = socket
            if (s == null) {
                droppedFrames++
                return
            }
            if (waitKey && !isKey) {
                droppedFrames++
                return
            }
            waitKey = false
            try {
                var off = 0
                while (off < ts.size) {
                    val end = minOf(off + CHUNK, ts.size)
                    s.send(ts.copyOfRange(off, end))
                    off = end
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                Log.w(TAG, "发送失败，重连: $lastError")
                state = "连接断开，重连中…"
                closeSocketLocked()
                reconnects++
            }
        }
    }

    private fun connectLoop() {
        try { Srt.startUp() } catch (t: Throwable) {
            state = "SRT 库加载失败"
            lastError = t.message ?: t.toString()
            return
        }
        while (running) {
            val s = socket
            if (s == null) {
                if (!connect()) sleepQuietly(RECONNECT_DELAY_MS)
                continue
            }
            readStats(s)
            sleepQuietly(1000)
        }
    }

    private fun connect(): Boolean {
        state = "连接 ${cfg.host}:${cfg.port} …"
        val s = SrtSocket()
        return try {
            // TRANSTYPE 最先设：它会把其它选项重置为 live 模式默认值
            s.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
            s.setSockFlag(SockOpt.STREAMID, cfg.publishStreamId)
            s.setSockFlag(SockOpt.SENDER, true)
            s.setSockFlag(SockOpt.TSBPDMODE, true)
            s.setSockFlag(SockOpt.LATENCY, cfg.latencyMs)
            s.setSockFlag(SockOpt.PEERLATENCY, cfg.latencyMs)
            s.setSockFlag(SockOpt.RCVLATENCY, cfg.latencyMs)
            s.setSockFlag(SockOpt.PAYLOADSIZE, CHUNK)
            s.setSockFlag(SockOpt.CONNTIMEO, maxOf(1000, cfg.latencyMs * 20))
            s.connect(cfg.host, cfg.port)
            synchronized(lock) {
                if (!running) {
                    try { s.close() } catch (_: Throwable) {}
                    return false
                }
                socket = s
                waitKey = true
            }
            onNeedKeyFrame()   // 新连接从关键帧开始，观看端立刻能解码
            state = "推流中"
            lastError = ""
            Log.i(TAG, "已连接 ${cfg.host}:${cfg.port} streamid=publish:${cfg.path} latency=${cfg.latencyMs}ms")
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
            state = "连接失败：$lastError"
            Log.w(TAG, "连接失败: $lastError")
            try { s.close() } catch (_: Throwable) {}
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
            sndBufMs = st.msSndBuf
        } catch (_: Throwable) {}
    }

    private fun closeSocketLocked() {
        val s = socket
        socket = null
        try { s?.close() } catch (_: Throwable) {}
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
