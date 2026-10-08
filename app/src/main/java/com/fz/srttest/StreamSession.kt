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
    cfgStart: StreamConfig,
    private val onError: (String) -> Unit,
) : UvcSource.Callback {
    companion object {
        private const val TAG = "StreamSession"
        /** 网页可选的分辨率（与 App 下拉一致） */
        val RESOLUTIONS = listOf("3840x2160", "2560x1440", "1920x1080", "1440x1080", "1280x720", "1024x768", "640x480")
    }

    /** 用户/网页要的参数（分辨率可能被摄像头/OTG 协商修正，实际值见 [cfg]） */
    @Volatile private var cfgIn: StreamConfig = cfgStart
    private val muxer = TsMuxer()
    @Volatile private var encoder: H264Encoder? = null
    private val sender = SrtSender(cfgStart, onNeedKeyFrame = { encoder?.requestKeyFrame() })
    private var camera: CameraSource? = null
    private var cameraId: String? = null
    private var uvc: UvcSource? = null
    private val encoderLock = Any()
    private var startMs = 0L
    private val remote = RemoteControl(context, this)

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
        remote.start()
        if (cfgIn.isUvc) {
            uvc = UvcSource(context, cfgIn, this).also { it.start() }
        } else {
            val id = CameraSource.backCameraId(context) ?: throw IllegalStateException("没有可用的摄像头")
            cameraId = id
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
        remote.stop()
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
        lateinit var enc: H264Encoder
        enc = H264Encoder(c, surfaceInput) { data, ptsUs, isKey ->
            // 换分辨率时新旧编码器短暂并存：只收当前编码器的输出（muxer 不是线程安全的）
            if (encoder === enc) sender.offer(muxer.mux(data, ptsUs, isKey), isKey)
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

    // ---------- 网页远程控制 ----------

    /** 执行网页下发的命令（RemoteControl 线程调用） */
    fun applyRemote(c: RemoteControl.Command) {
        EventLog.i(TAG, "网页命令 v${c.ver}: 快门=${if (c.shutterNs > 0) "1/${1_000_000_000L / c.shutterNs}s" else "自动"}" +
                " 增益=${if (c.gain > 0) c.gain.toString() else "自动"} 变焦=${if (c.zoomX100 > 0) "%.2fx".format(c.zoomX100 / 100f) else "默认"}" +
                " 分辨率=${c.width}x${c.height} 码率=${c.bitrateKbps}")
        camera?.setExposure(c.shutterNs, c.gain)
        camera?.let { if (it.reqZoomX100 != c.zoomX100) it.setZoom(c.zoomX100) }
        uvc?.setExposure(c.shutterNs, c.gain)

        val base = cfgIn
        val sizeChanged = c.width > 0 && c.height > 0 && (c.width != base.width || c.height != base.height)
        val brChanged = c.bitrateKbps in 300..50000 && c.bitrateKbps != base.bitrateKbps
        if (!sizeChanged && !brChanged) return
        val newBase = base.copy(
            width = if (sizeChanged) c.width else base.width,
            height = if (sizeChanged) c.height else base.height,
            bitrateKbps = if (brChanged) c.bitrateKbps else base.bitrateKbps,
        )
        cfgIn = newBase
        newBase.save(context)
        if (sizeChanged) {
            changeResolution(newBase)
        } else {
            cfg = cfg.copy(bitrateKbps = newBase.bitrateKbps)
            encoder?.setBitrate(newBase.bitrateKbps)
        }
    }

    /** 自带摄像头：新编码器 → 摄像头会话换到新 Surface → 停旧编码器；OTG：同设备重新协商。SRT 不断 */
    private fun changeResolution(newBase: StreamConfig) {
        uvc?.let {
            it.changeConfig(newBase)
            return
        }
        val cam = camera ?: return
        val id = cameraId ?: return
        synchronized(encoderLock) {
            val size = CameraSource.chooseSize(context, id, newBase.width, newBase.height)
            val newCfg = newBase.copy(width = size.width, height = size.height)
            val old = encoder
            val enc = try {
                newEncoder(newCfg, surfaceInput = true)
            } catch (t: Throwable) {
                EventLog.w(TAG, "换分辨率失败（编码器）: ${t.message ?: t}")
                return
            }
            cfg = newCfg
            val p = CameraSource.choosePreviewSize(context, id, size.width, size.height)
            cameraPreviewSize = p.width to p.height
            cam.switchEncoder(newCfg, enc.inputSurface!!) { old?.stop() }
            EventLog.i(TAG, "换分辨率 → ${size.width}x${size.height} ${newCfg.bitrateKbps}kbps（SRT 不断）")
        }
    }

    /** 上报给网页的状态：能力范围 + 当前值 + 统计 */
    fun remoteState(): org.json.JSONObject {
        val o = org.json.JSONObject()
        val c = cfg
        val enc = encoder
        o.put("model", Build.MODEL)
        o.put("android", Build.VERSION.RELEASE)
        o.put("source", if (uvc != null) "uvc" else "camera")
        o.put("width", c.width).put("height", c.height).put("fps", c.fps)
        o.put("reqWidth", cfgIn.width).put("reqHeight", cfgIn.height)
        o.put("bitrateKbps", cfgIn.bitrateKbps)
        o.put("encKbps", enc?.statKbps ?: 0).put("encFps", enc?.statFps ?: 0).put("qp", enc?.statQp ?: -1)
        o.put("srtState", sender.state.toString())
        o.put("sndBufMs", sender.sndBufMs).put("rttMs", sender.rttMs.toInt())
        o.put("sendMbps", sender.sendMbps.toDouble())
        o.put("sndDrop", sender.sndDropTotal).put("reconnects", sender.reconnects)
        batteryTempC()?.let { o.put("tempC", it.toDouble()) }
        o.put("uptimeSec", (System.currentTimeMillis() - startMs) / 1000)
        val sizes = org.json.JSONArray()
        val u = uvc
        val cam = camera
        if (u != null) {
            o.put("device", u.deviceName)
            o.put("gainLabel", "UVC增益")
            o.put("manualSupported", UvcControls.available && u.exposureRangeNs != null)
            u.exposureRangeNs?.let { o.put("expMinNs", it.first).put("expMaxNs", it.second) }
            u.gainRange?.let { o.put("gainMin", it.first).put("gainMax", it.second) }
            o.put("curExpNs", u.curExposureNs).put("curGain", u.curGain)
            o.put("reqShutterNs", u.reqShutterNs).put("reqGain", u.reqGain)
            o.put("exposureDesc", u.exposureDesc)
            (if (u.supportedSizes.isNotEmpty()) u.supportedSizes else RESOLUTIONS).forEach { sizes.put(it) }
        } else if (cam != null) {
            o.put("gainLabel", "ISO")
            o.put("manualSupported", cam.manualSupported)
            cam.exposureRangeNs?.let { o.put("expMinNs", it.lower).put("expMaxNs", it.upper) }
            cam.isoRange?.let { o.put("gainMin", it.lower).put("gainMax", it.upper) }
            o.put("curExpNs", cam.curExposureNs).put("curGain", cam.curIso)
            o.put("reqShutterNs", cam.reqShutterNs).put("reqGain", cam.reqIso)
            o.put("exposureDesc", cam.exposureDesc)
            val zr = cam.zoomRangeX100
            o.put("zoomSupported", zr != null)
            zr?.let { o.put("zoomMin", it.first).put("zoomMax", it.second) }
            o.put("reqZoom", cam.reqZoomX100)
            cameraSizes().forEach { sizes.put(it) }
        }
        o.put("sizes", sizes)
        return o
    }

    private var cameraSizesCache: List<String>? = null

    /** 摄像头能原样输出给编码器的分辨率（RESOLUTIONS 里筛） */
    private fun cameraSizes(): List<String> {
        cameraSizesCache?.let { return it }
        val id = cameraId ?: return RESOLUTIONS
        return RESOLUTIONS.filter { r ->
            val (w, h) = r.split("x").map { it.toInt() }
            val s = CameraSource.chooseSize(context, id, w, h)
            s.width == w && s.height == h
        }.also { cameraSizesCache = it }
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
                append("     曝光 ${u.exposureDesc}\n")
            } else {
                val cam = camera
                append("摄像头: AE帧率 ${cam?.fpsRangeDesc}   防抖 ${cam?.stabilizationDesc}   ${cam?.previewDesc}\n")
                append("     曝光 ${cam?.exposureDesc}   实际 1/${cam?.curExposureNs?.takeIf { it > 0 }?.let { 1_000_000_000L / it } ?: "-"}s" +
                        " ISO ${cam?.curIso ?: "-"}" +
                        "   变焦 ${cam?.reqZoomX100?.takeIf { it > 0 }?.let { "%.2fx".format(it / 100f) } ?: "1x"}\n")
            }
            append("网页控制: ${remote.status}\n")
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
