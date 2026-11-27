package com.chencohen.ptt

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chencohen.ptt.messaging.ClipKind
import com.chencohen.ptt.messaging.QuickReply
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.contacts.ConversationActions
import com.chencohen.ptt.ui.contacts.ConversationHeader
import com.chencohen.ptt.ui.contacts.ConversationScreen
import com.chencohen.ptt.ui.contacts.GroupChat
import com.chencohen.ptt.ui.home.liveDrafts
import com.chencohen.ptt.ui.home.liveCarets
import com.chencohen.ptt.ui.home.viewingIn
import androidx.compose.ui.draw.clip
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The quick-reply window (2026-11-23, hen; Settings › Talk › Quick reply window, on by default; needs "Display over
 * other apps", like the reply bubble): a message that comes in while the phone is in use shows its conversation at the
 * bottom of the screen, 40% of its height: the sender (a tap opens the chat), and below it the conversation screen
 * itself (2026-11-24, hen: the same design and ways), its draft pinned at the bottom and its buttons (video call, hold
 * to talk, voice call; a voice message opens the chat). A swipe down or × closes it; untouched and empty for 20 s it closes by itself. Which
 * conversation it shows: QuickReply. The notification shows as well.
 */
object QuickReplyWindow {
    private const val TAG = "QuickReply"
    private val main = Handler(Looper.getMainLooper())
    private val logic = QuickReply()
    /** Dismissed conversations: their messages are notifications until they go quiet (QuickReplyQuiet). */
    private val quiet = com.chencohen.ptt.messaging.QuickReplyQuiet()

    /** The user opened [chat] in the app themselves: the window may come for it again. */
    fun chatOpened(chat: String) = synchronized(quiet) { quiet.opened(chat) }

    /** The floating window's bar was swiped away for [chat]: as this window dismissed. */
    fun dismissed(chat: String) = synchronized(quiet) { quiet.dismissed(chat, System.currentTimeMillis()) }

    /** A message of mine went out in [chat] from outside the window (a notification's Reply): the conversation goes on. */
    fun sent(chat: String) {
        synchronized(quiet) { quiet.message(chat, System.currentTimeMillis()) }
    }
    private var view: FrameLayout? = null
    private var owner: Owner? = null
    private val chat = mutableStateOf<String?>(null)
    private val typed = mutableStateOf("")
    private val talking = mutableStateOf(false)
    /** How solid the window is but for an incoming video (OverlayArrival: 0.5 for its first second). */
    private val seeThrough = androidx.compose.runtime.mutableFloatStateOf(1f)

    /**
     * Growing into the app's chat and back (2026-11-26, hen: "the navigation from floating to chat screen should feel
     * seamless, an expanding transformation"). [Phase.Floating]: the window as it floats. [Phase.Growing]: it grows,
     * from where and what it is, over the whole screen. [Phase.Full]: it covers the screen, showing the chat as the app
     * does, while the app opens under it, unseen (or, on the way back, leaves). [Phase.Handing]: the app's chat is
     * drawn, the window goes. [Phase.Shrinking]: back to floating.
     */
    private enum class Phase { Floating, Growing, Full, Handing, Shrinking }
    private var phase = Phase.Floating
    /** How far it has grown: 0 floating, 1 the whole screen. Read while placing and drawing only (no recomposition). */
    private val grow = androidx.compose.runtime.mutableFloatStateOf(0f)
    /** Not floating: growing, covering or shrinking. */
    private val morphing = mutableStateOf(false)
    /** It covers the screen: the chat's own header shows, as in the app. */
    private val full = mutableStateOf(false)
    /** What it grows out of when that isn't the floating chat (the floating window's pill): where on the screen, how round, what colour. */
    private class Origin(val left: Int, val top: Int, val right: Int, val bottom: Int, val radius: Float, val color: Int)
    private val origin = mutableStateOf<Origin?>(null)
    private var morph: android.animation.ValueAnimator? = null
    /** Debug builds only: growing stops at this share of the way and stays (null: as always). */
    internal var debugHold: Float? = null
    private const val MORPH_MS = 380L

    /** Where unanswered messages go back to (a silent notification) and answered ones are dropped. */
    @Volatile var notifications: Notifications? = null

    /** The conversation shown was answered (a reply, a burst, or the chat opened): no notification for it after. */
    private var answered = false

    /**
     * A message came into [chat] (Notifications): shows, updates or queues the window; true when the window has it (no
     * notification then).
     */
    fun takes(context: Context, chat: String): Boolean = arrive(context, chat) != QuickReply.Step.None

    /**
     * As [takes], and what it did: [QuickReply.Step.Show] opens the window (the message's sound plays, 2026-11-23,
     * hen: "only on the first"); [QuickReply.Step.Update] (open already) and [QuickReply.Step.Wait] are silent.
     */
    fun arrive(context: Context, chat: String): QuickReply.Step {
        val app = context.applicationContext
        // Dismissed, and the conversation still going on (2026-11-26, hen): a notification, not the window.
        if (synchronized(quiet) { quiet.message(chat, System.currentTimeMillis()) }) {
            Log.i(TAG, "quick reply for $chat: None (dismissed, until the chat is quiet for 5 minutes)")
            return QuickReply.Step.None
        }
        val setting = messagingState(app).value.talkSettings.quickReply
        val overlay = android.provider.Settings.canDrawOverlays(app)
        val screenOn = app.getSystemService(android.os.PowerManager::class.java).isInteractive
        val inFront = chatInFront(app)
        // Growing into the chat or shrinking back counts as busy: another conversation waits its turn.
        val busy = typed.value.isNotBlank() || talking.value || phase != Phase.Floating
        val step = synchronized(logic) { logic.arrived(chat, setting && overlay && screenOn, inFront, busy) }
        Log.i(TAG, "quick reply for $chat: $step (setting $setting, over other apps $overlay, screen on $screenOn, open in front $inFront, busy $busy)")
        if (step is QuickReply.Step.Show) main.post { show(app, step.chat) }
        if (step is QuickReply.Step.Update) main.post { answered = false }
        return step
    }

    /** The window is open on [chat] now (TalkBubble: no bottom pill over it; the window tells the live row itself). */
    fun showing(chat: String): Boolean = view != null && this.chat.value == chat

    /** Debug builds: as [takes] for a message from [chat]; [fresh]: as if it hadn't been dismissed before. */
    fun arrived(context: Context, chat: String, fresh: Boolean = true) {
        if (fresh) chatOpened(chat)
        takes(context, chat)
    }

    private fun show(context: Context, which: String) {
        if (phase != Phase.Floating) return
        // Switching away from one left unanswered: its notification, silent.
        chat.value?.takeIf { it != which && !answered }?.let { notifications?.unanswered(it) }
        if (chat.value != which) setWindowChat(context, null)
        answered = false
        typed.value = ""
        chat.value = which
        if (view != null) return
        val wm = context.getSystemService(WindowManager::class.java)
        val frame = addWindow(context, wholeScreen = false)
        val params = layout
        if (frame == null || params == null) {
            synchronized(logic) { logic.closed() }
            return
        }
        // Slides up, gently, untouchable and a little see-through for a moment (OverlayArrival); the keyboard waits
        // for a tap.
        OverlayArrival.arrive(wm, frame, params, frame, params.height.toFloat()) { a -> seeThrough.floatValue = a }
        Log.i(TAG, "shown for $which")
    }

    /**
     * The window itself, at the bottom of the screen ([FLOATING_SHARE] of its height, more by an open keyboard's), or
     * over the [wholeScreen]. Null when Android wouldn't add it.
     */
    private fun addWindow(context: Context, wholeScreen: Boolean): FrameLayout? {
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = wm.currentWindowMetrics
        val o = Owner().also { owner = it }
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent { TalkTheme { Window(context) } }
        }
        val frame = object : FrameLayout(context) {
            // Back closes it (the window takes keys once it was touched).
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss(context)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // The first touch makes it the window in use: until then the app under it keeps the keys and its keyboard.
            // As the finger lifts, not under it: the window changes then, and a touch in progress isn't disturbed.
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                val handled = super.dispatchTouchEvent(event)
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) post { takeKeys(context) }
                return handled
            }
        }
        // Compose looks for its lifecycle from the window's root view: set on the ComposeView alone, it crashed the
        // app as the window attached (2026-11-23, hen: "the window doesn't appear").
        frame.setViewTreeLifecycleOwner(o)
        frame.setViewTreeSavedStateRegistryOwner(o)
        frame.setViewTreeViewModelStoreOwner(o)
        frame.addView(compose)
        val insets = metrics.windowInsets
        val nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        // Another app's keyboard is open: the window comes in above it, at its full height from its first frame.
        val keyboard = (insets.getInsets(android.view.WindowInsets.Type.ime()).bottom - nav).coerceAtLeast(0)
        statusBar = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        baseHeight = (metrics.bounds.height() * FLOATING_SHARE).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (wholeScreen) metrics.bounds.height() else restHeight(context, keyboard, TalkBubble.topRoom.intValue),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // It doesn't take the keys as it comes (2026-11-26, hen's recording at 9.0 s: it arrived while he was
            // typing in another app, took the focus, and that app's keyboard closed under his fingers): the app in use
            // keeps its keyboard, and the window sits above it. A touch on the window gives it the keys ([takeKeys]).
            // With "alt focusable" it still counts as a window that uses the keyboard: it stays under the keyboard and
            // is told its height (without it, a window that takes no keys is put over the keyboard, covering it).
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or NO_KEYS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            // Above the keyboard while it shows (2026-11-23, hen): the window's frame keeps clear of it, moving up with
            // it and back down; with too little room the window gets shorter and the message list gives way.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            // 2026-11-24 (hen): not fitted by the system (that moved it only once the keyboard was up); the window
            // grows by the keyboard's height as it animates and its content keeps clear of it (imePadding).
            fitInsetsTypes = 0
            // Up to the screen's very top when it grows into the chat, under the status bar and the camera's cutout.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Its height changes with the keyboard's room and when it grows into the chat. Android slides a window
            // whose place changed (a "move animation"), though what is drawn in it is already at the new size: the
            // chat showed some way off and slid into place (2026-11-26, hen's recording at 19.5 s and 25.7 s). Never.
            setCanPlayMoveAnimation(false)
            // Nor Android's own fade as a window comes and goes: the window slides, grows and hands over by itself.
            windowAnimations = R.style.NoWindowAnimation
            title = "Talk quick reply"
        }
        layout = params
        return try {
            wm.addView(frame, params)
            view = frame
            o.start()
            frame
        } catch (e: Exception) {
            Log.w(TAG, "can't show the quick-reply window", e)
            o.stop()
            owner = null
            layout = null
            null
        }
    }

    /** The window takes the keys (a touch on it): its field can be written in, and Back closes it. */
    private fun takeKeys(context: Context) {
        val v = view ?: return
        val p = layout ?: return
        if (phase != Phase.Floating || p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
        p.flags = p.flags and NO_KEYS.inv()
        runCatching { context.getSystemService(WindowManager::class.java).updateViewLayout(v, p) }
    }

    /**
     * The user sends the window away (×, a swipe down, Back): closed, and that conversation's next messages are
     * notifications until it goes quiet (QuickReplyQuiet). Closing by itself, untouched, isn't that.
     */
    private fun dismiss(context: Context) {
        if (phase == Phase.Floating && view != null) chat.value?.let { c -> synchronized(quiet) { quiet.dismissed(c, System.currentTimeMillis()) } }
        close(context)
    }

    /** Closes the window, sliding it down off the screen; the next conversation waiting shows. */
    fun close(context: Context) = main.post {
        val app = context.applicationContext
        // Growing into the chat or shrinking back: that ends it, not this.
        if (phase != Phase.Floating) return@post
        if (talking.value) endTalk(app, send = false)
        val v = view ?: return@post
        val leaving = chat.value
        setWindowChat(app, null)
        leaving?.let {
            if (typed.value.isNotBlank()) {
                sendTyping(app, it, false)
                sendDraft(app, it, "")
            }
        }
        if (leaving != null && !answered) notifications?.unanswered(leaving)
        Log.i(TAG, "closed $leaving (answered $answered)")
        view = null
        // Someone still live in it: the floating window takes over, its row at the top staying as it is.
        (app as? TalkApp)?.let { TalkPlaying.windowClosed(it) }
        v.animate().translationY(v.height.toFloat()).setDuration(SLIDE_MS).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { gone(app, v) }.start()
    }

    /** [v], the window, leaves the screen for good; the next conversation waiting shows. */
    private fun gone(app: Context, v: android.view.View) {
        runCatching { app.getSystemService(WindowManager::class.java).removeView(v) }
        if (view === v) view = null
        owner?.stop()
        owner = null
        layout = null
        chat.value = null
        typed.value = ""
        answered = false
        morph?.cancel()
        morph = null
        phase = Phase.Floating
        grow.floatValue = 0f
        morphing.value = false
        full.value = false
        origin.value = null
        synchronized(logic) { logic.closed(chatInFront(app)) }?.let { show(app, it) }
    }

    /** Follows a swipe down by [dy] pixels so far; [done]: let go (slides out past a third of its height, else back). */
    private fun drag(context: Context, dy: Float, done: Boolean) {
        val v = view ?: return
        if (phase != Phase.Floating) return
        if (!done) {
            v.translationY = dy.coerceAtLeast(0f)
        } else if (dy > v.height / 3f) {
            dismiss(context)
        } else {
            v.animate().translationY(0f).setDuration(SLIDE_MS).start()
        }
    }

    private const val SLIDE_MS = 250L
    /** The window takes no keys, yet keeps its place under the keyboard. */
    private const val NO_KEYS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
    /**
     * The floating chat's share of the screen's height (2026-11-26, hen: "a little higher"; it was 0.4): room for two
     * or three more messages, the app behind still showing over half the screen.
     */
    private const val FLOATING_SHARE = 0.47f
    private var baseHeight = 0
    private var statusBar = 0
    private var layout: WindowManager.LayoutParams? = null

    /**
     * The floating window's height with the keyboard [extra] pixels above its bottom room: taller by that, but clear of
     * the status bar and of the live row at the top of the screen ([topRoom]: its bottom edge, 0 without one;
     * 2026-11-25, hen: the pills and videos show there, not in this window).
     */
    private fun restHeight(context: Context, extra: Int, topRoom: Int): Int {
        val screen = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
        return (baseHeight + extra.coerceAtLeast(0)).coerceAtMost((screen - maxOf(topRoom, statusBar)).coerceAtLeast(baseHeight / 2))
    }

    /** The keyboard is [extra] pixels above the window's bottom room now: the window grows by that, upward ([restHeight]). */
    private fun growBy(context: Context, extra: Int, topRoom: Int) {
        val v = view ?: return
        val p = layout ?: return
        // Over the whole screen (growing into the chat, or back): its size is that until it floats again.
        if (phase != Phase.Floating) return
        val height = restHeight(context, extra, topRoom)
        if (p.height == height) return
        p.height = height
        runCatching { context.getSystemService(WindowManager::class.java).updateViewLayout(v, p) }
    }

    // ---- Into the app's chat, and back ------------------------------------------------------------------------

    /** [grow] from [from] to [to] in one eased move; [done] once it got there. */
    private fun morphTo(from: Float, to: Float, done: () -> Unit) {
        morph?.cancel()
        grow.floatValue = from
        // Debug builds (DebugTriggers "morph_hold"): the move stops part of the way, to be looked at.
        debugHold?.let { held ->
            grow.floatValue = held
            return
        }
        morph = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = MORPH_MS
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { grow.floatValue = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    grow.floatValue = to
                    done()
                }
            })
            start()
        }
    }

    /** [then], once what [v] shows now is on the screen (its next frame drawn), or after 300 ms without one. */
    private fun afterDrawn(v: android.view.View, then: () -> Unit) {
        var done = false
        val once = Runnable {
            if (!done) {
                done = true
                then()
            }
        }
        v.post {
            runCatching { v.viewTreeObserver.registerFrameCommitCallback { main.post(once) } }
            v.invalidate()
        }
        main.postDelayed(once, 300)
    }

    /**
     * The floating chat grows into the app's chat, one move: its top edge rises to the top of the screen, its corners
     * flatten and its header becomes the chat's, while the draft and the buttons stay where they are; the keyboard,
     * if open, goes down in the same move. Then, covered by it, the app opens on that chat ([launch]).
     */
    private fun expand(context: Context, which: String) {
        val app = context.applicationContext
        val v = view ?: return
        val p = layout ?: return
        if (phase != Phase.Floating) return
        phase = Phase.Growing
        if (talking.value) endTalk(app, send = false)
        v.animate().cancel()
        v.translationY = 0f
        seeThrough.floatValue = 1f
        origin.value = null
        morphing.value = true
        com.chencohen.ptt.ui.contacts.ChatHandover.landing.value = com.chencohen.ptt.ui.contacts.ChatHandover.Landing(which, typed.value, System.currentTimeMillis())
        // The whole screen is the window's from here, in one change; what it shows doesn't move by it (placed from the
        // bottom, [Window]).
        val wm = app.getSystemService(WindowManager::class.java)
        p.height = wm.currentWindowMetrics.bounds.height()
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(v, p) }
        Log.i(TAG, "grows into the chat $which")
        morphTo(0f, 1f) {
            phase = Phase.Full
            full.value = true
            launch(app, which)
        }
    }

    /**
     * The floating window's pill grows into the app's chat (TalkBubble's Chat button): from the pill's own place,
     * shape and colour on the screen, to the whole screen, the chat showing through as it grows. False when it can't
     * (no window allowed, or the floating chat is busy): the chat then opens the plain way.
     */
    fun growFrom(context: Context, which: String, left: Int, top: Int, right: Int, bottom: Int, radius: Float, color: Int): Boolean {
        val app = context.applicationContext
        if (view != null || phase != Phase.Floating || !android.provider.Settings.canDrawOverlays(app)) return false
        synchronized(logic) {
            if (logic.shown != null) return false
            logic.arrived(which, true, null, false)
        }
        chat.value = which
        typed.value = ""
        answered = true
        seeThrough.floatValue = 1f
        origin.value = Origin(left, top, right, bottom, radius, color)
        grow.floatValue = 0f
        morphing.value = true
        full.value = false
        phase = Phase.Growing
        val v = addWindow(app, wholeScreen = true)
        if (v == null) {
            phase = Phase.Floating
            morphing.value = false
            origin.value = null
            chat.value = null
            synchronized(logic) { logic.closed() }
            return false
        }
        notifications?.answered(which)
        com.chencohen.ptt.ui.contacts.ChatHandover.landing.value = com.chencohen.ptt.ui.contacts.ChatHandover.Landing(which, "", System.currentTimeMillis())
        Log.i(TAG, "the pill grows into the chat $which")
        // Its first frame is the pill's own shape over the pill: then it grows.
        afterDrawn(v) {
            if (view === v && phase == Phase.Growing) {
                morphTo(0f, 1f) {
                    phase = Phase.Full
                    full.value = true
                    launch(app, which)
                }
            }
        }
        return true
    }

    /**
     * The app opens on [which] under the window, which covers the whole screen and shows the same chat: nothing of
     * the app's coming is seen. When the app says its chat is drawn ([landed]), the window goes.
     */
    private fun launch(app: Context, which: String) {
        com.chencohen.ptt.ui.contacts.ChatHandover.onLanded = { c -> if (c == which) main.post { landed(app) } }
        val started = runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java).setAction(Notifications.ACTION_OPEN).putExtra(Notifications.EXTRA_USER, which)
                    .putExtra(MainActivity.EXTRA_FROM_WINDOW, true)
                    // Over another app: the app has a way in to wait out, and Back from the chat comes back here.
                    .putExtra(MainActivity.EXTRA_OVER_ANOTHER, !MainActivity.onScreen)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                android.app.ActivityOptions.makeCustomAnimation(app, 0, 0).toBundle(),
            )
        }.onFailure { Log.w(TAG, "can't open the chat from the window", it) }.isSuccess
        main.removeCallbacks(landLate)
        landLateApp = app
        // The app never said so (it couldn't open, or is slow): the window goes anyway.
        main.postDelayed(landLate, if (started) 2_500L else 0L)
    }

    private var landLateApp: Context? = null
    private val landLate = Runnable { landLateApp?.let { landed(it) } }

    /** The app's chat is drawn under the window: the window goes (120 ms, so the little that differs dissolves). */
    private fun landed(app: Context) {
        if (phase != Phase.Full) return
        val v = view ?: return
        phase = Phase.Handing
        main.removeCallbacks(landLate)
        com.chencohen.ptt.ui.contacts.ChatHandover.onLanded = null
        setWindowChat(app, null)
        Log.i(TAG, "the app's chat took over")
        v.animate().alpha(0f).setDuration(120).withEndAction { gone(app, v) }.start()
    }

    /**
     * Back from the app's chat to the floating chat (the chat was opened from it, over another app): the window
     * covers the screen showing the same chat, the app leaves under it ([leave]), unseen, and the window shrinks to
     * floating, the app that was in use showing again behind it. False when it can't: Back does what it always did.
     */
    fun shrinkFrom(context: Context, which: String, leave: () -> Unit): Boolean {
        val app = context.applicationContext
        if (view != null || phase != Phase.Floating || !android.provider.Settings.canDrawOverlays(app)) return false
        if (!messagingState(app).value.talkSettings.quickReply) return false
        synchronized(logic) {
            if (logic.shown != null) return false
            logic.arrived(which, true, null, false)
        }
        chat.value = which
        typed.value = ""
        answered = true
        seeThrough.floatValue = 1f
        origin.value = null
        grow.floatValue = 1f
        morphing.value = true
        full.value = true
        phase = Phase.Full
        val v = addWindow(app, wholeScreen = true)
        if (v == null) {
            phase = Phase.Floating
            morphing.value = false
            full.value = false
            grow.floatValue = 0f
            chat.value = null
            synchronized(logic) { logic.closed() }
            return false
        }
        Log.i(TAG, "back to floating from the chat $which")
        afterDrawn(v) {
            if (view !== v || phase != Phase.Full) return@afterDrawn
            com.chencohen.ptt.ui.contacts.ChatHandover.onLeft = { main.post(shrink) }
            runCatching(leave)
            // The app never said it left: shrinks anyway.
            main.postDelayed(shrink, 900)
        }
        return true
    }

    private val shrink = Runnable { shrinkNow() }

    private fun shrinkNow() {
        main.removeCallbacks(shrink)
        com.chencohen.ptt.ui.contacts.ChatHandover.onLeft = null
        if (phase != Phase.Full || view == null) return
        phase = Phase.Shrinking
        full.value = false
        morphTo(1f, 0f) {
            // Floating again: the window is its own size, the rest of the screen the app's.
            val v = view
            val p = layout
            phase = Phase.Floating
            morphing.value = false
            if (v != null && p != null) {
                p.height = restHeight(v.context, 0, TalkBubble.topRoom.intValue)
                runCatching { v.context.getSystemService(WindowManager::class.java).updateViewLayout(v, p) }
            }
        }
    }

    /** What [chat] wrote counts as read now (writing, talking or sending from the window): the receipt goes at once. */
    private fun readNow(context: Context, chat: String) {
        // From here on, what comes in is read at once, and they're told it's in view (Messaging.windowChat).
        setWindowChat(context, chat)
        answered = true
        notifications?.answered(chat)
        CoroutineScope(Dispatchers.Default).launch { runCatching { markReadFromNotification(context, chat) } }
    }

    private fun startTalk(context: Context, chat: String, video: Boolean) {
        readNow(context, chat)
        talking.value = true
        answered = true
        notifications?.answered(chat)
        runCatching {
            context.startForegroundService(Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_TALK_BACK).putExtra(TalkReadyService.EXTRA_CHAT, chat)
                    .putExtra(TalkReadyService.EXTRA_VIDEO, video),
            )
        }.onFailure {
            Log.w(TAG, "can't talk from the window", it)
            talking.value = false
        }
    }

    private fun endTalk(context: Context, send: Boolean) {
        if (!talking.value) return
        talking.value = false
        runCatching {
            context.startService(Intent(context, TalkReadyService::class.java).setAction(if (send) TalkReadyService.ACTION_SEND else TalkReadyService.ACTION_CANCEL_TALK))
        }
    }

    /** The chat itself is asked for (the header, or something only the chat can do): the window grows into it. */
    private fun openChat(context: Context, chat: String) {
        answered = true
        notifications?.answered(chat)
        expand(context, chat)
    }

    /** The floating window's Chat button, while this window shows [which]: as a tap on its header. */
    fun intoChat(context: Context, which: String): Boolean {
        if (view == null || chat.value != which || phase != Phase.Floating) return false
        openChat(context, which)
        return true
    }

    @Composable
    private fun Window(context: Context) {
        val current = chat.value ?: return
        val state by messagingState(context).collectAsStateWithLifecycle()
        var title by remember(current) { mutableStateOf(current) }
        LaunchedEffect(current) { title = runCatching { chatName(context, current) }.getOrDefault(current) }
        val messages = state.conversations[current].orEmpty()
        var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
        // Closes by itself: untouched, nothing typed, nobody talking.
        LaunchedEffect(current) {
            touchedAt = System.currentTimeMillis()
            while (true) {
                delay(1_000)
                if (phase == Phase.Floating && QuickReply.idle(System.currentTimeMillis(), touchedAt, typed.value, talking.value)) {
                    close(context)
                    return@LaunchedEffect
                }
            }
        }
        // A new message counts as a touch: the window stays for it.
        LaunchedEffect(messages.lastOrNull()?.id) { touchedAt = System.currentTimeMillis() }
        // A burst of this phone's ended (the service's): the button is free again.
        val own = state.talk.own
        LaunchedEffect(own?.chat, own?.phase) { if (own == null && talking.value) talking.value = false }
        val group = Group.idOf(current)?.let { id -> state.groups.firstOrNull { it.id == id } }
        val groupTyping = state.typing.filterKeys { it.startsWith("$current/") }
        val typingUntil = if (Group.idOf(current) != null) groupTyping.values.maxOrNull() else state.typing[current]
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(typingUntil, state.drafts[current]?.at) {
            now = System.currentTimeMillis()
            val until = maxOf(typingUntil ?: 0L, (state.drafts[current]?.at ?: 0L) + com.chencohen.ptt.messaging.Draft.FRESH_MS)
            if (until > now) {
                delay(until - now)
                now = System.currentTimeMillis()
            }
        }
        val me = remember { myUser(context) }
        val nameOf: (String) -> String = { u -> state.contacts.firstOrNull { it.user == u }?.name ?: u }
        val canVideo = context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val pushVideo = state.talkSettings.pushVideoFor(current) && canVideo
        var pulled by remember { mutableStateOf(0f) }
        // The keyboard (2026-11-24, hen; a trace showed a window relayout on every frame of its animation, ~6 ms each):
        // the window takes the keyboard's full room once as it starts, and gives it back once it has closed; the
        // chat inside rides up and down with it frame by frame, a clear space above it filling the rest.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(density)
        val imeNow = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        val imeMost = maxOf(
            androidx.compose.foundation.layout.WindowInsets.imeAnimationSource.getBottom(density),
            androidx.compose.foundation.layout.WindowInsets.imeAnimationTarget.getBottom(density),
            imeNow,
        )
        val roomExtra = (imeMost - nav).coerceAtLeast(0)
        val usedExtra = (imeNow - nav).coerceAtLeast(0)
        val topRoom = TalkBubble.topRoom.intValue
        androidx.compose.runtime.SideEffect { growBy(context, roomExtra, topRoom) }
        // The conversation screen itself (2026-11-24, hen: the same design and ways as the chat), with this window's
        // header: a swipe down closes it, the name opens the chat, × closes.
        // Placed from the window's bottom edge at its own height, whatever the window's height is at that moment
        // (2026-11-26, hen's recording at 19.5 s: a spacer for the keyboard's room pushed the chat down a frame before
        // the window had grown). Growing into the app's chat ([grow]), its top edge rises to the screen's and its
        // corners flatten; out of the pill ([origin]), all four edges go, the chat laid out for the whole screen and
        // showing through. Left to right whatever the language.
        val from = origin.value
        val calm = morphing.value
        val ground = LibraryColors.Background
        fun edges(width: Int, height: Int): IntArray {
            val g = grow.floatValue
            return if (from == null) {
                val rest = (baseHeight + usedExtra).coerceAtMost(height)
                intArrayOf(0, height - (rest + (height - rest) * g).toInt(), width, height)
            } else {
                intArrayOf(
                    (from.left * (1f - g)).toInt(), (from.top * (1f - g)).toInt(),
                    (from.right + (width - from.right) * g).toInt(), (from.bottom + (height - from.bottom) * g).toInt(),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val e = edges(constraints.maxWidth, constraints.maxHeight)
                    val placed = measurable.measure(androidx.compose.ui.unit.Constraints.fixed((e[2] - e[0]).coerceAtLeast(0), (e[3] - e[1]).coerceAtLeast(0)))
                    layout(constraints.maxWidth, constraints.maxHeight) { placed.place(e[0], e[1]) }
                }
                .graphicsLayer {
                    val g = grow.floatValue
                    clip = true
                    shape = if (from == null) RoundedCornerShape(topStart = 20.dp * (1f - g), topEnd = 20.dp * (1f - g))
                    else RoundedCornerShape((from.radius * (1f - g)).coerceAtLeast(0f))
                }
                .drawBehind {
                    drawRect(if (from == null) ground.copy(alpha = seeThrough.floatValue) else androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.Color(from.color), ground, (grow.floatValue * 2f).coerceAtMost(1f)))
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            touchedAt = System.currentTimeMillis()
                        }
                    }
                },
        ) {
          Box(
              if (from == null) Modifier.fillMaxSize()
              else Modifier
                  .layout { measurable, constraints ->
                      // The whole screen's chat, still on the screen while the frame around it grows.
                      val screen = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                      val e = edges(screen.width(), screen.height())
                      val placed = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(screen.width(), screen.height()))
                      layout(constraints.maxWidth, constraints.maxHeight) { placed.place(-e[0], -e[1]) }
                  }
                  .graphicsLayer { alpha = ((grow.floatValue - 0.15f) / 0.5f).coerceIn(0f, 1f) },
          ) {
            ConversationScreen(
                user = current,
                contact = state.contacts.firstOrNull { it.user == current },
                messages = messages,
                header = ConversationHeader(typing = (typingUntil ?: 0L) > now, presence = state.presence[current]),
                drafts = liveDrafts(state.drafts, current, now, Group.idOf(current) != null),
                pushVideo = pushVideo,
                presences = state.presence,
                liveEdits = state.liveEdits,
                viewing = viewingIn(state.viewing, current, System.currentTimeMillis(), Group.idOf(current) != null),
                direction = state.talkSettings.directionOf(current),
                colors = state.talkSettings.colorsOf(current).also { com.chencohen.ptt.ui.style.ChatShade.take(state.talkSettings.colorsFor[com.chencohen.ptt.ui.style.ChatShade.KEY]) },
                live = com.chencohen.ptt.ui.contacts.ChatLive(
                    keeps = { m -> m.clip?.let { state.talkSettings.keeps(current, it) } ?: true },
                    // Who is live and waiting for this phone's floor, as in the chat itself.
                    liveWaiting = viewingIn(state.liveWaiting, current, System.currentTimeMillis(), Group.idOf(current) != null),
                    cameraReady = state.talkSettings.cameraReady,
                    // Live pills and videos aren't drawn in this window (2026-11-25, hen: "should appear from the top
                    // of the screen, not in the chat floating window"): the floating window's row at the top has them.
                    // Grown into the chat, it draws them itself, as the app's chat does.
                    liveRow = if (calm) null else { now -> TalkBubble.liveRow(context, current, now) },
                ),
                draftCarets = liveCarets(state.drafts, current, Group.idOf(current) != null),
                warmCamera = { which -> preview(context, which) },
                group = Group.idOf(current)?.let {
                    GroupChat(
                        name = title,
                        picture = group?.picture,
                        members = group?.readers?.map { it.user }?.filter { it != me }.orEmpty(),
                        line = "",
                        note = when {
                            group == null -> context.getString(R.string.group_missing)
                            group.gone -> context.getString(R.string.group_gone)
                            group.isInvitation(me) -> context.getString(R.string.group_invitation_note)
                            !group.isReader(me) -> context.getString(R.string.group_waiting_owner)
                            else -> null
                        },
                    )
                },
                talk = state.talk,
                talkLine = com.chencohen.ptt.ui.talk.talkLine(current, state.talk, nameOf, me),
                actions = ConversationActions(
                    back = { dismiss(context) },
                    openContact = { openChat(context, current) },
                    sendFace = { code, fallback -> readNow(context, current); com.chencohen.ptt.sendFace(context, current, code, fallback) },
                    react = { id, code -> com.chencohen.ptt.react(context, id, code) },
                    send = { body ->
                        typed.value = ""
                        answered = true
                        notifications?.answered(current)
                        CoroutineScope(Dispatchers.Default).launch { runCatching { replyFromNotification(context, current, body) } }
                    },
                    retry = { openChat(context, current) },
                    typing = { on -> sendTyping(context, current, on) },
                    draft = { text, s, e ->
                        typed.value = text
                        sendDraft(context, current, text, s, e)
                    },
                    playClip = { id -> CoroutineScope(Dispatchers.Default).launch { runCatching { playClip(context, id) } } },
                    stopClip = { CoroutineScope(Dispatchers.Default).launch { runCatching { stopTalkPlayback(context) } } },
                    startVoice = { startTalk(context, current, pushVideo) },
                    // A voice message needs the chat (its recording, its send).
                    startVoiceMessage = { openChat(context, current) },
                    finishVoice = { send -> endTalk(context, send) },
                    micDenied = { openChat(context, current) },
                    call = { video -> call(context, current, video) },
                    // "Make it a call": the talk room becomes the call, no ringing, and the chat opens with it;
                    // without a room to turn, a short word (2026-11-25, hen).
                    callInChat = {
                        startRoomCall(context, current, pushVideo) { ok ->
                            if (ok) openChat(context, current)
                            else android.widget.Toast.makeText(context, R.string.talk_call_no_room, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    shareCall = { openChat(context, current) },
                    playKept = { openChat(context, current) },
                    // The same memory as the reply bubble's Voice | Video.
                    setPushVideo = { on -> setTalkSettings(context, state.talkSettings.withVideoFor(current, on)) },
                    // Writing back reads them (2026-11-24, hen); only showing the window doesn't.
                    answering = { readNow(context, current) },
                    edit = { id, body -> editText(context, id, body) },
                    editLive = { id, text, s, e -> editLive(context, id, text, s, e) },
                    delete = { ids, everyone -> deleteMessages(context, ids, everyone) },
                ),
                seeThrough = seeThrough.floatValue,
                embeddedHeader = {
                    WindowHeader(context, current, title, typing = (typingUntil ?: 0L) > now, online = state.presence[current]?.online == true, pulled = { pulled }, pull = { pulled = it })
                },
                // Over the whole screen, or out of the pill: the chat as the app shows it.
                embeddedFull = full.value || from != null,
                embeddedCalm = calm,
            )
          }
        }
        }
    }

    /**
     * The grab bar, the sender (a tap opens the chat; typing or online under the name) and ×. Growing into the app's
     * chat ([grow]) it becomes the chat's header in the same move: it rises under the status bar, the grab bar and ×
     * go, Back comes in before the face, and the face and the name land where the chat has them.
     */
    @Composable
    private fun WindowHeader(context: Context, current: String, title: String, typing: Boolean, online: Boolean, pulled: () -> Float, pull: (Float) -> Unit) {
        val g = grow.floatValue
        val density = androidx.compose.ui.platform.LocalDensity.current
        val status = with(density) { androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density).toDp() }
        fun mix(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp) = a + (b - a) * g
        Column(
            Modifier.fillMaxWidth().padding(top = mix(8.dp, status)).pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        drag(context, pulled(), done = true)
                        pull(0f)
                    },
                    onDragCancel = {
                        drag(context, 0f, done = true)
                        pull(0f)
                    },
                ) { _, dy ->
                    pull((pulled() + dy).coerceAtLeast(0f))
                    drag(context, pulled(), done = false)
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(width = 36.dp, height = 4.dp * (1f - g)).graphicsLayer { alpha = 1f - g }.background(LibraryColors.Muted.copy(alpha = 0.4f), CircleShape))
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp * (1f - g)).height(mix(44.dp, 48.dp)).padding(start = mix(12.dp, 4.dp), end = mix(12.dp, 16.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back, as in the chat's header: it takes its room as the window grows.
                Box(Modifier.size(width = 48.dp * g, height = 44.dp).clipToBounds().graphicsLayer { alpha = g }, contentAlignment = Alignment.Center) {
                    if (g > 0f) Icon(LibraryIcons.Back, null, tint = LibraryColors.OnBackground, modifier = Modifier.requiredSize(24.dp))
                }
                Row(
                    Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = stringResource(R.string.quick_reply_open)) { openChat(context, current) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(mix(36.dp, 40.dp)), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize(36.dp).graphicsLayer { val s = 1f - g * (4f / 36f); scaleX = s; scaleY = s }) { Avatar(current, size = 36.dp, name = title) }
                    }
                    Column(Modifier.padding(start = mix(10.dp, 8.dp))) {
                        Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                        androidx.compose.animation.AnimatedVisibility(typing || online) {
                            Text(
                                if (typing) Gendered.string(context, R.string.chat_typing, current) else stringResource(R.string.chat_online),
                                color = LibraryColors.Accent,
                                style = LibraryType.Caption,
                                // The chat's header says only "typing…": "online" goes as the window grows.
                                modifier = Modifier.graphicsLayer { alpha = if (typing) 1f else 1f - g },
                            )
                        }
                    }
                }
                Box(
                    Modifier.size(width = 44.dp * (1f - g), height = 44.dp).clipToBounds().graphicsLayer { alpha = 1f - g }
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.quick_reply_close)) { dismiss(context) },
                    contentAlignment = Alignment.Center,
                ) { Icon(LibraryIcons.Close, stringResource(R.string.quick_reply_close), tint = LibraryColors.Muted, modifier = Modifier.requiredSize(24.dp)) }
            }
        }
    }

    /** Push-to-video from the window: the camera kept open (Talk's service) while its chat shows; null lets it go. */
    private fun preview(context: Context, which: String?) {
        val target = which ?: chat.value ?: return
        runCatching {
            context.startForegroundService(
                Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_PREVIEW)
                    .putExtra(TalkReadyService.EXTRA_CHAT, target).putExtra(TalkReadyService.EXTRA_VIDEO, which != null),
            )
        }.onFailure { Log.w(TAG, "no camera preview", it) }
    }

    private fun call(context: Context, chat: String, video: Boolean) {
        answered = true
        notifications?.answered(chat)
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_CALL).putExtra(Notifications.EXTRA_USER, chat)
                    .putExtra(MainActivity.EXTRA_VIDEO, video)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        close(context)
    }

    /** A lifecycle for Compose in a window of its own (no activity). */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        init {
            saved.performRestore(null)
        }

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
