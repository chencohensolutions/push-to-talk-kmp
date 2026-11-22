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

// Talk outside the screen (docs/design.md, "Woken by talk"). With Stay connected off nothing connects in the
// background: a `talk` push wakes the phone. Android 17 lets an app play from the background only from a foreground
// service with while-in-use rights, which a service started by a push doesn't get. So TalkReadyService, started when
// the app opens (it gets them then), waits idle with a silent notification, holding no connection; a woken burst
// plays through it as media: the loudspeaker at media volume. Without it (after a restart, stopped, or turned off in
// Settings › Talk) the burst plays in a Telecom talk session (Core-Telecom's addCall, answered by the app itself) as
// the call's voice on the loudspeaker. If neither can play, or the chat is Notify only, a notification with Play and
// Reply. Stay connected keeps /ws in a foreground service.

/** The platform's part in playing talk (TalkHost): the talk session, and the talk notifications. */
class AndroidTalkHost(private val app: TalkApp) : TalkHost {
    override suspend fun prepareToPlay(chat: String, from: String): Boolean {
        if (StayConnectedService.running) return true
        if (onScreen()) {
            Log.i(TAG, "burst plays as media: the app is on screen")
            return true
        }
        if (TalkPlaying.muted(app, chat)) {
            Log.i(TAG, "muted for now: a notification instead")
            return false
        }
        if (TalkReady.play(app)) {
            Log.i(TAG, "woken burst plays as media through Talk ready")
            TalkPlaying.watch(app)
            return true
        }
        val ok = TalkSession.begin(app, chat, from)
        if (ok) TalkPlaying.watch(app)
        Log.i(TAG, if (ok) "woken burst plays in a Telecom talk session, on the loudspeaker" else "no talk session: a notification instead")
        return ok
    }

    /** The app is on screen (its activity started): talk may play as media without a session. */
    private fun onScreen(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private companion object {
        const val TAG = "TalkHost"
    }

    override fun talkNotify(chat: String, from: String, clip: String) {
        app.scope.launch { TalkNotifications.show(app, chat, from, clip) }
    }

    override fun idle() {
        TalkReady.endSoon(app)
        TalkSession.endSoon(app)
    }
}

/**
 * Woken bursts played as media through TalkReadyService (2026-11-22, hen's idea: the app stays in the notifications,
 * not connected). The service runs from the last app open, so it has while-in-use rights; a burst takes transient
 * audio focus (others duck) while it plays and gives it back 20 s after the last one, so a reply can follow.
 */
object TalkReady {
    const val TAG = "TalkReady"
    private const val REPLY_WAIT = 20_000L

    @Volatile
    var active = false
        private set

    private var ending: Job? = null
    private var focus: android.media.AudioFocusRequest? = null

    /** Ready to play a woken burst as media: the service runs and audio focus is granted. */
    fun play(app: TalkApp): Boolean {
        ending?.cancel()
        if (active) return true
        if (!TalkReadyService.running) {
            Log.i(TAG, "not running: the call session plays it")
            return false
        }
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        val result = audio.requestAudioFocus(request)
        if (result != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Android 17 refuses focus to an app without while-in-use rights, or a phone call is on.
            Log.w(TAG, "audio focus refused ($result): the call session plays it")
            return false
        }
        focus = request
        active = true
        Log.i(TAG, "audio focus granted; media ${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}")
        return true
    }

    /** Nothing plays: the focus goes back after the reply time unless another burst comes. */
    fun endSoon(app: TalkApp) {
        if (!active) return
        ending?.cancel()
        ending = app.scope.launch {
            delay(REPLY_WAIT)
            done(app)
        }
    }

