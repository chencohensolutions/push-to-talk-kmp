package com.chencohen.ptt

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.asAndroidBitmap
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.ui.contacts.LiveKind
import com.chencohen.ptt.ui.contacts.LiveNow
import com.chencohen.ptt.ui.live.LiveItem
import com.chencohen.ptt.ui.live.LiveLayout
import com.chencohen.ptt.ui.live.LiveMotion
import com.chencohen.ptt.ui.live.LiveRect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The floating window (docs/screens/notifications.md, "Floating window"; 2026-11-24, hen picked "A, a small pill at
 * the bottom"): with Settings › Talk › Reply bubble over other apps on and Android's "Display over other apps" allowed,
 * someone talking while the phone is in use (and the app isn't on screen) shows two small floating pieces, and the app
 * beneath stays usable (a touch outside closes nothing):
 *
 * - At the top centre, sliding down from the top edge: their video, 23.6% of the screen's height and as wide as the
 *   picture's own shape (no crop, no bars), rounded with a thin teal outline and "Name · 0:04" inside. While I send
 *   video my own picture shows there (coral outline, "You · LIVE 0:03"), side by side with theirs when both are live,
 *   with "You're both live · Make it a call" under them. Drag moves it, a swipe up puts it away, a tap opens the chat
 *   (the video goes on there without a break).
 * - Voice (2026-11-24, hen, "c, but remove the wave, make the green circle glow as the voice"): no top box; the pill
 *   grows to show the talker's face in a teal ring that glows with their voice (brightness, glow and thickness), their
 *   name and the time. My own voice push the same, with my face and my microphone.
 * - At the bottom, about 24 dp above the navigation bar: one pill, Chat · Push-to-talk · Close. Hold the push button
 *   for voice push-to-talk and let go to send (a tap starts it and the next tap sends). Sliding up while holding, to
 *   the camera-and-lock target above the button (2026-11-24, hen: "holding the button transmitting audio, sliding the
 *   button up open hands free video"), switches to hands-free video: the camera (warmed as the finger starts to slide)
 *   sends, my box shows at the top, and the button becomes a coral "■ Stop · 0:42" until tapped (renewed every 55 s,
 *   as the server ends a floor at 60 s). There is no Voice | Video switch and no hands-free voice any more. A call
 *   ringing turns the pill into Answer and Decline.
 */
object TalkBubble {
    private const val TAG = "TalkBubble"
    private const val SLIDE_MS = 250L
    /** The top box's height: this share of the screen's (hen). */
    private const val TOP_SHARE = 0.236f
    private const val HOLD_MS = 350L
    /** How long their item stays after a burst ends, in case the next (voice into video) follows at once. */
    private const val BURST_HOLD_MS = 2_500L
    /** The pill window's fixed width. */
    private const val PILL_WINDOW_DP = 300
    /** Slid up this far while holding: hands-free. */
    private const val LOCK_DP = 56
    /** Hands-free is let go and taken again before the server's 60 s floor ends. */
    private const val RENEW_MS = 55_000L

    private val main = Handler(Looper.getMainLooper())

    // The bottom pill (its own window).
    private var pillWindow: LinearLayout? = null
    private var pill: LinearLayout? = null
    private var chatButton: ImageView? = null
    private var button: ImageView? = null
    private var closeButton: ImageView? = null
    /** The finger started sliding up: the camera opens, so the video starts fast at the lock. */
    private var warming = false
    private var pushBox: FrameLayout? = null
    private var stopPill: TextView? = null
    private var lockHint: ImageView? = null
    // The live row at the top (its own window, as wide as the screen; what shows in it never moves or resizes it):
    // one cell for each side, a voice pill or a video box, placed by LiveLayout and moved by LiveMotion.
    private var topWindow: FrameLayout? = null
    private var topCard: FrameLayout? = null
    private var theirCell: LiveCell? = null
    private var myCell: LiveCell? = null
    private val topMotion = LiveMotion()
    /** The cells' resting top in the window: just below the status bar. */
    private var restTop = 0
    /** The language reads right to left: theirs rests at the right. Only the order; the geometry is left to right. */
    private var topRtl = false
    /** Voice pills (2026-11-24, hen: "both voice pushing then side by side pills"): theirs and mine, each in its cell. */
    private var theirPill: VoicePill? = null
    private var myPill: VoicePill? = null
    private var theirBox: FrameLayout? = null
    private var theirTag: TextView? = null
    private var theirFace: ImageView? = null
    private var theirFaceOf: String? = null
    private var videoSurface: android.view.Surface? = null
    /** Their video through the talk room, while its frames come (LiveTalkVideo): shown instead of the clip's. */
    private var roomRenderer: io.livekit.android.renderer.TextureViewRenderer? = null
    private var roomTrack: io.livekit.android.room.track.VideoTrack? = null
    private var roomLive: com.chencohen.ptt.talk.LiveTalkVideo.Live? = null
    private var myBox: FrameLayout? = null
    private var mySurface: android.view.Surface? = null
    private var myTag: TextView? = null
    private var bothChip: TextView? = null
    private var topHiding = false

    private var chat: String? = null
    private var title: String = ""
    private var from: String? = null
    /** Their burst plays now; with video. */
    private var burstLive = false
    private var burstVideo = false
    private var burstSince = 0L
    /** Swiped up: the top box stays away until their next burst. */
    private var topPutAway = false
    private var talking = false
    private var talkStarted = 0L
    private var locked = false
    /** Slid up to the lock: between the voice burst's end and the video one's start, the pill is already the box. */
    private var goingLive = false
    /** When this run of mine began (voice, then video): one time that keeps counting. */
    private var runStarted = 0L
    private var lockSince = 0L
    private var downY = 0f
    private var sendingVideo = false
    private var previewOn = false
    private var ringing: com.chencohen.ptt.calls.CallUi? = null
    /** The last picture's shape and turn, kept when the burst ends (no sideways frame as it goes). */
    private var lastShown: com.chencohen.ptt.talk.AndroidTalkVideoOut.Shown? = null
    /**
     * What a chat drawn elsewhere says is live (the floating chat, 2026-11-25, hen: "the video and pills should appear
     * from the top of the screen, not in the chat floating window"): shown in the row with the window's own.
     */
    private var outside: LiveNow? = null
    private var outsideChat: String? = null
    /** Test builds only (TestBubble): their picture's shape as if they had said it. */
    internal var pretendShape: Float? = null

    /**
     * The live row's bottom edge on the screen while it shows, else 0 (the floating chat keeps below it): a Compose
     * state, read there.
     */
    val topRoom = androidx.compose.runtime.mutableIntStateOf(0)

    /** The floating window is on screen now. */
    val showing: Boolean get() = pillWindow != null

    /** The floating window may show now: the setting, Android's permission, the phone in use and unlocked. */
    fun allowed(context: Context): Boolean {
        if (!messagingState(context).value.talkSettings.replyBubble) return false
        if (!Settings.canDrawOverlays(context)) return false
        if (!context.getSystemService(android.os.PowerManager::class.java).isInteractive) return false
        return !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    }

    /** [who] talks in [chat] now ([live], with [video]) or just did (the pill stays for a reply). */
    fun show(context: Context, chat: String, title: String, live: Boolean, video: Boolean = false, who: String? = null) = main.post {
        val app = context.applicationContext
        if (pillWindow == null) {
            if (!allowed(app)) return@post
            // The floating chat shows this conversation: it has its own buttons, and tells the row what is live.
            if (QuickReplyWindow.showing(chat)) return@post
            if (!addPill(app)) return@post
        }
        if (this.chat != chat) setHere(app, chat)
        this.chat = chat
        this.title = title
        if (who != null) from = who
        main.removeCallbacks(burstOver)
        if (!live && burstLive) {
            // Their burst ended: its pill (or box) stays a moment, so voice turning into video is one item that
            // changes, not one leaving and another coming (2026-11-25, hen: "move up out of screen and down again").
            burstOverApp = app
            main.postDelayed(burstOver, BURST_HOLD_MS)
            showMode(app)
            return@post
        }
        if (live && !burstLive) {
            // Their next burst: the top box comes back even if it was put away.
            if (burstSince == 0L) burstSince = System.currentTimeMillis()
            topPutAway = false
        }
        burstLive = live
        burstVideo = live && video
        if (!live) burstSince = 0L
        refreshTop(app)
        showMode(app)
    }

    private var burstOverApp: Context? = null

    /** Their burst ended and no other followed within [BURST_HOLD_MS]: now it goes. */
    private val burstOver = Runnable {
        val app = burstOverApp ?: return@Runnable
        burstLive = false
        burstVideo = false
        burstSince = 0L
        refreshTop(app)
        showMode(app)
    }

    /**
     * [chat], drawn elsewhere (the floating chat), says what is live in it ([now]; null or nothing live: no more): the
     * row at the top shows it, alone or with the window's own, without the bottom pill.
     */
    fun liveRow(context: Context, chat: String, now: LiveNow?) = main.post {
        val app = context.applicationContext
        val live = now?.takeIf { it.theirs != LiveKind.None || it.mine != LiveKind.None }
        if (live == null && (outside == null || outsideChat != chat)) return@post
        if (live != null && !Settings.canDrawOverlays(app)) return@post
        // Their next burst: the row comes back even if it was put away.
        if (live != null && live.theirs != LiveKind.None && outside?.theirs.let { it == null || it == LiveKind.None }) topPutAway = false
        outside = live
        outsideChat = if (live != null) chat else null
        refreshTop(app)
    }

    /** The app came on screen, or the floating window isn't wanted any more. */
    fun dismiss() = main.post { if (!talking) dismissNow() }

    /**
     * The video box's height: 23.6% of the screen's height as the app's own screens count it (the chat's boxes use the
     * same), so the two are the same size (2026-11-25, hen: the floating one looked taller).
     */
    private fun topHeight(context: Context): Int =
        (context.resources.configuration.screenHeightDp * TOP_SHARE * context.resources.displayMetrics.density).toInt()

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(context, 24)
    }

    private fun dp(context: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    // ---- The bottom pill ----------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addPill(context: Context): Boolean {
        val wm = context.getSystemService(WindowManager::class.java)
        fun dp(v: Int) = dp(context, v)
        val bars = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars())
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // One order whatever the language (2026-11-25, hen: mirrored in Hebrew it read wrong): Chat, Push, Close,
            // as the chat's own button row; its changes (Push to Stop and back) animate, growing from the centre.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutTransition = android.animation.LayoutTransition().apply {
                enableTransitionType(android.animation.LayoutTransition.CHANGING)
                setDuration(200)
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(40).toFloat(); setColor(0xF2191D1C.toInt()) }
            elevation = dp(8).toFloat()
        }
        fun round(icon: Int, label: Int, size: Int, color: Int, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            val pad = size / 4
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            contentDescription = context.getString(label)
            setOnClickListener { onClick() }
        }
        chatButton = round(R.drawable.ic_bubble_chat, R.string.talk_action_chat, dp(34), 0x33FFFFFF) { chat?.let { intoChat(context, it) } }
        // The push button: hold for voice, slide up to the lock for hands-free video.
        val push = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        button = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PINK) }
            contentDescription = context.getString(R.string.bubble_talk_hint)
            setOnTouchListener { v, e -> touch(context, v, e) }
        }
        push.addView(button, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
        pushBox = push
        // Hands-free: the push button's place shows a coral "■ Stop · 0:42"; a tap stops.
        stopPill = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply { cornerRadius = dp(23).toFloat(); setColor(PINK) }
            contentDescription = context.getString(R.string.talk_stop)
            setOnClickListener {
                locked = false
                endTalk(context, send = true)
            }
        }
        closeButton = round(R.drawable.ic_bubble_close, R.string.bubble_close, dp(34), 0x33FFFFFF) { closeOrDecline(context) }
        val gap = dp(10)
        row.addView(chatButton, LinearLayout.LayoutParams(dp(34), dp(34)))
        row.addView(push, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = gap; marginEnd = gap })
        row.addView(stopPill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)).apply { marginStart = gap; marginEnd = gap })
        row.addView(closeButton, LinearLayout.LayoutParams(dp(34), dp(34)))
        // The lock, above the push button while it is held (the window grows upwards; the pill stays put).
        lockHint = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_lock)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xF2191D1C.toInt()) }
            visibility = View.GONE
        }
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            addView(lockHint, LinearLayout.LayoutParams(dp(42), dp(58)).apply { bottomMargin = dp(8) })
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            // Room for the shadow.
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val params = WindowManager.LayoutParams(
            // As wide as the pill gets (with Stop): the window doesn't change width, so the pill grows and shrinks
            // about its centre instead of the window jumping to a new one.
            dp(PILL_WINDOW_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // The app beneath stays usable: touches outside the pill go to it, and nothing closes on them.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = bars.bottom + dp(24) - dp(8)
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            title = "Talk pill"
        }
        return try {
            wm.addView(holder, params)
            pillWindow = holder
            pill = row
            // Slides up and fades in, untouchable for a moment (OverlayArrival).
            row.post { OverlayArrival.arrive(wm, holder, params, row, (row.height + params.y + dp(16)).toFloat()) { a -> row.alpha = a } }
            Log.i(TAG, "shown")
            main.post(pulse)
            true
        } catch (e: Exception) {
            Log.w(TAG, "can't show the floating window", e)
            false
        }
    }

    /** A camera preview's TextureView: [surface] is told its Surface (null when it goes). */
    private fun ownTexture(context: Context, surface: (android.view.Surface?) -> Unit) = android.view.TextureView(context).apply {
        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            private var mine: android.view.Surface? = null
            override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH, com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT)
                mine = android.view.Surface(texture).also(surface)
                main.post { pickPreview() }
            }

            override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                val gone = mine ?: return true
                mine = null
                surface(null)
                val capture = com.chencohen.ptt.talkVideoCapture ?: run { gone.release(); return true }
                capture.clearPreview(gone) {
                    gone.release()
                    texture.release()
                }
                return false
            }

            override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
        }
    }

    private fun cameraAllowed(context: Context) =
        context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The push button's icon, and the camera kept open while warming or sending video. */
    private fun showMode(context: Context) {
        if (!talking && ringing == null) {
            button?.setImageResource(R.drawable.ic_bubble_mic)
            button?.contentDescription = context.getString(R.string.bubble_talk_hint)
        }
        val wantCamera = (warming || sendingVideo) && ringing == null
        if (wantCamera && !previewOn) preview(context, true) else if (!wantCamera && previewOn) preview(context, false)
        pickPreview()
    }

    /**
     * The camera draws into my box at the top while I send video; while the talk room reads the camera, my box shows
     * the room's own track instead (the camera has no separate preview output then).
     */
    private fun pickPreview() {
        val local = com.chencohen.ptt.talk.LiveTalkVideo.local.value
        val box = myBox
        if ((sendingVideo || goingLive || outside?.mine == LiveKind.Video) && local != null && box != null) {
            if (myRoomTrack !== local) {
                dropMyRoomVideo()
                val r = io.livekit.android.renderer.TextureViewRenderer(box.context)
                if (com.chencohen.ptt.talk.LiveTalkVideo.initRenderer(r)) {
                    r.setScalingType(livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    r.setMirror(true)
                    box.addView(r, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    local.addRenderer(r)
                    myRoomRenderer = r
                    myRoomTrack = local
                }
            }
        } else if (myRoomTrack != null) {
            dropMyRoomVideo()
        }
        val capture = com.chencohen.ptt.talkVideoCapture ?: return
        val mine = mySurface ?: return
        if (sendingVideo || outside?.mine == LiveKind.Video) capture.setPreview(mine) else capture.clearPreview(mine) {}
    }

    private var myRoomRenderer: io.livekit.android.renderer.TextureViewRenderer? = null
    private var myRoomTrack: io.livekit.android.room.track.VideoTrack? = null

    private fun dropMyRoomVideo() {
        val r = myRoomRenderer ?: return
        myRoomTrack?.removeRenderer(r)
        (r.parent as? android.view.ViewGroup)?.removeView(r)
        r.release()
        myRoomRenderer = null
        myRoomTrack = null
    }

    /** Asks Talk's service to keep the camera open for the window's preview ([on]) or let it go. */
    private fun preview(context: Context, on: Boolean) {
        val chat = chat ?: return
        previewOn = on
        runCatching {
            context.startForegroundService(
                Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_PREVIEW)
                    .putExtra(TalkReadyService.EXTRA_CHAT, chat).putExtra(TalkReadyService.EXTRA_VIDEO, on),
            )
        }.onFailure { Log.w(TAG, "no camera preview", it) }
    }

    private fun touch(context: Context, v: View, e: MotionEvent): Boolean {
        // Ringing: the button answers.
        ringing?.let {
            if (e.actionMasked == MotionEvent.ACTION_UP) answer(context)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (talking) {
                    // A tap (or the lock) started it; this one sends.
                    locked = false
                    endTalk(context, send = true)
                } else {
                    locked = false
                    main.removeCallbacks(coolCamera)
                    // Get camera ready while talking (on by default): the camera opens now, unseen.
                    warming = messagingState(context).value.talkSettings.cameraReady && cameraAllowed(context)
                    downY = e.rawY
                    goingLive = false
                    startTalk(context, video = false)
                    runStarted = talkStarted
                    showLockHint(true)
                }
                v.isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                val up = downY - e.rawY
                // Starting to slide up: the camera opens now, so the video starts fast at the lock.
                if (talking && !locked && !warming && up > dp(context, 16) && cameraAllowed(context)) {
                    warming = true
                    showMode(context)
                }
                // At the lock: hands-free video until Stop. The voice so far goes as it is; the video burst follows.
                if (talking && !locked && up > dp(context, LOCK_DP)) {
                    if (!cameraAllowed(context)) {
                        chat?.let { askCamera(context, it) }
                        return true
                    }
                    locked = true
                    lockSince = System.currentTimeMillis()
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    showLockHint(false)
                    showLocked(true)
                    goingLive = true
                    endTalk(context, send = true, keepLock = true)
                    main.postDelayed({
                        if (locked && !talking && pillWindow != null) {
                            startTalk(context, video = true)
                            main.removeCallbacks(renew)
                            main.postDelayed(renew, RENEW_MS)
                        }
                    }, 400)
                    Log.i(TAG, "hands-free video")
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                showLockHint(false)
                // Let go 2 s after the finger lifts without the slide up: a quick press again reuses it.
                if (warming && !locked) {
                    main.removeCallbacks(coolCamera)
                    main.postDelayed(coolCamera, 2_000)
                }
                // Held: letting go sends. A short tap (or the lock) keeps talking until the next one.
                if (talking && !locked && System.currentTimeMillis() - talkStarted >= HOLD_MS) endTalk(context, send = true)
                else if (talking && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            }
        }
        return true
    }

    /** The lock above the push button while it is held. */
    private fun showLockHint(on: Boolean) {
        val lock = lockHint ?: return
        val push = pushBox ?: return
        if (on) {
            // Over the push button, wherever it sits in the pill.
            val holder = pillWindow ?: return
            val at = IntArray(2).also { push.getLocationInWindow(it) }[0] + push.width / 2
            lock.translationX = (at - holder.width / 2).toFloat()
            lock.alpha = 0f
            lock.visibility = View.VISIBLE
            lock.animate().alpha(1f).setDuration(150).start()
        } else if (lock.visibility == View.VISIBLE) {
            lock.visibility = View.GONE
        }
    }

    /** Hands-free: the push button's place is "■ Stop · 0:42". */
    private fun showLocked(on: Boolean) {
        pushBox?.visibility = if (on) View.GONE else View.VISIBLE
        stopPill?.visibility = if (on) View.VISIBLE else View.GONE
        updateStop()
    }

    private fun updateStop() {
        val stop = stopPill ?: return
        if (stop.visibility != View.VISIBLE) return
        val s = ((System.currentTimeMillis() - lockSince) / 1000).coerceAtLeast(0)
        stop.text = "■  " + stop.context.getString(R.string.talk_stop) + " · %d:%02d".format(s / 60, s % 60)
    }

    private val coolCamera = Runnable {
        val root = pillWindow ?: return@Runnable
        if (!locked && !sendingVideo) {
            warming = false
            showMode(root.context)
        }
    }

    /** Hands-free at 55 s: let go and taken again at once. */
    private val renew = object : Runnable {
        override fun run() {
            val root = pillWindow ?: return
            if (!talking || !locked) return
            val context = root.context
            endTalk(context, send = true, keepLock = true)
            main.postDelayed({
                if (locked && !talking && pillWindow != null) {
                    startTalk(context, video = true)
                    main.postDelayed(this, RENEW_MS)
                }
            }, 900)
        }
    }

    /** The camera isn't allowed yet: the chat opens, where Android asks. */
    private fun askCamera(context: Context, chat: String) {
        if (talking) endTalk(context, send = true)
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat)
                    .putExtra(MainActivity.EXTRA_ASK_CAMERA, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        dismissNow()
    }

    private fun startTalk(context: Context, video: Boolean) {
        val chat = chat ?: return
        // Video (hands-free): the camera too, unless a call has it (then voice only).
        val wantVideo = video && cameraAllowed(context)
        val callOn = messagingState(context).value.call != null
        sendingVideo = wantVideo && !callOn
        if (sendingVideo) goingLive = false
        talking = true
        talkStarted = System.currentTimeMillis()
        // Glows while held.
        (button?.background as? GradientDrawable)?.apply { setColor(PINK); setStroke(dp(context, 4), 0x80FFB3A8.toInt()) }
        button?.setImageResource(R.drawable.ic_bubble_send)
        button?.contentDescription = context.getString(R.string.bubble_send)
        showMode(context)
        refreshTop(context)
        runCatching {
            context.startForegroundService(
                Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_TALK_BACK).putExtra(TalkReadyService.EXTRA_CHAT, chat)
                    .putExtra(TalkReadyService.EXTRA_VIDEO, sendingVideo),
            )
        }.onFailure {
            Log.w(TAG, "can't talk back from the floating window: the Talk screen opens", it)
            talking = false
            openTalk(context, chat)
            dismissNow()
        }
    }

    private fun endTalk(context: Context, send: Boolean, keepLock: Boolean = false) {
        if (!talking) return
        talking = false
        if (!keepLock) {
            locked = false
            warming = false
            goingLive = false
            runStarted = 0L
            main.removeCallbacks(renew)
            showLocked(false)
        }
        runCatching {
            context.startService(Intent(context, TalkReadyService::class.java).setAction(if (send) TalkReadyService.ACTION_SEND else TalkReadyService.ACTION_CANCEL_TALK))
        }
        (button?.background as? GradientDrawable)?.apply { setColor(PINK); setStroke(0, Color.TRANSPARENT) }
        sendingVideo = false
        showMode(context)
        refreshTop(context)
    }

    /** Close; while a call rings, Decline. */
    private fun closeOrDecline(context: Context) {
        if (ringing != null) {
            context.sendBroadcast(Intent(context, CallActionReceiver::class.java).setAction(CallNotifications.ACTION_DECLINE))
            callGone(context)
            return
        }
        if (talking) endTalk(context, send = true)
        dismissNow()
    }

    // ---- The live row at the top -------------------------------------------------------------------------------

    /** The conversation the row is about, whose face and name theirs is, and since when each side is live. */
    private fun rowChat(): String? = chat ?: outsideChat
    private fun rowFrom(): String = (if (burstLive) from else null) ?: outside?.theirUser?.takeIf { it.isNotEmpty() } ?: from ?: ""
    private fun rowTitle(): String = if (chat != null) title else outside?.theirName ?: title
    private fun theirSince(): Long = burstSince.takeIf { it > 0 } ?: outside?.theirSince?.takeIf { it > 0 } ?: System.currentTimeMillis()
    private fun mySince(): Long = runStarted.takeIf { it > 0 } ?: talkStarted.takeIf { talking && it > 0 } ?: outside?.mySince?.takeIf { it > 0 } ?: talkStarted

    /** Their side is on video (the window's own burst, or the floating chat's). */
    private fun theirVideoWanted(): Boolean = burstVideo || outside?.theirs == LiveKind.Video

    /**
     * What the row shows now: for each side nothing, a voice pill or a video box (side by side when both, with the
     * both-live chip under two boxes). Whatever changed, everything moves to its new place in one move ([placeTop]).
     */
    private fun refreshTop(context: Context) {
        val out = outside
        // Their video shows once its picture's shape is known: until then their pill stays, so the pill grows once,
        // straight to the right size (2026-11-25, hen: "the video size aspect ratio that comes in delay").
        val theirWasPill = theirCell?.let { it.on && !it.video } == true
        val theirOn = (burstLive || (out != null && out.theirs != LiveKind.None)) && !topPutAway
        val theirsVideo = theirOn && theirVideoWanted() && (theirPictureReady(context) || !theirWasPill)
        val mine = sendingVideo || goingLive || out?.mine == LiveKind.Video
        val myVoice = !mine && !topPutAway && (talking || out?.mine == LiveKind.Voice)
        if (!theirOn && !mine && !myVoice) {
            // Nothing live for a moment (one push ending as the next starts): the row waits before it goes, so it
            // isn't taken away and brought back (2026-11-25, hen: "move up out of screen and down again").
            main.removeCallbacks(hideSoon)
            main.postDelayed(hideSoon, 900)
            return
        }
        main.removeCallbacks(hideSoon)
        if (!addTop(context)) return
        topHiding = false
        theirCell?.let { it.on = theirOn; it.video = theirsVideo }
        myCell?.let { it.on = mine || myVoice; it.video = mine }
        theirPill?.show(context, if (theirOn) rowFrom() else null, rowTitle())
        myPill?.show(context, if (mine || myVoice) "" else null, rowTitle())
        // Their words as they are now, before the pills are measured: a pill comes in at its real width.
        theirPill?.tick(0f, theirSince())
        myPill?.tick(0f, mySince())
        bothChip?.visibility = if (bothLive(context)) View.VISIBLE else View.GONE
        placeTop(context)
        pickPreview()
        main.removeCallbacks(topTick)
        main.post(topTick)
    }

    /** Their video's shape is known (said with their key, or from a first picture, through the room or the clip). */
    private fun theirPictureReady(context: Context): Boolean {
        val talker = rowChat()?.let { messagingState(context).value.talk.talkers[it] }
        val room = talker?.let { com.chencohen.ptt.talk.LiveTalkVideo.arriving.value["${it.user}.${it.device}"] }
        val said = talker?.let { com.chencohen.ptt.talk.LiveTalkVideo.shapes.value["${it.user}.${it.device}"] }
        return room != null || said != null || pretendShape != null || com.chencohen.ptt.talk.AndroidTalkVideoOut.shown.value != null
    }

    /** The row goes once nothing has been live for a moment. */
    private val hideSoon = Runnable { hideTop() }

    /** Both live: I send while their video plays, or while they wait for my floor. */
    private fun bothLive(context: Context): Boolean {
        if (outside?.bothLive == true) return true
        val chat = chat ?: return false
        if (!sendingVideo) return false
        val waiting = (messagingState(context).value.liveWaiting[chat] ?: 0L) > System.currentTimeMillis()
        return burstVideo || waiting
    }

    /** Where the window's left and top edges are on the screen, without what a drag added. */
    private fun windowAt(): IntArray {
        val window = topWindow?.takeIf { it.isAttachedToWindow } ?: return intArrayOf(0, 0)
        val at = IntArray(2).also { window.getLocationOnScreen(it) }
        val params = topParams ?: return at
        return intArrayOf(at[0] - params.x, at[1] - params.y)
    }

    /**
     * Where everything in the row rests now (LiveLayout: side by side, centred on the screen, theirs at the language's
     * start side) and the ONE move there (LiveMotion; 2026-11-25, hen: "every combined animation in one move"): a new
     * item drops in from the screen's top edge into its own place while the other glides aside, one leaving goes back
     * up while the other glides to the centre, a pill grows into its box about its own centre. Counted in pixels from
     * the screen's left edge, whatever the language and wherever the system put the window.
     */
    private fun placeTop(context: Context) {
        // Not on screen yet (just added): its first layout calls this again, so the first move is seen from its start.
        if (topWindow?.isLaidOut != true) return
        val theirs = theirCell ?: return
        val mine = myCell ?: return
        val screen = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width().toFloat()
        val window = windowAt()
        val gap = dp(context, 6).toFloat()
        // Their picture's shape: said with their key, else the first picture's; mine is the camera's, upright.
        val shown = com.chencohen.ptt.talk.AndroidTalkVideoOut.shown.value ?: lastShown
        lastShown = shown
        val rotation = shown?.rotation ?: 0
        val aspect = if (shown != null && shown.width > 0 && shown.height > 0) shown.width.toFloat() / shown.height else 3f / 4f
        val turned = rotation % 180 != 0
        val talker = rowChat()?.let { messagingState(context).value.talk.talkers[it] }
        val said = talker?.let { com.chencohen.ptt.talk.LiveTalkVideo.shapes.value["${it.user}.${it.device}"] }?.let { it / 1000f }
        val theirShape = roomLive?.aspect ?: said ?: pretendShape ?: if (turned) 1f / aspect else aspect
        val myShape = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
        fun shape(cell: LiveCell) = if (cell === theirs) theirShape else myShape
        // Reading order: theirs, then mine.
        val cells = listOf(theirs, mine).filter { it.on }
        val pills = cells.filter { !it.video }.associateWith { it.pillSize(screen.toInt()) }
        // 23.6% of the screen high, all shrunk together only if side by side they'd be wider than the screen
        // (2026-11-25, hen: testbot's box was cut at the edge).
        val h = LiveLayout.boxHeight(
            topHeight(context).toFloat(), cells.filter { it.video }.map { shape(it) }, pills.values.map { it.first.toFloat() },
            screen - dp(context, 40), gap,
        )
        // Each box's inside at its full size, centred in its cell: the growing frame uncovers it.
        if (theirs.on && theirs.video) {
            val w = (h * theirShape).toInt()
            size(theirs.box, w, h.toInt())
            theirs.box.findViewWithTag<View>("video")?.let { v ->
                val (vw, vh) = if (turned) h.toInt() to w else w to h.toInt()
                val lp = v.layoutParams
                if (lp.width != vw || lp.height != vh) {
                    lp.width = vw
                    lp.height = vh
                    v.layoutParams = lp
                }
                v.rotation = rotation.toFloat()
            }
        }
        if (mine.on && mine.video) size(mine.box, (h * myShape).toInt(), h.toInt())
        val items = cells.map { cell ->
            if (cell.video) LiveItem(cell.key, h * shape(cell), h, true)
            else pills.getValue(cell).let { (w, ph) -> LiveItem(cell.key, w.toFloat(), ph.toFloat(), false) }
        }
        // The screen's own left edge and width, as the window sees them.
        val targets = LiveLayout.place(items, screen, gap, topRtl, left = -window[0].toFloat())
        // From above the screen's top edge, the shadow too.
        val moved = topMotion.target(targets, (restTop + window[1] + dp(context, 12)).toFloat(), dp(context, 16).toFloat())
        topRoom.intValue = if (targets.isEmpty()) 0 else window[1] + restTop + targets.values.maxOf { it.bottom }.toInt() + dp(context, 8)
        if (moved) Log.i(TAG, "row: " + targets.entries.joinToString { (k, r) -> "$k ${if (r.video > 0f) "video" else "voice"} x=${r.x.toInt()} w=${r.w.toInt()} h=${r.h.toInt()}" } + " (screen ${screen.toInt()}, window at ${window[0]},${window[1]}, ${if (topRtl) "right to left" else "left to right"})")
        showTop()
        touchTop()
        if (topMotion.running) frames()
    }

    /** Each cell where the motion has it now, and the chip under them. */
    private fun showTop() {
        val rects = topMotion.rects
        theirCell?.place(rects[LiveLayout.THEIRS], restTop)
        myCell?.place(rects[LiveLayout.MINE], restTop)
        bothChip?.takeIf { it.visibility == View.VISIBLE }?.translationY = restTop + (rects.values.maxOfOrNull { it.bottom } ?: 0f) + dp(topCard?.context ?: return, 8)
    }

    private var topFrames = false

    /** A move's frames, in step with the display; the system's animation speed counts (Remove animations: at once). */
    private val topFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            topFrames = false
            if (topWindow == null) return
            topMotion.frame(nanos / 1_000_000, android.animation.ValueAnimator.getDurationScale())
            showTop()
            // `adb shell setprop log.tag.TalkBubble VERBOSE`: each frame of a move, to check it is one move.
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "frame ${nanos / 1_000_000}: " + topMotion.rects.entries.joinToString { (k, r) -> "$k cx=%.1f top=%.1f w=%.1f h=%.1f".format(r.centreX, restTop + r.y, r.w, r.h) })
            if (topMotion.running) frames() else settled()
        }
    }

    private fun frames() {
        if (topFrames) return
        topFrames = true
        android.view.Choreographer.getInstance().postFrameCallback(topFrame)
    }

    /** A move ended: touches go only to what is there now, and a row asked to go, goes. */
    private fun settled() {
        touchTop()
        if (topHiding && topMotion.rects.isEmpty()) removeTop()
    }

    /**
     * The window is as wide as the screen, but only the items in it take touches (where they are and where they are
     * going, and the chip): the rest goes to the app beneath, which stays usable.
     */
    private fun touchTop() {
        val window = topWindow?.takeIf { it.isAttachedToWindow } ?: return
        val region = android.graphics.Region()
        for (r in topMotion.rects.values + topMotion.goals.values) {
            region.union(android.graphics.Rect(r.x.toInt(), (restTop + r.y).toInt().coerceAtLeast(0), (r.x + r.w).toInt() + 1, (restTop + r.bottom).toInt().coerceAtLeast(0) + 1))
        }
        bothChip?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }?.let { chip ->
            val top = chip.top + chip.translationY.toInt()
            region.union(android.graphics.Rect(chip.left, top, chip.right, top + chip.height))
        }
        window.rootSurfaceControl?.setTouchableRegion(region)
    }

    private var topParams: WindowManager.LayoutParams? = null

    private fun size(v: View, w: Int, h: Int) {
        val lp = v.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            v.layoutParams = lp
        }
    }

    /** The tags' time, the pictures' sources and shape, while the row shows. */
    private val topTick = object : Runnable {
        override fun run() {
            val window = topWindow ?: return
            if (topHiding) return
            fun clock(since: Long): String {
                val s = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
                return "%d:%02d".format(s / 60, s % 60)
            }
            theirTag?.text = "${android.text.BidiFormatter.getInstance().unicodeWrap(rowTitle())} · ${clock(theirSince())}"
            useRoomVideo(window.context)
            showTheirFace(window.context)
            // Their picture's shape came while their pill waited for it: now the pill grows into the box.
            if (theirVideoWanted() && theirCell?.let { it.on && !it.video } == true && theirPictureReady(window.context)) refreshTop(window.context)
            pickPreview()
            myTag?.text = "● " + window.context.getString(R.string.talk_panel_you) + " · " + window.context.getString(R.string.talk_panel_live).uppercase() + " " + clock(mySince())
            // A pill's words got wider, their picture's shape changed: everything to its place again.
            placeTop(window.context)
            main.postDelayed(this, 250)
        }
    }

    /** The voice pills' rings glow with the voice, theirs and mine, and their times count. */
    private val topPulse = object : Runnable {
        override fun run() {
            if (topWindow == null) return
            theirPill?.tick(com.chencohen.ptt.talk.TalkAudio.playLevel, theirSince())
            myPill?.tick(com.chencohen.ptt.talk.TalkAudio.micLevel, mySince())
            main.postDelayed(this, 60)
        }
    }

    /** Their face in their box until its first picture comes (room or clip), then gone. */
    private fun showTheirFace(context: Context) {
        val faceView = theirFace ?: return
        val picture = roomLive != null || com.chencohen.ptt.talk.AndroidTalkVideoOut.shown.value != null
        faceView.visibility = if (picture) View.GONE else View.VISIBLE
        val who = rowFrom().takeIf { it.isNotEmpty() } ?: return
        if (picture || theirFaceOf == who) return
        theirFaceOf = who
        Log.i(TAG, "their face until the first picture ($who)")
        val app = context.applicationContext as? TalkApp ?: return
        app.scope.launch {
            // In a process just woken the faces' list may not be published yet: waited for a moment, then the picture
            // is read directly.
            val id = kotlinx.coroutines.withTimeoutOrNull(1_000) { com.chencohen.ptt.ui.face.Faces.ids(app).first { it.containsKey(who) } }?.get(who) ?: "-"
            val bitmap = runCatching { com.chencohen.ptt.ui.face.Faces.load(app, who, id) }.getOrNull() ?: run { Log.i(TAG, "no face for $who"); return@launch }
            main.post { if (theirFace === faceView) faceView.setImageBitmap(bitmap.asAndroidBitmap()) }
        }
    }

    /** Their video through the talk room while its frames come, else the clip's (never both). */
    private fun useRoomVideo(context: Context) {
        val box = theirBox ?: return
        val talker = rowChat()?.let { messagingState(context).value.talk.talkers[it] }
        val live = talker?.let { com.chencohen.ptt.talk.LiveTalkVideo.arriving.value["${it.user}.${it.device}"] }?.takeIf { theirVideoWanted() }
        roomLive = live
        if (live != null && roomTrack !== live.track) {
            dropRoomVideo()
            val r = io.livekit.android.renderer.TextureViewRenderer(context)
            if (!com.chencohen.ptt.talk.LiveTalkVideo.initRenderer(r)) return
            r.setScalingType(livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            box.addView(r, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            live.track.addRenderer(r)
            roomRenderer = r
            roomTrack = live.track
            box.findViewWithTag<View>("video")?.visibility = View.INVISIBLE
        } else if (live == null && roomTrack != null) {
            dropRoomVideo()
            box.findViewWithTag<View>("video")?.visibility = View.VISIBLE
        }
    }

    private fun dropRoomVideo() {
        val r = roomRenderer ?: return
        roomTrack?.removeRenderer(r)
        (r.parent as? android.view.ViewGroup)?.removeView(r)
        r.release()
        roomRenderer = null
        roomTrack = null
    }

    /** The row's window, once (true: it is there). Nothing shows in it until [placeTop] brings the items in. */
    // Left, not start: the cells are placed by their left edge whatever the language (LiveLayout).
    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun addTop(context: Context): Boolean {
        if (topWindow != null) return true
        val wm = context.getSystemService(WindowManager::class.java)
        fun dp(v: Int) = dp(context, v)
        val bars = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
        // An overlay window may be told no insets (2026-11-25, hen: the box sat over the status bar): the status bar's
        // own height then.
        val statusTop = maxOf(bars.top, statusBarHeight(context))
        // Just under the status bar (2026-11-24, hen: "enter from the edge of the screen and start just below the
        // notification bar").
        restTop = statusTop + dp(2)
        // The language's order (theirs at its start side: the right in Hebrew, the left in English), decided here once
        // and said outright to everything below; no view in the row takes its direction from where it happens to sit.
        val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        topRtl = rtl
        val inside = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        fun tag() = TextView(context).apply {
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x80000000.toInt()) }
        }
        fun tagAt() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        // Their video, drawn in step with the voice (AndroidTalkVideoOut); the same decoder moves to the chat on a tap.
        val theirs = FrameLayout(context).apply {
            layoutDirection = inside
            addView(
                android.view.TextureView(context).apply {
                    tag = "video"
                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            videoSurface = android.view.Surface(texture).also { com.chencohen.ptt.talk.AndroidTalkVideoOut.attach(it) }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                            videoSurface?.let { com.chencohen.ptt.talk.AndroidTalkVideoOut.detach(it); it.release() }
                            videoSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
                    }
                },
                FrameLayout.LayoutParams(dp(120), dp(160), Gravity.CENTER),
            )
            // Their face, large, until the first picture (2026-11-25, hen: the box is never black).
            theirFace = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            addView(theirFace, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
            theirTag = tag()
            addView(theirTag, tagAt())
        }
        // Mine while I send video: "● You · LIVE 0:03".
        val mine = FrameLayout(context).apply {
            layoutDirection = inside
            addView(ownTexture(context) { s -> mySurface = s }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            myTag = tag()
            addView(myTag, tagAt())
        }
        val theirVoice = VoicePill(context, mine = false, rtl = rtl)
        val myVoice = VoicePill(context, mine = true, rtl = rtl)
        // One cell a side: a voice pill that grows into a video box and back (teal theirs, coral mine).
        val theirItem = LiveCell(context, LiveLayout.THEIRS, TEAL, theirVoice, theirs)
        val myItem = LiveCell(context, LiveLayout.MINE, PINK, myVoice, mine)
        // The stage: as large as the window, its children put by their left edge and top (never start or end).
        val stage = FrameLayout(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
            addView(theirItem.view, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
            addView(myItem.view, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
        }
        // Both live: "You're both live · Make it a call" under the boxes; a tap starts a video call.
        val chip = TextView(context).apply {
            visibility = View.GONE
            text = context.getString(R.string.talk_both_live) + " · " + context.getString(R.string.talk_make_call)
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xF2191D1C.toInt()); setStroke(dp(1), PINK) }
            setOnClickListener {
                val chat = rowChat() ?: return@setOnClickListener
                if (talking) {
                    locked = false
                    endTalk(context, send = true)
                }
                call(context, chat, true)
                dismissNow()
            }
        }
        stage.addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val holder = FrameLayout(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            addView(stage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val params = WindowManager.LayoutParams(
            // As wide as the screen and tall enough for the tallest row, once: nothing that shows in it moves or
            // resizes the window (2026-11-25, hen: "symmetrical, in one move"; a window resizing each frame nudged the
            // items). Only the items take touches ([touchTop]).
            WindowManager.LayoutParams.MATCH_PARENT,
            restTop + statusTop + topHeight(context) + dp(96),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            fitInsetsTypes = 0
            title = "Talk video"
        }
        val touch = TopTouch(context, wm, holder, params)
        theirItem.view.setOnTouchListener(touch)
        myItem.view.setOnTouchListener(touch)
        try {
            wm.addView(holder, params)
        } catch (e: Exception) {
            Log.w(TAG, "can't show the top row", e)
            return false
        }
        topParams = params
        topWindow = holder
        topCard = stage
        theirCell = theirItem
        myCell = myItem
        theirPill = theirVoice
        myPill = myVoice
        theirBox = theirs
        myBox = mine
        bothChip = chip
        topHiding = false
        // Wherever the system put the window (the S25 drew it at the screen's very top, under the status bar;
        // 2026-11-25), the items rest just below the status bar and are centred on the screen: once the window is
        // laid out, where it really is is taken into the places (the window itself stays put).
        holder.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (topWindow !== holder || (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom)) return@addOnLayoutChangeListener
            val at = IntArray(2).also { holder.getLocationOnScreen(it) }
            val low = statusTop + dp(2) - (at[1] - params.y + restTop)
            if (low > 0) restTop += low
            Log.i(TAG, "top row: window at ${at[0]},${at[1]}, ${right - left} wide, rests at ${at[1] + restTop}, status bar $statusTop, ${if (rtl) "right to left" else "left to right"}")
            holder.post { if (topWindow === holder && !topHiding) placeTop(context) }
        }
        main.removeCallbacks(topPulse)
        main.post(topPulse)
        return true
    }

    /** Drag moves the row; a swipe up puts it away (until their next burst); a tap opens the chat. */
    private class TopTouch(val context: Context, val wm: WindowManager, val window: View, val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var x0 = 0f
        private var y0 = 0f
        private var px = 0
        private var py = 0
        private var moved = false
        private var t0 = 0L

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX
                    y0 = e.rawY
                    px = params.x
                    py = params.y
                    moved = false
                    t0 = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0
                    val dy = e.rawY - y0
                    if (!moved && kotlin.math.hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        params.x = px + dx.toInt()
                        params.y = (py + dy.toInt()).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(window, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - y0
                    val fast = System.currentTimeMillis() - t0 < 400
                    when {
                        !moved -> rowChat()?.let { intoChat(context, it) }
                        // Up and quick, or dragged up past the top: put away.
                        (fast && dy < -dp(context, 40)) || py + dy < -dp(context, 24) -> {
                            topPutAway = true
                            hideTop()
                        }
                    }
                }
            }
            return true
        }
    }

    /** Every item goes back up through the screen's top edge, in one move; then the window goes. */
    private fun hideTop() {
        val window = topWindow ?: return
        if (topHiding) return
        topHiding = true
        main.removeCallbacks(topTick)
        theirCell?.on = false
        myCell?.on = false
        bothChip?.visibility = View.GONE
        topRoom.intValue = 0
        topMotion.target(emptyMap(), (restTop + windowAt()[1] + dp(window.context, 12)).toFloat())
        if (topMotion.running) frames() else removeTop()
    }

    private fun removeTop() {
        val window = topWindow ?: return
        topWindow = null
        main.removeCallbacks(topTick)
        main.removeCallbacks(topPulse)
        topMotion.clear()
        topRoom.intValue = 0
        videoSurface?.let { com.chencohen.ptt.talk.AndroidTalkVideoOut.detach(it) }
        videoSurface = null
        dropRoomVideo()
        dropMyRoomVideo()
        roomLive = null
        topCard = null
        topParams = null
        theirCell = null
        myCell = null
        theirBox = null
        theirTag = null
        theirFace = null
        theirFaceOf = null
        myBox = null
        myTag = null
        theirPill = null
        myPill = null
        bothChip = null
        topHiding = false
        runCatching { window.context.getSystemService(WindowManager::class.java).removeView(window) }
    }

    /**
     * One side's item in the row (2026-11-25, hen: "the voice pill should transform to a video box"): ONE view whose
     * frame is the voice pill or the video box and grows from one into the other about its centre, its corners, colour
     * and inside changing with it: the pill's face and words fade as the box's picture and tag show. It never leaves
     * and comes back for that. Put by its left edge and top (LiveMotion's frame), never by start or end.
     */
    private class LiveCell(context: Context, val key: String, color: Int, val pill: VoicePill, val box: FrameLayout) {
        val view = FrameLayout(context)
        /** In the row now; as a video box (else a voice pill). */
        var on = false
        var video = false
        private val density = context.resources.displayMetrics.density
        private val fill = GradientDrawable()
        private val edge = GradientDrawable()
        private var radius = -1f

        init {
            view.layoutDirection = View.LAYOUT_DIRECTION_LTR
            view.visibility = View.GONE
            view.elevation = 8 * density
            view.background = fill
            edge.setColor(Color.TRANSPARENT)
            edge.setStroke(density.toInt().coerceAtLeast(2), color)
            view.foreground = edge
            view.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, v.width, v.height, radius.coerceAtLeast(0f))
            }
            view.clipToOutline = true
            view.addView(box, FrameLayout.LayoutParams((120 * density).toInt(), (160 * density).toInt(), Gravity.CENTER))
            view.addView(pill.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        /** The pill's own size, whatever the cell's is now ([most]: the screen's width). */
        fun pillSize(most: Int): Pair<Int, Int> {
            // Asked afresh, as a window would ask it (a text keeps the width it was last laid out at otherwise).
            pill.forceLayout()
            pill.view.measure(View.MeasureSpec.makeMeasureSpec(most, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return pill.view.measuredWidth to pill.view.measuredHeight
        }

        /** The cell at [rect] (null: not in the row), [rest] below the window's top. */
        fun place(rect: LiveRect?, rest: Int) {
            if (rect == null) {
                if (view.visibility != View.GONE) view.visibility = View.GONE
                return
            }
            // Both edges from the centre: growing, the frame's centre stays where it is to the pixel.
            val left = kotlin.math.round(rect.centreX - rect.w / 2).toInt()
            val right = kotlin.math.round(rect.centreX + rect.w / 2).toInt()
            val h = kotlin.math.round(rect.h).toInt()
            val lp = view.layoutParams
            if (lp.width != right - left || lp.height != h) {
                lp.width = right - left
                lp.height = h
                view.layoutParams = lp
            }
            view.translationX = left.toFloat()
            view.translationY = rest + rect.y
            val p = rect.video.coerceIn(0f, 1f)
            val r = (32 - 14 * p) * density
            if (r != radius) {
                radius = r
                fill.cornerRadius = r
                edge.cornerRadius = r
                view.invalidateOutline()
            }
            fill.setColor(androidx.core.graphics.ColorUtils.blendARGB(0xF2191D1C.toInt(), Color.BLACK, p))
            box.alpha = p
            val boxShows = if (p > 0f) View.VISIBLE else View.GONE
            if (box.visibility != boxShows) box.visibility = boxShows
            pill.view.alpha = 1f - p
            val pillShows = if (p < 1f) View.VISIBLE else View.GONE
            if (pill.view.visibility != pillShows) pill.view.visibility = pillShows
            if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        }
    }

    // ---- The pulse, calls, leaving --------------------------------------------------------------------------------

    /** A ring around the pill pulses with the voice (teal; green while a call rings). */
    private val pulse = object : Runnable {
        override fun run() {
            val row = pill ?: return
            val context = row.context
            val state = messagingState(context).value
            val theirs = state.talk.playing?.takeIf { it.chat == chat }
            val level = when {
                talking -> com.chencohen.ptt.talk.TalkAudio.micLevel
                theirs != null -> com.chencohen.ptt.talk.TalkAudio.playLevel
                else -> 0f
            }.coerceIn(0f, 1f)
            val density = context.resources.displayMetrics.density
            val active = talking || theirs != null || ringing != null
            (row.background as? GradientDrawable)?.setStroke(
                if (active) (density * (1.5f + 4 * level)).toInt().coerceAtLeast((1.5f * density).toInt()) else 0,
                if (ringing != null) HERE else TEAL,
            )
            val scale = 1f + 0.15f * (if (talking) level else 0f)
            button?.scaleX = scale
            button?.scaleY = scale
            updateStop()
            main.postDelayed(this, 60)
        }
    }

    /** A call rings while the floating window shows: the pill becomes Answer (the push button) and Decline (Close). */
    fun incoming(context: Context, call: com.chencohen.ptt.calls.CallUi, @Suppress("UNUSED_PARAMETER") name: String) = main.post {
        if (pillWindow == null) return@post
        if (talking) endTalk(context, send = true)
        ringing = call
        (button?.background as? GradientDrawable)?.setColor(HERE)
        button?.setImageResource(if (call.video) R.drawable.ic_bubble_video else R.drawable.ic_bubble_call)
        button?.contentDescription = context.getString(R.string.call_answer)
        (closeButton?.background as? GradientDrawable)?.setColor(Color.RED)
        closeButton?.contentDescription = context.getString(R.string.call_decline)
        chatButton?.visibility = View.GONE
        showMode(context)
    }

    /** The call stopped ringing (answered, declined, missed): the pill is Chat · Push · Close again. */
    fun callGone(context: Context) = main.post {
        if (ringing == null) return@post
        ringing = null
        (button?.background as? GradientDrawable)?.setColor(PINK)
        (closeButton?.background as? GradientDrawable)?.setColor(0x33FFFFFF)
        closeButton?.contentDescription = context.getString(R.string.bubble_close)
        chatButton?.visibility = View.VISIBLE
        showMode(context)
    }

    private fun answer(context: Context) {
        ringing ?: return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).setAction(CallNotifications.ACTION_ANSWER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        callGone(context)
        dismissNow()
    }

    /**
     * To the chat: it opens under the floating window with their video already in its top panel (the same decoder
     * moves there), and the top box grows a little and fades into it (200 ms) instead of sliding away.
     */
    private fun intoChat(context: Context, chat: String) {
        openChat(context, chat)
        if (outside != null) {
            // The floating chat's row: the chat opens in the app, the floating one closes, and the row goes with it.
            QuickReplyWindow.close(context)
        } else {
            topCard?.let { card ->
                // About the row's own middle.
                card.pivotX = card.width / 2f
                card.pivotY = restTop + (topMotion.rects.values.maxOfOrNull { it.bottom } ?: 0f) / 2
                card.animate().scaleX(1.1f).scaleY(1.1f).alpha(0f).setDuration(200).start()
            }
        }
        pill?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction { remove() }?.start() ?: remove()
    }

    private fun openChat(context: Context, chat: String) = runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).setAction(Notifications.ACTION_OPEN).putExtra(Notifications.EXTRA_USER, chat)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun call(context: Context, chat: String, video: Boolean) = runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_CALL).putExtra(Notifications.EXTRA_USER, chat)
                .putExtra(MainActivity.EXTRA_VIDEO, video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun openTalk(context: Context, chat: String) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    /** Both pieces slide away (the pill down, the top box up), then go. */
    private fun dismissNow() {
        val row = pill ?: return remove()
        // The floating chat's row stays while that chat is live.
        if (outside == null) hideTop()
        row.animate().translationY((row.height + dp(row.context, 60)).toFloat()).setDuration(SLIDE_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { if (pill === row) remove() }
            .start()
    }

    private fun remove() {
        val window = pillWindow
        val context = window?.context
        // The floating chat's row outlives the bottom pill.
        if (outside == null) removeTop()
        if (window == null || context == null) return
        pillWindow = null
        pill = null
        main.removeCallbacks(pulse)
        main.removeCallbacks(renew)
        main.removeCallbacks(burstOver)
        main.removeCallbacks(hideSoon)
        setHere(context, null)
        if (previewOn) preview(context, false)
        previewOn = false
        ringing = null
        chatButton = null
        button = null
        closeButton = null
        pushBox = null
        stopPill = null
        lockHint = null
        talking = false
        locked = false
        sendingVideo = false
        burstLive = false
        burstVideo = false
        burstSince = 0L
        topPutAway = false
        goingLive = false
        chat = null
        from = null
        runCatching { context.getSystemService(WindowManager::class.java).removeView(window) }
        Log.i(TAG, "gone")
        if (outside != null) refreshTop(context.applicationContext)
    }

    /** The title for a burst from [from] in [chat]: their name, and the group's. */
    suspend fun titleOf(context: Context, chat: String, from: String): String {
        val who = runCatching { chatName(context, from) }.getOrDefault(from)
        return if (Group.idOf(chat) != null) {
            context.getString(R.string.playing_group, who, runCatching { chatName(context, chat) }.getOrDefault(chat))
        } else {
            who
        }
    }

    /**
     * A slim voice pill at the top (2026-11-24, hen, "B"): the talker's face in a ring glowing with the voice (teal,
     * coral for mine; no wave), "Sharon is talking" and the time, or for mine one line, "You · 0:03". Only the
     * inside: its frame (shape, colour, outline, shadow) is its LiveCell's, which grows into the video box.
     */
    private class VoicePill(context: Context, val mine: Boolean, rtl: Boolean) {
        private fun dp(v: Int) = dp(view.context, v)
        val view = LinearLayout(context)
        private val ring = GlowRing(context).also { it.color = if (mine) PINK else TEAL }
        private val face = ImageView(context).apply { visibility = View.GONE; scaleType = ImageView.ScaleType.FIT_CENTER }
        private val letter = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER }
        private val name = TextView(context).apply { setTextColor(Color.WHITE); textSize = 14f; maxLines = 1; setTypeface(typeface, android.graphics.Typeface.BOLD); textDirection = View.TEXT_DIRECTION_LOCALE; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
        private val time = TextView(context).apply { setTextColor(0xFFC9D1CF.toInt()); textSize = 12f; textDirection = View.TEXT_DIRECTION_LOCALE; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
        /** Whose face shows ("" mine); null: hidden. */
        var shown: String? = null
            private set

        init {
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER_VERTICAL
            // Its inside in the language's direction, said outright (the cell around it is laid out left to right): the
            // face at the start, the room after the words at the end (2026-11-25: it was at the right in Hebrew too).
            view.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            view.setPaddingRelative(dp(2), dp(2), dp(18), dp(2))
            letter.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            val faceBox = FrameLayout(context).apply { clipChildren = false }
            faceBox.addView(ring, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
            faceBox.addView(letter, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
            faceBox.addView(face, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            view.addView(faceBox, LinearLayout.LayoutParams(dp(60), dp(60)))
            val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(2), 0, 0, 0) }
            words.addView(name)
            if (!mine) words.addView(time)
            view.addView(words)
        }

        /** Shows [user]'s face ("" mine; null hides), titled [title] (theirs). */
        fun show(context: Context, user: String?, title: String) {
            if (user == null) {
                shown = null
                return
            }
            // In their own form (testbot set male: "testbot מדבר"), the name wrapped so a Latin name doesn't turn the
            // Hebrew line around (2026-11-25, hen).
            if (!mine) name.text = Gendered.string(context, R.string.talk_someone, user, android.text.BidiFormatter.getInstance().unicodeWrap(title))
            if (shown == user) return
            shown = user
            letter.text = (if (mine) context.getString(R.string.talk_panel_you) else title).take(1).uppercase()
            letter.visibility = View.VISIBLE
            face.visibility = View.GONE
            val app = context.applicationContext as? TalkApp ?: return
            app.scope.launch {
                val id = kotlinx.coroutines.withTimeoutOrNull(1_000) { com.chencohen.ptt.ui.face.Faces.ids(app).first { it.containsKey(user) } }?.get(user) ?: "-"
                val bitmap = runCatching { com.chencohen.ptt.ui.face.Faces.load(app, user, id) }.getOrNull() ?: return@launch
                main.post {
                    if (shown != user) return@post
                    face.setImageBitmap(bitmap.asAndroidBitmap())
                    face.visibility = View.VISIBLE
                    letter.visibility = View.GONE
                }
            }
        }

        /** Its texts measure themselves anew at the next measure. */
        fun forceLayout() {
            name.forceLayout()
            time.forceLayout()
            (name.parent as? View)?.forceLayout()
            view.forceLayout()
        }

        /** The voice's [level] on the ring, and the time since [since]. */
        fun tick(level: Float, since: Long) {
            if (view.visibility == View.VISIBLE) ring.level(level)
            val s = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
            val t = "%d:%02d".format(s / 60, s % 60)
            // Only when it changes: a text set again asks for a layout each time.
            val (line, words) = if (mine) name to view.context.getString(R.string.talk_panel_you) + " · " + t else time to t
            if (line.text.toString() != words) line.text = words
        }
    }

    /**
     * A teal ring around the talker's face that glows with the voice (2026-11-24, hen: "make the green circle glow as
     * the voice"): brightness, glow and thickness follow the level, smoothed 60 ms up and 200 ms down (VoiceLevel).
     */
    private class GlowRing(context: Context) : View(context) {
        private var level = 0f
        private var target = 0f
        private var last = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = TEAL }

        /** Teal for theirs, coral for mine. */
        var color: Int
            get() = paint.color
            set(value) {
                paint.color = value
                invalidate()
            }
        private val density = context.resources.displayMetrics.density

        fun level(v: Float) {
            target = v.coerceIn(0f, 1f)
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (last == 0L) 16f else (now - last).toFloat().coerceIn(1f, 100f)
            last = now
            level = com.chencohen.ptt.talk.VoiceLevel.smooth(level, target, dt)
            val cx = width / 2f
            val cy = height / 2f
            // The ring just outside the 36 dp face.
            val r = 19.5f * density
            val thick = (2f + 2.5f * level) * density
            // The glow: rings further out, fainter, as far as the voice is loud.
            val glow = 7f * density * level
            var g = glow
            while (g > 0.5f * density) {
                paint.strokeWidth = 2f * density
                paint.alpha = (90 * level * (1 - g / (glow + 0.01f))).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, r + thick / 2 + g, paint)
                g -= 1.5f * density
            }
            paint.strokeWidth = thick
            paint.alpha = (255 * (0.55f + 0.45f * level)).toInt()
            canvas.drawCircle(cx, cy, r, paint)
            if (kotlin.math.abs(level - target) > 0.005f) postInvalidateOnAnimation()
        }
    }

    private const val PINK = 0xFFE9504F.toInt()
    private const val TEAL = 0xFF2BB3A3.toInt()
    private const val HERE = 0xFF34C759.toInt()
}
