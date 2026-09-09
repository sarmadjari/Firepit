package com.getfirepit.app.notifications

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
import com.getfirepit.core.data.MeshRepository
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

        val name = mesh.observeNodes().first()
            .firstOrNull { it.nodeNum == message.fromNodeNum }
            ?.displayName
            ?: "Unknown node"
        val room = mesh.channels.value.firstOrNull { it.index == message.channel }?.displayName

        val open = PendingIntent.getActivity(
            context,
            message.channel,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_CHANNEL, message.channel)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio_notification)
            .setContentTitle(room?.let { "$name in $it" } ?: name)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // One notification per conversation, replaced as it moves on.
            .setGroup(GROUP)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(message.channel, notification)
        }
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
        const val EXTRA_CHANNEL = "com.getfirepit.app.CHANNEL"
        private const val CHANNEL_ID = "messages"
        private const val GROUP = "com.getfirepit.app.MESSAGES"
    }
}
