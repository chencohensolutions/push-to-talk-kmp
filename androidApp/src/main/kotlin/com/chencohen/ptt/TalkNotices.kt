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

    /** A quiet record in the shade (low importance, no sound or heads-up): a burst that woke the phone, after it. */
    const val RECORD_CHANNEL = "talk_record"
    private const val ID = 45
    private const val TAG = "TalkPlaying"

    private var watching: Job? = null
    /** The clip the screen was woken for, once each. */
    private var wokeFor: String? = null
    private var shown: Pair<String, String>? = null
    private var lastClip: String? = null

    /**
     * The phone was woken for the burst playing now, its Talk screen over the lock screen (2026-11-23, hen: "if video
     * is displayed when the screen was off, there's no need for a notification"): no notification, sound or bubble
     * while it plays; messages meanwhile wait (Notifications) and everything is posted quietly after.
     */
    @Volatile var quiet = false
        private set

    /** A voice burst that played with the screen off: its notifications go on the quiet channel (no heads-up). */
    private var dark: String? = null

    /** When [quiet] ends (Notifications posts what it held, silently). */
    @Volatile var onQuietEnd: (() -> Unit)? = null

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
            if (shown == null) TalkWidgets.show(app, playing.chat, com.chencohen.ptt.talk.TalkWidgetView.Incoming(playing.from))
            shown = playing.chat to playing.from
            if (lastClip != playing.clip) WakeTimeline.mark("burst plays")
            lastClip = playing.clip
            post(app, playing.chat, playing.from, playing.playedMs, live = true, video = playing.video)
            if (!quiet && TalkBubble.allowed(app)) TalkBubble.show(app, playing.chat, TalkBubble.titleOf(app, playing.chat, playing.from), live = true, video = playing.video)
        } else {
            val (chat, from) = shown ?: return
            shown = null
            TalkWidgets.show(app, chat, com.chencohen.ptt.talk.TalkWidgetView.Idle)
            TalkReply.open(chat)
            post(app, chat, from, 0, live = false)
            if (quiet) {
                Log.i(TAG, "woken burst over: a quiet record in the shade, no bubble")
                quiet = false
                onQuietEnd?.invoke()
            } else {
                TalkBubble.show(app, chat, TalkBubble.titleOf(app, chat, from), live = false)
            }
        }
    }

    private suspend fun post(app: TalkApp, chat: String, from: String, playedMs: Long, live: Boolean, video: Boolean = false) {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val who = runCatching { chatName(app, from) }.getOrDefault(from)
        val title = if (Group.idOf(chat) != null) {
            app.getString(R.string.playing_group, who, runCatching { chatName(app, chat) }.getOrDefault(chat))
        } else {
            who
        }
        val seconds = playedMs / 1000
        // Only video wakes the screen (2026-11-23, hen: "if it's only voice PTT, the screen should stay off"); a voice
        // burst that turns into video wakes it then.
        val asleep = !app.getSystemService(android.os.PowerManager::class.java).isInteractive
        if (live && lastClip != wokeFor && video && wakes(app, chat)) {
            wokeFor = lastClip
            quiet = true
            wake(app, chat, title)
        } else if (live && asleep && lastClip != wokeFor) {
            if (lastClip != dark) Log.i(TAG, "${if (video) "video" else "voice"} burst while the screen is off, not woken: a silent notification without heads-up")
            dark = lastClip
        }
        // Woken for it: nothing over the video while it plays; after, a quiet record.
        if (live && quiet) {
            NotificationManagerCompat.from(app).cancel(ID)
            return
        }
        // The bubble or the app shows the burst already (2026-11-23, hen: "when video is pushed I can still see the
        // notification"): only a quiet record in the shade, no heads-up.
        val shownElsewhere = TalkBubble.showing || chatInFront(app) == chat
        val builder = NotificationCompat.Builder(app, if ((!live && quiet) || lastClip == dark || shownElsewhere) RECORD_CHANNEL else CHANNEL)
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

    /** The woken Talk screen is up: the full-screen notification that may have opened it goes. */
    fun wokenShown(context: Context) {
        woken = true
        NotificationManagerCompat.from(context).cancel(WAKE_ID)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
        NotificationManagerCompat.from(context).cancel(WAKE_ID)
    }

    private const val WAKE_ID = 46

    /**
     * Turns the screen on with [chat]'s Talk screen over the lock screen (2026-11-22, hen: it didn't). Android 14+
     * gives full-screen intents only to apps the user allowed; and one fires only when its notification is new, while
     * the playing notification was updated in place (the last burst's often still showed), so it never fired. Now:
     * with "Display over other apps" (the floating window's), the activity is started straight away, which Android
     * allows such apps from the background; else a new notification of its own carries the full-screen intent.
     */
    private fun wake(app: TalkApp, chat: String, title: String) {
        WakeTimeline.mark("waking the screen")
        val intent = wakeIntent(app, chat)
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        if (android.provider.Settings.canDrawOverlays(app)) {
            // Android 15+ lets an app that may draw over others start an activity from the background only while one
            // of its windows shows: a tiny invisible one for a few seconds (2026-11-23).
            val shim = showShim(app)
            val started = runCatching { app.startActivity(intent) }.onFailure { Log.w(TAG, "can't open the Talk screen", it) }.isSuccess
            Log.i(TAG, "wake for $chat: started the Talk screen $started (window to allow it ${shim != null}), full-screen intents allowed $fullScreen")
            shim?.let { v -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { app.getSystemService(android.view.WindowManager::class.java).removeView(v) } }, 3_000) }
            if (started && !fullScreen) return
            if (started) {
                // Started: the full-screen notification only if the Talk screen hasn't come up in a moment (Android may
                // drop a background start without a word); otherwise it would show over the video as a notification.
                woken = false
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!woken) {
                        Log.i(TAG, "wake for $chat: the Talk screen didn't come up, full-screen intent")
                        fullScreenNotice(app, chat, title, intent)
                    }
                }, 1_200)
                return
            }
        }
        if (!fullScreen) {
            Log.i(TAG, "wake for $chat: can't turn the screen on: neither over other apps nor full-screen intents are allowed")
            return
        }
        fullScreenNotice(app, chat, title, intent)
    }

    @Volatile private var woken = false

    private fun fullScreenNotice(app: TalkApp, chat: String, title: String, intent: Intent) {
        NotificationManagerCompat.from(app).cancel(WAKE_ID)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(app.getString(R.string.playing_now, "0:00"))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(PendingIntent.getActivity(app, chat.hashCode() + 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE), true)
            .setTimeoutAfter(15_000)
            .setAutoCancel(true)
            .build()
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(app).notify(WAKE_ID, n)
        } catch (_: SecurityException) {
            return
        }
        Log.i(TAG, "full-screen intent for $chat")
    }

    /**
     * Open Talk when someone talks to me (Settings › Talk, off by default): only with the screen off, when Android
     * allows full-screen intents, Do Not Disturb is off, and the chat isn't muted.
     */
    /** A 1-pixel, untouchable, invisible window over other apps (see [wake]); null when it can't be shown. */
    private fun showShim(app: TalkApp): android.view.View? = runCatching {
        val v = android.view.View(app)
        val p = android.view.WindowManager.LayoutParams(
            1, 1, android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSPARENT,
        ).apply { title = "Talk wake" }
        app.getSystemService(android.view.WindowManager::class.java).addView(v, p)
        v
    }.onFailure { Log.w(TAG, "no window for the wake-up", it) }.getOrNull()

    /** Under 20% and not charging, now (Don't turn the screen on when battery is low). */
    private fun lowBattery(app: TalkApp): Boolean {
        val battery = app.getSystemService(android.os.BatteryManager::class.java)
        val level = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 0 until 20 && !battery.isCharging
    }

    /** Debug builds (DebugTriggers): the wake-up as a burst from [chat] would run it. */
    fun debugWake(app: TalkApp, chat: String) {
        Log.i(TAG, "debug wake for $chat: wakes ${wakes(app, chat)}")
        wake(app, chat, chat)
    }

    private fun wakes(app: TalkApp, chat: String): Boolean {
        val why = when {
            !messagingState(app).value.talkSettings.openOnTalk -> "Open Talk when someone talks to me is off"
            // Only when the screen was off: in use (even on the lock screen) the heads-up is enough (hen, 17:07).
            app.getSystemService(android.os.PowerManager::class.java).isInteractive -> "the screen is on"
            app.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL -> "Do Not Disturb"
            muted(app, chat) -> "muted"
            messagingState(app).value.talkSettings.lowBatteryNoWake && lowBattery(app) -> "battery under 20% and not charging"
            else -> null
        }
        Log.i(TAG, "wake for $chat: ${why?.let { "skipped, $it" } ?: "yes"}")
        return why == null
    }

    private fun wakeIntent(context: Context, chat: String) =
        Intent(context, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat)
            .putExtra(MainActivity.EXTRA_WAKE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Talk back: opens [chat]'s Talk screen, the big button with the microphone ready (2026-11-22, hen: not a voice
     * message); over the lock screen it asks to unlock first.
     */
    fun talkBackAction(context: Context, chat: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, context.getString(R.string.playing_talk_back), openTalk(context, chat)).build()

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
