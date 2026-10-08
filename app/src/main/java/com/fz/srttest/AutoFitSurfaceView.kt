package com.fz.srttest

import android.content.Context
import android.view.SurfaceView

/**
 * 按视频宽高比在可用区域内居中缩放的预览 View。
 * 用 SurfaceView（照 silu）：画面由系统硬件合成器直接叠加，不经 GPU 重绘；摄像头/libuvc 直接写进它的 Surface。
 */
class AutoFitSurfaceView(context: Context) : SurfaceView(context) {
    private var ratioW = 16
    private var ratioH = 9

    fun setAspectRatio(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == ratioW && h == ratioH)) return
        ratioW = w
        ratioH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (w == 0 || h == 0) {
            setMeasuredDimension(w, h)
        } else if (w * ratioH < h * ratioW) {
            setMeasuredDimension(w, w * ratioH / ratioW)
        } else {
            setMeasuredDimension(h * ratioW / ratioH, h)
        }
    }
}
