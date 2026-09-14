package com.sakhand.downloadmanager.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sakhand.downloadmanager.MainActivity
import com.sakhand.downloadmanager.R
import com.sakhand.downloadmanager.data.DownloadStatus
import com.sakhand.downloadmanager.util.formatBytes
import com.sakhand.downloadmanager.util.formatSpeed
import com.sakhand.downloadmanager.util.toFa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * سرویس پیش‌زمینه: نوتیفیکیشن زنده پیشرفت دانلود
 * با دکمه‌های توقف/ادامه/لغو روی خود نوتیفیکیشن
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification())

        when (intent?.action) {
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { DownloadManager.pause(it) }
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let { DownloadManager.resume(it) }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { DownloadManager.cancel(it) }
        }

        if (!observing) {
            observing = true
            scope.launch {
                DownloadManager.items.collect { list ->
                    if (list.any { it.isActive }) {
                        notify(buildNotification())
                    } else {
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        observing = false
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val active = DownloadManager.items.value.filter { it.isActive }
        val primary = active.firstOrNull()

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (active.size > 1) {
            "در حال دانلود ${toFa(active.size.toLong())} فایل"
        } else {
            "در حال دانلود"
        }

        var progressMax = 0
        var progress = 0
        var indeterminate = true
        val text = if (primary == null) {
            "آماده دانلود"
        } else {
            if (primary.totalBytes > 0) {
                val percent = (primary.downloadedBytes * 100) / primary.totalBytes
                progressMax = 100
                progress = percent.toInt().coerceIn(0, 100)
                indeterminate = false
                "${toFa(percent)}٪ — ${formatSpeed(primary.speedBps)}\n${primary.fileName}"
            } else {
                "${formatBytes(primary.downloadedBytes)} — ${formatSpeed(primary.speedBps)}\n${primary.fileName}"
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setProgress(progressMax, progress, indeterminate)

        if (primary != null) {
            val pauseIntent = serviceIntent(ACTION_PAUSE, primary.id, 1)
            val resumeIntent = serviceIntent(ACTION_RESUME, primary.id, 2)
            val cancelIntent = serviceIntent(ACTION_CANCEL, primary.id, 3)

            when (primary.status) {
                DownloadStatus.DOWNLOADING,
                DownloadStatus.CONNECTING,
                DownloadStatus.QUEUED -> builder.addAction(0, "توقف", pauseIntent)

                DownloadStatus.PAUSED -> builder.addAction(0, "ادامه", resumeIntent)

                else -> Unit
            }
            builder.addAction(0, "لغو", cancelIntent)
        }
        return builder.build()
    }

    private fun serviceIntent(action: String, id: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notify(notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "دانلودها",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "نمایش پیشرفت زنده دانلودها"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "sakhand_downloads"
        private const val NOTIF_ID = 1001
        private const val EXTRA_ID = "id"

        const val ACTION_PAUSE = "com.sakhand.action.PAUSE"
        const val ACTION_RESUME = "com.sakhand.action.RESUME"
        const val ACTION_CANCEL = "com.sakhand.action.CANCEL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