    fun done(context: Context) {
        ending?.cancel()
        ending = null
        focus?.let { context.getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        active = false
    }
}

/**
 * "Talk is ready": a media-playback foreground service started when the app opens (so it has while-in-use rights),
 * idle until a woken burst plays through it (TalkReady). No connection, no wake lock, no timer. Its notification is
 * silent and the least important, with Stop (until the next app open). Never started at boot: Android would mute it.
 */
class TalkReadyService : Service() {
    private var session: MediaSession? = null
    private var recording: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as TalkApp
        when (intent?.action) {
            ACTION_STOP -> {
                TalkReady.done(this)
                running = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND, ACTION_CANCEL -> {
                endTalkBack(app, intent.action == ACTION_SEND)
                return START_NOT_STICKY
            }
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, TalkReadyService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, TalkNotifications.READY_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.ready_title))
            .setContentText(getString(R.string.ready_body))
            .setContentIntent(open)
            .addAction(0, getString(R.string.ready_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ready = notification
        try {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            running = true
            Log.i(TalkReady.TAG, "Talk is ready")
        } catch (e: Exception) {
            // Not started from the screen: Android says no; bursts play in the call session.
            Log.w(TalkReady.TAG, "can't run in the foreground", e)
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        // A headset's or a Bluetooth talk button, the screen off too: the reply window's sender, or Buttons talk in.
        if (session == null) session = mediaSession(app)
        if (intent?.action == ACTION_TALK_BACK) intent.getStringExtra(EXTRA_CHAT)?.let { startTalkBack(app, it) }
        return START_NOT_STICKY
    }

    private var ready: android.app.Notification? = null

    /**
     * Talk back from the "Talk playing" notification: a tap, so the microphone may open in the background (while-in-use
     * from the notification). Talks to [chat] until Send, Cancel, or 60 s (sent).
     */
    private fun startTalkBack(app: TalkApp, chat: String) {
        if (recording?.isActive == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        try {
            startForeground(ID, ready!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TalkReady.TAG, "no microphone in the background: the Talk screen opens", e)
            startActivity(Intent(this, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        TalkPlaying.cancel(this)
        recording = app.scope.launch {
            val name = runCatching { chatName(app, chat) }.getOrDefault(chat)
            talkBack(app, chat, down = true)
            Log.i(TalkReady.TAG, "talking back to $chat")
            val started = System.currentTimeMillis()
            while (true) {
                val s = (System.currentTimeMillis() - started) / 1000
                recordingNotice(name, s)
                if (s >= TALK_BACK_MAX_S) break
                delay(1_000)
            }
            endTalkBack(app, send = true)
        }
    }

    private fun endTalkBack(app: TalkApp, send: Boolean) {
        val job = recording ?: return
        recording = null
        if (!job.isCompleted) job.cancel()
        app.scope.launch {
            talkBack(app, null, down = false, send = send)
            Log.i(TalkReady.TAG, if (send) "talk back sent" else "talk back cancelled")
        }
        NotificationManagerCompat.from(this).cancel(RECORDING_ID)
        runCatching { startForeground(ID, ready!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
    }

    /** "Talking to Dana 0:03" with Send and Cancel. */
    private fun recordingNotice(name: String, seconds: Long) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        fun act(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, TalkReadyService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, TalkPlaying.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.talking_to, name, "%d:%02d".format(seconds / 60, seconds % 60)))
            .setContentText(getString(R.string.talking_auto_send))
            .addAction(0, getString(R.string.talking_send), act(ACTION_SEND))
            .addAction(0, getString(R.string.talking_cancel), act(ACTION_CANCEL))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notify(RECORDING_ID, n)
    }

    private fun mediaSession(app: TalkApp): MediaSession = MediaSession(this, "talk-ready").apply {
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
        recording?.cancel()
        session?.release()
        session = null
        TalkReady.done(this)
        super.onDestroy()
    }

    companion object {
        private const val ID = 44
        private const val RECORDING_ID = 46
        private const val TALK_BACK_MAX_S = 60L
        private const val ACTION_STOP = "com.chencohen.ptt.TALK_READY_STOP"
        const val ACTION_TALK_BACK = "com.chencohen.ptt.TALK_READY_TALK_BACK"
        private const val ACTION_SEND = "com.chencohen.ptt.TALK_READY_SEND"
        private const val ACTION_CANCEL = "com.chencohen.ptt.TALK_READY_CANCEL"
        const val EXTRA_CHAT = "chat"

        @Volatile
        var running = false
            private set

        /** From the screen (MainActivity.onStart, Settings): start it, or stop it when the setting is off. */
        fun set(context: Context, on: Boolean) {
            if (on) {
                if (running) return
                runCatching { context.startForegroundService(Intent(context, TalkReadyService::class.java)) }
                    .onFailure { Log.w(TalkReady.TAG, "can't start", it) }
            } else {
                context.stopService(Intent(context, TalkReadyService::class.java))
            }
        }
    }
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
                        com.chencohen.ptt.talk.TalkAudio.inCallSession = true
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
                com.chencohen.ptt.talk.TalkAudio.inCallSession = false
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
    const val READY_CHANNEL = "talk_ready"
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
        manager.createNotificationChannel(NotificationChannel(READY_CHANNEL, context.getString(R.string.ready_channel), NotificationManager.IMPORTANCE_MIN))
        // The burst is the sound: this channel makes none of its own.
        manager.createNotificationChannel(
            NotificationChannel(TalkPlaying.CHANNEL, context.getString(R.string.playing_channel), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
        )
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
