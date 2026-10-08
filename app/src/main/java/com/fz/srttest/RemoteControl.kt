package com.fz.srttest

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网页远程调参通道（服务端 `tools/srtlog/server.js` 的 /srtlog/ctl/sync，网页 /srtlog/ctl）。
 * 推流期间每秒 POST 一次本机状态，回包带网页最新下发的命令；版本号变了才执行。
 * 按流名区分手机：网页打开 `?path=<流名>` 就控制正在推这个流的手机。
 */
class RemoteControl(private val context: Context, private val session: StreamSession) {
    companion object {
        private const val ENDPOINT = "https://api.147258yql.cn/srtlog/ctl/sync"
        /** 与日志上传同一个弱口令（仓库公开），只挡随手乱调 */
        private const val KEY = "srttest-log"
        private const val INTERVAL_MS = 1000L
    }

    /** 0 表示自动 / 不改 */
    data class Command(
        val ver: Long,
        val shutterNs: Long,
        val gain: Int,
        val width: Int,
        val height: Int,
        val bitrateKbps: Int,
    )

    @Volatile private var running = false
    private var thread: Thread? = null
    /** -1 = 还没同步过：首次只记下服务器版本，不执行（免得上一轮留下的命令改掉本次手动参数） */
    private var lastVer = -1L

    @Volatile var status: String = "未连接"
        private set

    fun start() {
        running = true
        thread = Thread({ loop() }, "remote-ctl").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var fails = 0
        while (running) {
            try {
                syncOnce()
                fails = 0
            } catch (t: Throwable) {
                if (!running) break
                if (++fails == 1 || fails % 30 == 0) EventLog.w("RemoteControl", "同步失败: ${t.message ?: t}")
                status = "连不上控制服务器（${t.message ?: t}）"
            }
            try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun syncOnce() {
        val state = session.remoteState().put("appliedVer", lastVer)
        val body = state.toString().toByteArray(Charsets.UTF_8)
        val path = session.cfg.path
        val url = "$ENDPOINT?path=${enc(path)}&dev=${enc(deviceId())}&model=${enc(Build.MODEL)}"
        val conn = URL(url).openConnection() as HttpURLConnection
        val text = try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Log-Key", KEY)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val j = JSONObject(text)
        val ver = j.optLong("ver", 0)
        if (lastVer < 0) {
            lastVer = ver
            status = "已连接（网页打开 api.147258yql.cn/srtlog/ctl?path=$path）"
            return
        }
        if (ver == lastVer) return
        lastVer = ver
        val c = j.optJSONObject("cmd") ?: return
        val cmd = Command(
            ver = ver,
            shutterNs = c.optLong("shutterNs", 0),
            gain = c.optInt("gain", 0),
            width = c.optInt("width", 0),
            height = c.optInt("height", 0),
            bitrateKbps = c.optInt("bitrateKbps", 0),
        )
        session.applyRemote(cmd)
        status = "已执行网页命令 v$ver"
    }

    @SuppressLint("HardwareIds")
    private fun deviceId(): String =
        (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "nodev").take(8)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
