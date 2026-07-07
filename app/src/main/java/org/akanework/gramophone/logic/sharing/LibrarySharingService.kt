package org.akanework.gramophone.logic.sharing

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.fragments.settings.LibrarySharingSettingsActivity

class LibrarySharingService : Service() {
    companion object {
        private const val NOTIFY_CHANNEL_ID = "librarySharing"
        private const val NOTIFY_ID = 4

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LibrarySharingService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LibrarySharingService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startInForeground()
        acquireLocks()
        LibrarySharingManager.ensureServerRunningFromService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LibrarySharingManager.shouldKeepAliveServer()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        acquireLocks()
        LibrarySharingManager.ensureServerRunningFromService()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val settingsIntent = Intent(this, LibrarySharingSettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gramophone_monochrome)
            .setContentTitle(getString(R.string.library_sharing_notification_title))
            .setContentText(getString(R.string.library_sharing_notification_text))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFY_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            runCatching {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:LibrarySharing"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                wakeLock = null
            }
        }
        if (wifiLock == null) {
            runCatching {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "$packageName:LibrarySharing"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }.onFailure {
                wifiLock = null
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun createNotificationChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(
                NOTIFY_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            ).apply {
                setName(getString(R.string.library_sharing_notification_channel))
                setVibrationEnabled(false)
                setLightsEnabled(false)
                setShowBadge(false)
                setSound(null, null)
            }.build()
        )
    }
}
