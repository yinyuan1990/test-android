package com.fz.srttest

import android.content.Context
import android.view.TextureView

/** 按视频宽高比在可用区域内居中缩放的预览 View */
class AutoFitTextureView(context: Context) : TextureView(context) {
    private var ratioW = 16
    private var ratioH = 9

    fun setAspectRatio(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
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
