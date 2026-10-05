package com.fz.srttest

import android.content.Context

/**
 * 一次推流会话的全部参数。默认值 = 竞品 silu 的配置（见 yql-android/docs/竞品画质调研-silu.md），
 * 全部可在界面上改，便于 A/B 对比。
 */
data class StreamConfig(
    val host: String = "",
    val port: Int = 8890,
    val path: String = "test1",
    val user: String = "",
    val pass: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 8000,
    /** QP 上限，0 = 不限 */
    val qpMax: Int = 30,
    /** MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_*：1=VBR 2=CBR */
    val bitrateMode: Int = 1,
    /** high / main / baseline */
    val profile: String = PROFILE_HIGH,
    val keyIntervalSec: Int = 1,
    val latencyMs: Int = 50,
    val disableStabilization: Boolean = true,
    /** full / limited / none（none = 不设，交给编码器默认） */
    val colorRange: String = RANGE_FULL,
) {
    /** MediaMTX 的 SRT streamid：publish:路径[:用户:密码] */
    val publishStreamId: String
        get() = if (user.isNotEmpty()) "publish:$path:$user:$pass" else "publish:$path"

    val srtPlayUrl: String get() = "srt://$host:$port?streamid=read:$path"
    val webPlayUrl: String get() = "http://$host:8889/$path"

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", host).putInt("port", port).putString("path", path)
            .putString("user", user).putString("pass", pass)
            .putInt("width", width).putInt("height", height).putInt("fps", fps)
            .putInt("bitrateKbps", bitrateKbps).putInt("qpMax", qpMax).putInt("bitrateMode", bitrateMode)
            .putString("profile", profile).putInt("keyIntervalSec", keyIntervalSec)
            .putInt("latencyMs", latencyMs).putBoolean("disableStabilization", disableStabilization)
            .putString("colorRange", colorRange)
            .apply()
    }

    companion object {
        const val PROFILE_HIGH = "high"
        const val PROFILE_MAIN = "main"
        const val PROFILE_BASELINE = "baseline"
        const val RANGE_FULL = "full"
        const val RANGE_LIMITED = "limited"
        const val RANGE_NONE = "none"
        private const val PREFS = "stream_config"

        fun load(ctx: Context): StreamConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = StreamConfig()
            return StreamConfig(
                host = p.getString("host", d.host) ?: d.host,
                port = p.getInt("port", d.port),
                path = p.getString("path", d.path) ?: d.path,
                user = p.getString("user", d.user) ?: d.user,
                pass = p.getString("pass", d.pass) ?: d.pass,
                width = p.getInt("width", d.width),
                height = p.getInt("height", d.height),
                fps = p.getInt("fps", d.fps),
                bitrateKbps = p.getInt("bitrateKbps", d.bitrateKbps),
                qpMax = p.getInt("qpMax", d.qpMax),
                bitrateMode = p.getInt("bitrateMode", d.bitrateMode),
                profile = p.getString("profile", d.profile) ?: d.profile,
                keyIntervalSec = p.getInt("keyIntervalSec", d.keyIntervalSec),
                latencyMs = p.getInt("latencyMs", d.latencyMs),
                disableStabilization = p.getBoolean("disableStabilization", d.disableStabilization),
                colorRange = p.getString("colorRange", d.colorRange) ?: d.colorRange,
            )
        }
    }
}
