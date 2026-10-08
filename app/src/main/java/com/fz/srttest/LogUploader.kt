package com.fz.srttest

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 [EventLog] 里还没传过的行 POST 到 app 服务器（`api.147258yql.cn/srtlog/`，服务端 `tools/srtlog/server.js`），
 * 服务器按天、按「机型_设备id」追加成文件。失败的行留着下次一起补传。
 */
object LogUploader {
    private const val ENDPOINT = "https://api.147258yql.cn/srtlog/upload"
    /** 仓库公开，这只是挡随手乱传，不是机密 */
    private const val KEY = "srttest-log"

    @Volatile private var uploadedSeq = 0L
    private val busy = AtomicBoolean(false)

    /** 最近一次上传结果（界面显示） */
    @Volatile var lastResult: String = ""
        private set

    /** 后台线程上传；[header] 非空时放在正文最前（手动上传时带当前统计） */
    fun uploadAsync(ctx: Context, reason: String, header: String? = null, onDone: ((Boolean, String) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread({
            val r = upload(app, reason, header)
            onDone?.invoke(r.first, r.second)
        }, "log-upload").apply { isDaemon = true; start() }
    }

    private fun upload(ctx: Context, reason: String, header: String?): Pair<Boolean, String> {
        if (!busy.compareAndSet(false, true)) return false to "上一次上传还没结束"
        try {
            val (lastSeq, text) = EventLog.since(uploadedSeq)
            if (text.isEmpty() && header == null) return true to "没有新日志"
            val body = buildString {
                append("== $reason  ${EventLog.header()} ==\n")
                if (header != null) append(header).append('\n')
                append(text)
            }.toByteArray(Charsets.UTF_8)
            val url = "$ENDPOINT?dev=${enc(deviceId(ctx))}&model=${enc(Build.MODEL)}"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.setRequestProperty("X-Log-Key", KEY)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                return if (code == 200) {
                    uploadedSeq = lastSeq
                    val msg = "已上传 ${body.size / 1024}KB"
                    lastResult = "$msg（$reason）"
                    true to msg
                } else {
                    lastResult = "上传失败 HTTP $code（$reason）"
                    false to "HTTP $code"
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            val msg = t.message ?: t.toString()
            lastResult = "上传失败 $msg（$reason）"
            return false to msg
        } finally {
            busy.set(false)
        }
    }

    @SuppressLint("HardwareIds")
    private fun deviceId(ctx: Context): String =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "nodev").take(8)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
