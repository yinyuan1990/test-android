package com.fz.srttest

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import java.nio.ByteBuffer

/**
 * 一次推流（照 silu 链路）：
 * - 自带摄像头：Camera2 → 编码器输入 Surface（+ 可选屏幕预览）→ H.264 → MPEG-TS → SRT
 * - OTG：libuvc 帧回调 → FrameGate → 一次拷进编码器 → H.264 → MPEG-TS → SRT
 * SRT 连接与 TS 封装在整个会话内不断；OTG 拔插后按新尺寸重建编码器。
 */
class StreamSession(
    private val context: Context,
    private val cfgIn: StreamConfig,
    private val onError: (String) -> Unit,
) : UvcSource.Callback {
    companion object {
        private const val TAG = "StreamSession"
    }

    private val muxer = TsMuxer()
    @Volatile private var encoder: H264Encoder? = null
    private val sender = SrtSender(cfgIn, onNeedKeyFrame = { encoder?.requestKeyFrame() })
    private var camera: CameraSource? = null
    private var uvc: UvcSource? = null
    private val encoderLock = Any()
    private var startMs = 0L

    /** 实际推流配置（自带摄像头按可用尺寸修正，OTG 按协商结果修正） */
    @Volatile var cfg: StreamConfig = cfgIn
        private set

    /** 屏幕预览要固定的尺寸：自带摄像头 ≤1080p（Camera2 预览上限，与推流尺寸无关）；OTG = 协商尺寸 */
    @Volatile private var cameraPreviewSize: Pair<Int, Int>? = null
    val previewSize: Pair<Int, Int> get() = cameraPreviewSize ?: (cfg.width to cfg.height)

    @Volatile private var ticking = false
    private var tickThread: Thread? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetDesc = ""

    /** @throws IllegalStateException 编码器无法配置 / 没有摄像头 */
    fun start() {
        startMs = System.currentTimeMillis()
        val c = cfgIn
        EventLog.i(TAG, "开始推流 源=${if (c.isUvc) "OTG" else "自带摄像头"} ${c.host}:${c.port}/${c.path}" +
                " 账号=${c.user.ifEmpty { "无" }} ${c.width}x${c.height}@${c.fps} ${c.bitrateKbps}kbps" +
                " ${if (c.bitrateMode == 2) "CBR" else "VBR"} QP≤${c.qpMax} profile=${c.profile} GOP=${c.keyIntervalSec}s" +
                " latency=${c.latencyMs}ms 关防抖=${c.disableStabilization} 色彩=${c.colorRange} NV12=${c.uvcNv12}")
        sender.start()
        startTicker()
        watchNetwork()
        if (cfgIn.isUvc) {
            uvc = UvcSource(context, cfgIn, this).also { it.start() }
        } else {
            val id = CameraSource.backCameraId(context) ?: throw IllegalStateException("没有可用的摄像头")
            val size = CameraSource.chooseSize(context, id, cfgIn.width, cfgIn.height)
            cfg = cfgIn.copy(width = size.width, height = size.height)
            val p = CameraSource.choosePreviewSize(context, id, size.width, size.height)
            cameraPreviewSize = p.width to p.height
            val enc = newEncoder(cfg, surfaceInput = true)
            camera = CameraSource(context).also {
                it.open(id, cfg, enc.inputSurface!!) { msg ->
                    EventLog.w(TAG, "摄像头错误: $msg")
                    onError(msg)
                }
            }
        }
    }

    fun stop() {
        ticking = false
        tickThread?.interrupt()
        netCallback?.let { cb ->
            try { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } catch (_: Throwable) {}
        }
        netCallback = null
        camera?.close()
        camera = null
        uvc?.stop()
        uvc = null
        synchronized(encoderLock) {
            encoder?.stop()
            encoder = null
        }
        sender.stop()
        LogUploader.uploadAsync(context, "停止推流")
    }

    private fun newEncoder(c: StreamConfig, surfaceInput: Boolean): H264Encoder {
        val enc = H264Encoder(c, surfaceInput) { data, ptsUs, isKey ->
            sender.offer(muxer.mux(data, ptsUs, isKey), isKey)
        }
        enc.start()
        encoder = enc
        EventLog.i(TAG, "编码器启动 ${enc.codecName} 降级档=${enc.tierName} ${c.width}x${c.height}" +
                " 输入=${if (surfaceInput) "Surface" else "YUV"}")
        return enc
    }

    /** 记默认网络的切换/断开/类型与上行估计变化（WiFi↔移动网切换会直接打断 SRT） */
    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = EventLog.i(TAG, "网络可用 $network")

            override fun onLost(network: Network) = EventLog.w(TAG, "网络断开 $network")

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "其它"
                }
                val signal = if (Build.VERSION.SDK_INT >= 29 && caps.signalStrength != Int.MIN_VALUE) {
                    " 信号 ${caps.signalStrength}dBm"
                } else ""
                // 上行估计变化很频繁，按 1Mbps 取整后才算变化，避免刷屏
                val desc = "$type 估计上行 ${caps.linkUpstreamBandwidthKbps / 1000}Mbps" +
                        " 下行 ${caps.linkDownstreamBandwidthKbps / 1000}Mbps$signal"
                if (desc != lastNetDesc) {
                    lastNetDesc = desc
                    EventLog.i(TAG, "网络 $network $desc")
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (t: Throwable) {
            EventLog.w(TAG, "无法监听网络: ${t.message}")
        }
    }

    /** 每 2s 记一行统计（界面不可见/锁屏时也记） */
    private fun startTicker() {
        ticking = true
        tickThread = Thread({
            var n = 0
            while (ticking) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                if (!ticking) break
                try { EventLog.i(TAG, tickLine()) } catch (_: Throwable) {}
                if (++n % 15 == 0) LogUploader.uploadAsync(context, "推流中定时上传")
            }
        }, "stats-log").apply { isDaemon = true; start() }
    }

    private fun tickLine(): String = buildString {
        val enc = encoder
        append("统计 ${sender.state}")
        append(" | 编码 ${enc?.statKbps ?: 0}kbps ${enc?.statFps ?: 0}fps QP ${enc?.statQp ?: -1} 关键帧 ${enc?.keyFrames ?: 0}")
        val u = uvc
        if (u != null) {
            append(" | OTG 采集 ${u.captureFps}fps 入口丢帧 ${u.gateDrops} 坏帧 ${u.badFrames} 编码器忙丢帧 ${enc?.inputFullDrops ?: 0}")
        }
        append(" | SRT ${sender.statsLine()} 断线丢帧 ${sender.droppedFrames} 重连 ${sender.reconnects}")
        batteryTempC()?.let { append(" | 电池 ${"%.1f".format(it)}℃") }
    }

    // ---------- OTG 回调 ----------

    override fun onUvcStarted(width: Int, height: Int, fps: Int) {
        EventLog.i(TAG, "OTG 出流 ${width}x${height}@${fps} ${uvc?.deviceName ?: ""} ${uvc?.formatDesc ?: ""}")
        synchronized(encoderLock) {
            val cur = encoder
            if (cur != null && cfg.width == width && cfg.height == height) return
            cur?.stop()
            cfg = cfgIn.copy(width = width, height = height)
            try {
                newEncoder(cfg, surfaceInput = false)
            } catch (t: Throwable) {
                encoder = null
                EventLog.w(TAG, "编码器启动失败: ${t.message ?: t}")
                onError(t.message ?: "编码器启动失败")
            }
        }
    }

    override fun onUvcFrame(frame: ByteBuffer, width: Int, height: Int, ptsUs: Long) {
        val enc = encoder ?: return
        if (width != cfg.width || height != cfg.height) return
        enc.feedYuv420sp(frame, width, height, cfgIn.uvcNv12, ptsUs)
    }

    override fun onUvcStopped(reason: String) {
        EventLog.w(TAG, "OTG 停止: $reason")
        onError(reason)
    }

    override fun onUvcError(msg: String) {
        EventLog.w(TAG, "OTG 错误: $msg")
        onError(msg)
    }

    // ---------- 统计 ----------

    /** 电池温度（℃），读系统粘性广播；读不到 null */
    private fun batteryTempC(): Float? = try {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t == Int.MIN_VALUE) null else t / 10f
    } catch (_: Throwable) { null }

    fun statsText(): String {
        val enc = encoder
        val c = cfg
        val sec = ((System.currentTimeMillis() - startMs) / 1000).toInt()
        val qp = enc?.statQp ?: -1
        val temp = batteryTempC()
        return buildString {
            append("状态: ${sender.state}   已推 ${sec / 60}:${"%02d".format(sec % 60)}" +
                    "   电池 ${temp?.let { "%.1f℃".format(it) } ?: "?"}\n")
            if (sender.lastError.isNotEmpty()) append("上次断线: ${sender.lastError}\n")
            if (sec > 5 && (enc == null || enc.statFps == 0)) {
                append("⚠ 编码器没有输出：" + (if (uvc != null) "外接摄像头未出帧（插好/允许USB访问/看下方 OTG 行）"
                        else "摄像头未出帧（看下方摄像头行的预览状态）") + "，服务器收不到画面\n")
            }
            val u = uvc
            if (u != null) {
                append("OTG: ${u.deviceName}   ${u.formatDesc}   采集 ${u.captureFps}fps" +
                        "   入口丢帧 ${u.gateDrops}   坏帧 ${u.badFrames}   预览 ${u.previewDesc}\n")
                append("     编码器输入 ${enc?.inputLayout ?: "-"}   编码器忙丢帧 ${enc?.inputFullDrops ?: 0}" +
                        "   色度按 ${if (c.uvcNv12) "NV12" else "NV21"}\n")
            } else {
                val cam = camera
                append("摄像头: AE帧率 ${cam?.fpsRangeDesc}   防抖 ${cam?.stabilizationDesc}   ${cam?.previewDesc}\n")
            }
            append("编码器: ${enc?.codecName}   降级档: ${enc?.tierName}   ${enc?.outputFormatDesc}" +
                    "   ${c.width}x${c.height}@${c.fps}\n")
            append("编码: ${enc?.statKbps} kbps / 目标 ${c.bitrateKbps}   ${enc?.statFps} fps" +
                    "   平均QP ${if (qp >= 0) qp.toString() else "不支持(需Android13+)"}" +
                    "   关键帧 ${enc?.keyFrames}\n")
            append("SRT: 发送 ${"%.2f".format(sender.sendMbps)} Mbps   RTT ${"%.0f".format(sender.rttMs)} ms" +
                    "   发送缓冲 ${sender.sndBufMs} ms（持续变大=上行不够）\n")
            append("     丢包 ${sender.lossTotal}   重传 ${sender.retransTotal}" +
                    "   迟到丢弃 ${sender.sndDropTotal}   断线丢帧 ${sender.droppedFrames}" +
                    "   重连 ${sender.reconnects}")
            if (LogUploader.lastResult.isNotEmpty()) append("\n日志: ${LogUploader.lastResult}")
        }
    }
}
