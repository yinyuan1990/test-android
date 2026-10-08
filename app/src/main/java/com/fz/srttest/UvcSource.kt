package com.fz.srttest

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.usb.UsbDevice
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera
import java.nio.ByteBuffer

/**
 * OTG 外接 USB 摄像头采集（照 silu `AusbcUvcBridge` 的默认后端 + android-otg 的协商经验）。
 *
 * 帧路径：libuvc 解 MJPEG → 帧回调（NV12）→ [FrameGate] 按推流 fps 丢多余帧 → 回调方**一次拷进**编码器。
 * 比 android-otg 少掉：Kotlin UV 交换、WebRTC 的 NV21→I420（每帧新分配）+ I420→NV12、全分辨率 RGBA 哑窗口。
 *
 * 预览窗口：libuvc 必须有窗口才起取流线程。界面可见 → 用屏幕 SurfaceView（画面有用）；
 * 不可见 → 先挂 ImageReader 占位窗口起流，**出帧后摘掉**（native 摘窗后跳过每帧 RGBX 转换+拷贝，
 * 只保留解码与回调），摘窗后若 1.5s 无帧则挂回并记为「该设备不支持摘窗」。
 */
class UvcSource(
    private val context: Context,
    @Volatile private var cfg: StreamConfig,
    private val callback: Callback,
) : UvcDeviceMonitor.Listener, PreviewRegistry.Listener {

    interface Callback {
        /** 协商完成、即将开流：回调方据此（重）建编码器。在 uvc 线程同步调用。 */
        fun onUvcStarted(width: Int, height: Int, fps: Int)
        /** 一帧（frame 只在回调内有效） */
        fun onUvcFrame(frame: ByteBuffer, width: Int, height: Int, ptsUs: Long)
        fun onUvcStopped(reason: String)
        fun onUvcError(msg: String)
    }

    companion object {
        private const val TAG = "UvcSource"
        private const val FIRST_FRAME_TIMEOUT_MS = 2500L
        private const val DETACH_CHECK_MS = 1500L
    }

    private val thread = HandlerThread("uvc").apply { start() }
    private val handler = Handler(thread.looper)
    private val gate = FrameGate()

    private var camera: UVCCamera? = null
    private var dummyReader: ImageReader? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var fps = 0
    @Volatile private var running = false
    @Volatile private var frameCount = 0L
    /** 该设备摘窗后不出帧 → 以后不再摘（本次会话内） */
    private var detachUnsupported = false

    // —— 显示用 ——
    @Volatile var deviceName = ""
        private set
    @Volatile var formatDesc = ""
        private set
    /** 屏幕预览 / 无预览(已摘窗) / 占位窗口 */
    @Volatile var previewDesc = ""
        private set
    @Volatile var badFrames = 0
        private set
    val gateDrops: Int get() = gate.dropped
    @Volatile var captureFps = 0
        private set
    private var fpsWindowStart = 0L
    private var fpsWindowCount = 0

    // —— 曝光 / 增益（网页远程调；uvc 线程每秒刷新这些快照供上报）——
    /** 请求值：0 = 自动；快门单位 ns，增益为 UVC 绝对值 */
    @Volatile var reqShutterNs = 0L
        private set
    @Volatile var reqGain = 0
        private set
    @Volatile var exposureDesc = "自动"
        private set
    @Volatile var exposureRangeNs: Pair<Long, Long>? = null
        private set
    @Volatile var gainRange: Pair<Int, Int>? = null
        private set
    @Volatile var curExposureNs = 0L
        private set
    @Volatile var curGain = -1
        private set
    @Volatile var aeMode = -1
        private set
    @Volatile var supportedSizes: List<String> = emptyList()
        private set
    /** 打开时的原始 AE 模式，恢复自动时优先回到它 */
    private var defaultAeMode = -1
    private val controlTick = object : Runnable {
        override fun run() {
            refreshControlState()
            if (camera != null) handler.postDelayed(this, 1000)
        }
    }

    fun setExposure(shutterNs: Long, gain: Int) {
        handler.post {
            reqShutterNs = shutterNs
            reqGain = gain
            camera?.let { applyExposure(it) }
        }
    }

    /** 换分辨率：同一设备重新协商，回调 onUvcStarted 让会话按新尺寸重建编码器（SRT 不断） */
    fun changeConfig(newCfg: StreamConfig) {
        handler.post {
            cfg = newCfg
            val cam = camera ?: return@post
            try {
                cam.setFrameCallback(null, 0)
                cam.stopPreview()
                negotiateAndStart(cam)
            } catch (t: Throwable) {
                callback.onUvcError("外接摄像头切换分辨率失败: ${t.message}")
            }
        }
    }

    private fun applyExposure(cam: UVCCamera) {
        if (!UvcControls.available) {
            exposureDesc = "不支持（库接口不可用）"
            return
        }
        val units = if (reqShutterNs > 0) {
            val frameUnits = 10000 / fps.coerceAtLeast(1)
            val r = UvcControls.exposureRange(cam)
            var u = (reqShutterNs / 100_000L).toInt().coerceAtMost(frameUnits)
            if (r != null) u = u.coerceIn(r.first, maxOf(r.first, minOf(r.second, frameUnits)))
            u
        } else 0
        when {
            reqShutterNs <= 0 && reqGain <= 0 -> {
                val modes = linkedSetOf<Int>()
                if (defaultAeMode > 0 && defaultAeMode != UvcControls.MODE_MANUAL &&
                    defaultAeMode != UvcControls.MODE_SHUTTER_PRIORITY) modes += defaultAeMode
                modes += UvcControls.MODE_APERTURE_PRIORITY
                modes += UvcControls.MODE_AUTO
                val okMode = modes.firstOrNull { UvcControls.setMode(cam, it) }
                AeConstantFps.apply(cam)
                exposureDesc = if (okMode != null) "自动(模式$okMode)" else "恢复自动失败"
            }
            reqShutterNs > 0 && reqGain <= 0 -> {
                // 快门优先：曝光固定、摄像头自己调增益；不支持就纯手动，增益保持当前
                val prio = UvcControls.setMode(cam, UvcControls.MODE_SHUTTER_PRIORITY) &&
                        UvcControls.getMode(cam) == UvcControls.MODE_SHUTTER_PRIORITY
                if (!prio) UvcControls.setMode(cam, UvcControls.MODE_MANUAL)
                UvcControls.setExposure(cam, units)
                exposureDesc = "快门 ${units / 10.0}ms " + (if (prio) "快门优先(增益自动)" else "纯手动(设备不支持快门优先，增益保持)")
            }
            else -> {
                val keepExp = if (units > 0) units else UvcControls.getExposure(cam)
                UvcControls.setMode(cam, UvcControls.MODE_MANUAL)
                if (keepExp > 0) UvcControls.setExposure(cam, keepExp)
                UvcControls.setGain(cam, reqGain)
                exposureDesc = "手动 快门 ${keepExp / 10.0}ms${if (units <= 0) "(保持当前)" else ""} 增益 $reqGain"
            }
        }
        EventLog.i(TAG, "远程曝光 → $exposureDesc")
        refreshControlState()
    }

    private fun refreshControlState() {
        val cam = camera ?: return
        if (!UvcControls.available) return
        exposureRangeNs = UvcControls.exposureRange(cam)?.let { it.first * 100_000L to it.second * 100_000L }
        gainRange = UvcControls.gainRange(cam)
        curExposureNs = UvcControls.getExposure(cam).let { if (it > 0) it * 100_000L else 0L }
        curGain = UvcControls.getGain(cam)
        aeMode = UvcControls.getMode(cam)
    }

    fun start() {
        running = true
        UvcDeviceMonitor.addListener(this)
        PreviewRegistry.addListener(this)
        UvcDeviceMonitor.start(context)
        UvcDeviceMonitor.currentReady()?.let { (d, cb) -> handler.post { open(d, cb) } }
    }

    fun stop() {
        running = false
        UvcDeviceMonitor.removeListener(this)
        PreviewRegistry.removeListener(this)
        handler.post {
            closeCamera()
            UvcDeviceMonitor.stop()
            thread.quitSafely()
        }
    }

    // ---------- 设备事件 ----------

    override fun onUvcDeviceReady(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        handler.post { open(device, ctrlBlock) }
    }

    override fun onUvcDeviceGone(device: UsbDevice) {
        handler.post {
            closeCamera()
            callback.onUvcStopped("外接摄像头已拔出")
        }
    }

    override fun onPreviewChanged(surface: Surface?) {
        handler.post { applyPreviewWindow() }
    }

    // ---------- 打开 / 协商 ----------

    private fun open(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        if (!running || camera != null) return
        try {
            val cam = UVCCamera()
            cam.open(ctrlBlock)
            camera = cam
            deviceName = "${device.productName ?: device.deviceName} " +
                    "%04X:%04X".format(device.vendorId, device.productId)
            defaultAeMode = if (UvcControls.available) UvcControls.getMode(cam) else -1
            supportedSizes = try {
                cam.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG)?.filterIsInstance<com.jiangdg.utils.Size>()
                    ?.filter { it.width > 0 && it.height > 0 }
                    ?.sortedByDescending { it.width * it.height }
                    ?.map { "${it.width}x${it.height}" }?.distinct()
            } catch (_: Throwable) { null } ?: emptyList()
            negotiateAndStart(cam)
            handler.removeCallbacks(controlTick)
            handler.post(controlTick)
        } catch (t: Throwable) {
            Log.w(TAG, "打开外接摄像头失败: ${t.message}")
            callback.onUvcError("打开外接摄像头失败: ${t.message}")
            closeCamera()
        }
    }

    private fun negotiateAndStart(cam: UVCCamera) {
        val formats = listOf(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.FRAME_FORMAT_YUYV)
        var lastErr: Throwable? = null
        for (format in formats) {
            val (w, h) = pickSize(cam, format)
            for (f in fpsCandidates(cfg.fps)) {
                try {
                    setPreviewSizeForced(cam, w, h, f, format)
                    width = w; height = h; fps = f
                    formatDesc = "${if (format == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"} ${w}x$h@$f"
                    Log.i(TAG, "协商成功 $formatDesc（推流 ${cfg.fps}fps）")
                    startStreaming(cam)
                    return
                } catch (t: Throwable) {
                    lastErr = t
                    Log.d(TAG, "协商 ${w}x$h@$f 格式$format 失败: ${t.message}")
                }
            }
        }
        throw IllegalStateException("所有分辨率/帧率/格式都协商失败: ${lastErr?.message}", lastErr)
    }

    /** 设备支持的尺寸里选目标尺寸；没有就选面积最接近、宽高比相近的 */
    private fun pickSize(cam: UVCCamera, format: Int): Pair<Int, Int> {
        val sizes = try {
            cam.getSupportedSizeList(format)?.filterIsInstance<com.jiangdg.utils.Size>()
                ?.filter { it.width > 0 && it.height > 0 }
        } catch (_: Throwable) { null }
        if (sizes.isNullOrEmpty()) return cfg.width to cfg.height
        sizes.firstOrNull { it.width == cfg.width && it.height == cfg.height }?.let { return it.width to it.height }
        val ratio = cfg.width.toFloat() / cfg.height
        val best = sizes.minByOrNull {
            Math.abs(it.width.toLong() * it.height - cfg.width.toLong() * cfg.height) +
                    (Math.abs(it.width.toFloat() / it.height - ratio) * 1_000_000).toLong()
        }!!
        return best.width to best.height
    }

    /** 先请求推流帧率，再往下降；最后试 60（采集偏高由 FrameGate 丢帧，无害） */
    private fun fpsCandidates(target: Int): List<Int> =
        (listOf(target) + listOf(30, 25, 20, 15, 10).filter { it < target } + listOf(60).filter { it > target })
            .distinct()

    /**
     * 强制真谈判（android-otg §56.18 经验）：libuvc 发现宽高格式没变会短路「假接受」，
     * 所以每次先用另一格式同尺寸调一次把 native 状态弄脏，再发真请求。fps 窗口 [fps-1, fps]。
     */
    private fun setPreviewSizeForced(cam: UVCCamera, w: Int, h: Int, f: Int, format: Int) {
        val other = if (format == UVCCamera.FRAME_FORMAT_MJPEG) UVCCamera.FRAME_FORMAT_YUYV else UVCCamera.FRAME_FORMAT_MJPEG
        try { cam.setPreviewSize(w, h, f - 1, f, other, UVCCamera.DEFAULT_BANDWIDTH) } catch (_: Throwable) {}
        cam.setPreviewSize(w, h, f - 1, f, format, UVCCamera.DEFAULT_BANDWIDTH)
    }

    private fun startStreaming(cam: UVCCamera) {
        callback.onUvcStarted(width, height, fps)
        gate.reset()
        frameCount = 0
        val screen = PreviewRegistry.current()
        cam.setPreviewDisplay(screen ?: ensureDummySurface(width, height))
        previewDesc = if (screen != null) "屏幕预览" else "占位窗口"
        cam.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
        cam.startPreview()
        AeConstantFps.apply(cam)
        if (reqShutterNs > 0 || reqGain > 0) applyExposure(cam)
        // 首帧看门狗：出帧后若没有屏幕预览就摘窗；超时无帧报错
        handler.postDelayed({ afterFirstFrames() }, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun afterFirstFrames() {
        if (camera == null) return
        if (frameCount == 0L) {
            callback.onUvcError("外接摄像头开流后 ${FIRST_FRAME_TIMEOUT_MS}ms 无帧（$formatDesc）")
            return
        }
        applyPreviewWindow()
    }

    // ---------- 预览窗口：屏幕 / 摘窗 / 占位 ----------

    private fun applyPreviewWindow() {
        val cam = camera ?: return
        if (frameCount == 0L) return   // 还没出帧，别动窗口（libuvc 起流依赖它）
        val screen = PreviewRegistry.current()
        try {
            when {
                screen != null -> {
                    cam.setPreviewDisplay(screen)
                    previewDesc = "屏幕预览"
                    releaseDummySurface()
                }
                !detachUnsupported -> {
                    val before = frameCount
                    cam.setPreviewDisplay(null as Surface?)
                    previewDesc = "无预览(已摘窗)"
                    releaseDummySurface()
                    handler.postDelayed({
                        if (camera != null && previewDesc.startsWith("无预览") && frameCount == before) {
                            Log.w(TAG, "摘窗后无帧 → 该设备不支持摘窗，挂回占位窗口")
                            detachUnsupported = true
                            cam.setPreviewDisplay(ensureDummySurface(width, height))
                            previewDesc = "占位窗口(该设备不支持摘窗)"
                        }
                    }, DETACH_CHECK_MS)
                }
                else -> {
                    cam.setPreviewDisplay(ensureDummySurface(width, height))
                    previewDesc = "占位窗口(该设备不支持摘窗)"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "切换预览窗口失败: ${t.message}")
        }
    }

    /** 自动排空的占位窗口（只在起流瞬间或不支持摘窗的设备上用） */
    private fun ensureDummySurface(w: Int, h: Int): Surface {
        dummyReader?.let { return it.surface }
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rr -> try { rr.acquireLatestImage()?.close() } catch (_: Throwable) {} }, handler)
        dummyReader = r
        return r.surface
    }

    private fun releaseDummySurface() {
        try { dummyReader?.close() } catch (_: Throwable) {}
        dummyReader = null
    }

    // ---------- 帧回调（native 采集线程） ----------

    private val frameCallback = IFrameCallback { frame ->
        frameCount++
        val now = System.nanoTime()
        countFps(now)
        if (!gate.shouldAccept(now, cfg.fps)) return@IFrameCallback
        val w = width
        val h = height
        if (frame == null || frame.remaining() < w * h * 3 / 2) {
            badFrames++
            return@IFrameCallback
        }
        callback.onUvcFrame(frame, w, h, now / 1000)
    }

    private fun countFps(now: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsWindowCount++
        val dt = now - fpsWindowStart
        if (dt >= 1_000_000_000L) {
            captureFps = (fpsWindowCount * 1_000_000_000L / dt).toInt()
            fpsWindowCount = 0
            fpsWindowStart = now
        }
    }

    private fun closeCamera() {
        val cam = camera ?: return
        camera = null
        handler.removeCallbacks(controlTick)
        try { cam.setFrameCallback(null, 0) } catch (_: Throwable) {}
        try { cam.stopPreview() } catch (_: Throwable) {}
        try { cam.destroy() } catch (_: Throwable) {}
        releaseDummySurface()
        previewDesc = ""
        formatDesc = ""
    }
}

/**
 * §117 结论：默认 AE 优先级 = 0（帧率恒定），否则暗光下自动曝光会把帧率降到 6~8fps。
 * AUSBC 没把它做成 public 方法，反射调 native（与 android-otg UvcExposureBridge 同法）。
 */
private object AeConstantFps {
    fun apply(cam: UVCCamera) {
        try {
            val cls = UVCCamera::class.java
            val ptr = cls.getDeclaredField("mNativePtr").apply { isAccessible = true }.getLong(cam)
            val m = cls.getDeclaredMethod("nativeSetExposurePriority", java.lang.Long.TYPE, Integer.TYPE)
                .apply { isAccessible = true }
            val recv = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else cam
            val r = m.invoke(recv, ptr, 0)
            Log.i("UvcSource", "AE优先级=0（帧率恒定） rc=$r")
        } catch (t: Throwable) {
            Log.i("UvcSource", "AE优先级不可设（库或设备不支持）: ${t.message}")
        }
    }
}
