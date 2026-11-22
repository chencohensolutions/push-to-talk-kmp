package com.chencohen.ptt

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.IBinder
import android.telecom.DisconnectCause
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.chencohen.ptt.talk.TalkHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Talk outside the screen (docs/design.md, "Phase 5a"; the plan's "Background delivery and battery"). With Stay
// connected off nothing runs in the background: a `talk` push wakes the phone, and a burst it should play aloud is
// registered with Telecom as a talk session (Core-Telecom's addCall, answered by the app itself), which the system
// backs with a phone-call service, so Android 17 lets it play. If that can't start, or the chat is Notify only, a
// notification with Play and Reply instead. Stay connected keeps /ws in a foreground service started from Settings.

/** The platform's part in playing talk (TalkHost): the talk session, and the talk notifications. */
class AndroidTalkHost(private val app: TalkApp) : TalkHost {
    override suspend fun prepareToPlay(chat: String, from: String): Boolean {
        if (StayConnectedService.running) return true
        return TalkSession.begin(app, chat, from)
    }

    override fun talkNotify(chat: String, from: String, clip: String) {
        app.scope.launch { TalkNotifications.show(app, chat, from, clip) }
    }

    override fun idle() = TalkSession.endSoon(app)
}

/**
 * The talk session: one self-answered incoming Telecom call ("Talk · Family") while woken bursts play, ended 20 s
 * after the last one so a reply can follow. Its ongoing notification has Leave.
 */
object TalkSession {
    private const val TAG = "TalkSession"
    private const val REPLY_WAIT = 20_000L

    @Volatile
    var active = false
        private set

    private var call: Job? = null
    private var ending: Job? = null
    private var leave: CompletableDeferred<Unit>? = null
    private var registered = false

    /** Starts the session if none runs; true once Telecom made it active (within a few seconds). */
    suspend fun begin(app: TalkApp, chat: String, from: String): Boolean {
        ending?.cancel()
        if (active) return true
        val ready = CompletableDeferred<Boolean>()
        val done = CompletableDeferred<Unit>()
        leave = done
        val name = runCatching { chatName(app, chat) }.getOrDefault(chat)
        call = app.scope.launch {
            try {
                val manager = CallsManager(app)
                if (!registered) {
                    manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                    registered = true
                }
                val attributes = CallAttributesCompat(
                    displayName = app.getString(R.string.talk_session_name, name),
                    address = Uri.fromParts("ptt", chat, null),
                    direction = CallAttributesCompat.DIRECTION_INCOMING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
                )
                manager.addCall(
                    attributes,
                    onAnswer = {},
                    onDisconnect = { done.complete(Unit) },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    launch {
                        // Answered by the app itself: a talk session isn't a call the user picks up.
                        answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                        launch { toLoudspeaker() }
                        active = true
                        TalkSessionService.start(app, name)
                        ready.complete(true)
                        done.await()
                        disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "no talk session", e)
                ready.complete(false)
            } finally {
                active = false
                TalkSessionService.stop(app)
            }
        }
        return withTimeoutOrNull(4_000) { ready.await() } ?: false.also { leave() }
    }

    /**
     * A walkie-talkie is heard on the loudspeaker. Telecom starts an audio call on the earpiece, at the earpiece's
     * call volume, so a burst played in the background sounded like a phone call (2026-11-22, hen). Move the session
     * to the speaker unless a headset or Bluetooth device is the route; the volume buttons then set the speaker's
     * call volume while the session lasts.
     */
    private suspend fun CallControlScope.toLoudspeaker() {
        val current = runCatching { currentCallEndpoint.first() }.getOrNull() ?: return
        if (current.type != CallEndpointCompat.TYPE_EARPIECE) return
        val speaker = runCatching { availableEndpoints.first() }.getOrNull()
            ?.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER } ?: return
        val result = requestEndpointChange(speaker)
        Log.i(TAG, "talk session to the loudspeaker: $result")
    }

    /** Nothing plays: the session ends after the reply time unless another burst comes. */
    fun endSoon(app: TalkApp) {
        if (!active) return
        ending?.cancel()
        ending = app.scope.launch {
            delay(REPLY_WAIT)
            leave()
        }
    }

    /** Ends the session now (Leave, or the reply time is up). */
    fun leave() {
        leave?.complete(Unit)
        leave = null
    }
}

/**
 * The phone-call service Telecom's talk session runs in, with its ongoing call-style notification ("Talk · Family",
 * Leave). Started only once Telecom answered the session.
 */
class TalkSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            TalkSession.leave()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val leave = PendingIntent.getService(
            this, 0, Intent(this, TalkSessionService::class.java).setAction(ACTION_LEAVE), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val person = Person.Builder().setName(getString(R.string.talk_session_name, name)).build()
        val notification = NotificationCompat.Builder(this, TalkNotifications.SESSION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, leave))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        try {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (e: Exception) {
            Log.w("TalkSession", "no foreground service", e)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 41
        private const val ACTION_LEAVE = "com.chencohen.ptt.TALK_LEAVE"
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) {
            runCatching { context.startForegroundService(Intent(context, TalkSessionService::class.java).putExtra(EXTRA_NAME, name)) }
                .onFailure { Log.w("TalkSession", "can't start the service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TalkSessionService::class.java))
        }
    }
}

/**
 * Settings › Talk › Stay connected: /ws kept in the background, with an ongoing notification and Turn off. Started
 * from the screen, so it may use the microphone and play (its while-in-use rights), and a headset or Bluetooth talk
 * button works with the screen off (its media session).
 */
class StayConnectedService : Service() {
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as TalkApp
        if (intent?.action == ACTION_TURN_OFF) {
            app.scope.launch { turnOff(app) }
            return START_NOT_STICKY
        }
        val off = PendingIntent.getService(this, 0, Intent(this, StayConnectedService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, TalkNotifications.STAY_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.stay_title))
            .setContentText(getString(R.string.stay_body))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stay_turn_off), off)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            (if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        try {
            startForeground(ID, notification, types)
        } catch (e: Exception) {
            // Not started from the screen (after a restart): Android says no; the setting shows Tap to resume.
            Log.w("StayConnected", "can't run in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (session == null) session = mediaSession(app)
        app.scope.launch { keepConnected(app, true) }
        return START_STICKY
    }

    /** A headset's or a Bluetooth talk button's press and release, while it runs. */
    private fun mediaSession(app: TalkApp): MediaSession = MediaSession(this, "talk").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                if (!HardwareButtons.isTalkKey(event.keyCode) || event.repeatCount > 0) return false
                app.scope.launch { talkButton(app, event.action == KeyEvent.ACTION_DOWN) }
                return true
            }
        })
        setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f).setActions(PlaybackState.ACTION_PLAY_PAUSE).build())
        isActive = true
    }

    override fun onDestroy() {
        running = false
        session?.release()
        session = null
        val app = application as TalkApp
        app.scope.launch { keepConnected(app, false) }
        super.onDestroy()
    }

    companion object {
        private const val ID = 42
        private const val ACTION_TURN_OFF = "com.chencohen.ptt.STAY_OFF"

        @Volatile
        var running = false
            private set

        /** Turns it on or off (from Settings, while the app is on screen). */
        fun set(context: Context, on: Boolean) {
            if (on) {
                runCatching { context.startForegroundService(Intent(context, StayConnectedService::class.java)) }
                    .onFailure { Log.w("StayConnected", "can't start", it) }
            } else {
                context.stopService(Intent(context, StayConnectedService::class.java))
            }
        }

        private suspend fun turnOff(app: TalkApp) {
            setStayConnectedSetting(app, false)
            set(app, false)
        }
    }
}

