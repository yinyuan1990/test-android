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

    @Suppress("DEPRECATION")
    private fun createSession() {
        val camera = device ?: return
        val enc = encoderSurface ?: return
        try { session?.close() } catch (_: Throwable) {}
        session = null
        val preview = if (previewUnsupported) null else PreviewRegistry.current()
        val targets = if (preview != null) listOf(enc, preview) else listOf(enc)
        previewDesc = when {
            preview != null -> "屏幕预览"
            previewUnsupported -> "无预览（带预览的会话配置失败，已改为只写编码器）"
            else -> "无预览（只写编码器）"
        }
        try {
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) { s.close(); return }
                    session = s
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        targets.forEach { req.addTarget(it) }
                        applyParams(req)
                        s.setRepeatingRequest(req.build(), null, handler)
                    } catch (t: Throwable) {
                        onError("下发采集参数失败: ${t.message}")
                    }
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
        Log.i(TAG, "采集参数: fps=$fpsRangeDesc 防抖=$stabilizationDesc 预览=$previewDesc")
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
