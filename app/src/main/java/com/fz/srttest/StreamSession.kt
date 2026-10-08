package com.fz.srttest

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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

    /** @throws IllegalStateException 编码器无法配置 / 没有摄像头 */
    fun start() {
        startMs = System.currentTimeMillis()
        sender.start()
        if (cfgIn.isUvc) {
            uvc = UvcSource(context, cfgIn, this).also { it.start() }
        } else {
            val id = CameraSource.backCameraId(context) ?: throw IllegalStateException("没有可用的摄像头")
            val size = CameraSource.chooseSize(context, id, cfgIn.width, cfgIn.height)
            cfg = cfgIn.copy(width = size.width, height = size.height)
            val p = CameraSource.choosePreviewSize(context, id, size.width, size.height)
            cameraPreviewSize = p.width to p.height
            val enc = newEncoder(cfg, surfaceInput = true)
            camera = CameraSource(context).also { it.open(id, cfg, enc.inputSurface!!, onError) }
        }
    }

    fun stop() {
        camera?.close()
        camera = null
        uvc?.stop()
        uvc = null
        synchronized(encoderLock) {
            encoder?.stop()
            encoder = null
        }
        sender.stop()
    }

    private fun newEncoder(c: StreamConfig, surfaceInput: Boolean): H264Encoder {
        val enc = H264Encoder(c, surfaceInput) { data, ptsUs, isKey ->
            sender.offer(muxer.mux(data, ptsUs, isKey), isKey)
        }
        enc.start()
        encoder = enc
        return enc
    }

    // ---------- OTG 回调 ----------

    override fun onUvcStarted(width: Int, height: Int, fps: Int) {
        synchronized(encoderLock) {
            val cur = encoder
            if (cur != null && cfg.width == width && cfg.height == height) return
            cur?.stop()
            cfg = cfgIn.copy(width = width, height = height)
            try {
                newEncoder(cfg, surfaceInput = false)
            } catch (t: Throwable) {
                encoder = null
                onError(t.message ?: "编码器启动失败")
            }
        }
    }

    override fun onUvcFrame(frame: ByteBuffer, width: Int, height: Int, ptsUs: Long) {
        val enc = encoder ?: return
        if (width != cfg.width || height != cfg.height) return
        enc.feedYuv420sp(frame, width, height, cfgIn.uvcNv12, ptsUs)
    }

    override fun onUvcStopped(reason: String) = onError(reason)

    override fun onUvcError(msg: String) = onError(msg)

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
            if (sender.lastError.isNotEmpty()) append("错误: ${sender.lastError}\n")
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
        }
    }
}
