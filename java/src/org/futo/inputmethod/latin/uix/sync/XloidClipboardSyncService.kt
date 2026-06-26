package org.futo.inputmethod.latin.uix.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.getSettingFlow
import org.futo.inputmethod.latin.uix.settings.SettingsActivity

class XloidClipboardSyncService : Service() {
    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationId,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationId, buildNotification())
        }

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = serviceScope
        XloidClipboardSync.start(this, serviceScope)

        serviceScope.launch {
            applicationContext.getSettingFlow(XloidClipboardSyncEnabled).collectLatest { enabled ->
                if (!enabled) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!applicationContext.getSettingBlocking(XloidClipboardSyncEnabled)) {
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        XloidClipboardSync.stop()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ChannelId,
            getString(R.string.clipboard_sync_notification_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            description = getString(R.string.clipboard_sync_notification_body)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.clipboard_sync_notification_title))
            .setContentText(getString(R.string.clipboard_sync_notification_body))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, SettingsActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    companion object {
        private const val Tag = "XloidClipboardSyncSvc"
        private const val ChannelId = "XLOID_CLIPBOARD_SYNC"
        private const val NotificationId = 48123

        @JvmStatic
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, XloidClipboardSyncService::class.java)
                )
            } catch (e: Exception) {
                Log.w(Tag, "Failed to start sync service", e)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, XloidClipboardSyncService::class.java)
            )
        }

        @JvmStatic
        fun startIfEnabled(context: Context) {
            if (context.applicationContext.getSettingBlocking(XloidClipboardSyncEnabled)) {
                start(context)
            } else {
                stop(context)
            }
        }
    }
}
