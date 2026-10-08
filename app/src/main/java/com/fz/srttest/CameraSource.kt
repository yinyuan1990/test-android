package com.fz.srttest

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * Camera2 采集（照 silu `Camera2MediaStreamer.createSession`）：后置主摄，录像模板，
 * 会话输出 = **编码器 Surface（必选）+ 屏幕预览 Surface（仅界面可见时）**。摄像头直写编码器，
 * 不经 GPU 重绘、不经 CPU 拷贝；息屏/切后台时预览撤掉，只剩编码器一路。
 * 固定帧率、连续视频对焦、关闭电子与光学防抖（防抖会裁切重采样让画面变软）。
 */
class CameraSource(private val context: Context) : PreviewRegistry.Listener {
    companion object {
        private const val TAG = "CameraSource"

        /** 后置主摄 id；没有后置就用第一个 */
        fun backCameraId(ctx: Context): String? {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull()
        }

        /** 编码器可用的输出尺寸里，与目标尺寸最接近的一个（优先同宽高比） */
        fun chooseSize(ctx: Context, cameraId: String, w: Int, h: Int): Size {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val map = cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaCodec::class.java) ?: return Size(w, h)
            sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
            val ratio = w.toFloat() / h
            return sizes.sortedWith(compareBy<Size>(
                { Math.abs(it.width.toFloat() / it.height - ratio) > 0.01f },
                { Math.abs(it.width * it.height - w * h) },
            )).first()
        }

