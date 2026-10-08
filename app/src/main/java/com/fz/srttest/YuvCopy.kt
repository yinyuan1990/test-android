package com.fz.srttest

import android.media.Image
import java.nio.ByteBuffer

/**
 * 把 OTG 回调的 YUV420 半平面帧（Y + 交错色度，紧密排列）一次拷进编码器输入 Image。
 *
 * 照 silu `feedOwnEncoder` 只做一遍：编码器输入是 NV12/NV21 半平面且与源色度顺序一致时按行批量拷
 * （最常见，硬编几乎都是 NV12）；顺序相反或是全平面时才逐像素处理。
 */
object YuvCopy {
    /** 最近一次识别到的编码器输入布局：NV12 / NV21 / I420（显示用） */
    @Volatile var lastLayout = ""
        private set

    fun copySemiPlanar(src: ByteBuffer, w: Int, h: Int, srcNv12: Boolean, img: Image) {
        val planes = img.planes
        val s = src.duplicate()
        copyPlane(s, 0, w, h, planes[0].buffer, planes[0].rowStride)

        val chromaRows = h / 2
        val chromaCols = w / 2
        val srcC = w * h
        val uP = planes[1]
        val vP = planes[2]
        if (uP.pixelStride == 2 && vP.pixelStride == 2) {
            val u = uP.buffer
            val v = vP.buffer
            val destNv12 = overlaps(u, v)
            val destNv21 = !destNv12 && overlaps(v, u)
            if ((destNv12 && srcNv12) || (destNv21 && !srcNv12)) {
                // 源与目标色度顺序一致：整行批量拷进先开始的那个平面
                val first = if (destNv12) u else v
                val second = if (destNv12) v else u
                val rowStride = if (destNv12) uP.rowStride else vP.rowStride
                for (r in 0 until chromaRows) {
                    val n = minOf(w, first.capacity() - r * rowStride)
                    if (n <= 0) break
                    s.limit(srcC + r * w + n).position(srcC + r * w)
                    first.position(r * rowStride)
                    first.put(s)
                    s.limit(s.capacity())
                    // 最后一行少 1 字节（两个平面视图错开 1 字节），用另一个平面补上
                    if (n == w - 1) second.put(second.capacity() - 1, s.get(srcC + r * w + w - 1))
                }
                lastLayout = if (destNv12) "NV12" else "NV21"
                return
            }
            lastLayout = if (destNv12) "NV12(源顺序相反)" else if (destNv21) "NV21(源顺序相反)" else "SP(独立平面)"
        } else {
            lastLayout = "I420"
        }
        // 兜底：逐像素拆色度
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        for (r in 0 until chromaRows) {
            val sRow = srcC + r * w
            val uRow = r * uP.rowStride
            val vRow = r * vP.rowStride
            for (c in 0 until chromaCols) {
                val a = s.get(sRow + c * 2)
                val b = s.get(sRow + c * 2 + 1)
                uBuf.put(uRow + c * uP.pixelStride, if (srcNv12) a else b)
                vBuf.put(vRow + c * vP.pixelStride, if (srcNv12) b else a)
            }
        }
    }

    private fun copyPlane(s: ByteBuffer, srcOff: Int, w: Int, h: Int, dst: ByteBuffer, dstStride: Int) {
        if (dstStride == w && dst.capacity() >= w * h) {
            s.limit(srcOff + w * h).position(srcOff)
            dst.position(0)
            dst.put(s)
            s.limit(s.capacity())
            return
        }
        for (r in 0 until h) {
            val n = minOf(w, dst.capacity() - r * dstStride)
            if (n <= 0) break
            s.limit(srcOff + r * w + n).position(srcOff + r * w)
            dst.position(r * dstStride)
            dst.put(s)
            s.limit(s.capacity())
        }
    }

    /** a 的第 2 个字节是不是 b 的第 1 个字节（即 b = a + 1 的交错视图） */
    private fun overlaps(a: ByteBuffer, b: ByteBuffer): Boolean {
        if (a.capacity() < 2 || b.capacity() < 1) return false
        val saveA1 = a.get(1)
        val saveB0 = b.get(0)
        val probe = (saveB0 + 1).toByte()
        a.put(1, probe)
        val same = b.get(0) == probe
        a.put(1, saveA1)
        if (!same) b.put(0, saveB0)
        return same
    }
}
