package com.fz.srttest

import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.SockStatus
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        private const val SLOW_SEND_MS = 300L
        private const val NO_FRAME_WARN_MS = 2000L
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
    /** SRT 估计的链路带宽 */
    @Volatile var bandwidthMbps = 0.0
        private set
    /** 已发出未确认的包数 */
    @Volatile var flightPkts = 0
        private set
    /** 最近一次断线/连接失败原因（带时间，重连成功后保留，便于事后看） */
    @Volatile var lastError: String = ""
        private set

    @Volatile private var connectedAtMs = 0L
    @Volatile private var lastOfferMs = 0L
    @Volatile private var noFrameWarned = false
    /** 连接线程主动关 socket 时的原因，给正卡在 send 里的编码线程记日志用 */
    @Volatile private var closeReason: String? = null
    private var waitKeySinceMs = 0L
    private var waitKeyDrops = 0
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun start() {
        running = true
        thread = Thread({ connectLoop() }, "srt-conn").apply { start() }
    }

    fun stop() {
        EventLog.i(TAG, "停止推流（共重连 $reconnects 次，断线丢帧 $droppedFrames）")
        running = false
        thread?.interrupt()
        synchronized(lock) { closeSocketLocked() }
        state = "已停止"
    }

    /** 编码线程调用：同步发送一帧 TS（断线中直接丢弃） */
    fun offer(ts: ByteArray, isKey: Boolean) {
        if (!running) return
        val now = System.currentTimeMillis()
        if (noFrameWarned) {
            EventLog.i(TAG, "帧恢复送来（中断 ${now - lastOfferMs}ms）")
            noFrameWarned = false
        }
        lastOfferMs = now
        synchronized(lock) {
            val s = socket
            if (s == null) {
                droppedFrames++
                return
            }
            if (waitKey && !isKey) {
                droppedFrames++
                waitKeyDrops++
                return
            }
            if (waitKey) {
                EventLog.i(TAG, "连上后首个关键帧已发：等了 ${now - waitKeySinceMs}ms，期间丢 $waitKeyDrops 帧")
                waitKey = false
            }
            val t0 = System.currentTimeMillis()
            try {
                var off = 0
                while (off < ts.size) {
                    val end = minOf(off + CHUNK, ts.size)
                    s.send(ts.copyOfRange(off, end))
                    off = end
                }
                val cost = System.currentTimeMillis() - t0
                if (cost > SLOW_SEND_MS) {
                    EventLog.w(TAG, "发送阻塞 ${cost}ms（帧 ${ts.size / 1024}KB${if (isKey) " 关键帧" else ""}）" +
                            " 发送缓冲 ${sndBufMs}ms 链路估计 ${"%.1f".format(bandwidthMbps)}Mbps")
                }
            } catch (t: Throwable) {
                val err = t.message ?: t.toString()
                val sock = try { s.sockState.name } catch (_: Throwable) { "?" }
                val cause = closeReason?.let { "$it；" } ?: ""
                onDisconnected(s, "${cause}发送失败: $err（socket=$sock，发送耗时 ${System.currentTimeMillis() - t0}ms，" +
                        "帧 ${ts.size / 1024}KB）")
            }
        }
    }

    /** 记一次断线并清掉当前 socket（同一个 socket 只记一次） */
    private fun onDisconnected(s: SrtSocket, reason: String) {
        synchronized(lock) {
            if (socket !== s) return
            val alive = (System.currentTimeMillis() - connectedAtMs) / 1000.0
            readStats(s)
            EventLog.w(TAG, "断线 $reason；本次连接存活 ${"%.1f".format(alive)}s；断前统计 ${statsLine()}")
            lastError = "${timeFmt.format(Date())} $reason"
            state = "连接断开，重连中…"
            closeSocketLocked()
            reconnects++
        }
    }

    fun statsLine(): String =
        "发送 ${"%.2f".format(sendMbps)}Mbps 链路估计 ${"%.1f".format(bandwidthMbps)}Mbps RTT ${"%.0f".format(rttMs)}ms" +
                " 发送缓冲 ${sndBufMs}ms 在途 $flightPkts 包 丢包 $lossTotal 重传 $retransTotal 迟到丢弃 $sndDropTotal"

    private fun connectLoop() {
        try { Srt.startUp() } catch (t: Throwable) {
            state = "SRT 库加载失败"
            lastError = t.message ?: t.toString()
            EventLog.w(TAG, "SRT 库加载失败: $lastError")
            return
        }
        while (running) {
            val s = socket
            if (s == null) {
                if (!connect()) sleepQuietly(RECONNECT_DELAY_MS)
                continue
            }
            readStats(s)
            checkSocket(s)
            sleepQuietly(1000)
        }
    }

    /** 不等下次发送失败，主动发现 socket 已断（比如服务器关了连接、而编码器正好没出帧） */
    private fun checkSocket(s: SrtSocket) {
        val st = try { s.sockState } catch (_: Throwable) { null }
        if (st == SockStatus.BROKEN || st == SockStatus.CLOSED || st == SockStatus.NONEXIST) {
            // 发送线程可能正卡在 send 里拿着锁：先关 socket 让它抛出来，再记断线
            val reason = "检测到 socket 状态 $st"
            closeReason = reason
            try { s.close() } catch (_: Throwable) {}
            onDisconnected(s, reason)
            return
        }
        val gap = System.currentTimeMillis() - maxOf(lastOfferMs, connectedAtMs)
        if (!noFrameWarned && gap > NO_FRAME_WARN_MS) {
            noFrameWarned = true
            EventLog.w(TAG, "已连接但 ${gap}ms 没有帧送来发送（编码器/摄像头没出帧，或编码线程卡在上一次发送里）")
        }
    }

    private fun connect(): Boolean {
        state = "连接 ${cfg.host}:${cfg.port} …"
        val t0 = System.currentTimeMillis()
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
                waitKeySinceMs = System.currentTimeMillis()
                waitKeyDrops = 0
                connectedAtMs = waitKeySinceMs
                noFrameWarned = false
                closeReason = null
            }
            onNeedKeyFrame()   // 新连接从关键帧开始，观看端立刻能解码
            state = "推流中"
            EventLog.i(TAG, "已连接 ${cfg.host}:${cfg.port} 路径 ${cfg.path} latency=${cfg.latencyMs}ms" +
                    " 握手 ${System.currentTimeMillis() - t0}ms（第 ${reconnects} 次重连）")
            true
        } catch (t: Throwable) {
            val err = t.message ?: t.toString()
            lastError = "${timeFmt.format(Date())} 连接失败: $err"
            state = "连接失败：$err"
            EventLog.w(TAG, "连接失败 ${cfg.host}:${cfg.port}: $err（耗时 ${System.currentTimeMillis() - t0}ms）")
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
            bandwidthMbps = st.mbpsBandwidth
            flightPkts = st.pktFlightSize
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
