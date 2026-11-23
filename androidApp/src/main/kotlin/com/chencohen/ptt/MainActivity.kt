package com.chencohen.ptt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.chencohen.ptt.enrollment.parseEnrollmentLink
import com.chencohen.ptt.messaging.InviteLink
import com.chencohen.ptt.ui.EnrollmentWizard
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.home.HomeApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: PttViewModel by viewModels()

    /** Set when an enrollment link arrives, to open the wizard on it. */
    private var linkOpened by mutableStateOf(false)

    /** An invite link opened on an enrolled phone, for Add contact. */
    private var openedInvite by mutableStateOf<String?>(null)

    /** A conversation to open: a notification was tapped. */
    private var openedChat by mutableStateOf<String?>(null)

    /** Shown as a small floating window (picture-in-picture) during a video call. */
    private var inPip by mutableStateOf(false)

    /** A call asked from Android Contacts: the user, and video. */
    private var openedCall by mutableStateOf<Pair<String, Boolean>?>(null)

    /** A talk notification's Play: the clip to play. */
    private var openedClip by mutableStateOf<String?>(null)

    /** Opened over the lock screen for a burst (Open Talk when someone talks to me), and not touched since. */
    private var wokeForTalk by mutableStateOf(false)

    /** The burst that woke the phone is over: the dim "after talk" screen shows for [wokenChat] until the screen goes off. */
    private var afterTalk by mutableStateOf(false)
    private var wokenChat: String? = null
    private var touched = false

    /** A volume key held down for talk, not yet let go. */
    private var volumeTalk = false
    private var volumeDownAt = 0L

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    private fun pipParams(on: Boolean): android.app.PictureInPictureParams {
        val hangUp = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_bubble_close),
            getString(R.string.call_hang_up), getString(R.string.call_hang_up),
            android.app.PendingIntent.getBroadcast(
                this, 7, Intent(this, CallActionReceiver::class.java).setAction(CallNotifications.ACTION_HANG_UP),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return android.app.PictureInPictureParams.Builder()
            .setAutoEnterEnabled(on)
            .setAspectRatio(android.util.Rational(3, 4))
            .setActions(listOf(hangUp))
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a rotation the link was handled already.
        if (savedInstanceState == null) openLink(intent)

        setContent {
            TalkTheme {
                // Android 17 blocks connections to local network addresses (a home server) without this.
                // Then notifications for texts (Android 13+ asks); the answer goes to the server with the push token.
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    (application as TalkApp).scope.launch { Push.register(this@MainActivity) }
                }
                val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                        localNetwork.launch(LOCAL_NETWORK)
                    } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(wokeForTalk) { if (wokeForTalk) backAfterWake() }
                LaunchedEffect(afterTalk) { if (afterTalk) watchAfterTalk() }
                // A call rings or runs: the app shows over the lock screen and wakes the screen for it.
                val ringing by viewModel.messaging.state.collectAsStateWithLifecycle()
                val callOn = ringing.call != null
                // Also while woken for a burst: this used to set both back to false as soon as the screen composed,
                // undoing the wake-up's (2026-11-23, hen: "when the phone is sleeping I don't see the PTT screen").
                LaunchedEffect(callOn, wokeForTalk) {
                    setShowWhenLocked(callOn || wokeForTalk)
                    setTurnScreenOn(callOn || wokeForTalk)
                }
                // A video call goes on in a small floating window (picture-in-picture) when the app is left: it can be
                // dragged and resized, and Hang up is on it (2026-11-22, hen).
                val activeVideo = ringing.call?.let { it.video && it.phase == com.chencohen.ptt.calls.CallPhase.ACTIVE } == true
                LaunchedEffect(activeVideo) { setPictureInPictureParams(pipParams(activeVideo)) }
                // Without an identity the phone has to be registered first.
                var showWizard by rememberSaveable { mutableStateOf(!viewModel.connection.currentState.enrolled) }
                // An enrollment QR code scanned with the camera: register (again) with its code.
                LaunchedEffect(linkOpened) {
                    if (linkOpened) {
                        showWizard = true
                        linkOpened = false
                    }
                }
                if (showWizard) {
                    EnrollmentWizard(
                        controller = viewModel.enrollment,
                        onOpenApp = {
                            showWizard = false
                            viewModel.enrollment.reset()
                            viewModel.connection.connect()
                            viewModel.messaging.start()
                            (application as TalkApp).scope.launch { Push.register(this@MainActivity) }
                        },
                    )
                } else {
                    HomeApp(
                        connection = viewModel.connection,
                        settings = viewModel.settings,
                        messaging = viewModel.messaging,
                        pairing = viewModel.pairing,
                        onRegisterAgain = {
                            viewModel.enrollment.reset()
                            showWizard = true
                        },
                        openedInvite = openedInvite,
                        onInviteTaken = { openedInvite = null },
                        openedChat = openedChat,
                        onChatTaken = { openedChat = null },
                        openedCall = openedCall,
                        onCallTaken = { openedCall = null },
                        inPip = inPip,
                        wokeForTalk = wokeForTalk,
                        onTalkSettings = { new ->
                            viewModel.messaging.setTalkSettings(new)
                            StayConnectedService.set(this@MainActivity, new.stayConnected)
                        },
                        openedClip = openedClip,
                    )
                    if (afterTalk) {
                        // The black curtain: nothing but black; a tap goes to the lock screen.
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
                                .clickable(onClick = ::curtainTapped),
                        )
                    }
                }
            }
        }
    }

    // Each time the app comes to the front it checks the connection (and finds the server if it moved), checks the
    // keys and opens /ws; in the back it stops checking again while offline and closes /ws. Staying connected in the
    // background is a setting in phase 5 (FCM wakes the phone in phase 3).
    override fun onStart() {
        super.onStart()
        // On screen: the reply bubble isn't needed.
        TalkBubble.dismiss()
        // A newer release of the app, as the server read it from GitHub (docs/design.md, "App updates").
        AppUpdates.checkIfDue(application as TalkApp)
        if (viewModel.connection.currentState.enrolled) {
            viewModel.connection.connect()
            viewModel.messaging.start()
            // The push token and the notifications setting, told to the server when they changed.
            (application as TalkApp).scope.launch { Push.register(this@MainActivity) }
            // Stay connected after a restart: nothing may start at boot, so it resumes when the app is opened.
            if (viewModel.messaging.talkSettings.stayConnected && !StayConnectedService.running) StayConnectedService.set(this, true)
            // "Talk is ready": started from the screen, so woken bursts may play as media (Android 17).
            TalkReadyService.set(this, true)
        }
    }

    /**
     * Hardware buttons (Settings › Talk): a held volume key talks in the default conversation while the app is on
     * screen (a short press still changes the volume); a headset's or Bluetooth talk button presses and releases.
     *
     * RestrictedApi is suppressed because lint misreads this: androidx.core's ComponentActivity marks its own
     * dispatchKeyEvent restricted, but this overrides (and calls super on) the public Activity.dispatchKeyEvent,
     * which is how a key event is meant to be seen before the focused view takes it.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val talk = viewModel.messaging.talkSettings
        if (!talk.hardwareButtons || talk.defaultChat == null) return super.dispatchKeyEvent(event)
        val app = application as TalkApp
        val code = event.keyCode
        if (HardwareButtons.isTalkKey(code)) {
            if (event.repeatCount == 0) app.scope.launch { talkButton(app, event.action == android.view.KeyEvent.ACTION_DOWN) }
            return true
        }
        if (HardwareButtons.isVolumeKey(code)) {
            when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) volumeDownAt = event.eventTime
                    // Held past a moment: it is a talk press, not a volume change.
                    if (!volumeTalk && event.eventTime - volumeDownAt > 350) {
                        volumeTalk = true
                        app.scope.launch { talkButton(app, true) }
                    }
                    return true
                }
                android.view.KeyEvent.ACTION_UP -> {
                    if (volumeTalk) {
                        volumeTalk = false
                        app.scope.launch { talkButton(app, false) }
                    } else {
                        val audio = getSystemService(android.media.AudioManager::class.java)
                        audio.adjustSuggestedStreamVolume(
                            if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                            android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI,
                        )
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        touched = true
    }

    /**
     * After the burst (and any queued) and 3 quiet seconds, untouched and still locked: off the lock screen and closed,
     * so the phone is back on the lock screen and Android turns the screen off after its short lock-screen timeout.
     */
    private suspend fun backAfterWake() {
        WakeTimeline.mark("Talk screen up")
        var quietSince = 0L
        while (wokeForTalk) {
            kotlinx.coroutines.delay(100)
            val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            if (touched || !locked) {
                // Touched or unlocked: it stays as the user left it.
                wokeForTalk = false
                break
            }
            val busy = viewModel.messaging.state.value.talk.let { it.playing != null || it.queued.isNotEmpty() || it.talkers.isNotEmpty() }
            val now = System.currentTimeMillis()
            if (busy) quietSince = 0L else if (quietSince == 0L) quietSince = now
            // Over (2026-11-23, hen: "close and turn off the screen immediately"): back behind the lock screen at once,
            // kept in memory (it comes back fast next time); with Turn the screen off right after talk, the screen too.
            if (quietSince != 0L && now - quietSince >= 0) {
                wokeForTalk = false
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (ScreenOff.lockNow(this)) {
                    // Turn the screen off right after talk: locked now; the screen goes off at once.
                    setTurnScreenOn(false)
                    setShowWhenLocked(false)
                    moveTaskToBack(true)
                    android.util.Log.i("MainActivity", "woken burst over: screen off now (device admin)")
                } else {
                    // A black curtain over the lock screen until the screen goes off (2026-11-23, hen: "the screen
                    // should be blank on video end"): nothing lit, the bars hidden, the brightness at its lowest.
                    setTurnScreenOn(false)
                    setShowWhenLocked(true)
                    window.attributes = window.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF }
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    }
                    afterTalk = true
                    android.util.Log.i("MainActivity", "woken burst over: black curtain until the screen goes off")
                }
            }
        }
    }

    /** While the dim after-talk screen shows: a new burst brings the Talk screen back, full screen. */
    private suspend fun watchAfterTalk() {
        while (afterTalk) {
            kotlinx.coroutines.delay(300)
            val talk = viewModel.messaging.state.value.talk
            if (talk.playing != null) {
                android.util.Log.i("MainActivity", "after talk: a new burst, back to the Talk screen")
                dimEnd()
                wokeForTalk = true
                touched = false
                setTurnScreenOn(true)
                break
            }
        }
    }

    /** The curtain goes: normal brightness and bars. */
    private fun dimEnd() {
        afterTalk = false
        window.attributes = window.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    /** A tap on the curtain: the normal lock screen (the app goes behind). */
    private fun curtainTapped() {
        android.util.Log.i("MainActivity", "curtain tapped: to the lock screen")
        dimEnd()
        setShowWhenLocked(false)
        moveTaskToBack(true)
    }

    override fun onStop() {
        // The screen went off (or the lock screen came) over the dim after-talk screen: it goes, and the app with it.
        if (afterTalk) {
            android.util.Log.i("MainActivity", "after talk: screen off, closing")
            dimEnd()
            setShowWhenLocked(false)
            moveTaskToBack(true)
        }
        viewModel.messaging.stop()
        viewModel.connection.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent?) {
        // Opened again from Recents, Android hands back the intent the task started with: a link used long ago.
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // Answer on a ringing call's notification: the app opens on the call screen and answers.
        if (intent?.action == CallNotifications.ACTION_ANSWER) {
            viewModel.messaging.acceptCall()
            return
        }
        if (intent?.action == AppUpdates.ACTION_UPDATE) {
            AppUpdates.updateFromNotification(application as TalkApp)
            return
        }
        if (intent?.action == ACTION_CALL) {
            val user = intent.getStringExtra(Notifications.EXTRA_USER) ?: return
            openedCall = user to intent.getBooleanExtra(EXTRA_VIDEO, false)
            return
        }
        // A Talk contact's Message, Voice call or Video call in Android Contacts.
        AndroidContacts.opened(this, intent)?.let { (user, kind) ->
            when (kind) {
                AndroidContacts.MESSAGE -> openedChat = user
                // Their chat: push-to-talk lives there now (2026-11-23, hen: no Talk screen).
                AndroidContacts.TALK -> openedChat = user
                else -> openedCall = user to (kind == AndroidContacts.VIDEO)
            }
            return
        }
        if (intent?.action == Notifications.ACTION_OPEN) {
            openedChat = intent.getStringExtra(Notifications.EXTRA_USER)
            return
        }
        // A talk notification: Play plays the burst in its chat; Reply opens Talk mode there.
        if (intent?.action == TalkNotifications.ACTION_PLAY || intent?.action == TalkNotifications.ACTION_TALK) {
            val chat = intent.getStringExtra(Notifications.EXTRA_USER) ?: return
            if (intent.getBooleanExtra(EXTRA_WAKE, false)) {
                // The screen on, over the lock screen, for this burst only.
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                wokeForTalk = true
                touched = false
                wokenChat = chat
                if (afterTalk) dimEnd()
                WakeTimeline.mark("Talk screen opening")
                TalkPlaying.wokenShown(this)
            }
            // Talk back from a notification or the bubble: the chat, where push-to-talk is (no Talk screen any more).
            openedChat = chat
            // The reply bubble's Video without the camera allowed yet: Android asks here.
            if (intent.getBooleanExtra(EXTRA_ASK_CAMERA, false) && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 41)
            }
            if (intent.action == TalkNotifications.ACTION_PLAY) openedClip = intent.getStringExtra(TalkNotifications.EXTRA_CLIP)
            return
        }
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        InviteLink.parse(data)?.let { invite ->
            // Enrolled on that server: pair with the inviter. Not yet (or on another): enroll with it, then pair.
            if (viewModel.connection.currentState.enrolled && viewModel.pairing.isForThisServer(invite)) {
                openedInvite = data
            } else {
                viewModel.enrollment.openInvite(invite)
                linkOpened = true
            }
            return
        }
        com.chencohen.ptt.messaging.LinkCode.parse(data)?.let { code ->
            // A Link a phone code opened from the camera: on a phone that isn't enrolled, it enrolls and links.
            if (!viewModel.connection.currentState.enrolled) {
                viewModel.enrollment.openLinkCode(code)
                linkOpened = true
            }
            return
        }
        val link = parseEnrollmentLink(data) ?: return
        viewModel.enrollment.openLink(link)
        linkOpened = true
    }

    companion object {
        /** A talk notification's full-screen intent: show over the lock screen and turn the screen on. */
        const val EXTRA_WAKE = "wake"
        /** With ACTION_TALK: ask for the camera (the reply bubble's Video, 2026-11-23). */
        const val EXTRA_ASK_CAMERA = "ask_camera"

        /** A call from the reply bubble: [EXTRA_VIDEO], to Notifications.EXTRA_USER. */
        const val ACTION_CALL = "com.chencohen.ptt.CALL_FROM_BUBBLE"
        const val EXTRA_VIDEO = "video"
    }
}

/** `Manifest.permission.ACCESS_LOCAL_NETWORK`, API 37. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
