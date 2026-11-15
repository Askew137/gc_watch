package com.askew137.gcwatch.wear.navigation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.askew137.gcwatch.wear.R
import com.askew137.gcwatch.wear.WearApplication
import com.askew137.gcwatch.wear.ui.WearMainActivity
import timber.log.Timber

/**
 * Keeps GC Watch alive while the screen is off: a foreground service of type "location" plus an
 * [OngoingActivity] (the small chip on the watch face that leads back into the app).
 *
 * Without it Wear OS pauses the app when the display turns off and eventually kills the process,
 * so the GPS stops and the map is stale/gone on the next wake-up. The service itself does no work:
 * it only declares that the GPS is needed ([com.askew137.gcwatch.wear.gnss.GnssRepository.setActive]),
 * which then runs at the slower "screen off" interval. It is started when the app is opened and
 * stopped when the app is closed (swipe away / "Ukončit") or from the notification.
 */
class NavigationService : Service() {

    private val gnss get() = (applicationContext as WearApplication).gnssRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Timber.i("NavigationService: stopped from the notification")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        gnss.setActive("service", true)
        Timber.i("NavigationService: running in the foreground")
        // Not sticky: if the system kills the process, do not silently restart a GPS nobody asked for.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        gnss.setActive("service", false)
        Timber.i("NavigationService: destroyed")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Navigace", NotificationManager.IMPORTANCE_LOW)
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, WearMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ongoing)
            .setContentTitle("GC Watch")
            .setContentText("Navigace běží")
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Ukončit", stop)

        // The chip on the watch face; apply() adds the extras to the builder.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_ongoing)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("Navigace běží").build())
            .build()
            .apply(applicationContext)

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1137
        private const val ACTION_STOP = "com.askew137.gcwatch.wear.STOP_NAVIGATION"

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, NavigationService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