        /**
         * 屏幕预览尺寸：同宽高比、不超过 1080p 的最大 SurfaceHolder 尺寸。
         * Camera2 规定 SurfaceView 预览最大 1080p，推 1440p/4K 时预览若用推流尺寸，会话会配置失败、编码器零帧。
         */
        fun choosePreviewSize(ctx: Context, cameraId: String, w: Int, h: Int): Size {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val map = cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.view.SurfaceHolder::class.java)
                ?.filter { it.width <= 1920 && it.height <= 1080 }
            if (sizes.isNullOrEmpty()) return Size(minOf(w, 1920), minOf(h, 1080))
            val ratio = w.toFloat() / h
            return sizes.sortedWith(compareBy<Size>(
                { Math.abs(it.width.toFloat() / it.height - ratio) > 0.01f },
                { -(it.width * it.height) },
            )).first()
        }
    }

    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var chars: CameraCharacteristics? = null
    private var cfg: StreamConfig? = null
    private var encoderSurface: Surface? = null
    private var onError: (String) -> Unit = {}
    @Volatile private var closed = false
    /** 带预览的会话配置失败过 → 本次推流只输出给编码器（推流绝不被预览卡住） */
    @Volatile private var previewUnsupported = false

    /** 最终生效的 AE 帧率区间 / 防抖状态 / 预览（显示用） */
    @Volatile var fpsRangeDesc: String = ""
        private set
    @Volatile var stabilizationDesc: String = ""
        private set
    @Volatile var previewDesc: String = ""
        private set

    // —— 曝光（网页远程调）——
    /** 请求值：0 = 自动 */
    @Volatile var reqShutterNs = 0L
        private set
    @Volatile var reqIso = 0
        private set
    /** 拍摄结果回读的实际曝光时间 / ISO */
    @Volatile var curExposureNs = 0L
        private set
    @Volatile var curIso = 0
        private set
    /** 最近一次自动曝光时的测光结果：只手动一项时，另一项按「曝光×ISO 不变」折算 */
    private var lastAutoExposureNs = 0L
    private var lastAutoIso = 0
    @Volatile var aeAuto = true
        private set

    val manualSupported: Boolean
        get() = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    val exposureRangeNs: android.util.Range<Long>?
        get() = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val isoRange: android.util.Range<Int>?
        get() = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest,
                                        result: android.hardware.camera2.TotalCaptureResult) {
            val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return
            curExposureNs = exp
            curIso = iso
            if (aeAuto) {
                lastAutoExposureNs = exp
                lastAutoIso = iso
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun open(cameraId: String, cfg: StreamConfig, encoderSurface: Surface, onError: (String) -> Unit) {
        this.cfg = cfg
        this.encoderSurface = encoderSurface
        this.onError = onError
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        chars = cm.getCameraCharacteristics(cameraId)
        PreviewRegistry.addListener(this)
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    onError("摄像头被断开（可能被其它应用占用）")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    onError("摄像头错误 code=$error")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            onError("打开摄像头失败: ${e.message}")
        }
    }

    /** 预览出现/消失：只重建会话，不重开摄像头 */
    override fun onPreviewChanged(surface: Surface?) {
        handler.post { if (device != null && !closed) createSession() }
    }

    /**
     * 换分辨率：不重开摄像头，停掉旧会话 → [releaseOld]（停旧编码器）→ 用新编码器 Surface 建会话。
     * 旧编码器必须在旧会话停掉之后再停，否则摄像头还往已释放的 Surface 写。
     */
    fun switchEncoder(newCfg: StreamConfig, newSurface: Surface, releaseOld: () -> Unit) {
        handler.post {
            try { session?.stopRepeating() } catch (_: Throwable) {}
            try { session?.close() } catch (_: Throwable) {}
            session = null
            releaseOld()
            cfg = newCfg
            encoderSurface = newSurface
            previewUnsupported = false
            if (device != null && !closed) createSession()
        }
    }

    /** 快门 / ISO：0 = 自动。只换 repeating request，不重建会话 */
    fun setExposure(shutterNs: Long, iso: Int) {
        handler.post {
            reqShutterNs = shutterNs
            reqIso = iso
            submitRepeating()
        }
    }

    private var targets: List<Surface> = emptyList()

    private fun submitRepeating() {
        val camera = device ?: return
        val s = session ?: return
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            targets.forEach { req.addTarget(it) }
            applyParams(req)
            s.setRepeatingRequest(req.build(), captureCallback, handler)
        } catch (t: Throwable) {
            onError("下发采集参数失败: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val camera = device ?: return
        val enc = encoderSurface ?: return
        try { session?.close() } catch (_: Throwable) {}
        session = null
        val preview = if (previewUnsupported) null else PreviewRegistry.current()
        val targets = if (preview != null) listOf(enc, preview) else listOf(enc)
        this.targets = targets
        previewDesc = when {
            preview != null -> "屏幕预览"
            previewUnsupported -> "无预览（带预览的会话配置失败，已改为只写编码器）"
            else -> "无预览（只写编码器）"
        }
        try {
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed || targets !== this@CameraSource.targets) { s.close(); return }
                    session = s
                    submitRepeating()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (preview != null) {
                        Log.w(TAG, "带预览的会话配置失败 → 改为只写编码器")
                        previewUnsupported = true
                        handler.post { createSession() }
                    } else {
                        onError("摄像头会话配置失败（该分辨率可能不被支持）")
                    }
                }
            }, handler)
        } catch (t: Throwable) {
            onError("创建摄像头会话失败: ${t.message}")
        }
    }

    private fun applyParams(req: CaptureRequest.Builder) {
        val chars = chars ?: return
        val cfg = cfg ?: return
        req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val range = ranges.firstOrNull { it.lower == cfg.fps && it.upper == cfg.fps }
            ?: ranges.filter { it.upper == cfg.fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper >= cfg.fps }.minByOrNull { it.upper - cfg.fps }
        if (range != null) req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        fpsRangeDesc = range?.let { "[${it.lower},${it.upper}]" } ?: "默认"

        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        if (cfg.disableStabilization) {
            req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
            if (ois.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
                req.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
            stabilizationDesc = "已关闭(电子${if (ois.isNotEmpty()) "+光学" else ""})"
        } else {
            stabilizationDesc = "设备默认"
        }
        applyExposure(req, chars, cfg)
        Log.i(TAG, "采集参数: fps=$fpsRangeDesc 防抖=$stabilizationDesc 预览=$previewDesc 曝光=$exposureDesc")
    }

    @Volatile var exposureDesc: String = "自动"
        private set

    /**
     * 快门/ISO 任一手动 → AE 关闭，曝光时间 + ISO + 帧间隔全部手动给；另一项为自动时按切换前的
     * 测光结果折算（曝光×ISO 不变，亮度不跳）。快门超过一帧时间钳到 1/fps，保证帧率不掉。
     */
    private fun applyExposure(req: CaptureRequest.Builder, chars: CameraCharacteristics, cfg: StreamConfig) {
        if ((reqShutterNs <= 0 && reqIso <= 0) || !manualSupported) {
            aeAuto = true
            exposureDesc = if (reqShutterNs > 0 || reqIso > 0) "自动（该摄像头不支持手动曝光）" else "自动"
            return
        }
        val frameNs = 1_000_000_000L / cfg.fps.coerceAtLeast(1)
        val expRange = exposureRangeNs
        val isoR = isoRange
        val baseExp = if (lastAutoExposureNs > 0) lastAutoExposureNs else frameNs / 2
        val baseIso = if (lastAutoIso > 0) lastAutoIso else 400
        var exp = if (reqShutterNs > 0) reqShutterNs
            else if (reqIso > 0) baseExp * baseIso / reqIso else baseExp
        exp = exp.coerceAtMost(frameNs)
        if (expRange != null) exp = exp.coerceIn(expRange.lower, minOf(expRange.upper, frameNs).coerceAtLeast(expRange.lower))
        var iso = if (reqIso > 0) reqIso else (baseExp.toDouble() * baseIso / exp).toInt()
        if (isoR != null) iso = iso.coerceIn(isoR.lower, isoR.upper)
        aeAuto = false
        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
        req.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        req.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        exposureDesc = "手动 快门 1/${1_000_000_000L / exp.coerceAtLeast(1)}s ISO $iso" +
                (if (reqShutterNs <= 0) "（快门按测光折算）" else "") +
                (if (reqIso <= 0) "（ISO 按测光折算）" else "")
    }

    fun close() {
        closed = true
        PreviewRegistry.removeListener(this)
        handler.post {
            try { session?.close() } catch (_: Throwable) {}
            session = null
            try { device?.close() } catch (_: Throwable) {}
            device = null
            thread.quitSafely()
        }
    }
}
