package com.getfirepit.app.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.getfirepit.app.MainActivity
import com.getfirepit.app.R
import com.getfirepit.core.transport.LinkState
import com.getfirepit.core.transport.RadioLink
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch

/**
 * Keeps the radio session alive while the app is not in the foreground.
 *
 * Deliberately not the owner of the link: [RadioLink] is a singleton and stays
 * so. This only anchors the process and mirrors the link's state into a
 * notification, so there is one place that decides what "connected" means.
 */
@AndroidEntryPoint
class RadioService : Service() {

    @Inject lateinit var link: RadioLink

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // Must be inside a few seconds of startForegroundService or the system
        // kills the process, so it happens before anything else can fail.
        startForeground(notificationFor(link.state.value))

        scope.launch {
            link.state.collect { state -> updateNotification(state) }
        }
        scope.launch {
            // The initial state is Disconnected; only a return to it after a
            // real session means the session is over.
            link.state.dropWhile { it is LinkState.Disconnected }
                .collect { state ->
                    if (state is LinkState.Disconnected) {
                        Log.i(TAG, "link disconnected; stopping service")
                        stopSelf()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { link.disconnect() }
            stopSelf()
            return START_NOT_STICKY
        }
        // Not sticky: a restarted service would have no radio to reconnect to,
        // and a notification claiming otherwise would be a lie.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateNotification(state: LinkState) {
        // Silently dropped when notifications are denied; the service still runs.
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notificationFor(state))
    }

    private fun notificationFor(state: LinkState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio_notification)
            .setContentTitle(titleFor(state))
            .setContentText(getString(R.string.radio_service_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.radio_service_disconnect), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun titleFor(state: LinkState): String = when (state) {
        is LinkState.Ready -> getString(R.string.radio_service_connected)
        LinkState.Downloading -> getString(R.string.radio_service_downloading)
        is LinkState.Connecting -> getString(R.string.radio_service_connecting)
        is LinkState.Reconnecting -> getString(R.string.radio_service_reconnecting)
        is LinkState.Unsupported -> getString(R.string.radio_service_unsupported)
        LinkState.Disconnected -> getString(R.string.radio_service_disconnected)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.radio_service_channel),
            // Low: this is a status line, not an event worth interrupting for.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.radio_service_channel_description)
            setShowBadge(false)
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "FirepitService"
        private const val CHANNEL_ID = "radio_session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.getfirepit.app.STOP_RADIO"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RadioService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RadioService::class.java))
        }
    }
}
