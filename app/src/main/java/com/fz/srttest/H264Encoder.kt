package com.fz.srttest

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * 硬件 H.264 编码器，输入为 Surface（Camera2 直接写入，零拷贝）。
 *
 * 默认参数对齐 silu：VBR + High@4.2 + QP 上限 + 1s 关键帧 + operating-rate + 实时优先级。
 * 编码器不认某些键时 configure 会抛异常，所以按 [TIERS] 逐级去掉可选项重试，
 * [tierName] 记录最终生效的是哪一档，界面上显示出来，判断 QP 上限等是否真的设进去了。
 */
class H264Encoder(
    private val cfg: StreamConfig,
    /** 每个编码输出（Annex-B，含起始码）；isKey=IDR 帧。在编码线程回调。 */
    private val onFrame: (data: ByteArray, ptsUs: Long, isKey: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        private const val OPT_VENDOR_QP = 1
        private const val OPT_QP = 2
        private const val OPT_PROFILE = 4
        private const val OPT_EXTRA = 8   // priority / operating-rate / color-range / prepend-sps-pps

        /** 逐级降级：名称 → 启用的可选项 */
        private val TIERS = listOf(
            "1 全部(含厂商QP键)" to (OPT_VENDOR_QP or OPT_QP or OPT_PROFILE or OPT_EXTRA),
            "2 全部(标准QP键)" to (OPT_QP or OPT_PROFILE or OPT_EXTRA),
            "3 去掉QP上限" to (OPT_PROFILE or OPT_EXTRA),
            "4 去掉Profile/Level" to OPT_EXTRA,
            "5 最简配置" to 0,
        )
    }

    private val thread = HandlerThread("h264-enc").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null

    /** Camera2 往这里画；[start] 成功后可用 */
    var inputSurface: Surface? = null
        private set
    var codecName: String = ""
        private set
    var tierName: String = ""
        private set
    /** 编码器输出格式里报告的实际 profile/level（configure 后可能被改写） */
    var outputFormatDesc: String = ""
        private set

    @Volatile private var csd: ByteArray? = null

    // —— 每秒统计（编码线程写，UI 线程读快照）——
    private var secBytes = 0L
    private var secFrames = 0
    private var secQpSum = 0L
    private var secQpCount = 0
    private var secStartMs = 0L
    @Volatile var statKbps = 0
        private set
    @Volatile var statFps = 0
        private set
    /** -1 = 编码器不报 QP（Android 13 以下或编码器不支持） */
    @Volatile var statQp = -1
        private set
    @Volatile var keyFrames = 0
        private set

    /** @throws IllegalStateException 所有降级档都配置失败 */
    fun start() {
        var lastError: Throwable? = null
        for ((name, opts) in TIERS) {
            val c = try { MediaCodec.createEncoderByType(MIME) } catch (t: Throwable) {
                throw IllegalStateException("创建 H.264 编码器失败: ${t.message}", t)
            }
            val format = buildFormat(c.name, opts)
            try {
                c.setCallback(callback, handler)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
                codec = c
                codecName = c.name
                tierName = name
                Log.i(TAG, "编码器 ${c.name} 档位[$name] format=$format")
                return
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "档位[$name] 配置失败: ${t.message}")
                try { c.release() } catch (_: Throwable) {}
                inputSurface?.release()
                inputSurface = null
            }
        }
        throw IllegalStateException("编码器所有降级档都配置失败: ${lastError?.message}", lastError)
    }

    private fun buildFormat(name: String, opts: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, cfg.width, cfg.height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrateKbps * 1000)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
        if (Build.VERSION.SDK_INT >= 25) {
            f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.keyIntervalSec.toFloat())
        } else {
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.keyIntervalSec)
        }
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, cfg.bitrateMode)

        if (opts and OPT_PROFILE != 0) {
            val profile = when (cfg.profile) {
                StreamConfig.PROFILE_HIGH -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                StreamConfig.PROFILE_MAIN -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            }
            f.setInteger(MediaFormat.KEY_PROFILE, profile)
            f.setInteger(MediaFormat.KEY_LEVEL, avcLevelFor(cfg.width, cfg.height, cfg.fps))
        }
        if (opts and OPT_EXTRA != 0) {
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, cfg.fps)
            when (cfg.colorRange) {
                StreamConfig.RANGE_FULL -> f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                StreamConfig.RANGE_LIMITED -> f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            f.setInteger("prepend-sps-pps-to-idr-frames", 1)
            if (Build.VERSION.SDK_INT >= 33) {
                // 让编码器在每个输出里报告平均 QP（界面显示，判断是否「糊」）
                f.setInteger("video-encoding-statistics-level", 1)
            }
        }
        if (cfg.qpMax > 0 && opts and OPT_QP != 0) {
            f.setInteger("video-qp-i-max", cfg.qpMax)
            f.setInteger("video-qp-p-max", cfg.qpMax)
            f.setInteger("video-qp-b-max", cfg.qpMax)
        }
        if (cfg.qpMax > 0 && opts and OPT_VENDOR_QP != 0 && isQualcomm(name)) {
            // 高通私有键：Android 12 以下标准 QP 键不生效时靠它
            f.setInteger("vendor.qti-ext-enc-qp-range.qp-i-max", cfg.qpMax)
            f.setInteger("vendor.qti-ext-enc-qp-range.qp-p-max", cfg.qpMax)
            f.setInteger("vendor.qti-ext-enc-qp-range.qp-b-max", cfg.qpMax)
        }
        return f
    }

    /**
     * 按 H.264 附录 A 的宏块数 / 宏块速率上限选最低够用的 Level（silu 固定 4.2，只够到 1080p）：
     * 1080p30=4.0、1080p60=4.2、1440p30=5.0、4K30=5.1、4K60=5.2。
     */
    private fun avcLevelFor(w: Int, h: Int, fps: Int): Int {
        val mbs = ((w + 15) / 16) * ((h + 15) / 16)
        val mbps = mbs.toLong() * fps
        return when {
            mbs <= 8192 && mbps <= 245_760 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            mbs <= 8704 && mbps <= 522_240 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
            mbs <= 22_080 && mbps <= 589_824 -> MediaCodecInfo.CodecProfileLevel.AVCLevel5
            mbs <= 36_864 && mbps <= 983_040 -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
        }
    }

    private fun isQualcomm(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("qcom") || n.contains("qti")
    }

    /** 立即要一个关键帧（SRT 重连后观看端要尽快拿到 IDR） */
    fun requestKeyFrame() {
        val c = codec ?: return
        handler.post {
            try {
                c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: Throwable) {}
        }
    }

    fun stop() {
        val c = codec
        codec = null
        handler.post {
            try { c?.stop() } catch (_: Throwable) {}
            try { c?.release() } catch (_: Throwable) {}
            inputSurface?.release()
            inputSurface = null
            thread.quitSafely()
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface 输入，不会走到这里
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buf.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        csd = data
                    } else {
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (isKey) keyFrames++
                        if (Build.VERSION.SDK_INT >= 33) readQp(codec, index)
                        val out = if (isKey && !H264Nal.containsSps(data)) (csd ?: ByteArray(0)) + data else data
                        accountStats(out.size)
                        onFrame(out, info.presentationTimeUs, isKey)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "处理编码输出失败: ${t.message}")
            } finally {
                try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "编码器错误: ${e.diagnosticInfo}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val p = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else -1
            val l = if (format.containsKey(MediaFormat.KEY_LEVEL)) format.getInteger(MediaFormat.KEY_LEVEL) else -1
            outputFormatDesc = "profile=${profileName(p)} level=$l"
            Log.i(TAG, "输出格式: $format")
        }
    }

    private fun readQp(codec: MediaCodec, index: Int) {
        try {
            val f = codec.getOutputFormat(index)
            if (f.containsKey("video-qp-average")) {
                secQpSum += f.getInteger("video-qp-average")
                secQpCount++
            }
        } catch (_: Throwable) {}
    }

    private fun accountStats(bytes: Int) {
        val now = System.currentTimeMillis()
        if (secStartMs == 0L) secStartMs = now
        secBytes += bytes
        secFrames++
        val elapsed = now - secStartMs
        if (elapsed >= 1000) {
            statKbps = (secBytes * 8 / elapsed).toInt()
            statFps = (secFrames * 1000 / elapsed).toInt()
            statQp = if (secQpCount > 0) (secQpSum / secQpCount).toInt() else -1
            secBytes = 0; secFrames = 0; secQpSum = 0; secQpCount = 0
            secStartMs = now
        }
    }

    private fun profileName(p: Int): String = when (p) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "ConstrainedBaseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> "ConstrainedHigh"
        -1 -> "?"
        else -> p.toString()
    }
}
