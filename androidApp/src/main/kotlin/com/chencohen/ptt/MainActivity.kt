package com.chencohen.ptt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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

    /** A talk notification's Play: the clip to play. */
    private var openedClip by mutableStateOf<String?>(null)

    /** A volume key held down for talk, not yet let go. */
    private var volumeTalk = false
    private var volumeDownAt = 0L

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
                // A call rings or runs: the app shows over the lock screen and wakes the screen for it.
                val ringing by viewModel.messaging.state.collectAsStateWithLifecycle()
                val callOn = ringing.call != null
                LaunchedEffect(callOn) {
                    setShowWhenLocked(callOn)
                    setTurnScreenOn(callOn)
                }
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
                        onTalkSettings = { new ->
                            viewModel.messaging.setTalkSettings(new)
                            StayConnectedService.set(this@MainActivity, new.stayConnected)
                        },
                        openedClip = openedClip,
                    )
                }
            }
        }
    }

    // Each time the app comes to the front it checks the connection (and finds the server if it moved), checks the
    // keys and opens /ws; in the back it stops checking again while offline and closes /ws. Staying connected in the
    // background is a setting in phase 5 (FCM wakes the phone in phase 3).
    override fun onStart() {
        super.onStart()
        if (viewModel.connection.currentState.enrolled) {
            viewModel.connection.connect()
            viewModel.messaging.start()
            // The push token and the notifications setting, told to the server when they changed.
            (application as TalkApp).scope.launch { Push.register(this@MainActivity) }
            // Stay connected after a restart: nothing may start at boot, so it resumes when the app is opened.
            if (viewModel.messaging.talkSettings.stayConnected && !StayConnectedService.running) StayConnectedService.set(this, true)
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

    override fun onStop() {
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
        if (intent?.action == Notifications.ACTION_OPEN) {
            openedChat = intent.getStringExtra(Notifications.EXTRA_USER)
            return
        }
        // A talk notification: Play plays the burst in its chat; Reply opens Talk mode there.
        if (intent?.action == TalkNotifications.ACTION_PLAY || intent?.action == TalkNotifications.ACTION_TALK) {
            val chat = intent.getStringExtra(Notifications.EXTRA_USER) ?: return
            openedChat = if (intent.action == TalkNotifications.ACTION_TALK) "$chat\ntalk/$chat" else chat
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
}

/** `Manifest.permission.ACCESS_LOCAL_NETWORK`, API 37. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
