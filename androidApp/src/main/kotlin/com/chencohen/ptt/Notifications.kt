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
        if (unread.isEmpty()) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val name = contact.displayName ?: contact.user
        val sender = Person.Builder().setName(name).setKey(contact.user).build()
        val me = Person.Builder().setName(context.getString(R.string.notify_you)).build()
        val style = NotificationCompat.MessagingStyle(me)
        unread.takeLast(MAX_LINES).forEach { style.addMessage(it.body, it.at, sender) }
        val id = idOf(contact.user)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_USER, contact.user)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(
            0, context.getString(R.string.notify_reply),
            PendingIntent.getBroadcast(
                context, id,
                Intent(context, NotificationActions::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_USER, contact.user),
                // Mutable: Android puts the typed reply into it.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.notify_reply_hint)).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(
            0, context.getString(R.string.notify_mark_read),
            PendingIntent.getBroadcast(
                context, id + 1,
                Intent(context, NotificationActions::class.java).setAction(ACTION_READ).putExtra(EXTRA_USER, contact.user),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(reply)
            .addAction(read)
            .setNumber(unread.size)
            .build()
        NotificationManagerCompat.from(context).notify(TAG, id, notification)
    }

    override fun read(user: String) {
        NotificationManagerCompat.from(context).cancel(TAG, idOf(user))
    }

    companion object {
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