/** A talk button: a held volume key (on screen), a headset's button, or a Bluetooth talk button. */
object HardwareButtons {
    fun isTalkKey(code: Int): Boolean = code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        code == KeyEvent.KEYCODE_CALL

    fun isVolumeKey(code: Int): Boolean = code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP
}

/** Talk's notifications: someone talked (Play, Reply), the talk session's, and Stay connected's. */
object TalkNotifications {
    const val TALK_CHANNEL = "talk"
    const val SESSION_CHANNEL = "talk_session"
    const val STAY_CHANNEL = "stay_connected"
    const val ACTION_PLAY = "com.chencohen.ptt.PLAY_CLIP"
    const val ACTION_TALK = "com.chencohen.ptt.TALK_BACK"
    const val EXTRA_CLIP = "clip"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(TALK_CHANNEL, context.getString(R.string.talk_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.talk_channel_description) },
        )
        manager.createNotificationChannel(NotificationChannel(SESSION_CHANNEL, context.getString(R.string.talk_session_channel), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(STAY_CHANNEL, context.getString(R.string.stay_channel), NotificationManager.IMPORTANCE_LOW))
    }

    /** "Dana is talking" (in a group, "Dana is talking in Family"), with Play and Reply, which open the app. */
    suspend fun show(context: Context, chat: String, from: String, clip: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val who = runCatching { chatName(context, from) }.getOrDefault(from)
        val title = if (com.chencohen.ptt.messaging.Group.idOf(chat) != null) {
            context.getString(R.string.talk_notify_group, who, runCatching { chatName(context, chat) }.getOrDefault(chat))
        } else {
            context.getString(R.string.talk_notify, who)
        }
        val id = (chat.hashCode() and 0x3fffffff) * 2
        fun open(action: String, request: Int) = PendingIntent.getActivity(
            context, request,
            Intent(context, MainActivity::class.java).setAction(action).putExtra(Notifications.EXTRA_USER, chat).putExtra(EXTRA_CLIP, clip)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(context, TALK_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.talk_notify_body))
            .setContentIntent(open(ACTION_PLAY, id))
            .addAction(0, context.getString(R.string.talk_notify_play), open(ACTION_PLAY, id))
            .addAction(0, context.getString(R.string.talk_notify_reply), open(ACTION_TALK, id + 1))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify("talk", id, notification)
    }
}
