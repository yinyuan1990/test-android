package com.fz.srttest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast

/**
 * 推流前台服务（照 silu `CameraLiveService`）：`startForeground` + `PARTIAL_WAKE_LOCK`，
 * 锁屏/切后台继续推流；界面只负责参数与预览（通过 [PreviewRegistry] 挂/摘预览）。
 */
class StreamService : Service() {
    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "stream"
        private const val NOTIF_ID = 3

        /** 当前推流会话（界面读统计用）；没在推为 null */
        @Volatile var session: StreamSession? = null
            private set

        /** 参数已由界面存进 SharedPreferences，服务启动时读取 */
        fun start(ctx: Context) {
            val i = Intent(ctx, StreamService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, StreamService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (session == null) startSession()
        return START_NOT_STICKY
    }

    private fun startSession() {
        val cfg = StreamConfig.load(this)
        val s = StreamSession(applicationContext, cfg) { msg -> ui.post { toast(msg) } }
        try {
            acquireWakeLock()
            s.start()
            session = s
            Log.i(TAG, "推流开始 source=${cfg.source} ${cfg.width}x${cfg.height}@${cfg.fps} ${cfg.bitrateKbps}kbps")
        } catch (t: Throwable) {
            EventLog.w(TAG, "推流启动失败: ${t.message ?: t}")
            toast(t.message ?: "启动失败")
            try { s.stop() } catch (_: Throwable) {}
            releaseWakeLock()
            stopSelf()
        }
    }

    override fun onDestroy() {
        EventLog.i(TAG, "推流服务结束")
        session?.stop()
        session = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "推流", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else
            @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("SRT 画质测试")
            .setContentText("正在推流（锁屏/后台继续）")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SrtTest::Stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
