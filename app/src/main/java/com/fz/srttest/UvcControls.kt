package com.fz.srttest

import android.util.Log
import com.jiangdg.uvc.UVCCamera
import java.lang.reflect.Method

/**
 * libuvc 的曝光模式 / 绝对曝光 / 增益：AUSBC 3.5.3 只声明了 native 方法没给 public 封装，
 * 反射直调（与 android-otg `UvcExposureBridge` 同法）。所有调用须在 uvc 线程。
 *
 * UVC CT_AE_MODE：1=手动 2=自动 4=快门优先 8=光圈优先；曝光单位 100µs。
 */
object UvcControls {
    private const val TAG = "UvcControls"
    const val MODE_MANUAL = 1
    const val MODE_AUTO = 2
    const val MODE_SHUTTER_PRIORITY = 4
    const val MODE_APERTURE_PRIORITY = 8
    /** 快门最短 0.5ms，更短室内必黑 */
    private const val MIN_EXPOSURE_UNITS = 5

    private val cls = UVCCamera::class.java
    private val ok: Boolean by lazy {
        try {
            listOf("nativeSetExposureMode", "nativeGetExposureMode", "nativeSetExposure", "nativeGetExposure",
                "nativeUpdateExposureLimit", "nativeSetGain", "nativeGetGain", "nativeUpdateGainLimit").forEach { method(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "反射不可用: ${t.message}")
            false
        }
    }

    val available: Boolean get() = ok

    private val methods = HashMap<String, Method>()

    private fun method(name: String): Method = methods.getOrPut(name) {
        val withArg = name.startsWith("nativeSet")
        val m = if (withArg) cls.getDeclaredMethod(name, java.lang.Long.TYPE, Integer.TYPE)
        else cls.getDeclaredMethod(name, java.lang.Long.TYPE)
        m.apply { isAccessible = true }
    }

    private fun ptr(cam: UVCCamera): Long =
        cls.getDeclaredField("mNativePtr").apply { isAccessible = true }.getLong(cam)

    private fun intField(cam: UVCCamera, name: String): Int =
        cls.getDeclaredField(name).apply { isAccessible = true }.getInt(cam)

    private fun call(cam: UVCCamera, name: String, vararg args: Int): Int {
        val m = method(name)
        val recv = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else cam
        val all = ArrayList<Any>(1 + args.size)
        all.add(ptr(cam))
        args.forEach { all.add(it) }
        return (m.invoke(recv, *all.toTypedArray()) as? Int) ?: 0
    }

    fun getMode(cam: UVCCamera): Int = try { call(cam, "nativeGetExposureMode") } catch (_: Throwable) { -1 }

    fun setMode(cam: UVCCamera, mode: Int): Boolean = try {
        call(cam, "nativeSetExposureMode", mode) >= 0
    } catch (t: Throwable) {
        Log.w(TAG, "设曝光模式 $mode 失败: ${t.message}")
        false
    }

    /** 曝光范围（100µs 单位），设备不报返回 null */
    fun exposureRange(cam: UVCCamera): Pair<Int, Int>? = try {
        call(cam, "nativeUpdateExposureLimit")
        val a = intField(cam, "mExposureMin")
        val b = intField(cam, "mExposureMax")
        val lo = maxOf(minOf(a, b), MIN_EXPOSURE_UNITS)
        val hi = maxOf(a, b)
        if (hi > lo) lo to hi else null
    } catch (_: Throwable) { null }

    fun getExposure(cam: UVCCamera): Int = try { call(cam, "nativeGetExposure") } catch (_: Throwable) { -1 }

    fun setExposure(cam: UVCCamera, units: Int): Boolean = try {
        call(cam, "nativeSetExposure", units) >= 0
    } catch (_: Throwable) { false }

    fun gainRange(cam: UVCCamera): Pair<Int, Int>? = try {
        call(cam, "nativeUpdateGainLimit")
        val a = intField(cam, "mGainMin")
        val b = intField(cam, "mGainMax")
        if (maxOf(a, b) > minOf(a, b)) minOf(a, b) to maxOf(a, b) else null
    } catch (_: Throwable) { null }

    fun getGain(cam: UVCCamera): Int = try { call(cam, "nativeGetGain") } catch (_: Throwable) { -1 }

    fun setGain(cam: UVCCamera, gain: Int): Boolean = try {
        call(cam, "nativeSetGain", gain) >= 0
    } catch (_: Throwable) { false }
}
