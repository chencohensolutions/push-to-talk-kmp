package com.chencohen.ptt

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.talk.TalkUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The "Talk playing" notification (docs/screens/notifications.md, "Talk"; 2026-11-22, hen): while a woken burst
 * plays, the sender and how long, with Talk back, Stop and Mute 1 h; after it, for a minute, Play again and Talk back,
 * and for 30 s a hardware talk button replies to the sender (TalkReply). On its own channel, high importance without
 * a sound of its own: the burst is the sound.
 */
object TalkPlaying {
    const val CHANNEL = "talk_playing"
    private const val ID = 45
    private const val TAG = "TalkPlaying"

    private var watching: Job? = null
    /** The clip the screen was woken for, once each. */
    private var wokeFor: String? = null
    private var shown: Pair<String, String>? = null
    private var lastClip: String? = null

    /** A woken burst is about to play (AndroidTalkHost): follow what plays until it is over. */
    fun watch(app: TalkApp) {
        if (watching?.isActive == true) return
        watching = app.scope.launch {
            messagingState(app).map { it.talk }.distinctUntilChanged().collect { update(app, it) }
        }
    }

    private suspend fun update(app: TalkApp, talk: TalkUiState) {
        val playing = talk.playing
        if (playing != null) {
            shown = playing.chat to playing.from
            lastClip = playing.clip
            post(app, playing.chat, playing.from, playing.playedMs, live = true)
        } else {
            val (chat, from) = shown ?: return
            shown = null
            TalkReply.open(chat)
            post(app, chat, from, 0, live = false)
        }
    }

    private suspend fun post(app: TalkApp, chat: String, from: String, playedMs: Long, live: Boolean) {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val who = runCatching { chatName(app, from) }.getOrDefault(from)
        val title = if (Group.idOf(chat) != null) {
            app.getString(R.string.playing_group, who, runCatching { chatName(app, chat) }.getOrDefault(chat))
        } else {
            who
        }
        val seconds = playedMs / 1000
        val builder = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(
                if (live) app.getString(R.string.playing_now, "%d:%02d".format(seconds / 60, seconds % 60))
                else app.getString(R.string.playing_done),
            )
            .setSubText(app.getString(R.string.playing_reply_hint))
            .setContentIntent(openTalk(app, chat))
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(talkBackAction(app, chat))
        if (live && lastClip != wokeFor && wakes(app, chat)) {
            wokeFor = lastClip
            // Screen off or locked: Android opens it (the Talk screen over the lock screen); in use: a heads-up only.
            builder.setFullScreenIntent(wakeTalk(app, chat), true)
            Log.i(TAG, "opening the Talk screen for $chat")
        }
        if (live) {
            builder.setOngoing(true)
                .addAction(0, app.getString(R.string.playing_stop), receiver(app, TalkActionReceiver.ACTION_STOP, chat))
                .addAction(0, app.getString(R.string.playing_mute, who), receiver(app, TalkActionReceiver.ACTION_MUTE, chat))
        } else {
            lastClip?.let { builder.addAction(0, app.getString(R.string.playing_again), receiver(app, TalkActionReceiver.ACTION_PLAY_AGAIN, chat, it)) }
            builder.setTimeoutAfter(60_000).setAutoCancel(true)
        }
        NotificationManagerCompat.from(app).notify(ID, builder.build())
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    /**
     * Open Talk when someone talks to me (Settings › Talk, off by default): only with the screen off, when Android
     * allows full-screen intents, Do Not Disturb is off, and the chat isn't muted.
     */
    private fun wakes(app: TalkApp, chat: String): Boolean {
        if (!messagingState(app).value.talkSettings.openOnTalk) return false
        // Only when the screen was off: in use (even on the lock screen) the heads-up is enough (hen, 17:07).
        if (app.getSystemService(android.os.PowerManager::class.java).isInteractive) return false
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) return false
        if (nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) return false
        return !muted(app, chat)
    }

    private fun wakeTalk(context: Context, chat: String) = PendingIntent.getActivity(
        context, chat.hashCode() + 2,
        Intent(context, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat)
            .putExtra(MainActivity.EXTRA_WAKE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Talk back: tap to talk to [chat] (TalkReadyService records), needs the phone unlocked. */
    fun talkBackAction(context: Context, chat: String): NotificationCompat.Action {
        val intent = Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_TALK_BACK).putExtra(TalkReadyService.EXTRA_CHAT, chat)
        val pending = PendingIntent.getForegroundService(context, chat.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, context.getString(R.string.playing_talk_back), pending)
            .apply { if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }
            .build()
    }

    private fun openTalk(context: Context, chat: String) = PendingIntent.getActivity(
        context, chat.hashCode() + 1,
        Intent(context, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun receiver(context: Context, action: String, chat: String, clip: String? = null) = PendingIntent.getBroadcast(
        context, (action + chat).hashCode(),
        Intent(context, TalkActionReceiver::class.java).setAction(action).putExtra(TalkReadyService.EXTRA_CHAT, chat).putExtra(TalkNotifications.EXTRA_CLIP, clip),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Mute 1 h: bursts from [chat] show a notification instead of playing until then. */
    fun mute(context: Context, chat: String, until: Long = System.currentTimeMillis() + 60 * 60 * 1000L) {
        context.getSharedPreferences("talk_mute", Context.MODE_PRIVATE).edit().putLong(chat, until).apply()
        Log.i(TAG, "muted $chat for an hour")
    }

    fun muted(context: Context, chat: String, now: Long = System.currentTimeMillis()): Boolean =
        context.getSharedPreferences("talk_mute", Context.MODE_PRIVATE).getLong(chat, 0) > now
}

/** Stop, Mute 1 h and Play again from the "Talk playing" notification. */
class TalkActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TalkApp
        val chat = intent.getStringExtra(TalkReadyService.EXTRA_CHAT) ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    ACTION_STOP -> stopTalkPlayback(app)
                    ACTION_MUTE -> {
                        TalkPlaying.mute(app, chat)
                        stopTalkPlayback(app)
                        TalkPlaying.cancel(app)
                    }
                    ACTION_PLAY_AGAIN -> intent.getStringExtra(TalkNotifications.EXTRA_CLIP)?.let { clip ->
                        // A tap: the ready service may play (while-in-use from the notification).
                        if (TalkReady.play(app)) {
                            TalkPlaying.watch(app)
                            playClip(app, clip)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.chencohen.ptt.TALK_STOP"
        const val ACTION_MUTE = "com.chencohen.ptt.TALK_MUTE"
        const val ACTION_PLAY_AGAIN = "com.chencohen.ptt.TALK_PLAY_AGAIN"
    }
}
