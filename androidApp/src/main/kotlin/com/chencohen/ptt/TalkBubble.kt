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
import com.chencohen.ptt.ui.contacts.LivePretend
import com.chencohen.ptt.ui.live.LiveCast
import com.chencohen.ptt.ui.live.LiveGroupLayout
import com.chencohen.ptt.ui.live.LiveItem
import com.chencohen.ptt.ui.live.LiveLayout
import com.chencohen.ptt.ui.live.LivePerson
import com.chencohen.ptt.ui.live.LiveSlot
import com.chencohen.ptt.ui.live.LiveMotion
import com.chencohen.ptt.ui.live.LiveRect
import com.chencohen.ptt.ui.live.Dock
import com.chencohen.ptt.ui.live.DockEdge
import com.chencohen.ptt.ui.live.FloatDock
import com.chencohen.ptt.ui.live.FloatScreen
import com.chencohen.ptt.ui.live.Height
import com.chencohen.ptt.ui.live.Lean
import com.chencohen.ptt.ui.live.ScreenRect
import com.chencohen.ptt.ui.live.VideoPlace
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
    private const val BURST_HOLD_MS = 1_000L
    private const val RUN_GAP_MS = 3_000L
    /** The pill window's fixed width. */
    private const val PILL_WINDOW_DP = 300
    /** Slid up this far while holding: hands-free. */
    private const val LOCK_DP = 56
    /** Hands-free is let go and taken again before the server's 60 s floor ends. */
    private const val RENEW_MS = 55_000L
    /** Held this long on the bar (not on Push): it lifts and can be dragged to another edge. */
    private const val LONG_PRESS_MS = 400L
    /** How long after the app or chat goes the top row takes over in place ([takingOverRow]). */
    private const val IN_PLACE_MS = 1_500L
    /** How long a press handed to the chat by Chat waits for the chat to take it. */
    private const val PENDING_BACK_MS = 5_000L
    /** Upright on a side edge: Stop is this tall (its icon over the time). */
    private const val STOP_UPRIGHT_DP = 84
    /** The room kept beside the bar for the lock while Push is held. */
    private const val HINTS_DP = 58
    /** Folded into its edge, this much of the bar still shows: a handle at the top or bottom, a tab at the sides. */
    private const val PEEK_DP = 20
    private const val PEEK_SIDE_DP = 24
    /** Folded, the handle or tab takes touches this much further in than it shows. */
    private const val PEEK_TOUCH_DP = 20
    /**
     * The grip (2026-11-27, hen: "make the gripper a little bigger, not visually, just to grip and drag more easily"):
     * a finger this close to the bar's inside edge, or up to [GRIP_OUT_DP] beyond it, moves the bar at once, no hold.
     */
    private const val GRIP_IN_DP = 18
    private const val GRIP_OUT_DP = 18

    // ---- Where the bar rests, and folding (2026-11-27, hen: "drag the floating bar ... along the edges", "auto hide in
    // a smart way", "if docked to the side edges ... hide and be able to return with a gesture") ----
    /** The edge the bar rests on (kept between showings); upright on the left and right edges. */
    private var dock = Dock.DEFAULT
    private var pillParams: WindowManager.LayoutParams? = null
    /** The system bar's room at the bar's edge (the navigation bar at the bottom, the status bar at the top). */
    private var edgeInset = 0
    /** Folded into its edge: only a slim handle or tab shows, and a tap or a swipe in brings it back. */
    private var folded = false
    /** How much of the bar's buttons show (1; 0 while folded): the fold fades them as it slides. */
    private var barShown = 1f
    private var foldMove: android.animation.ValueAnimator? = null
    /** When something last happened on the bar or in its chat (talk, a touch): it folds [FloatDock.QUIET_MS] after. */
    private var lastActive = 0L
    private var keyboardUp = false
    private var fullScreen = false
    /** Lifted by a long press: the bar follows the finger, and the edges it can go to show. */
    private var moving = false
    private var moveFrom = intArrayOf(0, 0)
    private var guide: DockGuide? = null
    /** Where the videos rest, on their own (2026-11-27, hen: "stay at the top in original position"). */
    private var videoPlace = VideoPlace.DEFAULT
    private var topMove: android.animation.ValueAnimator? = null
    private var placesRead = false
    private var wasBusy = false

    private val main = Handler(Looper.getMainLooper())

    // The bottom pill (its own window).
    private var pillWindow: LinearLayout? = null
    private var pill: FrameLayout? = null
    /** The pill's frame now: how far its middle is Stop (0: Push) and how far the Chat button is there (0: not). */
    private var pillStop = 0f
    private var pillChat = 1f
    /** How far Decline is there (1 while a call rings, else 0: the bar has no ✕, it is swiped down; 2026-11-26, hen). */
    private var pillDecline = 0f
    /** The bar's own face (solid, with its light rim): the voice's ring is drawn on it. */
    private var barFace: GradientDrawable? = null
    /** Under the Chat button's place while Push is held: what sliding onto it does ("Hands-free", "Urgent"). */
    private var sideLabel: TextView? = null
    /** The bar follows a finger swiping it down: where the swipe is, and how fast it last moved (pixels a millisecond). */
    private var barDrag = false
    private var dragLastY = 0f
    private var dragLastAt = 0L
    private var dragSpeed = 0f
    private var pillMorph: android.animation.ValueAnimator? = null
    /** "■ Stop · 00:00" at its widest: the Stop pill's width, the same whatever the time says. */
    private var stopWidth = 0
    private var chatButton: ImageView? = null
    private var button: ImageView? = null
    private var closeButton: ImageView? = null
    /** The finger started sliding up: the camera opens, so the video starts fast at the lock. */
    private var warming = false
    private var pushBox: FrameLayout? = null
    private var stopPill: TextView? = null
    private var lockHint: ImageView? = null
    /** The red bell beside Push while it is held: slid onto it, the push is urgent (2026-11-25, hen). */
    private var bellHint: ImageView? = null
    /**
     * The microphone in the bell's place while everyone can hear (2026-11-26, hen: "the urgent indicator should be only
     * if the user didn't hear; until then it should be a microphone icon, and sliding to it turns into hands-free voice
     * transmission"): slid onto it and let go, the push goes on hands-free, voice, until Stop.
     */
    private var micHint: ImageView? = null
    /** The side target is the bell now (someone can't hear), not the microphone. */
    private var sideBell = false
    /** This press is urgent. */
    private var urgent = false
    /** Slid onto the microphone: this press goes on hands-free, voice, when let go (and until Stop). */
    private var sideVoice = false
    private var downX = 0f
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
    private var myFace: ImageView? = null
    private var bothChip: TextView? = null
    private var topHiding = false
    // Several talking at once in a group (2026-11-25, hen): in a group chat's row, a cell for each member live, by their
    // "user.device" (their pictures through the room, else their face; the clip path's one decoder can't show
    // several), placed with mine by LiveGroupLayout; LiveCast keeps the four who spoke last in view, the chip offers
    // the rest.
    private val groupCells = LinkedHashMap<String, GroupCell>()
    private var groupCast = LiveCast()
    private var moreChip: TextView? = null
    /** Who the chip would bring into view. */
    private var moreKey: String? = null
    private var topTouch: TopTouch? = null
    /** Test builds only (TestBubble): the group chat whose pretended members (LivePretend.members) the row shows. */
    private var pretendChat: String? = null

    private var chat: String? = null
    private var title: String = ""
    private var from: String? = null
    /** Their burst plays now; with video. */
    private var burstLive = false
    private var burstVideo = false
    private var burstSince = 0L
    private var burstEndedAt = 0L
    /** When my last burst from here ended: a slide up's video right after keeps my time counting. */
    private var talkEndedAt = 0L
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

    /** Test builds only (TestBubble): [chat]'s pretended members (LivePretend.members) show in the row, or no more. */
    internal fun pretendMembers(context: Context, chat: String) = main.post {
        val app = context.applicationContext
        pretendChat = if (LivePretend.members.value.isEmpty()) null else chat
        if (pretendChat != null && !Settings.canDrawOverlays(app)) return@post
        refreshTop(app)
    }

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
            // The same run if their last burst ended moments ago (a slide up to video): its time keeps counting.
            if (burstSince == 0L || System.currentTimeMillis() - burstEndedAt > RUN_GAP_MS) burstSince = System.currentTimeMillis()
            topPutAway = false
            thaw(theirBox)
        }
        burstLive = live
        burstVideo = live && video
        if (!live) burstEndedAt = System.currentTimeMillis()
        refreshTop(app)
        showMode(app)
    }

    private var burstOverApp: Context? = null

    /** Their burst in [chat] ended while its chat was in front: the row's live state ends, nothing new shows. */
    fun burstEnded(context: Context, chat: String) = main.post {
        if (this.chat != chat || !burstLive) return@post
        burstOverApp = context.applicationContext
        main.removeCallbacks(burstOver)
        main.postDelayed(burstOver, BURST_HOLD_MS)
    }

    /** Their burst ended and no other followed within [BURST_HOLD_MS]: now it goes. */
    private val burstOver = Runnable {
        val app = burstOverApp ?: return@Runnable
        burstLive = false
        burstVideo = false
        burstEndedAt = System.currentTimeMillis()
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

    // Left, not start: the pill's buttons are put by their left edge whatever the language ([shapePill]).
    /**
     * The bar's window at [dock]. [from]: the bar was just dragged here, its centre was there on the screen (it glides
     * from there into its new place instead of sliding in from the edge).
     */
    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun addPill(context: Context, from: FloatArray? = null): Boolean {
        val wm = context.getSystemService(WindowManager::class.java)
        fun dp(v: Int) = dp(context, v)
        readPlaces(context)
        val edge = dock.edge
        val upright = edge.upright
        val bars = navGestureInsets(wm.currentWindowMetrics.windowInsets)
        val statusTop = maxOf(
            wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top,
            statusBarHeight(context),
        )
        // One frame that grows and shrinks about its centre in one move ([shapePill]; 2026-11-25, hen: "the bottom pill
        // transition is juggling when morphing"): Chat at its left end, Push or Stop after it, each put by its left
        // edge, the same in Hebrew and English. No LinearLayout and LayoutTransition any more. Upright on a side edge
        // (2026-11-27), the same from top to bottom: Chat above Push.
        //
        // The bar hen chose (2026-11-26, the floating-bar mock: "option A + button layout 1"): fully solid, a light rim
        // inside a dark hairline and a deep shadow, so one of its edges shows over any app, light or dark; two buttons
        // only, Chat (48 dp) and Push (60 dp), 12 dp apart, Push right of the middle for a right thumb; no ✕: the bar
        // is swiped toward its edge to fold it or flung to send it away ([dragBar], a small grab line hints at it).
        // While Push is held the Chat button's place is the slide target (hands-free voice, or the urgent bell).
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val row = object : FrameLayout(context) {
            private var downX = 0f
            private var downY = 0f
            private var mine = false
            /** Down on the grip: a move lifts the bar at once. */
            private var grip = false

            /** How far in from the bar's inside edge (the grip's side) the finger is. */
            private fun inside(e: MotionEvent): Float = when (dock.edge) {
                DockEdge.BOTTOM -> e.y
                DockEdge.TOP -> height - e.y
                DockEdge.LEFT -> width - e.x
                DockEdge.RIGHT -> e.x
            }

            /** Lifted by the grip: counted from here, so the bar doesn't jump by the slop. */
            private fun liftHere(e: MotionEvent) {
                main.removeCallbacks(longPress)
                liftBar()
                downX = e.rawX
                downY = e.rawY
            }

            private fun away(e: MotionEvent) = along(e.rawX - downX, e.rawY - downY, awayDir())
            private fun across(e: MotionEvent) = kotlin.math.abs(along(e.rawX - downX, e.rawY - downY, acrossDir()))
            private fun far(e: MotionEvent) = kotlin.math.hypot(e.rawX - downX, e.rawY - downY) > slop

            /** A finger going toward the bar's edge, wherever it started on it (Push has its own: [touch]). */
            private fun swipes(e: MotionEvent): Boolean {
                val d = away(e)
                return !talking && ringing == null && d > slop && d > across(e)
            }

            /** The finger is on Push (which has its own touches): no long press to move the bar there. */
            private fun onPush(e: MotionEvent): Boolean {
                val p = pushBox ?: return false
                val x = p.left + p.translationX
                val y = p.top + p.translationY
                return e.x >= x && e.x <= x + p.width && e.y >= y && e.y <= y + p.height
            }

            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        mine = false
                        lastActive = System.currentTimeMillis()
                        dragSample(awayAt(e), e.eventTime, first = true)
                        // Folded: the whole tab is one target, a tap or a swipe in.
                        if (folded) return true
                        main.removeCallbacks(longPress)
                        grip = !onPush(e) && inside(e) < dp(context, GRIP_IN_DP)
                        if (canLift() && !onPush(e)) main.postDelayed(longPress, LONG_PRESS_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (moving) return true
                        if (far(e)) main.removeCallbacks(longPress)
                        // From the grip, any way but toward the edge (that still folds it) moves the bar.
                        if (grip && far(e) && !swipes(e) && canLift()) {
                            liftHere(e)
                            return moving
                        }
                        if (!barDrag && swipes(e)) {
                            mine = true
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(longPress)
                        // Lifted and let go without moving: Chat under the finger isn't opened.
                        if (moving) return true
                    }
                }
                return false
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (folded) {
                            // Swiped in from the edge: it comes out.
                            if (-away(e) > slop) unfold()
                            return true
                        }
                        if (moving) {
                            moveBar(e.rawX - downX, e.rawY - downY)
                            return true
                        }
                        if (far(e)) main.removeCallbacks(longPress)
                        if (!mine && swipes(e)) mine = true
                        if (mine) {
                            dragSample(awayAt(e), e.eventTime, first = false)
                            dragBar(away(e) - slop)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(longPress)
                        val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                        when {
                            folded -> if (!cancelled && !far(e)) unfold()
                            moving -> dropMove(context, cancelled)
                            mine -> {
                                mine = false
                                dropBar(context, away(e) - slop, cancelled)
                            }
                        }
                    }
                }
                return true
            }
        }.apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            val face = GradientDrawable().apply { cornerRadius = dp(44).toFloat(); setColor(BAR); setStroke(rimPx(context), RIM) }
            barFace = face
            // The dark hairline outside the light rim.
            background = android.graphics.drawable.LayerDrawable(
                arrayOf(GradientDrawable().apply { cornerRadius = dp(44).toFloat(); setColor(0xA6000000.toInt()) }, face),
            ).apply { setLayerInset(1, dp(1), dp(1), dp(1), dp(1)) }
            elevation = dp(12).toFloat()
        }
        fun round(icon: Int, label: Int, size: Int, color: Int, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            val pad = size / 4
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            contentDescription = context.getString(label)
            setOnClickListener { onClick() }
        }
        chatButton = round(R.drawable.ic_bubble_chat, R.string.talk_action_chat, dp(48), 0x33FFFFFF) { chat?.let { intoChat(context, it) } }
        // The push button: hold for voice, slide toward the screen's inside to the lock for hands-free video, onto
        // Chat's place for hands-free voice.
        val push = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        button = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PINK) }
            contentDescription = context.getString(R.string.bubble_talk_hint)
            setOnTouchListener { v, e -> touch(context, v, e) }
        }
        push.addView(button, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        pushBox = push
        // Hands-free: the push button's place shows a coral "■ Stop · 0:42" (upright: "■" over "0:42"); a tap stops.
        stopPill = TextView(context).apply {
            visibility = View.INVISIBLE
            setTextColor(Color.WHITE)
            textSize = if (upright) 13f else 15f
            gravity = Gravity.CENTER
            maxLines = if (upright) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // Digits all one width, and the pill as wide as its widest time: the seconds don't nudge it.
            fontFeatureSettings = "tnum"
            setPadding(if (upright) 0 else dp(16), 0, if (upright) 0 else dp(16), 0)
            stopWidth = ((paint.measureText("■  " + context.getString(R.string.talk_stop) + " · 00:00") + dp(32)).toInt() / 2 + 1) * 2
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(PINK) }
            // Shown through a round frame that widens (or grows taller, upright) from the Push button's own circle to
            // the whole pill.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    if (dock.edge.upright) {
                        val shown = dp(60) + (v.height - dp(60)) * pillStop
                        outline.setRoundRect(0, ((v.height - shown) / 2).toInt(), v.width, ((v.height + shown) / 2).toInt(), dp(30).toFloat())
                    } else {
                        val shown = dp(60) + (v.width - dp(60)) * pillStop
                        outline.setRoundRect(((v.width - shown) / 2).toInt(), 0, ((v.width + shown) / 2).toInt(), v.height, dp(30).toFloat())
                    }
                }
            }
            clipToOutline = true
            contentDescription = context.getString(R.string.talk_stop)
            setOnClickListener {
                locked = false
                endTalk(context, send = true)
            }
        }
        // Decline, only while a call rings (the bar itself has no ✕).
        closeButton = round(R.drawable.ic_bubble_close, R.string.call_decline, dp(48), Color.RED) { closeOrDecline(context) }.apply { visibility = View.INVISIBLE }
        // The slide target, in the Chat button's own place while Push is held (2026-11-26, hen: no new target popping
        // up beside the bar): a teal microphone, "Hands-free" (voice until Stop), or, only while someone in the chat
        // can't hear now, the red bell, "Urgent". On the left of Push on the screen in either language (above it,
        // upright): a right thumb doesn't hide it.
        fun target(icon: Int, color: Int, ring: Int, label: Int) = ImageView(context).apply {
            setImageResource(icon)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), ring) }
            contentDescription = context.getString(label)
            visibility = View.INVISIBLE
        }
        micHint = target(R.drawable.ic_bubble_mic, 0xFF1F8A75.toInt(), 0xFF7FE6D0.toInt(), R.string.talk_hands_free)
        bellHint = target(R.drawable.ic_bubble_bell, URGENT, 0xFFFFB3A8.toInt(), R.string.talk_urgent)
        sideLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(7), dp(1), dp(7), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xC0000000.toInt()) }
            visibility = View.INVISIBLE
        }
        // The grab line, on the side facing the screen's inside (what still shows when the bar is folded): the bar can
        // be swiped toward its edge.
        val grab = View(context).apply { background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x66FFFFFF) } }
        val corner = Gravity.LEFT or Gravity.TOP
        row.addView(
            grab,
            when (edge) {
                DockEdge.BOTTOM -> FrameLayout.LayoutParams(dp(26), dp(3), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(5) }
                DockEdge.TOP -> FrameLayout.LayoutParams(dp(26), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(5) }
                DockEdge.LEFT -> FrameLayout.LayoutParams(dp(3), dp(26), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(5) }
                DockEdge.RIGHT -> FrameLayout.LayoutParams(dp(3), dp(26), Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(5) }
            },
        )
        row.addView(chatButton, FrameLayout.LayoutParams(dp(48), dp(48), corner))
        row.addView(micHint, FrameLayout.LayoutParams(dp(48), dp(48), corner))
        row.addView(bellHint, FrameLayout.LayoutParams(dp(48), dp(48), corner))
        row.addView(push, FrameLayout.LayoutParams(dp(60), dp(60), corner))
        row.addView(stopPill, if (upright) FrameLayout.LayoutParams(dp(60), dp(STOP_UPRIGHT_DP), corner) else FrameLayout.LayoutParams(stopWidth, dp(60), corner))
        row.addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), corner))
        // Beside the target (the window's own room around the bar).
        row.addView(sideLabel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, corner))
        // The lock, on the screen's inside of the push button while it is held (above it at the bottom). Its room is
        // always there (unseen), so the window never changes size and the pill never moves when it shows.
        lockHint = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_lock)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GradientDrawable().apply { cornerRadius = dp(21).toFloat(); setColor(BAR); setStroke(rimPx(context), RIM) }
            visibility = View.INVISIBLE
        }
        val hints = FrameLayout(context).apply {
            clipChildren = false
            addView(lockHint, if (upright) FrameLayout.LayoutParams(dp(58), dp(42), Gravity.CENTER) else FrameLayout.LayoutParams(dp(42), dp(58), Gravity.CENTER))
        }
        val holder = LinearLayout(context).apply {
            orientation = if (upright) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (upright) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
            val hintsAt = if (upright) {
                LinearLayout.LayoutParams(dp(HINTS_DP), LinearLayout.LayoutParams.MATCH_PARENT)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(HINTS_DP))
            }
            val rowAt = if (upright) LinearLayout.LayoutParams(dp(BAR_DP), dp(PILL_WINDOW_DP)) else LinearLayout.LayoutParams(dp(PILL_WINDOW_DP), dp(BAR_DP))
            // The lock's room on the screen's inside of the bar.
            when (edge) {
                DockEdge.BOTTOM -> { hintsAt.bottomMargin = dp(8); addView(hints, hintsAt); addView(row, rowAt) }
                DockEdge.TOP -> { hintsAt.topMargin = dp(8); addView(row, rowAt); addView(hints, hintsAt) }
                DockEdge.LEFT -> { hintsAt.leftMargin = dp(8); addView(row, rowAt); addView(hints, hintsAt) }
                DockEdge.RIGHT -> { hintsAt.rightMargin = dp(8); addView(hints, hintsAt); addView(row, rowAt) }
            }
            // Room for the shadow; at the bottom, down to the screen's own bottom edge over the navigation bar, so the
            // pill comes in from the very bottom and leaves there, never cut at the bar (2026-11-25, hen). At the top,
            // from the screen's top edge over the status bar.
            when (edge) {
                DockEdge.BOTTOM -> setPadding(dp(8), dp(8), dp(8), bars.bottom + dp(24))
                DockEdge.TOP -> setPadding(dp(8), statusTop + dp(12), dp(8), dp(8))
                DockEdge.LEFT -> setPadding(bars.left + dp(8), 0, dp(8), 0)
                DockEdge.RIGHT -> setPadding(dp(8), 0, bars.right + dp(8), 0)
            }
        }
        edgeInset = when (edge) {
            DockEdge.BOTTOM -> bars.bottom
            DockEdge.TOP -> statusTop
            DockEdge.LEFT -> bars.left
            DockEdge.RIGHT -> bars.right
        }
        val screen = wm.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            // As long as the pill gets (with Stop): the window doesn't change size, so the pill grows and shrinks
            // about its centre instead of the window jumping to a new one.
            if (upright) WindowManager.LayoutParams.WRAP_CONTENT else dp(PILL_WINDOW_DP),
            if (upright) dp(PILL_WINDOW_DP) else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // The app beneath stays usable: touches outside the pill go to it, and nothing closes on them.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            when (edge) {
                DockEdge.BOTTOM -> { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 0 }
                DockEdge.TOP -> { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 0 }
                DockEdge.LEFT, DockEdge.RIGHT -> {
                    gravity = Gravity.TOP or (if (edge == DockEdge.LEFT) Gravity.LEFT else Gravity.RIGHT)
                    x = 0
                    y = (screen.height() * dock.along - dp(PILL_WINDOW_DP) / 2f).toInt()
                        .coerceIn(statusTop, (screen.height() - bars.bottom - dp(PILL_WINDOW_DP)).coerceAtLeast(statusTop))
                }
            }
            // To the screen's edge (over the system bars): the holder's padding keeps the pill where it was.
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            title = "Talk pill"
        }
        return try {
            wm.addView(holder, params)
            pillWindow = holder
            pillParams = params
            pill = row
            pillStop = 0f
            pillChat = 1f
            pillDecline = 0f
            barDrag = false
            folded = false
            barShown = 1f
            moving = false
            lastActive = System.currentTimeMillis()
            shapePill()
            applySheet()
            // A new Chat button (the bar moved to another edge, or shown again): the chat's face on it again (2026-11-27,
            // hen: "when dragging the bar there is the chat icon instead of the avatar").
            chatFaceOf = null
            showChatFace(context)
            // Touches just beyond the bar (the grip's room, or a folded tab's): they move it, or bring it out.
            holder.setOnTouchListener { _, e -> edgeTouch(context, e) }
            // Only the pill takes touches (folded: the part of it that shows): the room kept for the lock beside it is
            // the app's beneath.
            row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (pill === row) touchBar() }
            // The keyboard coming up, or an app going full screen, folds the bar (when nothing is going on in it).
            holder.setOnApplyWindowInsetsListener { _, insets ->
                val ime = insets.isVisible(WindowInsets.Type.ime())
                val full = !insets.isVisible(WindowInsets.Type.statusBars())
                if (ime != keyboardUp || full != fullScreen) {
                    Log.i(TAG, "keyboard ${if (ime) "up" else "down"}, ${if (full) "full screen" else "status bar shows"}")
                    val fold = (ime && !keyboardUp) || (full && !fullScreen)
                    keyboardUp = ime
                    fullScreen = full
                    if (fold && pill === row && !folded && FloatDock.folds(0, ime, full, busy(), liveVideo(), ringing != null)) fold()
                }
                insets
            }
            if (from == null) {
                // Slides in from its edge and fades in, untouchable for a moment (OverlayArrival).
                row.post {
                    val off = foldReach() + dp(16)
                    OverlayArrival.arrive(wm, holder, params, row, 0f) { a -> row.alpha = a }
                    row.animate().cancel()
                    val (ax, ay) = awayDir()
                    row.translationX = ax * off
                    row.translationY = ay * off
                    row.animate().translationX(0f).translationY(0f).setDuration(OverlayArrival.ENTER_MS)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
                }
            } else {
                // Dragged here: from where it was let go into its place, in one move.
                row.post {
                    if (pill !== row) return@post
                    val at = IntArray(2).also { row.getLocationOnScreen(it) }
                    row.translationX = from[0] - (at[0] + row.width / 2f)
                    row.translationY = from[1] - (at[1] + row.height / 2f)
                    row.animate().translationX(0f).translationY(0f).setDuration(250)
                        .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                        .withEndAction { touchBar() }.start()
                }
            }
            // The videos may rest beside the bar ([anchorTop]).
            holder.post { if (pillWindow === holder) anchorTop(context) }
            Log.i(TAG, "shown at ${dock.save()}")
            main.removeCallbacks(pulse)
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
        showChatFace(context)
    }

    /** My press, handed to [chat]'s screen by a tap on Chat [at], until that screen takes it ([handBack]). */
    private class PendingBack(val chat: String, val video: Boolean, val urgent: Boolean, val at: Long)
    private var pendingBack: PendingBack? = null

    /** The chat whose face [chatButton] shows. */
    private var chatFaceOf: String? = null

    /**
     * Chat's button is the other person's face (2026-11-27, hen: "replace the chat icon with the recipient avatar"):
     * their picture, else the first letter of the chat's name on the button's circle (a group's always).
     */
    private fun showChatFace(context: Context) {
        val button = chatButton ?: return
        val chat = chat ?: return
        val key = "$chat|$title"
        if (chatFaceOf == key) return
        chatFaceOf = key
        button.setPadding(0, 0, 0, 0)
        button.scaleType = ImageView.ScaleType.CENTER_CROP
        button.clipToOutline = true
        button.setImageBitmap(letterFace(context, title))
        if (com.chencohen.ptt.messaging.Group.idOf(chat) != null) return
        val app = context.applicationContext as? TalkApp ?: return
        app.scope.launch {
            val id = kotlinx.coroutines.withTimeoutOrNull(1_000) { com.chencohen.ptt.ui.face.Faces.ids(app).first { it.containsKey(chat) } }?.get(chat) ?: return@launch
            val bitmap = runCatching { com.chencohen.ptt.ui.face.Faces.load(app, chat, id) }.getOrNull() ?: return@launch
            main.post { if (chatButton === button && chatFaceOf == key) button.setImageBitmap(bitmap.asAndroidBitmap()) }
        }
    }

    /** The first letter of [name], white on the button's circle. */
    private fun letterFace(context: Context, name: String): android.graphics.Bitmap {
        val size = dp(context, 48)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val letter = name.trim().takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))).uppercase() } ?: "?"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.42f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val canvas = Canvas(bitmap)
        canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
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
                    thaw(box)
                }
            }
        } else if (myRoomTrack != null) {
            // The room let my camera go: my box keeps its last picture while it is still there.
            freezeMine()
            dropMyRoomVideo()
        }
        // My face until my camera's picture really comes through the room (2026-11-25: a black box while waiting for
        // the floor), as theirs has their face until their first picture.
        myFace?.visibility = if (local != null && !com.chencohen.ptt.talk.LiveTalkVideo.localPicture.value && box?.findViewWithTag<View>("frozen") == null) View.VISIBLE else View.GONE
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
                barDrag = false
                lastActive = System.currentTimeMillis()
                // Folded: Push isn't there to press; the bar comes out first.
                if (folded) {
                    unfold()
                    return true
                }
                dragSample(awayAt(e), e.eventTime, first = true)
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
                    downX = e.rawX
                    urgent = false
                    sideVoice = false
                    goingLive = false
                    startTalk(context, video = false)
                    runStarted = talkStarted
                    showLockHint(true)
                }
                v.isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (folded) return true
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                // Toward the screen's inside (up at the bottom, left at the right edge): toward the lock.
                val up = along(dx, dy, inwardDir())
                // Toward Chat's place, in either language (left, or up when upright): toward the side target.
                val side = along(dx, dy, sideDir())
                // Toward the bar's edge: the bar is being swiped, though the finger started on Push. What was said so
                // far goes (a press shorter than a hold is nothing said); the microphone is off from here.
                val down = -up
                if (!barDrag && talking && !locked && !urgent && !sideVoice && down > dp(context, 16) && down > kotlin.math.abs(side)) {
                    barDrag = true
                    v.isPressed = false
                    showLockHint(false)
                    endTalk(context, send = System.currentTimeMillis() - talkStarted >= HOLD_MS)
                }
                if (barDrag) {
                    dragSample(awayAt(e), e.eventTime, first = false)
                    dragBar(down - dp(context, 16))
                    return true
                }
                // Slid left onto the side target. The bell (someone can't hear now): this push is urgent from here.
                // The microphone (everyone hears): it goes on hands-free, voice, once let go.
                if (talking && !locked && !urgent && !sideVoice && side > dp(context, URGENT_DP - 8)) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    if (sideBell) {
                        urgent = true
                        markUrgent(context)
                        showUrgent(context)
                    } else sideVoice = true
                    // Taken: the target swells a little.
                    (if (sideBell) bellHint else micHint)?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(120)?.start()
                }
                // Starting to slide up: the camera opens now, so the video starts fast at the lock.
                if (talking && !locked && !warming && !urgent && !sideVoice && up > side && up > dp(context, 16) && cameraAllowed(context)) {
                    warming = true
                    showMode(context)
                }
                // At the lock: hands-free video until Stop. The voice so far goes as it is; the video burst follows.
                // Not when heading for the side target, or on it already: the same push goes on (2026-11-26, hen).
                if (talking && !locked && !urgent && !sideVoice && up > side && up > dp(context, LOCK_DP)) {
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
                if (folded) return true
                if (barDrag) {
                    barDrag = false
                    dropBar(context, -along(e.rawX - downX, e.rawY - downY, inwardDir()) - dp(context, 16), e.actionMasked == MotionEvent.ACTION_CANCEL)
                    return true
                }
                v.isPressed = false
                showLockHint(false)
                // Let go 2 s after the finger lifts without the slide up: a quick press again reuses it.
                if (warming && !locked) {
                    main.removeCallbacks(coolCamera)
                    main.postDelayed(coolCamera, 2_000)
                }
                // Let go on the side target: hands-free voice until Stop (2026-11-26, hen), urgent when it was the bell.
                if (talking && !locked && (urgent || sideVoice)) {
                    locked = true
                    lockSince = System.currentTimeMillis()
                    showLocked(true)
                    main.removeCallbacks(renew)
                    main.postDelayed(renew, RENEW_MS)
                    refreshTop(context)
                    Log.i(TAG, if (urgent) "urgent lock: hands-free voice until Stop" else "voice lock: hands-free voice until Stop")
                }
                // Held: letting go sends. A short tap (or the lock) keeps talking until the next one.
                else if (talking && !locked && System.currentTimeMillis() - talkStarted >= HOLD_MS) endTalk(context, send = true)
                else if (talking && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            }
        }
        return true
    }

    /** The push button red with a bell while my push is urgent; Pink again after. */
    private fun showUrgent(context: Context) {
        (button?.background as? GradientDrawable)?.setColor(if (urgent) URGENT else PINK)
        if (urgent) button?.setImageResource(R.drawable.ic_bubble_bell)
        refreshTop(context)
    }

    /** The lock on the screen's inside of the push button while it is held (above it at the bottom). */
    private fun showLockHint(on: Boolean) {
        val lock = lockHint ?: return
        val push = pushBox ?: return
        val upright = dock.edge.upright
        if (on) {
            // Level with the push button, wherever it sits in the pill.
            val holder = pillWindow ?: return
            val at = IntArray(2).also { push.getLocationInWindow(it) }
            if (upright) {
                lock.translationX = 0f
                lock.translationY = (at[1] + push.height / 2 - holder.height / 2).toFloat()
            } else {
                lock.translationX = (at[0] + push.width / 2 - holder.width / 2).toFloat()
                lock.translationY = 0f
            }
            lock.alpha = 0f
            lock.visibility = View.VISIBLE
            lock.animate().alpha(1f).setDuration(150).start()
            // The slide target, in Chat's place: the bell only while someone can't hear now. Chat gives way to it in the
            // same 150 ms.
            sideBell = someoneCantHear(push.context)
            val chatX = chatButton?.translationX ?: 0f
            val chatY = chatButton?.translationY ?: 0f
            listOf(micHint to !sideBell, bellHint to sideBell).forEach { (hint, shown) ->
                hint ?: return@forEach
                hint.animate().cancel()
                hint.translationX = chatX
                hint.translationY = chatY
                hint.scaleX = 1f
                hint.scaleY = 1f
                hint.alpha = 0f
                hint.visibility = View.VISIBLE
                if (shown) hint.animate().alpha(1f).setDuration(150).start()
            }
            chatButton?.animate()?.alpha(0f)?.setDuration(150)?.start()
            sideLabel?.let { label ->
                label.setText(if (sideBell) R.string.talk_urgent else R.string.talk_hands_free)
                label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val c = push.context
                if (upright) {
                    // Above the target, centred on the bar.
                    label.translationX = (dp(c, BAR_DP) - label.measuredWidth) / 2f
                    label.translationY = chatY - label.measuredHeight - dp(c, 6)
                } else {
                    // Centred under the target, 9 dp below it (above it when the bar is at the top).
                    label.translationX = chatX + dp(c, 24) - label.measuredWidth / 2f
                    label.translationY = if (dock.edge == DockEdge.TOP) chatY - label.measuredHeight - dp(c, 9)
                    else (dp(c, BAR_DP) + dp(c, 48)) / 2f + dp(c, 9)
                }
                label.alpha = 0f
                label.visibility = View.VISIBLE
                label.animate().alpha(1f).setDuration(150).start()
            }
            main.removeCallbacks(sideWatch)
            main.postDelayed(sideWatch, 300)
        } else if (lock.visibility == View.VISIBLE) {
            lock.visibility = View.INVISIBLE
            listOf(micHint, bellHint, sideLabel).forEach {
                it?.animate()?.cancel()
                it?.visibility = View.INVISIBLE
            }
            chatButton?.animate()?.cancel()
            chatButton?.alpha = pillChat * barShown
            main.removeCallbacks(sideWatch)
        }
    }

    /**
     * Someone in this chat can't hear a push now: their phone says Do Not Disturb, silent or in a call, or this push
     * was saved there (TalkUiState.heard). Offline or unknown doesn't count: nothing urgent would get through.
     */
    private fun someoneCantHear(context: Context): Boolean {
        val chat = chat ?: return false
        val state = messagingState(context).value
        val users = Group.idOf(chat)?.let { id ->
            val me = com.chencohen.ptt.myUser(context)
            state.groups.firstOrNull { it.id == id }?.readers?.map { it.user }?.filter { it != me }
        } ?: listOf(chat)
        val cant = setOf(com.chencohen.ptt.talk.Hear.DND, com.chencohen.ptt.talk.Hear.SILENT, com.chencohen.ptt.talk.Hear.IN_CALL)
        if (users.any { u -> state.presence[u]?.let { com.chencohen.ptt.talk.Hear.of(it.online, it.hear) } in cant }) return true
        val saved = setOf(
            com.chencohen.ptt.talk.HeardStatus.SAVED_DND, com.chencohen.ptt.talk.HeardStatus.SAVED_SILENT,
            com.chencohen.ptt.talk.HeardStatus.SAVED_IN_CALL, com.chencohen.ptt.talk.HeardStatus.SAVED_URGENT_NOT_ALLOWED,
        )
        val mine = state.talk.own?.takeIf { it.chat == chat }?.clip?.let { state.talk.heard[it] }.orEmpty()
        return mine.values.any { it in saved }
    }

    /**
     * While Push is held: the side target follows who can hear, the microphone and the bell changing places in one
     * move (a cross-fade, 220 ms). Once slid onto it, it stays what it was.
     */
    private val sideWatch = object : Runnable {
        override fun run() {
            val lock = lockHint ?: return
            if (lock.visibility != View.VISIBLE || pillWindow == null) return
            val bell = someoneCantHear(lock.context)
            if (bell != sideBell && !urgent && !sideVoice) {
                sideBell = bell
                micHint?.animate()?.alpha(if (bell) 0f else 1f)?.setDuration(220)?.start()
                bellHint?.animate()?.alpha(if (bell) 1f else 0f)?.setDuration(220)?.start()
                sideLabel?.let { label ->
                    val middle = label.translationX + label.width / 2f
                    label.setText(if (bell) R.string.talk_urgent else R.string.talk_hands_free)
                    label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    label.translationX = middle - label.measuredWidth / 2f
                }
            }
            main.postDelayed(this, 300)
        }
    }

    /** Hands-free: the push button's place is "■ Stop · 0:42". */
    private fun showLocked(on: Boolean) {
        stopShown = on
        // Locked on the bell: a red Stop (2026-11-26, hen).
        (stopPill?.background as? GradientDrawable)?.setColor(if (urgent) URGENT else PINK)
        updateStop()
        morphPill()
    }

    private var stopShown = false

    /**
     * The pill's frame for [pillStop] and [pillChat]: as wide as what is in it, about its centre (its width kept
     * even, so the centre is a whole pixel), Chat at its left end, Close at its right, and in the middle the Push
     * button giving way to Stop, which widens out of the button's own circle.
     */
    private fun shapePill() {
        val row = pill ?: return
        val context = row.context
        val upright = dock.edge.upright
        val pad = dp(context, 8)
        val gap = dp(context, 12)
        val side = dp(context, 48)
        val bar = dp(context, BAR_DP)
        val stopLength = if (upright) dp(context, STOP_UPRIGHT_DP) else stopWidth
        val middle = dp(context, 60) + (stopLength - dp(context, 60)) * pillStop
        val chat = (side + gap) * pillChat
        val decline = (gap + side) * pillDecline
        val length = ((pad + chat + middle + decline + pad) / 2).toInt() * 2
        val lp = row.layoutParams
        val (w, h) = if (upright) bar to length else length to bar
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            row.layoutParams = lp
        }
        // [at] along the bar (left to right, or top to bottom upright), centred across it.
        fun put(v: View, at: Float, across: Int) {
            if (upright) {
                v.translationX = (bar - across) / 2f
                v.translationY = at
            } else {
                v.translationX = at
                v.translationY = (bar - across) / 2f
            }
        }
        val centre = pad + chat + middle / 2
        chatButton?.let {
            put(it, pad.toFloat(), side)
            // While Push is held the slide target has its place ([showLockHint]).
            if (lockHint?.visibility != View.VISIBLE) it.alpha = pillChat * barShown
            it.visibility = if (pillChat > 0f) View.VISIBLE else View.INVISIBLE
        }
        // Stop is there in the first third of the move, over the button it grows out of.
        val stop = (pillStop * 3).coerceAtMost(1f)
        pushBox?.let {
            put(it, centre - dp(context, 30), dp(context, 60))
            it.alpha = (1f - stop) * barShown
            it.visibility = if (stop < 1f) View.VISIBLE else View.INVISIBLE
        }
        stopPill?.let {
            put(it, centre - stopLength / 2f, dp(context, 60))
            it.alpha = stop * barShown
            it.visibility = if (pillStop > 0f) View.VISIBLE else View.INVISIBLE
            it.invalidateOutline()
        }
        closeButton?.let {
            put(it, (length - pad - side).toFloat(), side)
            it.alpha = pillDecline * barShown
            it.visibility = if (pillDecline > 0f) View.VISIBLE else View.INVISIBLE
        }
    }

    // ---- Directions: everything about the bar's gestures is counted from its edge, not from the screen's bottom ----

    /** [dx], [dy] along the unit [dir]. */
    private fun along(dx: Float, dy: Float, dir: Pair<Float, Float>) = dx * dir.first + dy * dir.second

    /** Toward the bar's edge (down at the bottom, left at the left edge): folding, and sending it away. */
    private fun awayDir(): Pair<Float, Float> = when (dock.edge) {
        DockEdge.BOTTOM -> 0f to 1f
        DockEdge.TOP -> 0f to -1f
        DockEdge.LEFT -> -1f to 0f
        DockEdge.RIGHT -> 1f to 0f
    }

    /** Toward the screen's inside: the lock (hands-free video) is that way from Push. */
    private fun inwardDir(): Pair<Float, Float> = awayDir().let { (x, y) -> -x to -y }

    /** Along the bar: from Push toward Chat's place (the side target): left, or up when upright. */
    private fun sideDir(): Pair<Float, Float> = if (dock.edge.upright) 0f to -1f else -1f to 0f

    private fun acrossDir(): Pair<Float, Float> = if (dock.edge.upright) 0f to 1f else 1f to 0f

    /** Where a finger is, counted toward the bar's edge (for how fast it goes that way). */
    private fun awayAt(e: MotionEvent): Float = along(e.rawX, e.rawY, awayDir())

    /** How far the bar goes into its edge to fold: all of it but [PEEK_DP] (a tab of [PEEK_SIDE_DP] at the sides). */
    private fun foldReach(): Float {
        val row = pill ?: return 0f
        val holder = pillWindow ?: return 0f
        val context = row.context
        val across = if (dock.edge.upright) row.width else row.height
        val pad = when (dock.edge) {
            DockEdge.BOTTOM -> holder.paddingBottom
            DockEdge.TOP -> holder.paddingTop
            DockEdge.LEFT -> holder.paddingLeft
            DockEdge.RIGHT -> holder.paddingRight
        }
        val peek = dp(context, if (dock.edge.upright) PEEK_SIDE_DP else PEEK_DP)
        return (across + pad - edgeInset - peek).toFloat().coerceAtLeast(0f)
    }

    /**
     * Only the bar takes touches, where it is now (folded: the part that shows, never the system bar it went into);
     * the rest of the window is the app's beneath.
     */
    private fun touchBar() {
        val row = pill ?: return
        val holder = pillWindow ?: return
        val r = android.graphics.Rect(
            (row.left + row.translationX).toInt(), (row.top + row.translationY).toInt(),
            (row.right + row.translationX).toInt(), (row.bottom + row.translationY).toInt(),
        )
        val inside = android.graphics.Rect(0, 0, holder.width, holder.height)
        when (dock.edge) {
            DockEdge.BOTTOM -> inside.bottom -= edgeInset
            DockEdge.TOP -> inside.top += edgeInset
            DockEdge.LEFT -> inside.left += edgeInset
            DockEdge.RIGHT -> inside.right -= edgeInset
        }
        // Folded: the tab or handle takes touches a little further in than it shows, and at a side Android's back
        // gesture leaves it alone there (2026-11-27, hen: "on the edge and minimized it's hard to interact with because
        // of the Android gesture"; "when floating layout on bottom it is hard to drag it or to close it").
        // Open: the grip takes touches a little past the bar's inside edge too ([edgeTouch]).
        if (!folded) {
            val more = dp(row.context, GRIP_OUT_DP)
            when (dock.edge) {
                DockEdge.LEFT -> r.right += more
                DockEdge.RIGHT -> r.left -= more
                DockEdge.BOTTOM -> r.top -= more
                DockEdge.TOP -> r.bottom += more
            }
        }
        if (folded) {
            val more = dp(row.context, PEEK_TOUCH_DP)
            when (dock.edge) {
                DockEdge.LEFT -> r.right += more
                DockEdge.RIGHT -> r.left -= more
                DockEdge.BOTTOM -> r.top -= more
                DockEdge.TOP -> r.bottom += more
            }
        }
        if (!r.intersect(inside) || sheetOpen) r.setEmpty()
        holder.rootSurfaceControl?.setTouchableRegion(android.graphics.Region(r))
        holder.systemGestureExclusionRects = if (dock.edge.upright && !r.isEmpty) listOf(android.graphics.Rect(r)) else emptyList()
    }

    /** Someone is talking in the bar's chat, or I am, or a call rings, or the bar is in my hand. */
    private fun busy(): Boolean = talking || locked || stopShown || burstLive || ringing != null || moving || barDrag ||
        outside?.let { it.theirs != LiveKind.None || it.mine != LiveKind.None } == true ||
        groupCells.values.any { it.cell.on }

    private fun liveVideo(): Boolean = burstVideo || sendingVideo || goingLive || outside?.let { it.theirs == LiveKind.Video || it.mine == LiveKind.Video } == true

    /**
     * Folds the bar into its edge (2026-11-27, hen: "auto hide in a smart way"): it slides in until only a slim handle
     * (a tab at the sides) shows, its buttons fading in the same move, 250 ms. A tap or a swipe in brings it back.
     */
    private fun fold() {
        if (folded || pill == null) return
        folded = true
        foldTo(1f)
        Log.i(TAG, "folded into the ${dock.edge.name.lowercase()} edge")
    }

    /** Back out of its edge, the same move the other way. */
    private fun unfold() {
        if (!folded) return
        folded = false
        lastActive = System.currentTimeMillis()
        foldTo(0f)
        Log.i(TAG, "out of its edge")
    }

    private fun foldTo(to: Float) {
        val row = pill ?: return
        val reach = foldReach()
        val (ax, ay) = awayDir()
        foldMove?.cancel()
        row.animate().cancel()
        val fromX = row.translationX
        val fromY = row.translationY
        val fromShown = barShown
        val fromAlpha = row.alpha
        val toShown = 1f - to
        foldMove = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                row.translationX = fromX + (ax * reach * to - fromX) * f
                row.translationY = fromY + (ay * reach * to - fromY) * f
                barShown = fromShown + (toShown - fromShown) * f
                row.alpha = fromAlpha + (1f - fromAlpha) * f
                shapePill()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (pill === row) touchBar()
                }
            })
            start()
        }
    }

    /**
     * The bar can be lifted to move: not while Push is held (the finger is on it) or a call rings, but while I talk
     * hands-free, yes (2026-11-27, hen: "when transmitting, I can't drag the bar").
     */
    private fun canLift(): Boolean = (!talking || locked) && ringing == null && !folded && pill != null

    /** Held on the bar: it lifts, and follows the finger to another edge ([moveBar], [dropMove]). */
    private val longPress = Runnable { liftBar() }

    private fun liftBar() {
        val row = pill ?: return
        val holder = pillWindow ?: return
        val params = pillParams ?: return
        if (!canLift() || moving) return
        moving = true
        lastActive = System.currentTimeMillis()
        row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        // The window put by its top left where it is now, so it can follow the finger anywhere.
        val at = IntArray(2).also { holder.getLocationOnScreen(it) }
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = at[0]
        params.y = at[1]
        moveFrom = at
        runCatching { holder.context.getSystemService(WindowManager::class.java).updateViewLayout(holder, params) }
        row.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
        guide = DockGuide.show(holder.context)
        guide?.mark(barCentre()?.let { (x, y) -> FloatDock.nearest(x, y, floatScreen(holder.context)) })
        Log.i(TAG, "lifted to move")
    }

    private fun barCentre(): Pair<Float, Float>? {
        val row = pill ?: return null
        val at = IntArray(2).also { row.getLocationOnScreen(it) }
        return (at[0] + row.width / 2f) to (at[1] + row.height / 2f)
    }

    private fun moveBar(dx: Float, dy: Float) {
        val holder = pillWindow ?: return
        val params = pillParams ?: return
        params.x = moveFrom[0] + dx.toInt()
        params.y = moveFrom[1] + dy.toInt()
        runCatching { holder.context.getSystemService(WindowManager::class.java).updateViewLayout(holder, params) }
        val (x, y) = (pill ?: return).let { row ->
            (params.x + row.left + row.width / 2f) to (params.y + row.top + row.height / 2f)
        }
        guide?.mark(FloatDock.nearest(x, y, floatScreen(holder.context)))
    }

    /** Let go: the bar goes to the nearest edge ([FloatDock.nearest]) and stays there next time too. */
    private fun dropMove(context: Context, cancelled: Boolean) {
        moving = false
        guide?.remove()
        guide = null
        val row = pill ?: return
        val params = pillParams ?: return
        val centre = floatArrayOf(params.x + row.left + row.width / 2f, params.y + row.top + row.height / 2f)
        val to = if (cancelled) dock else FloatDock.nearest(centre[0], centre[1], floatScreen(context))
        dock = to
        savePlaces(context)
        redock(context, centre)
    }

    /** The bar's window again at [dock] (upright or not), gliding from [from] (its centre on the screen) into place. */
    private fun redock(context: Context, from: FloatArray) {
        val old = pillWindow ?: return
        main.removeCallbacks(pulse)
        main.removeCallbacks(longPress)
        pillMorph?.cancel()
        foldMove?.cancel()
        pillWindow = null
        pill = null
        runCatching { context.getSystemService(WindowManager::class.java).removeView(old) }
        if (!addPill(context.applicationContext, from)) remove()
        // Moved while talking hands-free: Stop again as it was.
        else if (stopShown) morphPill()
    }

    /**
     * The floating chat is open: the bar is out of sight and takes no touches until it closes (2026-11-27, hen: "when
     * using the floating chat, don't show the bar"). Its window stays, at no opacity, so nothing it holds is lost.
     */
    @Volatile private var sheetOpen = false

    fun sheetOpen(open: Boolean) = main.post {
        if (sheetOpen == open) return@post
        sheetOpen = open
        applySheet()
    }

    private fun applySheet() {
        val holder = pillWindow ?: return
        val params = pillParams ?: return
        val alpha = if (sheetOpen) 0f else 1f
        if (params.alpha != alpha) {
            params.alpha = alpha
            runCatching { holder.context.getSystemService(WindowManager::class.java).updateViewLayout(holder, params) }
        }
        touchBar()
    }

    private var edgeX = 0f
    private var edgeY = 0f
    private var edgeFar = false

    /**
     * A finger on the bar's window beside the bar itself: in the grip's room past its inside edge, or past a folded
     * tab or handle ([touchBar] lets those in). A move lifts the bar (folded: a swipe in brings it out); a tap brings a
     * folded bar out.
     */
    private fun edgeTouch(context: Context, e: MotionEvent): Boolean {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeX = e.rawX
                edgeY = e.rawY
                edgeFar = false
                lastActive = System.currentTimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - edgeX
                val dy = e.rawY - edgeY
                if (moving) {
                    moveBar(dx, dy)
                    return true
                }
                if (kotlin.math.hypot(dx, dy) <= slop) return true
                edgeFar = true
                if (folded) {
                    if (-along(dx, dy, awayDir()) > slop) unfold()
                } else if (canLift()) {
                    liftBar()
                    edgeX = e.rawX
                    edgeY = e.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                when {
                    moving -> dropMove(context, cancelled)
                    folded && !edgeFar && !cancelled -> unfold()
                }
            }
        }
        return true
    }

    /** The screen as the bar and the videos see it. */
    /**
     * The navigation bar and Android's own gesture area together: at the bottom, a touch that starts in the home and
     * back swipe's area goes to the system, not the bar (2026-11-27, hen: "when floating layout on bottom it is hard to
     * drag it or to close it"), so the bar rests above both.
     */
    private fun navGestureInsets(insets: WindowInsets): android.graphics.Insets = android.graphics.Insets.max(
        insets.getInsets(WindowInsets.Type.navigationBars()),
        android.graphics.Insets.of(0, 0, 0, insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom),
    )

    private fun floatScreen(context: Context): FloatScreen {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        val insets = wm.currentWindowMetrics.windowInsets
        val status = maxOf(insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top, statusBarHeight(context))
        val nav = navGestureInsets(insets).bottom
        return FloatScreen(bounds.width().toFloat(), bounds.height().toFloat(), (status + dp(context, 2)).toFloat(), nav.toFloat(), dp(context, 12).toFloat())
    }

    /** Where the bar and the videos rest, as last left (kept on the phone). */
    private fun readPlaces(context: Context) {
        if (placesRead) return
        placesRead = true
        val prefs = context.getSharedPreferences(PLACES, Context.MODE_PRIVATE)
        dock = Dock.parse(prefs.getString("dock", null))
        videoPlace = prefs.getString("videos", null)?.split(':')?.let { p ->
            runCatching { VideoPlace(Lean.valueOf(p[0]), Height.valueOf(p[1]), p[2].toFloat()) }.getOrNull()
        } ?: VideoPlace.DEFAULT
    }

    private fun savePlaces(context: Context) {
        context.getSharedPreferences(PLACES, Context.MODE_PRIVATE).edit()
            .putString("dock", dock.save())
            .putString("videos", "${videoPlace.lean.name}:${videoPlace.height.name}:${videoPlace.top}")
            .apply()
    }

    /** The bar's resting frame on the screen with the room its lock takes beside it (null: no bar). */
    private fun barRest(): ScreenRect? {
        val holder = pillWindow?.takeIf { it.isLaidOut } ?: return null
        val row = pill ?: return null
        if (moving) return null
        val at = IntArray(2).also { holder.getLocationOnScreen(it) }
        var l = (at[0] + row.left).toFloat()
        var t = (at[1] + row.top).toFloat()
        var r = l + row.width
        var b = t + row.height
        val room = dp(holder.context, HINTS_DP + 8).toFloat()
        when (dock.edge) {
            DockEdge.BOTTOM -> t -= room
            DockEdge.TOP -> b += room
            DockEdge.LEFT -> r += room
            DockEdge.RIGHT -> l -= room
        }
        return ScreenRect(l, t, r, b)
    }

    /**
     * The videos to where they rest (2026-11-27, hen: "make the videos stay at the top in original position, not
     * attached to the bar unless they are at the same place"): [videoPlace], or beside the bar when that is the same
     * place ([FloatDock.videosAt]). The row's window moves (its items keep their own places and moves in it), in 250 ms.
     */
    private fun anchorTop(context: Context, animate: Boolean = true) {
        val window = topWindow?.takeIf { it.isLaidOut } ?: return
        val params = topParams ?: return
        if (topHiding || topTouch?.dragging == true) return
        val goals = topMotion.goals.filter { !topMotion.leaving(it.key) }.values
        if (goals.isEmpty()) return
        val minX = goals.minOf { it.x }
        val maxX = goals.maxOf { it.x + it.w }
        val minY = goals.minOf { it.y }
        var maxY = goals.maxOf { it.bottom }
        bothChip?.takeIf { it.visibility == View.VISIBLE }?.let { maxY += dp(context, 8) + it.height }
        val at = windowAt()
        val left0 = at[0] + minX
        val top0 = at[1] + restTop + minY
        val (x, y) = FloatDock.videosAt(videoPlace, maxX - minX, maxY - minY, floatScreen(context), pillWindow?.let { dock }, barRest(), dp(context, 8).toFloat())
        val toX = (x - left0).toInt()
        val toY = (y - top0).toInt()
        if (toX == params.x && toY == params.y && topMove?.isRunning != true) return
        topMove?.cancel()
        val wm = context.getSystemService(WindowManager::class.java)
        if (!animate) {
            params.x = toX
            params.y = toY
            layoutTop()
            return
        }
        val fromX = params.x
        val fromY = params.y
        topMove = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                if (topWindow !== window) return@addUpdateListener
                val f = it.animatedValue as Float
                params.x = (fromX + (toX - fromX) * f).toInt()
                params.y = (fromY + (toY - fromY) * f).toInt()
                layoutTop()
            }
            start()
        }
    }

    /**
     * The screen edge the videos rest at, which they come in and go out through (2026-11-27, hen: "if the video is
     * docked to any edge it should exit and enter from that edge"): the bottom when they rest there, a side when
     * dropped against it partway down, else the top (null: the row's own way, up through the top).
     */
    private fun awayEdge(context: Context, window: IntArray): ((com.chencohen.ptt.ui.live.LiveRect) -> com.chencohen.ptt.ui.live.LiveRect)? {
        val params = topParams ?: return null
        val screen = floatScreen(context)
        val left = (window[0] + params.x).toFloat()
        val top = (window[1] + params.y + restTop).toFloat()
        val pad = dp(context, 16).toFloat()
        return when {
            videoPlace.height == Height.BOTTOM -> { r -> r.copy(y = screen.h - top + pad) }
            videoPlace.height == Height.FREE && videoPlace.lean == Lean.LEFT -> { r -> r.copy(x = -left - r.w - pad) }
            videoPlace.height == Height.FREE && videoPlace.lean == Lean.RIGHT -> { r -> r.copy(x = screen.w - left + pad) }
            else -> null
        }
    }

    /** The videos were let go here ([at]: their frame on the screen): they rest there from now on. */
    private fun droppedVideos(context: Context, at: ScreenRect) {
        videoPlace = FloatDock.dropVideos(at, floatScreen(context))
        savePlaces(context)
        Log.i(TAG, "videos rest at ${videoPlace.lean.name.lowercase()}, ${videoPlace.height.name.lowercase()}")
        anchorTop(context)
    }

    /** The videos' frame on the screen now (with what a drag added). */
    private fun videosNow(): ScreenRect? {
        val params = topParams ?: return null
        val goals = topMotion.goals.filter { !topMotion.leaving(it.key) }.values
        if (goals.isEmpty()) return null
        val at = windowAt()
        val l = at[0] + params.x + goals.minOf { it.x }
        val t = at[1] + params.y + restTop + goals.minOf { it.y }
        return ScreenRect(l, t, at[0] + params.x + goals.maxOf { it.x + it.w }, at[1] + params.y + restTop + goals.maxOf { it.bottom })
    }

    /**
     * The chat's hands-free push goes on here (2026-11-27, hen: "when I'm transmitting hands-free from the chat screen
     * and exit to the home screen it should keep transmitting with the floating layout"): the same press, taken over as
     * it is, locked as if slid to hands-free here ([video]: my camera, kept open, through Talk's foreground service;
     * [urgent]: the bell's). Between two presses (or when the service can't take it), the chat's is let go and a new
     * one pressed here a moment later. False when the floating window may not show (the setting, the permission, the
     * screen off or locked): then the push ends with the chat, as before.
     */
    fun takeOver(context: Context, chat: String, title: String, video: Boolean, urgent: Boolean): Boolean {
        val app = context.applicationContext
        if (!allowed(app)) return false
        main.post {
            if (pillWindow == null && !addPill(app)) return@post
            if (this.chat != chat) setHere(app, chat)
            this.chat = chat
            this.title = title
            if (talking) endTalk(app, send = true)
            takingOverRow()
            // Mine shows from now, in its place, through the moment between the chat's burst and this one: one item
            // that stays, not one going up and coming back (2026-11-27, hen).
            this.urgent = urgent
            sideVoice = !video && !urgent
            locked = true
            lockSince = System.currentTimeMillis()
            showLocked(true)
            // My press still going on there: it goes on here as it is, the same burst, the camera and the room's
            // track never let go (2026-11-27, hen: "the video should stay as a floating window ... without remounting
            // the camera view"). The renewal comes when its own 55 s are up.
            val own = messagingState(app).value.talk.own?.takeIf {
                it.chat == chat && (it.phase == com.chencohen.ptt.talk.TalkPhase.TALKING || it.phase == com.chencohen.ptt.talk.TalkPhase.REQUESTING)
            }
            if (own != null && adopt(app, chat, video, own.startedAt)) {
                if (urgent) showUrgent(app)
                main.removeCallbacks(renew)
                main.postDelayed(renew, (RENEW_MS - (System.currentTimeMillis() - own.startedAt)).coerceIn(1_000L, RENEW_MS))
                refreshTop(app)
                Log.i(TAG, "hands-free ${if (video) "video" else "voice"} taken over from the chat, the same burst")
                return@post
            }
            refreshTop(app)
            // Not taken as it is: the chat's press (if any) is let go, and a new one is pressed here a moment later.
            if (own != null) (app as? TalkApp)?.scope?.launch { com.chencohen.ptt.talkBack(app, null, down = false, send = true) }
            main.postDelayed({
                if (pillWindow == null || talking || this.chat != chat || !locked) return@postDelayed
                startTalk(app, video = video)
                runStarted = talkStarted
                if (urgent) {
                    showUrgent(app)
                    main.postDelayed({ if (talking) markUrgent(app) }, 600)
                }
                main.removeCallbacks(renew)
                main.postDelayed(renew, RENEW_MS)
                refreshTop(app)
                Log.i(TAG, "hands-free ${if (video) "video" else "voice"} taken over from the chat")
            }, 900)
        }
        return true
    }

    /**
     * Until then, what comes into the top row is simply there in its place, not dropped in from the screen's edge: the
     * chat's live row became this one as the chat or the app went (2026-11-27, hen: "when moving out to home screen
     * the videos or voices should stay put, not exit and enter").
     */
    @Volatile private var inPlaceUntil = 0L

    /** The chat or the app is going off the screen now, with what is live in it: the row takes it over in place. */
    fun takingOverRow() {
        inPlaceUntil = System.currentTimeMillis() + IN_PLACE_MS
    }

    /** [chat] came on screen in the app: its own buttons are there, so the floating bar for it goes. */
    fun chatOpened(chat: String) = main.post { if (this.chat == chat && !talking && !locked) dismissNow() }

    /**
     * Where an item handed over by the chat starts in the top row: where it was in the chat's row, in this window's
     * frames (2026-11-27, hen: "when leaving, the video should move to the new position without leaving the screen").
     */
    private fun fromChat(window: IntArray): ((String, LiveRect) -> LiveRect?)? {
        if (System.currentTimeMillis() >= inPlaceUntil) return null
        val params = topParams ?: return null
        val rows = com.chencohen.ptt.ui.live.LiveHandoff.chat.takeIf { it.isNotEmpty() } ?: return null
        val left = (window[0] + params.x).toFloat()
        val top = (window[1] + params.y + restTop).toFloat()
        return { key, t -> rows[key]?.let { s -> LiveRect(s.left - left, s.top - top, s.w, s.h, t.video) } }
    }

    /** The top row's items where they are now on the screen. */
    private fun topOnScreen(): Map<String, ScreenRect> {
        val params = topParams ?: return emptyMap()
        val at = windowAt()
        val left = (at[0] + params.x).toFloat()
        val top = (at[1] + params.y + restTop).toFloat()
        return topMotion.rects.filterKeys { !topMotion.leaving(it) }.mapValues { (_, r) -> ScreenRect(left + r.x, top + r.y, left + r.x + r.w, top + r.y + r.h) }
    }

    /**
     * [chat]'s screen came to the front (2026-11-27, hen: "if I have the floating layout and enter the same user's chat
     * screen, the floating layout should close"; "the video should move to the in-chat position, at the moment I see
     * two camera videos"): the floating window goes at once, its items handed to the chat's row from where they are
     * ([LiveHandoff]). My press held here goes back to the chat as it is: returned as (video, urgent), null when none.
     * Main thread.
     */
    fun handBack(chat: String): Pair<Boolean, Boolean>? {
        // Chat tapped here while my press went on: it is the chat's now.
        pendingBack?.takeIf { it.chat == chat }?.let { back ->
            pendingBack = null
            if (System.currentTimeMillis() - back.at < PENDING_BACK_MS) return back.video to back.urgent
        }
        if (this.chat != chat || pillWindow == null) return null
        if (QuickReplyWindow.showing(chat)) return null
        com.chencohen.ptt.ui.live.LiveHandoff.fromFloating(topOnScreen(), System.currentTimeMillis())
        val context = pillWindow?.context
        val mine = if (talking && context != null) (sendingVideo to urgent).also { letGoOfPress(context) } else null
        if (outside == null) removeTop()
        dismissNow()
        Log.i(TAG, "back in the chat: the floating window goes" + if (mine != null) ", my press goes on there" else "")
        return mine
    }

    /**
     * While the bar is lifted: the edges it can go to, dashed, the one it would go to now filled (an overlay that
     * takes no touches).
     */
    private class DockGuide(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private var nearest: Dock? = null
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = TEAL
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun mark(dock: Dock?) {
            if (dock == nearest) return
            nearest = dock
            invalidate()
        }

        fun remove() {
            runCatching { context.getSystemService(WindowManager::class.java).removeView(this) }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val d = density
            val r = 24 * d
            val near = nearest
            fun zone(edge: DockEdge, rect: RectF) {
                val on = near?.edge == edge
                fill.color = if (on) 0x552BB3A3 else 0x1A2BB3A3
                canvas.drawRoundRect(rect, r, r, fill)
                canvas.drawRoundRect(rect, r, r, line)
            }
            val inset = rootWindowInsets
            val top = (inset?.getInsets(WindowInsets.Type.statusBars())?.top ?: (24 * d).toInt()) + 8 * d
            val bottom = h - (inset?.let { navGestureInsets(it).bottom } ?: 0) - 8 * d
            zone(DockEdge.TOP, RectF(w * 0.15f, top, w * 0.85f, top + 92 * d))
            zone(DockEdge.BOTTOM, RectF(w * 0.15f, bottom - 92 * d, w * 0.85f, bottom))
            zone(DockEdge.LEFT, RectF(8 * d, h * 0.14f, 8 * d + 92 * d, h * 0.86f))
            zone(DockEdge.RIGHT, RectF(w - 8 * d - 92 * d, h * 0.14f, w - 8 * d, h * 0.86f))
            // Where on the side it would rest.
            if (near != null && near.edge.upright) {
                val cy = h * near.along
                val x0 = if (near.edge == DockEdge.LEFT) 8 * d else w - 8 * d - 92 * d
                fill.color = 0x992BB3A3.toInt()
                canvas.drawRoundRect(RectF(x0 + 8 * d, cy - 60 * d, x0 + 84 * d, cy + 60 * d), r, r, fill)
            }
        }

        companion object {
            fun show(context: Context): DockGuide? {
                val view = DockGuide(context)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    fitInsetsTypes = 0
                    title = "Talk dock guide"
                }
                return runCatching { context.getSystemService(WindowManager::class.java).addView(view, params); view }
                    .onFailure { Log.w(TAG, "no dock guide", it) }.getOrNull()
            }
        }
    }

    /** Where the swipe is now ([y] toward the bar's edge, at [at]) and how fast it moves; [first]: it starts here. */
    private fun dragSample(y: Float, at: Long, first: Boolean) {
        if (!first && at > dragLastAt) dragSpeed = (y - dragLastY) / (at - dragLastAt)
        if (first) dragSpeed = 0f
        dragLastY = y
        dragLastAt = at
    }

    /** The bar follows a finger [d] pixels toward its edge, fading a little. */
    private fun dragBar(d: Float) {
        val row = pill ?: return
        val far = d.coerceAtLeast(0f)
        val across = if (dock.edge.upright) row.width else row.height
        val (ax, ay) = awayDir()
        row.animate().cancel()
        foldMove?.cancel()
        row.translationX = ax * far
        row.translationY = ay * far
        row.alpha = 1f - 0.45f * (far / across).coerceIn(0f, 1f)
    }

    /**
     * The finger let go [d] pixels toward the bar's edge. Flung (2026-11-27, hen: "how to hide it completely?"): the
     * bar is sent away ([swipedAway]), nothing left behind. Pushed past a third of its depth, slowly: it folds into its
     * edge ([fold]). Else (or [cancelled]) it settles back, 200 ms.
     */
    private fun dropBar(context: Context, d: Float, cancelled: Boolean) {
        val row = pill ?: return
        val across = if (dock.edge.upright) row.width else row.height
        val flung = dragSpeed > 0.8f * context.resources.displayMetrics.density && d > dp(context, 8)
        if (!cancelled && flung) {
            swipedAway(context)
            return
        }
        row.animate().alpha(1f).setDuration(200).start()
        if (!cancelled && d > across / 3f) {
            folded = false
            fold()
            return
        }
        val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        row.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(200).setInterpolator(ease).withEndAction { touchBar() }.start()
    }

    /**
     * Sent away: the bar goes on off the screen through its edge and the live row up, both fading, one move of 200 ms;
     * what was being said is sent first. It comes back when someone talks again. From here that chat's texts are
     * notifications until it goes quiet (QuickReplyQuiet).
     */
    private fun swipedAway(context: Context) {
        val row = pill ?: return remove()
        if (talking) endTalk(context, send = true)
        chat?.let { QuickReplyWindow.dismissed(it) }
        val ease = android.view.animation.AccelerateInterpolator()
        val (ax, ay) = awayDir()
        val off = foldReach() + dp(context, if (dock.edge.upright) PEEK_SIDE_DP else PEEK_DP) + edgeInset + dp(context, 16)
        row.animate().translationX(ax * off).translationY(ay * off).alpha(0f).setDuration(200).setInterpolator(ease)
            .withEndAction { if (pill === row) remove() }.start()
        if (outside == null) topCard?.let { card ->
            val up = restTop + (topMotion.rects.values.maxOfOrNull { it.bottom } ?: 0f) + dp(context, 12) + (topParams?.y ?: 0)
            card.animate().translationY(-up).alpha(0f).setDuration(200).setInterpolator(ease).start()
        }
        Log.i(TAG, "swiped away")
    }

    /** The pill to what it should be now (Push or Stop, with or without Chat), in one eased move. */
    private fun morphPill() {
        val toStop = if (stopShown) 1f else 0f
        // A call rings: Answer (the push button) and Decline, no Chat.
        val toChat = if (ringing == null) 1f else 0f
        val toDecline = 1f - toChat
        pillMorph?.cancel()
        pillMorph = null
        if (pill == null || (pillStop == toStop && pillChat == toChat && pillDecline == toDecline)) return
        val fromStop = pillStop
        val fromChat = pillChat
        val fromDecline = pillDecline
        pillMorph = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                pillStop = fromStop + (toStop - fromStop) * f
                pillChat = fromChat + (toChat - fromChat) * f
                pillDecline = fromDecline + (toDecline - fromDecline) * f
                shapePill()
            }
            start()
        }
    }

    /** Test builds only (TestBubble): the pill as if locked hands-free ([on]) or not, nothing sent. */
    internal fun pretendLocked(on: Boolean) = main.post {
        if (pill == null) return@post
        lockSince = System.currentTimeMillis()
        showLocked(on)
    }

    private fun updateStop() {
        val stop = stopPill ?: return
        if (!stopShown) return
        val s = ((System.currentTimeMillis() - lockSince) / 1000).coerceAtLeast(0)
        val upright = dock.edge.upright
        val words = when {
            // Upright on a side edge: the icon over the time.
            upright -> (if (urgent) "🔔" else "■") + "\n%d:%02d".format(s / 60, s % 60)
            else -> (if (urgent) "🔔  " else "■  ") + stop.context.getString(R.string.talk_stop) + " · %d:%02d".format(s / 60, s % 60)
        }
        if (stop.text.toString() != words) stop.text = words
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
                    // A lock from the side target renews as voice (urgent when it was the bell); the slide-up's as video.
                    startTalk(context, video = !urgent && !sideVoice)
                    if (urgent) main.postDelayed({ if (talking) markUrgent(context) }, 600)
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
        // One run with the burst just before it (voice, then video at the slide up): the time keeps counting.
        if (talkStarted == 0L || System.currentTimeMillis() - talkEndedAt > RUN_GAP_MS) talkStarted = System.currentTimeMillis()
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

    /**
     * Takes over my press already going on in [chat] (begun on the chat's screen [since]): talking here from now, the
     * service keeping the microphone (and the camera, [video]) in the background; nothing is pressed anew. False when
     * the service can't: the caller presses anew instead.
     */
    private fun adopt(context: Context, chat: String, video: Boolean, since: Long): Boolean {
        val wantVideo = video && cameraAllowed(context)
        val ok = runCatching {
            context.startForegroundService(
                Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_TALK_BACK).putExtra(TalkReadyService.EXTRA_CHAT, chat)
                    .putExtra(TalkReadyService.EXTRA_VIDEO, wantVideo).putExtra(TalkReadyService.EXTRA_ADOPT, true),
            )
        }.onFailure { Log.w(TAG, "can't take the chat's push over", it) }.isSuccess
        if (!ok) return false
        sendingVideo = wantVideo
        goingLive = false
        talking = true
        talkStarted = since
        runStarted = since
        (button?.background as? GradientDrawable)?.apply { setColor(PINK); setStroke(dp(context, 4), 0x80FFB3A8.toInt()) }
        button?.setImageResource(R.drawable.ic_bubble_send)
        button?.contentDescription = context.getString(R.string.bubble_send)
        showMode(context)
        return true
    }

    /** My press goes on elsewhere (full screen): this window stops holding it, without letting it go. */
    private fun letGoOfPress(context: Context) {
        main.removeCallbacks(renew)
        talking = false
        locked = false
        sendingVideo = false
        goingLive = false
        talkStarted = 0L
        runStarted = 0L
        runCatching { context.startService(Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_HAND_BACK)) }
    }

    private fun endTalk(context: Context, send: Boolean, keepLock: Boolean = false) {
        if (!talking) return
        // Stop: sending ends at once, and my box goes up showing my camera live: the room's track keeps reading it for
        // its linger, after the box is gone (2026-11-25, hen). With no room track, its last picture instead.
        if (sendingVideo && !keepLock) freezeMineUnlessLive()
        talking = false
        // Kept only for the lock's video right after; a real end starts the next press anew.
        talkEndedAt = if (keepLock) System.currentTimeMillis() else 0L
        if (!keepLock) talkStarted = 0L
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
        if (urgent && !keepLock) urgent = false
        if (sideVoice && !keepLock) sideVoice = false
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
    private fun rowChat(): String? = chat ?: outsideChat ?: pretendChat

    /** The row is a group chat's: everyone live in it shows (several at once), placed by LiveGroupLayout. */
    private fun groupRow(): Boolean = rowChat()?.let { Group.idOf(it) != null } == true

    /** A member live in the row's group chat: [key] their "user.device", on video or voice, since when. */
    private data class GroupMember(val key: String, val user: String, val video: Boolean, val since: Long)

    /** Everyone live in the row's group chat now but me (Talk's talking; the test builds' pretended members too). */
    private fun groupMembers(context: Context): List<GroupMember> {
        val chat = rowChat()?.takeIf { Group.idOf(it) != null } ?: return emptyList()
        val state = messagingState(context).value
        val arriving = com.chencohen.ptt.talk.LiveTalkVideo.arriving.value
        val own = state.talk.own?.clip
        val real = state.talk.talking[chat].orEmpty().filter { it.clip != own }.map { t ->
            val id = "${t.user}.${t.device}"
            GroupMember(id, t.user, arriving[id] != null || state.conversations[chat]?.firstOrNull { it.id == t.clip }?.clip?.video == true, t.since)
        }
        val pretend = if (pretendChat == chat) LivePretend.members.value.map { GroupMember("${it.user}.1", it.user, it.video, it.since) } else emptyList()
        return real + pretend
    }
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
        // A group's row shows every member live as their own cell (groupCells), not the window's one burst.
        val group = groupRow()
        val members = if (group && !topPutAway) groupMembers(context) else emptyList()
        val theirOn = !group && (burstLive || (out != null && out.theirs != LiveKind.None)) && !topPutAway
        val theirsVideo = theirOn && theirVideoWanted() && (theirPictureReady(context) || !theirWasPill)
        // Mine stays a box from the lock to Stop, through the moment between a hands-free floor and its renewal.
        // Nothing waits for a floor (2026-11-27, hen: "remove this logic"; before: "only when the UI
        // says it's transmitting, then transmit"): no pill or box of mine until its turn comes.
        // A lock from the side target is voice: my pill, staying through its renewals, never a box.
        val voiceLock = locked && (urgent || sideVoice)
        val mine = (sendingVideo || goingLive || (locked && !voiceLock)) || out?.mine == LiveKind.Video
        val myVoice = !mine && !topPutAway && ((talking || voiceLock) || out?.mine == LiveKind.Voice)
        main.removeCallbacks(hideSoon)
        val groupOn = members.isNotEmpty() || groupCells.values.any { it.cell.on }
        if (!theirOn && !mine && !myVoice && !groupOn) {
            // Nothing live: what shows goes up at once (2026-11-25, hen: "it should stop transmitting, slide up
            // immediately and then close the camera"); only the emptied window waits a moment before it goes, so the
            // next push doesn't have to bring it back.
            main.postDelayed(hideSoon, 900)
            if (topWindow == null) return
        } else if (!addTop(context)) return
        topHiding = false
        // Their box that goes: its last picture fades to black while it slides up, in the same move (2026-11-25, hen);
        // one that comes back shows anew.
        theirCell?.let { cell ->
            if (cell.on && cell.video && !theirOn) {
                freeze(theirBox, roomRenderer ?: theirBox?.findViewWithTag<View>("video") as? android.view.TextureView, theirTag)
                fadeToBlack(theirBox, theirTag)
            }
            if (!cell.on && theirOn) thaw(theirBox)
            cell.on = theirOn
            cell.video = theirsVideo
        }
        myCell?.let { cell ->
            if (cell.on && cell.video && !(mine || myVoice)) freezeMineUnlessLive()
            if (!cell.on && (mine || myVoice)) thaw(myBox)
            cell.on = mine || myVoice
            cell.video = mine
        }
        theirPill?.show(context, if (theirOn) rowFrom() else null, rowTitle())
        myPill?.show(context, if (mine || myVoice) "" else null, rowTitle())
        // My push urgent: "You · Urgent" in red (2026-11-25, hen).
        val urgentNow = urgent || outside?.myUrgent == true
        myPill?.urgent = urgentNow
        myCell?.edgeColor(if (urgentNow) URGENT else PINK)
        // Their words as they are now, before the pills are measured: a pill comes in at its real width.
        theirPill?.tick(0f, theirSince())
        myPill?.tick(0f, mySince())
        syncGroup(context, if (group) members else emptyList())
        // Full screen while both are live or a video shows.
        bothChip?.visibility = if (bothLive(context) || theirCell?.let { it.on && it.video } == true || myCell?.let { it.on && it.video } == true) View.VISIBLE else View.GONE
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
        // Only while both sides' items show (2026-11-25, hen: "it says that we both are transmitting and I see only
        // myself": the other was only waiting for the floor).
        if (theirCell?.on != true || myCell?.on != true) return false
        // Both items show, so both are live (one to one, both can talk at once since 2026-11-25): voice or video,
        // the room can become the call.
        return chat != null
    }

    /** Where the window's left and top edges are on the screen, without what a drag added. */
    private fun windowAt(): IntArray {
        val window = topWindow?.takeIf { it.isAttachedToWindow } ?: return intArrayOf(0, 0)
        val at = IntArray(2).also { window.getLocationOnScreen(it) }
        val params = topParams ?: return at
        val crop = topCrop
        return intArrayOf(at[0] - params.x - (crop?.left ?: 0), at[1] - params.y - (crop?.top ?: 0))
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
        if (groupRow() || groupCells.values.any { it.cell.on }) return placeGroup(context, mine, screen, window, gap)
        theirs.slides = false
        mine.slides = false
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
        val moved = topMotion.target(targets, (restTop + window[1] + dp(context, 12)).toFloat(), dp(context, 16).toFloat(), inPlace = System.currentTimeMillis() < inPlaceUntil, away = awayEdge(context, window), from = fromChat(window))
        topRoom.intValue = if (targets.isEmpty()) 0 else window[1] + restTop + targets.values.maxOf { it.bottom }.toInt() + dp(context, 8)
        if (moved) Log.i(TAG, "row: " + targets.entries.joinToString { (k, r) -> "$k ${if (r.video > 0f) "video" else "voice"} x=${r.x.toInt()} w=${r.w.toInt()} h=${r.h.toInt()}" } + " (screen ${screen.toInt()}, window at ${window[0]},${window[1]}, ${if (topRtl) "right to left" else "left to right"})")
        showTop()
        touchTop()
        if (topMotion.running) frames()
        anchorTop(context)
    }

    /**
     * A group's row (2026-11-25, hen: several talking at once): the members live and mine, the four who spoke last in
     * view (LiveCast), the videos in one row of equal boxes, each picture cropped to fill, the voices' pills under them
     * (face only when the names don't fit), "+N talking · Name" under all; placed by LiveGroupLayout, moved in ONE move
     * by LiveMotion as the one-to-one row is.
     */
    private fun placeGroup(context: Context, mine: LiveCell, screen: Float, window: IntArray, gap: Float) {
        val myShape = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
        val cells = LinkedHashMap<String, LiveCell>()
        val since = HashMap<String, Long>()
        groupCells.forEach { (k, g) -> if (g.cell.on) { cells[k] = g.cell; since[k] = g.member.since } }
        if (mine.on) {
            cells[LiveLayout.MINE] = mine
            since[LiveLayout.MINE] = mySince()
        }
        val shown = groupCast.update(cells.keys.map { LivePerson(it, since.getValue(it)) })
        val hidden = groupCast.hidden
        val margin = dp(context, 12).toFloat()
        val width = screen - 2 * margin
        val pills = shown.filter { !cells.getValue(it).video }.associateWith { cells.getValue(it).pillSize(screen.toInt()) }
        val slots = shown.map { k ->
            val cell = cells.getValue(k)
            if (cell.video) LiveSlot(k, true) else pills.getValue(k).let { (w, h) -> LiveSlot(k, false, w.toFloat(), h.toFloat()) }
        }
        // "+2 talking · Dana": the newest of those not in view.
        val chip = moreChip
        moreKey = hidden.firstOrNull()?.key
        val chipSize = if (chip != null && moreKey != null) {
            val who = moreKey?.let { k -> if (k == LiveLayout.MINE) context.getString(R.string.talk_panel_you) else groupCells[k]?.member?.user } ?: ""
            val words = context.getString(R.string.talk_more_talking, hidden.size, android.text.BidiFormatter.getInstance().unicodeWrap(who))
            if (chip.text.toString() != words) chip.text = words
            chip.visibility = View.VISIBLE
            chip.measure(View.MeasureSpec.makeMeasureSpec(screen.toInt(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            chip.measuredWidth.toFloat() to chip.measuredHeight.toFloat()
        } else null
        val (bw, bh) = LiveGroupLayout.boxSize(slots.count { it.video }, topHeight(context).toFloat(), myShape, width, gap)
        // Each box's inside at the box's own size: the pictures fill it, cropped (equal widths whatever their shapes).
        for (k in shown) {
            val cell = cells.getValue(k)
            cell.slides = true
            if (cell.video) size(cell.box, bw.toInt(), bh.toInt())
        }
        val targets = LiveGroupLayout.place(slots, width, gap, topHeight(context).toFloat(), myShape, topRtl, left = -window[0].toFloat() + margin, chip = chipSize)
        val moved = topMotion.target(targets, (restTop + window[1] + dp(context, 12)).toFloat(), dp(context, 16).toFloat(), inPlace = System.currentTimeMillis() < inPlaceUntil, away = awayEdge(context, window), from = fromChat(window))
        topRoom.intValue = if (targets.isEmpty()) 0 else window[1] + restTop + targets.values.maxOf { it.bottom }.toInt() + dp(context, 8)
        if (moved) Log.i(TAG, "group row: " + targets.entries.joinToString { (k, r) -> "$k ${if (r.video > 0f) "video" else "voice"} x=${r.x.toInt()} y=${r.y.toInt()} w=${r.w.toInt()} h=${r.h.toInt()}" } + " (${hidden.size} more; ${if (topRtl) "right to left" else "left to right"})")
        showTop()
        touchTop()
        if (topMotion.running) frames()
        anchorTop(context)
    }

    /** Each cell where the motion has it now, and the chip under them. */
    private fun showTop() {
        val rects = topMotion.rects
        theirCell?.place(rects[LiveLayout.THEIRS], restTop)
        myCell?.place(rects[LiveLayout.MINE], restTop)
        groupCells.forEach { (k, g) -> g.cell.place(rects[k], restTop) }
        moreChip?.let { chip ->
            val r = rects[LiveGroupLayout.MORE]
            if (r == null) {
                if (chip.visibility != View.GONE) chip.visibility = View.GONE
            } else {
                chip.translationX = r.x
                chip.translationY = restTop + r.y
                if (chip.visibility != View.VISIBLE) chip.visibility = View.VISIBLE
            }
        }
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
        // Members gone and up out of the row: their cells go.
        groupCells.entries.removeAll { (k, g) ->
            val gone = !g.cell.on && k !in topMotion.rects
            if (gone) dropGroupCell(g)
            gone
        }
        touchTop()
        if (topHiding && topMotion.rects.isEmpty()) removeTop()
    }

    /**
     * Only the items take touches (where they are and where they are going, and the chip), and the window is only as
     * big as they need: the rest goes to the app beneath, which stays usable. Android drops a touch that reaches
     * another app through an overlay's frame, whatever region it takes touches in (2026-11-27, hen: "on floating
     * layout I can't touch anywhere the same height on the screen"), so the window is cut down to the items ([cropTop]).
     */
    private fun touchTop() {
        val window = topWindow?.takeIf { it.isAttachedToWindow } ?: return
        val region = android.graphics.Region()
        for (r in topMotion.rects.values + topMotion.goals.values) {
            region.union(android.graphics.Rect(r.x.toInt(), (restTop + r.y).toInt().coerceAtLeast(0), (r.x + r.w).toInt() + 1, (restTop + r.bottom).toInt().coerceAtLeast(0) + 1))
        }
        // The group's "+N talking" chip is one of the rects (LiveGroupLayout.MORE), so it takes touches already.
        val chipAt = bothChip?.takeIf { it.width > 0 }?.let { chip ->
            val top = chip.top + chip.translationY.toInt()
            android.graphics.Rect(chip.left, top, chip.right, top + chip.height)
        }
        if (chipAt != null && bothChip?.visibility == View.VISIBLE) region.union(chipAt)
        val crop = cropTop(window.context, region, chipAt)
        val inWindow = android.graphics.Region(region).also { it.translate(-crop.left, -crop.top) }
        // Only when it changed: setting it asks the window for a layout.
        if (inWindow == touchable) return
        touchable = inWindow
        window.rootSurfaceControl?.setTouchableRegion(inWindow)
    }

    /**
     * The part of the row's full frame the window keeps: what [region] and the chip need, with room for shadows. While
     * items move it only grows (their goals are in [region] from the move's start, so it holds the whole way); at rest
     * it shrinks to them, once.
     */
    private fun cropTop(context: Context, region: android.graphics.Region, chip: android.graphics.Rect?): android.graphics.Rect {
        val full = topFull ?: return android.graphics.Rect()
        val pad = dp(context, 24)
        val want = android.graphics.Rect(region.bounds)
        if (chip != null) want.union(chip)
        if (want.isEmpty) want.set(0, 0, 1, 1) else want.inset(-pad, -pad)
        if (!want.intersect(full)) want.set(0, 0, 1, 1)
        val now = topCrop
        val next = when {
            now == null -> want
            topMotion.running || topTouch?.dragging == true -> android.graphics.Rect(now).also { it.union(want) }
            // At rest: down to the items, unless that only saves a little.
            now.contains(want) && now.width() - want.width() < pad / 3 && now.height() - want.height() < pad / 3 -> now
            else -> want
        }
        if (next != now) {
            topCrop = next
            layoutTop()
        }
        return next
    }

    /** The row's window where [topParams] puts its full frame, cut to [topCrop] ([topShift] keeps the items in place). */
    private fun layoutTop() {
        val window = topWindow ?: return
        val params = topParams ?: return
        val real = topReal ?: return
        val full = topFull ?: return
        val crop = topCrop ?: full
        real.x = params.x + crop.left
        real.y = params.y + crop.top
        real.width = crop.width()
        real.height = crop.height()
        topShift?.let {
            it.translationX = -crop.left.toFloat()
            it.translationY = -crop.top.toFloat()
        }
        runCatching { window.context.getSystemService(WindowManager::class.java).updateViewLayout(window, real) }
    }

    private var topReal: WindowManager.LayoutParams? = null
    private var topShift: FrameLayout? = null
    private var topFull: android.graphics.Rect? = null
    private var topCrop: android.graphics.Rect? = null

    private var touchable: android.graphics.Region? = null

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
            // Who only, at the box's top centre, no time (2026-11-25, hen).
            theirTag?.text = android.text.BidiFormatter.getInstance().unicodeWrap(rowTitle())
            useRoomVideo(window.context)
            showTheirFace(window.context)
            useGroupVideo(window.context)
            // Who is live in the group changed (Talk's talking): the row follows.
            if (groupRow() && groupMembers(window.context).map { it.key to it.video } != groupCells.filterValues { it.cell.on && it.goneAt == 0L }.map { (k, g) -> k to g.cell.video }) refreshTop(window.context)
            // Their picture's shape came while their pill waited for it: now the pill grows into the box.
            if (theirVideoWanted() && theirCell?.let { it.on && !it.video } == true && theirPictureReady(window.context)) refreshTop(window.context)
            pickPreview()
            myTag?.text = "● " + window.context.getString(R.string.talk_panel_you)
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
            // My push still connecting (its room isn't ready; 2026-11-27, hen: shown in the pill, without words, nothing
            // spinning): my pill and its frame in still grey dashes, no time, until the push is live.
            val connecting = topWindow?.context?.let { c -> messagingState(c).value.talk.own?.takeIf { it.chat == chat }?.connecting } == true
            myPill?.connecting = connecting
            myCell?.connecting(connecting)
            myPill?.tick(com.chencohen.ptt.talk.TalkAudio.micLevel, mySince())
            groupCells.values.forEach { it.cell.pill.tick(com.chencohen.ptt.talk.TalkAudio.playLevel, it.member.since) }
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
            thaw(box)
        } else if (live == null && roomTrack != null) {
            // Their track went (the burst ended): its last picture stays while their box is still there.
            freeze(box, roomRenderer, theirTag)
            dropRoomVideo()
            box.findViewWithTag<View>("video")?.visibility = View.VISIBLE
        }
    }

    /**
     * [box] keeps the picture [from] shows now, over whatever its video views turn to (2026-11-25, hen: "when stopping
     * video the camera turns black and then slides up"): put just under [above], the box's tag.
     */
    private fun freeze(box: FrameLayout?, from: android.view.TextureView?, above: View?) {
        box ?: return
        if (box.findViewWithTag<View>("frozen") != null) return
        val picture = runCatching { from?.takeIf { it.isAvailable && it.width > 0 && it.height > 0 }?.bitmap }.getOrNull() ?: return
        val still = ImageView(box.context).apply {
            tag = "frozen"
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(picture)
        }
        val at = above?.let { box.indexOfChild(it) }?.takeIf { it >= 0 } ?: box.childCount
        box.addView(still, at, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** My box keeps its last picture: the room's while it read the camera, else the camera's own preview. */
    private fun freezeMine() = freeze(myBox, myRoomRenderer ?: myBox?.getChildAt(0) as? android.view.TextureView, myFace ?: myTag)

    /** My box keeps its last picture only when nothing live feeds it (the room's track goes on reading the camera). */
    private fun freezeMineUnlessLive() {
        if (myRoomTrack == null) freezeMine()
    }

    /** [box]'s picture fades to black over the slide's length, under [above] (the tag); thaw takes it away. */
    private fun fadeToBlack(box: FrameLayout?, above: View?) {
        box ?: return
        if (box.findViewWithTag<View>("fade") != null) return
        val black = View(box.context).apply {
            tag = "fade"
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = 0f
        }
        val at = above?.let { box.indexOfChild(it) }?.takeIf { it >= 0 } ?: box.childCount
        box.addView(black, at, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        black.animate().alpha(1f).setDuration(300).start()
    }

    /** [box] shows its live picture again. */
    private fun thaw(box: FrameLayout?) {
        box?.findViewWithTag<View>("frozen")?.let { box.removeView(it) }
        box?.findViewWithTag<View>("fade")?.let { box.removeView(it) }
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
        readPlaces(context)
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
        fun tagAt() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
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
            // My face, large, until my camera's picture comes (never a black box).
            myFace = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE; setBackgroundColor(Color.BLACK) }
            addView(myFace, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { setMargins(0, dp(40), 0, dp(40)) })
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
        // "⤢ Full screen" under the boxes (2026-11-27, hen: it's not "make it a call", two people talking already is
        // one; "'Full screen' is enough"): a tap opens the same live talk full screen in the app, nothing dialled, me
        // muted there; shrinking it brings this floating layout back.
        val chip = TextView(context).apply {
            visibility = View.GONE
            text = "⤢  " + context.getString(R.string.talk_full_screen)
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xF2191D1C.toInt()); setStroke(dp(1), TEAL) }
            setOnClickListener {
                val chat = rowChat() ?: return@setOnClickListener
                // Live now: the same press goes on in full screen, my microphone (and camera) still on (2026-11-27,
                // hen); this window only stops holding it.
                val mic = talking && this@TalkBubble.chat == chat
                val camera = mic && sendingVideo
                if (mic) letGoOfPress(context)
                else if (talking) {
                    locked = false
                    endTalk(context, send = true)
                }
                com.chencohen.ptt.ui.calls.LiveFull.open(chat, fromFloating = true, mic = mic, camera = camera)
                openChat(context, chat)
                if (outside != null) QuickReplyWindow.close(context)
                dismissNow()
            }
        }
        stage.addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        // A group's "+2 talking · Dana" (2026-11-25, hen): a tap brings Dana into view in the quietest one's place.
        val more = TextView(context).apply {
            visibility = View.GONE
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            elevation = 6 * resources.displayMetrics.density
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xF2191D1C.toInt()) }
            setOnClickListener {
                val key = moreKey ?: return@setOnClickListener
                groupCast.choose(key, System.currentTimeMillis())
                placeTop(context)
            }
        }
        stage.addView(more, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        // The row as wide as the screen in [shift], which the window shows only the part of that holds the items
        // ([cropTop]).
        val fullHeight = restTop + statusTop + topHeight(context) + dp(176)
        val shift = FrameLayout(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            addView(stage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val holder = FrameLayout(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            addView(shift, FrameLayout.LayoutParams(wm.currentWindowMetrics.bounds.width(), fullHeight, Gravity.TOP or Gravity.LEFT))
        }
        val params = WindowManager.LayoutParams(
            // As wide as the screen and tall enough for the tallest row, once: nothing that shows in it moves or
            // resizes the window (2026-11-25, hen: "symmetrical, in one move"; a window resizing each frame nudged the
            // items). Only the items take touches ([touchTop]).
            WindowManager.LayoutParams.MATCH_PARENT,
            // A group's: the boxes' row, the pills' and the chip under them.
            fullHeight,
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
        topTouch = touch
        theirItem.view.setOnTouchListener(touch)
        myItem.view.setOnTouchListener(touch)
        val real = WindowManager.LayoutParams().apply { copyFrom(params) }
        try {
            wm.addView(holder, real)
        } catch (e: Exception) {
            Log.w(TAG, "can't show the top row", e)
            return false
        }
        topParams = params
        topReal = real
        topShift = shift
        topFull = android.graphics.Rect(0, 0, wm.currentWindowMetrics.bounds.width(), fullHeight)
        topCrop = null
        topWindow = holder
        topCard = stage
        theirCell = theirItem
        myCell = myItem
        theirPill = theirVoice
        myPill = myVoice
        theirBox = theirs
        myBox = mine
        bothChip = chip
        moreChip = more
        topHiding = false
        // Wherever the system put the window (the S25 drew it at the screen's very top, under the status bar;
        // 2026-11-25), the items rest just below the status bar and are centred on the screen: once the window is
        // laid out, where it really is is taken into the places (the window itself stays put).
        holder.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (topWindow !== holder || (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom)) return@addOnLayoutChangeListener
            val at = IntArray(2).also { holder.getLocationOnScreen(it) }
            val low = statusTop + dp(2) - (at[1] - params.y - (topCrop?.top ?: 0) + restTop)
            if (low > 0) restTop += low
            Log.i(TAG, "top row: window at ${at[0]},${at[1]}, ${right - left} wide, rests at ${at[1] + restTop}, status bar $statusTop, ${if (rtl) "right to left" else "left to right"}")
            holder.post { if (topWindow === holder && !topHiding) placeTop(context) }
        }
        main.removeCallbacks(topPulse)
        main.post(topPulse)
        // My own face, for my box while it waits for my camera's picture.
        (context.applicationContext as? TalkApp)?.let { app ->
            app.scope.launch {
                val id = kotlinx.coroutines.withTimeoutOrNull(1_000) { com.chencohen.ptt.ui.face.Faces.ids(app).first { it.containsKey("") } }?.get("") ?: "-"
                val bitmap = runCatching { com.chencohen.ptt.ui.face.Faces.load(app, "", id) }.getOrNull() ?: return@launch
                main.post { if (topWindow === holder) myFace?.setImageBitmap(bitmap.asAndroidBitmap()) }
            }
        }
        return true
    }

    /**
     * Drag moves the videos, and where they are let go they stay ([droppedVideos]: the top, the bottom, or where they
     * were, beside the bar when that is its place; 2026-11-27, hen: "make the video also to be dragged"); a swipe up at
     * the top puts them away (until their next burst); a tap opens the chat.
     */
    private class TopTouch(val context: Context, val wm: WindowManager, val window: View, val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var x0 = 0f
        private var y0 = 0f
        private var px = 0
        private var py = 0
        private var moved = false
        private var t0 = 0L
        private var atTop = false
        /** A drag is moving the window now: nothing else moves it meanwhile. */
        var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX
                    y0 = e.rawY
                    topMove?.cancel()
                    px = params.x
                    py = params.y
                    moved = false
                    t0 = System.currentTimeMillis()
                    atTop = (videosNow()?.top ?: 0f) < floatScreen(context).topInset + dp(context, 40)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0
                    val dy = e.rawY - y0
                    if (!moved && kotlin.math.hypot(dx, dy) > slop) {
                        moved = true
                        dragging = true
                    }
                    if (moved) {
                        params.x = px + dx.toInt()
                        params.y = py + dy.toInt()
                        layoutTop()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = e.rawY - y0
                    val fast = System.currentTimeMillis() - t0 < 400
                    dragging = false
                    val now = videosNow()
                    when {
                        !moved -> if (e.actionMasked == MotionEvent.ACTION_UP) rowChat()?.let { intoChat(context, it) }
                        // Flicked up from the top, or dragged up past the screen's top: put away.
                        (atTop && fast && dy < -dp(context, 40)) || (now != null && now.top < floatScreen(context).topInset - dp(context, 24)) -> {
                            topPutAway = true
                            hideTop()
                        }
                        now != null -> droppedVideos(context, now)
                    }
                }
            }
            return true
        }
    }

    /** A group member's cell: their pill or box, their face until a picture comes through the room, their name. */
    private class GroupCell(val cell: LiveCell, val face: ImageView, val tag: TextView, var member: GroupMember) {
        var renderer: io.livekit.android.renderer.TextureViewRenderer? = null
        var track: io.livekit.android.room.track.VideoTrack? = null
        /** When their push ended (0: live): kept [BURST_HOLD_MS], so voice turning into video is one cell that grows. */
        var goneAt = 0L
    }

    private var groupHoldApp: Context? = null

    /** A member's push ended a moment ago and no other of theirs followed: now their cell goes. */
    private val groupHold = Runnable { groupHoldApp?.let { refreshTop(it) } }

    /** The group's cells as [members] are now: new ones made, gone ones held a moment, then let go (up and out). */
    private fun syncGroup(context: Context, members: List<GroupMember>) {
        val stage = topCard ?: return
        val now = System.currentTimeMillis()
        val keys = members.map { it.key }.toSet()
        for (m in members) {
            val g = groupCells[m.key] ?: addGroupCell(context, stage, m).also { groupCells[m.key] = it }
            if (!g.cell.on) thaw(g.cell.box)
            g.member = m
            g.goneAt = 0L
            g.cell.on = true
            g.cell.video = m.video
            g.cell.pill.show(context, m.user, m.user)
            g.cell.pill.tick(0f, m.since)
            val name = android.text.BidiFormatter.getInstance().unicodeWrap(m.user)
            if (g.tag.text.toString() != name) g.tag.text = name
        }
        for ((k, g) in groupCells) {
            if (k in keys || !g.cell.on) continue
            if (g.goneAt == 0L) {
                g.goneAt = now
                groupHoldApp = context.applicationContext
                main.removeCallbacks(groupHold)
                main.postDelayed(groupHold, BURST_HOLD_MS)
            } else if (now - g.goneAt >= BURST_HOLD_MS - 50) {
                // Up and out, its picture fading to black in the same move (as theirs one to one).
                if (g.cell.video) {
                    freeze(g.cell.box, g.renderer, g.tag)
                    fadeToBlack(g.cell.box, g.tag)
                }
                g.cell.on = false
            }
        }
    }

    /** A member's cell, in the row's stage (not in view until placed). */
    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun addGroupCell(context: Context, stage: FrameLayout, m: GroupMember): GroupCell {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val face = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val tag = TextView(context).apply {
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x80000000.toInt()) }
        }
        val box = FrameLayout(context).apply {
            layoutDirection = if (topRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(Color.BLACK)
            // Their face, large, until a picture comes through the room (never a black box); their letter until the face
            // is read, or if they have none.
            addView(TextView(context).apply {
                setTag("letter")
                text = m.user.take(1).uppercase()
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            }, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            addView(face, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
            addView(tag, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }
        val cell = LiveCell(context, m.key, TEAL, VoicePill(context, mine = false, rtl = topRtl), box)
        // Under the chip, so the chip stays on top.
        stage.addView(cell.view, (stage.indexOfChild(moreChip).takeIf { it >= 0 } ?: stage.childCount), FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
        topTouch?.let { cell.view.setOnTouchListener(it) }
        val g = GroupCell(cell, face, tag, m)
        (context.applicationContext as? TalkApp)?.let { app ->
            app.scope.launch {
                val id = kotlinx.coroutines.withTimeoutOrNull(1_000) { com.chencohen.ptt.ui.face.Faces.ids(app).first { it.containsKey(m.user) } }?.get(m.user) ?: "-"
                val bitmap = runCatching { com.chencohen.ptt.ui.face.Faces.load(app, m.user, id) }.getOrNull() ?: return@launch
                main.post {
                    face.setImageBitmap(bitmap.asAndroidBitmap())
                    box.findViewWithTag<View>("letter")?.visibility = View.GONE
                }
            }
        }
        return g
    }

    /** Each member's picture through the room while it comes (their face until then, and after). */
    private fun useGroupVideo(context: Context) {
        val arriving = com.chencohen.ptt.talk.LiveTalkVideo.arriving.value
        for ((k, g) in groupCells) {
            val live = arriving[k]?.takeIf { g.cell.on }
            if (live != null && g.track !== live.track) {
                dropGroupVideo(g)
                val r = io.livekit.android.renderer.TextureViewRenderer(context)
                if (!com.chencohen.ptt.talk.LiveTalkVideo.initRenderer(r)) continue
                r.setScalingType(livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                g.cell.box.addView(r, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                live.track.addRenderer(r)
                g.renderer = r
                g.track = live.track
                thaw(g.cell.box)
            } else if (live == null && g.track != null) {
                freeze(g.cell.box, g.renderer, g.tag)
                dropGroupVideo(g)
            }
            g.face.visibility = if (g.renderer != null) View.GONE else View.VISIBLE
            g.cell.box.findViewWithTag<View>("letter")?.let { if (g.renderer != null || g.face.drawable != null) it.visibility = View.GONE else it.visibility = View.VISIBLE }
        }
    }

    private fun dropGroupVideo(g: GroupCell) {
        val r = g.renderer ?: return
        runCatching { g.track?.removeRenderer(r) }
        (r.parent as? android.view.ViewGroup)?.removeView(r)
        r.release()
        g.renderer = null
        g.track = null
    }

    private fun dropGroupCell(g: GroupCell) {
        dropGroupVideo(g)
        (g.cell.view.parent as? android.view.ViewGroup)?.removeView(g.cell.view)
    }

    /** Every item goes back up through the screen's top edge, in one move; then the window goes. */
    private fun hideTop() {
        val window = topWindow ?: return
        if (topHiding) return
        topHiding = true
        main.removeCallbacks(topTick)
        theirCell?.on = false
        myCell?.on = false
        groupCells.values.forEach { it.cell.on = false }
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
        touchable = null
        topRoom.intValue = 0
        videoSurface?.let { com.chencohen.ptt.talk.AndroidTalkVideoOut.detach(it) }
        videoSurface = null
        dropRoomVideo()
        dropMyRoomVideo()
        roomLive = null
        topCard = null
        topParams = null
        topReal = null
        topShift = null
        topFull = null
        topCrop = null
        theirCell = null
        myCell = null
        theirBox = null
        theirTag = null
        theirFace = null
        theirFaceOf = null
        myBox = null
        myTag = null
        myFace = null
        theirPill = null
        myPill = null
        bothChip = null
        groupCells.values.forEach { dropGroupCell(it) }
        groupCells.clear()
        groupCast = LiveCast()
        moreChip = null
        moreKey = null
        topTouch = null
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
        /**
         * In a group's row: a frame narrower than its pill (face only: the names don't fit) keeps the face, at the
         * language's start side, in view, the pill sliding to that edge and the frame covering the words.
         */
        var slides = false
        /** The pill's own width, last measured. */
        private var pillW = 0
        private val density = context.resources.displayMetrics.density
        private val fill = GradientDrawable()
        private val edge = GradientDrawable()
        private var radius = -1f

        /** Its outline's colour (mine red while my push is urgent; 2026-11-25, hen). */
        fun edgeColor(color: Int) {
            if (edgeInk == color) return
            edgeInk = color
            drawEdge()
        }

        private var edgeInk = color

        /** How far the outline's dashes have joined into its line: 0 while my push connects, 1 otherwise. */
        private var joined = 1f
        private var connectingNow = false
        private var joining: android.animation.ValueAnimator? = null

        /**
         * My push still connects ([on]): the outline in still grey dashes (6 dp of every 11), which lengthen until they
         * meet and take its colour when the push is live, in one 300 ms move. Nothing turns.
         */
        fun connecting(on: Boolean) {
            if (connectingNow == on) return
            connectingNow = on
            joining?.cancel()
            joining = android.animation.ValueAnimator.ofFloat(joined, if (on) 0f else 1f).apply {
                duration = 300
                addUpdateListener {
                    joined = it.animatedValue as Float
                    drawEdge()
                }
                start()
            }
        }

        private fun drawEdge() {
            val w = density.toInt().coerceAtLeast(2)
            if (joined >= 1f) edge.setStroke(w, edgeInk)
            else {
                val on = (6f + 5f * joined) * density
                edge.setStroke(w, androidx.core.graphics.ColorUtils.blendARGB(DASH_INK, edgeInk, joined), on, 11f * density - on)
            }
        }

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
            pillW = pill.view.measuredWidth
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
            // Face only: the pill at its own width (not squeezed into the frame), slid so its face shows; less so as it
            // grows into a box.
            val narrow = slides && pillW > 0 && rect.w < pillW - 0.5f
            val pillLp = pill.view.layoutParams
            val wantW = if (narrow) pillW else FrameLayout.LayoutParams.WRAP_CONTENT
            if (pillLp.width != wantW) {
                pillLp.width = wantW
                pill.view.layoutParams = pillLp
            }
            val dir = if (pill.view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
            pill.view.translationX = if (narrow) (pillW - rect.w) / 2 * (1 - p) * dir else 0f
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
            // Folding (2026-11-27, hen: "auto hide in a smart way"): out of its edge the moment anyone talks (the tab
            // glows with their voice as it comes), into it once nobody has for a while; never while Push is held,
            // video is live or a call rings.
            val now = System.currentTimeMillis()
            val busy = busy() || theirs != null
            if (busy) lastActive = now
            // Out when talk starts (folded by hand while someone talks, it stays in until the next).
            val started = busy && !wasBusy
            wasBusy = busy
            if (folded && started) unfold()
            else if (!folded && !moving && !barDrag && foldMove?.isRunning != true &&
                FloatDock.folds(now - lastActive, keyboard = false, fullScreen = false, holding = busy, liveVideo = liveVideo(), ringing = ringing != null)
            ) fold()
            // The voice's ring on the bar's face; at rest, its light rim.
            if (active) barFace?.setStroke((density * (1.5f + 4 * level)).toInt().coerceAtLeast(rimPx(context)), if (ringing != null) HERE else TEAL)
            else barFace?.setStroke(rimPx(context), RIM)
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
        morphPill()
        showMode(context)
    }

    /** The call stopped ringing (answered, declined, missed): the pill is Chat · Push · Close again. */
    fun callGone(context: Context) = main.post {
        if (ringing == null) return@post
        ringing = null
        (button?.background as? GradientDrawable)?.setColor(PINK)
        morphPill()
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
        // My push in this chat goes on in the chat's screen as it is (2026-11-27, hen: "when returning from floating to
        // the chat screen the stream turned off, it should stay on"); one in another chat ends (sent).
        if (talking && this.chat == chat) {
            com.chencohen.ptt.ui.live.LiveHandoff.fromFloating(topOnScreen(), System.currentTimeMillis())
            val back = PendingBack(chat, sendingVideo, urgent, System.currentTimeMillis())
            pendingBack = back
            letGoOfPress(context)
            // Not taken by the chat after all (it didn't open): let go, so nothing goes on unseen.
            val app = context.applicationContext as? TalkApp
            main.postDelayed({
                if (pendingBack === back) {
                    pendingBack = null
                    Log.i(TAG, "the chat didn't take my press back: let go")
                    app?.scope?.launch { com.chencohen.ptt.talkBack(app, null, down = false, send = true) }
                }
            }, PENDING_BACK_MS)
        } else if (talking) {
            locked = false
            endTalk(context, send = true)
        }
        val row = pill
        if (outside != null) {
            // The floating chat's row: the floating chat grows into the app's chat (or, showing another, closes and
            // the chat opens the plain way), and the row goes with it.
            if (!QuickReplyWindow.intoChat(context, chat)) {
                openChat(context, chat)
                QuickReplyWindow.close(context)
            }
            row?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction { remove() }?.start() ?: remove()
            return
        }
        // The pill itself grows into the chat (2026-11-26, hen: "an expanding transformation"): a window in its exact
        // place, shape and colour takes over and grows to the whole screen, the chat showing through; the pill's
        // buttons go in its first moments.
        val at = IntArray(2)
        val grows = row != null && row.isLaidOut && run {
            row.getLocationOnScreen(at)
            QuickReplyWindow.growFrom(
                context, chat, at[0], at[1] + row.translationY.toInt().coerceAtMost(0), at[0] + row.width, at[1] + row.height,
                radius = minOf(row.width, row.height) / 2f, color = BAR,
            )
        }
        if (!grows) openChat(context, chat)
        topCard?.let { card ->
            // About the row's own middle.
            card.pivotX = card.width / 2f
            card.pivotY = restTop + (topMotion.rects.values.maxOfOrNull { it.bottom } ?: 0f) / 2
            card.animate().scaleX(1.1f).scaleY(1.1f).alpha(0f).setDuration(200).start()
        }
        row?.animate()?.alpha(0f)?.setStartDelay(if (grows) 80 else 0)?.setDuration(if (grows) 120 else 200)?.withEndAction { remove() }?.start() ?: remove()
    }

    private fun openChat(context: Context, chat: String) = runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).setAction(Notifications.ACTION_OPEN).putExtra(Notifications.EXTRA_USER, chat)
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
        // Off the screen's very edge, past the system bar.
        val (ax, ay) = awayDir()
        val off = foldReach() + dp(row.context, if (dock.edge.upright) PEEK_SIDE_DP else PEEK_DP) + edgeInset + dp(row.context, 16)
        foldMove?.cancel()
        row.animate().translationX(ax * off).translationY(ay * off).setDuration(SLIDE_MS)
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
        pillParams = null
        main.removeCallbacks(longPress)
        foldMove?.cancel()
        foldMove = null
        folded = false
        barShown = 1f
        moving = false
        guide?.remove()
        guide = null
        main.removeCallbacks(pulse)
        main.removeCallbacks(renew)
        main.removeCallbacks(burstOver)
        main.removeCallbacks(hideSoon)
        setHere(context, null)
        if (previewOn) preview(context, false)
        previewOn = false
        ringing = null
        chatButton = null
        chatFaceOf = null
        button = null
        closeButton = null
        pushBox = null
        stopPill = null
        lockHint = null
        bellHint = null
        micHint = null
        sideLabel = null
        barFace = null
        barDrag = false
        sideVoice = false
        urgent = false
        main.removeCallbacks(sideWatch)
        pillMorph?.cancel()
        pillMorph = null
        stopShown = false
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

        /** Mine, while my push still connects: the ring in still dashes, the words without a time (it starts when live). */
        var connecting = false
            set(value) {
                if (field == value) return
                field = value
                ring.connecting = value
                name.setTextColor(if (value) DASH_INK else Color.WHITE)
            }
        private var liveFrom = 0L

        /** Mine, urgent (the red bell; 2026-11-25, hen): "You · Urgent · 0:03", the ring red. */
        var urgent = false
            set(value) {
                if (field == value) return
                field = value
                ring.color = if (value) URGENT else PINK
            }

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
            // The time counts from when the push went live, not from a press that was still connecting.
            if (connecting) liveFrom = System.currentTimeMillis()
            val s = ((System.currentTimeMillis() - maxOf(since, liveFrom)) / 1000).coerceAtLeast(0)
            val t = "%d:%02d".format(s / 60, s % 60)
            // Only when it changes: a text set again asks for a layout each time.
            val you = view.context.getString(R.string.talk_panel_you) + if (urgent) " · " + view.context.getString(R.string.talk_urgent) else ""
            val (line, words) = if (mine) name to (if (connecting) you else "$you · $t") else time to t
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

        /**
         * My push still connects: the ring in twelve still grey dashes with no glow, which lengthen until they meet and
         * take its colour when the push is live (300 ms). Nothing turns.
         */
        var connecting = false
            set(value) {
                if (field == value) return
                field = value
                postInvalidateOnAnimation()
            }
        private var joined = 1f

        fun level(v: Float) {
            target = v.coerceIn(0f, 1f)
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (last == 0L) 16f else (now - last).toFloat().coerceIn(1f, 100f)
            last = now
            val to = if (connecting) 0f else 1f
            joined = if (joined < to) (joined + dt / 300f).coerceAtMost(to) else (joined - dt / 300f).coerceAtLeast(to)
            level = com.chencohen.ptt.talk.VoiceLevel.smooth(level, target * joined, dt)
            val ink = paint.color
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
            if (joined < 1f) {
                val period = (2 * Math.PI * r / 12).toFloat()
                val on = period * (0.53f + 0.47f * joined)
                paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(on, period - on), 0f)
                paint.color = androidx.core.graphics.ColorUtils.blendARGB(DASH_INK, ink, joined)
            }
            paint.alpha = (255 * (0.55f + 0.45f * level + 0.35f * (1f - joined))).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r, paint)
            paint.pathEffect = null
            paint.color = ink
            if (kotlin.math.abs(level - target * joined) > 0.005f || joined != to) postInvalidateOnAnimation()
        }
    }

    private const val PINK = 0xFFE9504F.toInt()

    /** My pill's dashes while my push still connects (the palette's muted grey). */
    private const val DASH_INK = 0xFFA9B4B1.toInt()
    /** The bar: solid, whatever is behind it; a light rim inside a dark hairline (2026-11-26, hen's pick from the mock). */
    private const val BAR = 0xFF191D1C.toInt()
    private const val RIM = 0x59FFFFFF
    private const val BAR_DP = 76
    private fun rimPx(context: Context) = (1.5f * context.resources.displayMetrics.density).toInt().coerceAtLeast(2)
    /** An urgent push's red (2026-11-25, hen). */
    private const val URGENT = 0xFFD93025.toInt()
    /** How far sideways from Push the bell is, and the slide that reaches it. */
    private const val URGENT_DP = 56
    private const val TEAL = 0xFF2BB3A3.toInt()
    /** Where the bar and the videos rest (SharedPreferences). */
    private const val PLACES = "talk_bubble_places"
    private const val HERE = 0xFF34C759.toInt()
}
