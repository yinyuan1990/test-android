package com.fz.srttest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * TsMuxer 自检：
 * 1. 纯结构检查（不依赖外部文件）：包长、同步字节、连续计数、PAT/PMT 位置。
 * 2. 有 `build/tstest/in.h264`（ffmpeg 生成，无 B 帧）时，按帧封装写出 `out.ts`，
 *    再用 ffprobe / ffmpeg 校验（见仓库 README「本地自检」）。
 */
class TsMuxerTest {

    @Test
    fun packetsAreWellFormed() {
        val mux = TsMuxer()
        val idr = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1F) + byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte()) +
                byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(5000) { (it % 251).toByte() }
        val p = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(37) { 7 }
        val out = mux.mux(idr, 0, true) + mux.mux(p, 33_333, false) + mux.mux(idr, 66_666, true)
        assertEquals(0, out.size % TsMuxer.PACKET_SIZE)
        val lastCc = HashMap<Int, Int>()
        var i = 0
        while (i < out.size) {
            assertEquals(0x47, out[i].toInt() and 0xFF)
            val pid = ((out[i + 1].toInt() and 0x1F) shl 8) or (out[i + 2].toInt() and 0xFF)
            val cc = out[i + 3].toInt() and 0x0F
            lastCc[pid]?.let { assertEquals("PID $pid 连续计数", (it + 1) and 0x0F, cc) }
            lastCc[pid] = cc
            i += TsMuxer.PACKET_SIZE
        }
        // 关键帧前是 PAT(0x0000) + PMT(0x1000)
        assertEquals(0, ((out[1].toInt() and 0x1F) shl 8) or (out[2].toInt() and 0xFF))
        assertEquals(0x1000, ((out[189].toInt() and 0x1F) shl 8) or (out[190].toInt() and 0xFF))
    }

    @Test
    fun muxRealH264File() {
        val input = File("build/tstest/in.h264")
        assumeTrue("没有 $input，跳过（见 README 本地自检）", input.exists())
        val frames = splitAccessUnits(input.readBytes())
        assertTrue(frames.isNotEmpty())
        val mux = TsMuxer()
        File("build/tstest/out.ts").outputStream().use { os ->
            frames.forEachIndexed { idx, f ->
                os.write(mux.mux(f, idx * 33_333L, containsNal(f, 5)))
            }
        }
        println("TsMuxerTest: 封装 ${frames.size} 帧 → build/tstest/out.ts")
    }

    /** 按 VCL NAL（1/5）切访问单元：前面的 SPS/PPS/SEI 归到紧随其后的那一帧（单 slice 码流） */
    private fun splitAccessUnits(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i
                i += 3
            } else i++
        }
        val frames = ArrayList<ByteArray>()
        var auStart = 0
        for (k in starts.indices) {
            val s = starts[k]
            val hdr = if (data[s + 2].toInt() == 1) s + 3 else s + 4
            val type = data[hdr].toInt() and 0x1F
            if (type == 1 || type == 5) {
                val end = if (k + 1 < starts.size) starts[k + 1] else data.size
                frames += data.copyOfRange(auStart, end)
                auStart = end
            }
        }
        return frames
    }

    private fun containsNal(f: ByteArray, type: Int): Boolean {
        var i = 0
        while (i + 3 < f.size) {
            if (f[i].toInt() == 0 && f[i + 1].toInt() == 0 && f[i + 2].toInt() == 1 &&
                (f[i + 3].toInt() and 0x1F) == type) return true
            i++
        }
        return false
    }
}
