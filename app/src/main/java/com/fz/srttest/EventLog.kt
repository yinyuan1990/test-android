package com.fz.srttest

import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 推流事件日志：内存环形缓冲（进程内跨会话保留），界面「复制日志」导出、[LogUploader] 增量上传。
 * 同时写 logcat（tag = 调用方 tag）。
 */
object EventLog {
    /** 每 2s 一行统计 + 事件，约 1 小时；也控制复制到剪贴板的体积（Binder 上限约 1MB） */
    private const val MAX_LINES = 2000

    private class Entry(val seq: Long, val text: String)

    private val lines = ArrayDeque<Entry>(MAX_LINES)
    /** 行序号单调递增，清空日志也不回退，上传按它判断哪些已传过 */
    private var seq = 0L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, msg: String) = add("I", tag, msg)

    fun w(tag: String, msg: String) = add("W", tag, msg)

    private fun add(level: String, tag: String, msg: String) {
        if (level == "W") Log.w(tag, msg) else Log.i(tag, msg)
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(Entry(++seq, "${fmt.format(Date())} $level $tag: $msg"))
        }
    }

    fun clear() = synchronized(lines) { lines.clear() }

    fun header(): String =
        "机型 ${Build.MANUFACTURER} ${Build.MODEL}   Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun dump(current: String?): String = buildString {
        append("== SRT画质测试 日志 ${fmt.format(Date())} ==\n")
        append(header()).append('\n')
        if (current != null) append("== 当前统计 ==\n").append(current).append('\n')
        append("== 事件 ==\n")
        synchronized(lines) { for (e in lines) append(e.text).append('\n') }
    }

    /** 序号大于 [afterSeq] 的行；返回（最后一行序号, 文本），没有新行时文本为空 */
    fun since(afterSeq: Long): Pair<Long, String> = synchronized(lines) {
        val sb = StringBuilder()
        var last = afterSeq
        for (e in lines) {
            if (e.seq <= afterSeq) continue
            sb.append(e.text).append('\n')
            last = e.seq
        }
        last to sb.toString()
    }
}
