package com.chencohen.ptt

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.MessageNotifier
import com.chencohen.ptt.messaging.TextMessage
import kotlinx.coroutines.launch

/**
 * Notifications for texts (docs/screens/notifications.md): one per conversation, Android's messaging style, with
 * Reply (typed in the notification) and Mark as read. A text read here or on another phone of the user takes it down.
 * Nothing of a text goes through Google: FCM only woke the phone, and the text was read from the server's queue.
 */
class Notifications(private val context: Context) : MessageNotifier {
    override fun incoming(contact: Contact, unread: List<TextMessage>) {
        val name = contact.name
        val sender = Person.Builder().setName(name).setKey(contact.user).build()
        val me = Person.Builder().setName(context.getString(R.string.notify_you)).build()
        val style = NotificationCompat.MessagingStyle(me)
        unread.takeLast(MAX_LINES).forEach { style.addMessage(lineOf(it), it.at, sender) }
        show(contact.user, style, unread.size)
    }

    /** A group's: the group's name on top, each line with who wrote it. */
    override fun incomingGroup(group: Group, unread: List<TextMessage>) {
        val me = Person.Builder().setName(context.getString(R.string.notify_you)).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(group.name.ifBlank { context.getString(R.string.group_unnamed) })
            .setGroupConversation(true)
        unread.takeLast(MAX_LINES).forEach { m ->
            val author = m.author.orEmpty()
            style.addMessage(lineOf(m), m.at, Person.Builder().setName(author).setKey(author).build())
        }
        show(group.chat, style, unread.size)
    }

    /** [user]: the conversation's key (a contact, or a group's chat). */
    private fun show(user: String, style: NotificationCompat.MessagingStyle, count: Int) {
        if (count == 0) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = idOf(user)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_USER, user)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = replyAction(context, user, id)
        val read = readAction(context, user, id)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(reply)
            .addAction(read)
            .setNumber(count)
            .build()
        NotificationManagerCompat.from(context).notify(TAG, id, notification)
    }

    /** A text's line, or "Voice message" / "Talk" for a clip. */
    private fun lineOf(message: TextMessage): String = when (message.clip?.kind) {
        null -> message.body
        com.chencohen.ptt.messaging.ClipKind.VOICE -> context.getString(R.string.chats_voice)
        com.chencohen.ptt.messaging.ClipKind.BURST -> context.getString(R.string.chats_burst)
    }

    override fun read(user: String) {
        NotificationManagerCompat.from(context).cancel(TAG, idOf(user))
    }

    companion object {
        /** Reply with a typed or dictated text (Android Auto reads and dictates), without opening the app. */
        fun replyAction(context: Context, user: String, id: Int): NotificationCompat.Action = NotificationCompat.Action.Builder(
            0, context.getString(R.string.notify_reply),
            PendingIntent.getBroadcast(
                context, id,
                Intent(context, NotificationActions::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_USER, user),
                // Mutable: Android puts the typed reply into it.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.notify_reply_hint)).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        fun readAction(context: Context, user: String, id: Int): NotificationCompat.Action = NotificationCompat.Action.Builder(
            0, context.getString(R.string.notify_mark_read),
            PendingIntent.getBroadcast(
                context, id + 1,
                Intent(context, NotificationActions::class.java).setAction(ACTION_READ).putExtra(EXTRA_USER, user),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()

        const val CHANNEL = "messages"
        const val ACTION_OPEN = "com.chencohen.ptt.OPEN_CHAT"
        const val ACTION_REPLY = "com.chencohen.ptt.REPLY"
        const val ACTION_READ = "com.chencohen.ptt.MARK_READ"
        const val EXTRA_USER = "user"
        const val KEY_REPLY = "reply"
        private const val TAG = "chat"
        private const val MAX_LINES = 7

        /** Even ids, so a conversation's Mark as read (id + 1) never meets another's. */
        private fun idOf(user: String): Int = (user.hashCode() and 0x3fffffff) * 2

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.notify_channel_description) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/** Reply and Mark as read, from a notification: done with a short connection, then the notification goes. */
class NotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val user = intent.getStringExtra(Notifications.EXTRA_USER) ?: return
        val app = context.applicationContext as TalkApp
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    Notifications.ACTION_REPLY -> {
                        val body = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim()
                        if (!body.isNullOrEmpty()) replyFromNotification(app, user, body)
                    }
                    Notifications.ACTION_READ -> markReadFromNotification(app, user)
                }
            } finally {
                Notifications(app).read(user)
                pending.finish()
            }
        }
    }
}
