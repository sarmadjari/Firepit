package com.getfirepit.app.notifications

import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.getfirepit.app.MainActivity
import com.getfirepit.app.R
import com.getfirepit.core.data.ChatPresence
import com.getfirepit.app.settings.NotificationPreferences
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.database.ChannelStateDao
import com.getfirepit.core.database.observeMuted
import com.getfirepit.core.model.ChatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Raises a notification for messages that arrive while nobody is reading them.
 *
 * Deliberately quiet: muted rooms, the conversation currently on screen, and
 * anything already seen produce nothing.
 */
@Singleton
class MessageNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mesh: MeshRepository,
    private val channelState: ChannelStateDao,
    private val presence: ChatPresence,
    private val notificationPreferences: NotificationPreferences,
    private val rooms: RoomRepository,
    @param:com.getfirepit.core.data.ApplicationScope private val scope: CoroutineScope,
) {

    fun start() {
        createChannel()
        scope.launch {
            mesh.incomingMessages.collect { message -> notifyIfUnseen(message) }
        }
    }

    private suspend fun notifyIfUnseen(message: ChatMessage) {
        if (presence.isWatching(message.channel)) return
        if (message.channel in channelState.observeMuted().first()) return

        val name = rooms.observePersonCards().first()[message.fromNodeNum]
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: mesh.observeNodes().first()
                .firstOrNull { it.nodeNum == message.fromNodeNum }
                ?.displayName
            ?: "Unknown node"
        val room = mesh.channels.value.firstOrNull { it.index == message.channel }?.displayName

        // A direct message opens the person; anything else opens its room.
        val direct = message.isDirect
        val open = PendingIntent.getActivity(
            context,
            if (direct) message.fromNodeNum else message.channel,
            Intent(context, MainActivity::class.java)
                .apply {
                    if (direct) putExtra(EXTRA_PEER, message.fromNodeNum) else putExtra(EXTRA_CHANNEL, message.channel)
                }
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Who, where and what only when asked for: a notification is read by
        // whoever is looking at the phone, and copied to notification history
        // and to any app allowed to read notifications.
        val showText = notificationPreferences.showText.value
        val title = if (showText) room?.let { "$name in $it" } ?: name else context.getString(R.string.app_name)
        val body = if (showText) message.text else "New message"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio_notification)
            .setContentTitle(title)
            .setContentText(body)
            .apply { if (showText) setStyle(NotificationCompat.BigTextStyle().bigText(message.text)) }
            // Not bridged to a watch: that is another screen, in plainer view.
            .setLocalOnly(true)
            // Private, with a public version that names nobody: on a secure lock
            // screen set to hide sensitive content, that is all a passer-by
            // sees. Whether sensitive content shows there at all is the phone
            // owner's own lock screen setting, which no app can override.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_radio_notification)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText("New message")
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setLocalOnly(true)
                    .build(),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // One notification per conversation, replaced as it moves on.
            .setGroup(GROUP)
            .build()

        // Checked rather than caught: a denied permission is a standing state,
        // not an error, and swallowing it would leave someone believing they
        // were being alerted to messages they never saw. Inline because lint
        // only recognises the guard at the call site.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "notification suppressed: POST_NOTIFICATIONS not granted")
            return
        }
        NotificationManagerCompat.from(context).notify(message.channel, notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.message_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.message_channel_description) }
        context.getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "FirepitNotify"

        const val EXTRA_CHANNEL = "com.getfirepit.app.CHANNEL"
        const val EXTRA_PEER = "com.getfirepit.app.PEER"
        private const val CHANNEL_ID = "messages"
        private const val GROUP = "com.getfirepit.app.MESSAGES"
    }
}
