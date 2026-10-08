package com.fz.srttest

import android.content.Context
import android.view.Surface

/**
 * 一次推流：Camera2 →（预览 + 编码器 Surface）→ H.264 → MPEG-TS → SRT。
 * [cfg] 的宽高必须已经是摄像头支持的尺寸（见 [CameraSource.chooseSize]）。
 */
class StreamSession(
    private val context: Context,
    private val cfg: StreamConfig,
    private val cameraId: String,
    private val previewSurface: Surface,
    private val onError: (String) -> Unit,
) {
    private val muxer = TsMuxer()
    private var encoder: H264Encoder? = null
    private val sender = SrtSender(cfg, onNeedKeyFrame = { encoder?.requestKeyFrame() })
    private val camera = CameraSource(context)
    private var startMs = 0L

    /** @throws IllegalStateException 编码器无法配置 */
    fun start() {
        val enc = H264Encoder(cfg) { data, ptsUs, isKey ->
            sender.offer(muxer.mux(data, ptsUs, isKey), isKey)
        }
        enc.start()
        encoder = enc
        sender.start()
        camera.open(cameraId, cfg, listOf(previewSurface, enc.inputSurface!!), onError)
        startMs = System.currentTimeMillis()
    }

    fun stop() {
        camera.close()
        encoder?.stop()
        encoder = null
        sender.stop()
    }

    fun statsText(): String {
        val enc = encoder
        val sec = ((System.currentTimeMillis() - startMs) / 1000).toInt()
        val qp = enc?.statQp ?: -1
        return buildString {
            append("状态: ${sender.state}   已推 ${sec / 60}:${"%02d".format(sec % 60)}\n")
            if (sender.lastError.isNotEmpty()) append("错误: ${sender.lastError}\n")
            append("编码器: ${enc?.codecName}   降级档: ${enc?.tierName}\n")
            append("输出: ${enc?.outputFormatDesc}   ${cfg.width}x${cfg.height}@${cfg.fps}" +
                    "   AE帧率 ${camera.fpsRangeDesc}   防抖 ${camera.stabilizationDesc}\n")
            append("编码: ${enc?.statKbps} kbps / 目标 ${cfg.bitrateKbps}   ${enc?.statFps} fps" +
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
