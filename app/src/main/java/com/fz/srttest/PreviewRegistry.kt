package com.fz.srttest

import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 屏幕预览 Surface 的登记处（照 silu `UvcPreviewSurfaceRegistry`）。
 *
 * 推流在前台服务里跑，与界面生命周期解耦：界面可见时 Activity 登记 SurfaceView 的 Surface，
 * 息屏/切后台时撤销；推流端监听变化，有就把画面同时输出到预览，没有就只输出给编码器（不渲染、不发热）。
 */
object PreviewRegistry {
    fun interface Listener {
        fun onPreviewChanged(surface: Surface?)
    }

    @Volatile var surface: Surface? = null
        private set
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun set(s: Surface?) {
        surface = s
        listeners.forEach { it.onPreviewChanged(s) }
    }

    /** 当前可用的预览 Surface（已失效的视为没有） */
    fun current(): Surface? = surface?.takeIf { it.isValid }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
}
