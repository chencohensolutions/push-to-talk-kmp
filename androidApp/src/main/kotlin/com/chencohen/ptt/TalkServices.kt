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
import kotlinx.coroutines.flow.collectLatest
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

    override fun talkNotify(chat: String, from: String, clip: String, video: Boolean, status: Int) {
        app.scope.launch { TalkNotifications.show(app, chat, from, clip, video, status) }
    }

    override fun idle() {
        TalkReady.endSoon(app)
        // On screen there is no waiting for a reply in a call: the session goes now, and talk is media again.
        if (TalkSession.active && onScreen()) TalkSession.leave() else TalkSession.endSoon(app)
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
    /** It holds AndroidTalkFocus (shared with the live room's TalkDuck). */
    private var focused = false

    /** Ready to play a woken burst as media: the service runs and audio focus is granted. */
    fun play(app: TalkApp): Boolean {
        ending?.cancel()
        if (active) return true
        if (!TalkReadyService.running || !TalkReadyService.withRights) {
            // Started by a push (after a restart or an install): Android mutes its media, so the call session plays it.
            Log.i(TAG, if (TalkReadyService.running) "no background media rights: the call session plays it" else "not running: the call session plays it")
            return false
        }
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        // Lower other audio while someone talks off (2026-11-25, hen): no focus, the others play on at full volume.
        if (messagingState(app).value.talkSettings.lowerOthersForTalk) {
            if (!com.chencohen.ptt.talk.AndroidTalkFocus.take(app, "ready")) {
                // Android 17 refuses focus to an app without while-in-use rights, or a phone call is on.
                Log.w(TAG, "audio focus refused: the call session plays it")
                return false
            }
            focused = true
        }
        active = true
        Log.i(TAG, "ready to play; media ${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}")
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
        if (focused) com.chencohen.ptt.talk.AndroidTalkFocus.release(context, "ready")
        focused = false
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
                withRights = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND, ACTION_CANCEL, ACTION_CANCEL_TALK -> {
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
            // Started (or started again) with the app on screen: Android lets it play from the background.
            withRights = appOnScreen()
            Log.i(TalkReady.TAG, "Talk is ready${if (withRights) "" else " (started in the background: no media rights until the app opens)"}")
        } catch (e: Exception) {
            // Not started from the screen: Android says no; bursts play in the call session.
            Log.w(TalkReady.TAG, "can't run in the foreground", e)
            running = false
            withRights = false
            stopSelf()
            return START_NOT_STICKY
        }
        // A headset's or a Bluetooth talk button, the screen off too: the reply window's sender, or Buttons talk in.
        if (session == null) session = mediaSession(app)
        if (intent?.action == ACTION_TALK_BACK) intent.getStringExtra(EXTRA_CHAT)?.let { startTalkBack(app, it, intent.getBooleanExtra(EXTRA_VIDEO, false)) }
        if (intent?.action == ACTION_PREVIEW) preview(app, intent.getStringExtra(EXTRA_CHAT), intent.getBooleanExtra(EXTRA_VIDEO, false))
        // Keeping the camera for the bubble's preview: the camera type stays on this service.
        if (intent?.action != ACTION_TALK_BACK && intent?.action != ACTION_PREVIEW && previewChat != null) previewForeground()
        // The talk widget: a tap talks, the next one sends.
        if (intent?.action == ACTION_WIDGET) intent.getStringExtra(EXTRA_CHAT)?.let { chat ->
            if (recording?.isActive == true) endTalkBack(app, send = true) else startTalkBack(app, chat)
        }
        return START_NOT_STICKY
    }

    private var ready: android.app.Notification? = null

    /**
     * Talk back from the "Talk playing" notification: a tap, so the microphone may open in the background (while-in-use
     * from the notification). Talks to [chat] until Send, Cancel, or 60 s (sent).
     */
    private fun startTalkBack(app: TalkApp, chat: String, wantVideo: Boolean = false) {
        if (recording?.isActive == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // Push-to-video from the reply bubble (2026-11-23, hen): the camera in the background too.
        var video = wantVideo && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (video) {
            video = runCatching {
                startForeground(ID, ready!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            }.onFailure { Log.w(TalkReady.TAG, "no camera in the background: voice only", it) }.isSuccess
        }
        try {
            if (!video) {
                val keepCamera = if (previewChat != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
                startForeground(ID, ready!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or keepCamera)
            }
        } catch (e: Exception) {
            Log.w(TalkReady.TAG, "no microphone in the background: the Talk screen opens", e)
            startActivity(Intent(this, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        TalkPlaying.cancel(this)
        recordingChat = chat
        cameraWarmed = video && previewChat == null
        recording = app.scope.launch {
            val name = runCatching { chatName(app, chat) }.getOrDefault(chat)
            if (video) {
                // The camera opens first (about half a second), so the burst has video from its start.
                warmTalkCamera(app, chat)
                val until = System.currentTimeMillis() + 1_500
                while (talkVideoCapture?.ready != true && System.currentTimeMillis() < until) delay(50)
                Log.i(TalkReady.TAG, "talk back with video: camera ${if (talkVideoCapture?.ready == true) "ready" else "not ready, voice only"}")
            }
            // The floating window's own choice (2026-11-25: its slide up went as voice only, the chat's setting being off).
            talkBack(app, chat, down = true, video = video && talkVideoCapture?.ready == true)
            Log.i(TalkReady.TAG, "talking back to $chat" + if (video) " with video" else "")
            val started = System.currentTimeMillis()
            while (true) {
                val s = (System.currentTimeMillis() - started) / 1000
                val view = com.chencohen.ptt.talk.TalkWidgetView.of(chat, messagingState(app).value.talk, myUser(app), System.currentTimeMillis())
                if (view is com.chencohen.ptt.talk.TalkWidgetView.Busy) {
                    // The floor was taken: nothing is sent; the widget says who has it for a moment.
                    TalkWidgets.show(app, chat, view)
                    NotificationManagerCompat.from(this@TalkReadyService).cancel(RECORDING_ID)
                    recording = null
                    recordingChat = null
                    talkBack(app, null, down = false, send = false)
                    delay(3_000)
                    TalkWidgets.show(app, chat, com.chencohen.ptt.talk.TalkWidgetView.Idle)
                    runCatching { startForeground(ID, ready!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
                    return@launch
                }
                recordingNotice(name, s)
                TalkWidgets.show(app, chat, com.chencohen.ptt.talk.TalkWidgetView.Talking(s))
                if (s >= TALK_BACK_MAX_S) break
                delay(1_000)
            }
            endTalkBack(app, send = true)
        }
    }

    private var recordingChat: String? = null
    private var cameraWarmed = false

    /** The reply bubble's camera preview (its Voice | Video in Video): the camera kept open for [previewChat]. */
    private var previewChat: String? = null

    private fun preview(app: TalkApp, chat: String?, on: Boolean) {
        if (on && chat != null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (!previewForeground()) return
            previewChat = chat
            warmTalkCamera(app, chat)
            Log.i(TalkReady.TAG, "camera preview for the bubble ($chat)")
        } else if (previewChat != null) {
            previewChat = null
            if (recording?.isActive != true) {
                warmTalkCamera(app, null)
                runCatching { startForeground(ID, ready!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            }
            Log.i(TalkReady.TAG, "camera preview for the bubble: off")
        }
    }

    private fun previewForeground(): Boolean = runCatching {
        startForeground(ID, ready!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
    }.onFailure { Log.w(TalkReady.TAG, "no camera in the background for the preview", it) }.isSuccess

    private fun endTalkBack(app: TalkApp, send: Boolean) {
        val job = recording ?: return
        recording = null
        if (!job.isCompleted) job.cancel()
        recordingChat?.let { TalkWidgets.show(app, it, com.chencohen.ptt.talk.TalkWidgetView.Idle) }
        recordingChat = null
        val warmed = cameraWarmed
        cameraWarmed = false
        app.scope.launch {
            talkBack(app, null, down = false, send = send)
            if (warmed) warmTalkCamera(app, null)
            Log.i(TalkReady.TAG, if (send) "talk back sent" else "talk back cancelled")
        }
        NotificationManagerCompat.from(this).cancel(RECORDING_ID)
        if (previewChat != null) previewForeground() else runCatching { startForeground(ID, ready!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
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
        withRights = false
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
        /** The reply bubble's camera preview: [EXTRA_VIDEO] true keeps the camera open for [EXTRA_CHAT], false lets it go. */
        const val ACTION_PREVIEW = "com.chencohen.ptt.TALK_READY_PREVIEW"
        /** With [ACTION_TALK_BACK]: the camera goes too (push-to-video from the reply bubble). */
        const val EXTRA_VIDEO = "video"
        const val ACTION_WIDGET = "com.chencohen.ptt.TALK_READY_WIDGET"
        const val ACTION_CANCEL_TALK = "com.chencohen.ptt.TALK_READY_CANCEL_TALK"
        const val ACTION_SEND = "com.chencohen.ptt.TALK_READY_SEND"
        private const val ACTION_CANCEL = "com.chencohen.ptt.TALK_READY_CANCEL"
        const val EXTRA_CHAT = "chat"

        @Volatile
        var running = false
            private set

        /** It was started with the app on screen, so it may play from the background (Android 17's while-in-use). */
        @Volatile
        var withRights = false
            private set

        /** The app's process is in the foreground (its activity on screen). */
        fun appOnScreen(): Boolean {
            val info = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(info)
            return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        /** From the screen (MainActivity.onStart, Settings): start it, or stop it when the setting is off. */
        fun set(context: Context, on: Boolean) {
            if (on) {
                // Started from the background earlier: started again now, from the screen, for the rights.
                if (running && withRights) return
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
                        val loud = launch { keepLoud(app) }
                        com.chencohen.ptt.talk.TalkAudio.inCallSession = true
                        active = true
                        TalkSessionService.start(app, name)
                        ready.complete(true)
                        done.await()
                        loud.cancel()
                        // The call volume from before, while the session's route is still the one it was raised on.
                        TalkCallVolume.restore(app)
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
     * call volume, so a burst played in the background sounded like a phone call (2026-11-22, hen). For as long as the
     * session lasts: whenever its route is the earpiece it is moved to the speaker (a headset or Bluetooth device is
     * left as the route), and on each route the call volume is raised to the media's level (2026-11-27, hen: "it
     * suppose always to be loud as a speaker"; TalkCallVolume).
     */
    private suspend fun CallControlScope.keepLoud(app: TalkApp) {
        currentCallEndpoint.collectLatest { current ->
            if (current.type == CallEndpointCompat.TYPE_EARPIECE) {
                val speaker = runCatching { availableEndpoints.first() }.getOrNull()?.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
                if (speaker != null) {
                    val result = requestEndpointChange(speaker)
                    Log.i(TAG, "talk session to the loudspeaker: $result")
                    // The speaker comes here as the next route, and the volume is raised on it then.
                    return@collectLatest
                }
            }
            // The audio route follows the endpoint a moment later; the call volume is kept per route. Asked again when
            // Android ignored it (Android 17 lets an app off screen change a volume only once its call counts).
            for (wait in listOf(150L, 1_000L, 3_000L)) {
                delay(wait)
                if (TalkCallVolume.raise(app, current.type)) break
            }
        }
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

    /** Ends the session now (Leave, the reply time is up, or the app came on screen: talk is media there). */
    fun leave() {
        leave?.complete(Unit)
        leave = null
    }
}

/**
 * The call volume while a talk session plays (2026-11-27, hen: "sometimes i hear it as low as a phone call"). In the
 * session talk is the call's voice, on the call stream, whose volume is its own (and one per route): raised to what
 * media would play at (TalkSound.callLevel: the Talk volume's share, or the media volume's with the setting off; never
 * lowered) and put back when the session ends. Android lets any app set the call stream, but not to 0.
 */
object TalkCallVolume {
    private const val TAG = "TalkSession"
    private const val STREAM = android.media.AudioManager.STREAM_VOICE_CALL

    /** The route the volume was raised on, the level found there, and the level set. */
    private var raised: Triple<Int, Int, Int>? = null

    /** True once the call volume is what it should be on [route] (raised now or before, or loud enough already). */
    @Synchronized
    fun raise(app: TalkApp, route: Int): Boolean {
        val audio = app.getSystemService(android.media.AudioManager::class.java) ?: return true
        return runCatching {
            // Another route now: the first one's volume can't be reached any more (it is set for the route in use).
            if (raised != null && raised?.first != route) raised = null
            if (raised != null || audio.isVolumeFixed) return true
            val settings = messagingState(app).value.talkSettings
            val now = audio.getStreamVolume(STREAM)
            val music = android.media.AudioManager.STREAM_MUSIC
            val max = audio.getStreamMaxVolume(STREAM)
            val to = com.chencohen.ptt.talk.TalkSound.callLevel(
                mediaLevel = audio.getStreamVolume(music),
                mediaMax = audio.getStreamMaxVolume(music),
                callNow = now,
                callMin = audio.getStreamMinVolume(STREAM),
                callMax = max,
                talkVolume = settings.talkVolume.takeIf { settings.unmuteForTalk },
            )
            if (to != now) audio.setStreamVolume(STREAM, to, 0)
            val got = audio.getStreamVolume(STREAM)
            if (got != to) {
                Log.w(TAG, "call volume $now -> $to of $max asked for the talk session, Android left it at $got (route $route, mode ${audio.mode})")
                return false
            }
            raised = Triple(route, now, to)
            Log.i(TAG, "call volume $now -> $to of $max for the talk session (route $route, mode ${audio.mode})")
            true
        }.onFailure { Log.w(TAG, "the call volume wasn't raised", it) }.getOrDefault(false)
    }

    @Synchronized
    fun restore(context: Context) {
        val (_, before, to) = raised ?: return
        raised = null
        val audio = context.getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching {
            if (before != to) audio.setStreamVolume(STREAM, before, 0)
            Log.i(TAG, "call volume back to $before")
        }.onFailure { Log.w(TAG, "the call volume wasn't put back", it) }
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
        manager.createNotificationChannel(
            NotificationChannel(TalkPlaying.RECORD_CHANNEL, context.getString(R.string.playing_record_channel), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) },
        )
    }

    /**
     * A push the user didn't hear (2026-11-25, hen: "show notification only if i missed the stream"): "Missed push from
     * Sharon" ("Missed video push", "… in Family"), a tap opens the chat, with Play and Reply; it goes when the chat is
     * read (Notifications.read).
     */
    suspend fun show(context: Context, chat: String, from: String, clip: String, video: Boolean, status: Int = 0) {
        // Saved because this phone couldn't hear it (Do Not Disturb, silent, a call): a quiet one (2026-11-25, hen).
        val quiet = status != 0
        val urgent = status == com.chencohen.ptt.talk.HeardStatus.SAVED_URGENT_NOT_ALLOWED
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val who = runCatching { chatName(context, from) }.getOrDefault(from)
        val title = if (com.chencohen.ptt.messaging.Group.idOf(chat) != null) {
            context.getString(if (urgent) R.string.talk_missed_urgent_group else if (video) R.string.talk_missed_video_group else R.string.talk_missed_group, who, runCatching { chatName(context, chat) }.getOrDefault(chat))
        } else {
            context.getString(if (urgent) R.string.talk_missed_urgent else if (video) R.string.talk_missed_video else R.string.talk_missed, who)
        }
        val id = (chat.hashCode() and 0x3fffffff) * 2
        fun open(action: String, request: Int) = PendingIntent.getActivity(
            context, request,
            Intent(context, MainActivity::class.java).setAction(action).putExtra(Notifications.EXTRA_USER, chat).putExtra(EXTRA_CLIP, clip)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // A conversation's message, so Android Auto shows it with Reply and Mark as read.
        val sender = androidx.core.app.Person.Builder().setName(who).setKey(from).build()
        val style = NotificationCompat.MessagingStyle(androidx.core.app.Person.Builder().setName(context.getString(R.string.notify_you)).build())
            .addMessage(context.getString(R.string.talk_missed_body), System.currentTimeMillis(), sender)
        if (com.chencohen.ptt.messaging.Group.idOf(chat) != null) style.setConversationTitle(title).setGroupConversation(true)
        val notification: Notification = NotificationCompat.Builder(context, TALK_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.talk_missed_body))
            .setStyle(style)
            .setContentIntent(open(Notifications.ACTION_OPEN, id + 4))
            .addAction(0, context.getString(R.string.talk_notify_play), open(ACTION_PLAY, id))
            .addAction(0, context.getString(R.string.talk_notify_reply), open(ACTION_TALK, id + 1))
            .addAction(Notifications.replyAction(context, chat, id + 2))
            .addAction(Notifications.readAction(context, chat, id + 3))
            .setSilent(quiet)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify("talk", id, notification)
    }
}
