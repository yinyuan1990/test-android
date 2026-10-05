package com.fz.srttest

/** Annex-B H.264 码流的最小解析工具 */
object H264Nal {
    const val TYPE_SPS = 7

    /** 码流里是否有 SPS（MediaCodec 部分机型 IDR 前不带，需要我们补 csd） */
    fun containsSps(data: ByteArray): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val start = when {
                    data[i + 2].toInt() == 1 -> i + 3
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
                    else -> -1
                }
                if (start in 0 until data.size) {
                    if ((data[start].toInt() and 0x1F) == TYPE_SPS) return true
                    i = start
                    continue
                }
            }
            i++
        }
        return false
    }
}
