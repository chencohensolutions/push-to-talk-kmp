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
    private var view: FrameLayout? = null
    private var owner: Owner? = null
    private val chat = mutableStateOf<String?>(null)
    private val typed = mutableStateOf("")
    private val talking = mutableStateOf(false)
    /** How solid the window is but for an incoming video (OverlayArrival: 0.5 for its first second). */
    private val seeThrough = androidx.compose.runtime.mutableFloatStateOf(1f)

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
        val setting = messagingState(app).value.talkSettings.quickReply
        val overlay = android.provider.Settings.canDrawOverlays(app)
        val screenOn = app.getSystemService(android.os.PowerManager::class.java).isInteractive
        val inFront = chatInFront(app)
        val busy = typed.value.isNotBlank() || talking.value
        val step = synchronized(logic) { logic.arrived(chat, setting && overlay && screenOn, inFront, busy) }
        Log.i(TAG, "quick reply for $chat: $step (setting $setting, over other apps $overlay, screen on $screenOn, open in front $inFront, busy $busy)")
        if (step is QuickReply.Step.Show) main.post { show(app, step.chat) }
        if (step is QuickReply.Step.Update) main.post { answered = false }
        return step
    }

    /** The window is open on [chat] now (TalkBubble: no bottom pill over it; the window tells the live row itself). */
    fun showing(chat: String): Boolean = view != null && this.chat.value == chat

    /** Debug builds: as [takes] for a message from [chat]. */
    fun arrived(context: Context, chat: String) {
        takes(context, chat)
    }

    private fun show(context: Context, which: String) {
        // Switching away from one left unanswered: its notification, silent.
        chat.value?.takeIf { it != which && !answered }?.let { notifications?.unanswered(it) }
        if (chat.value != which) setWindowChat(context, null)
        answered = false
        typed.value = ""
        chat.value = which
        if (view != null) return
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = wm.currentWindowMetrics
        val o = Owner().also { owner = it }
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent { TalkTheme { Window(context) } }
        }
        // Back closes it (the window takes keys while its field is in use).
        val frame = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    close(context)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        // Compose looks for its lifecycle from the window's root view: set on the ComposeView alone, it crashed the
        // app as the window attached (2026-11-23, hen: "the window doesn't appear").
        frame.setViewTreeLifecycleOwner(o)
        frame.setViewTreeSavedStateRegistryOwner(o)
        frame.setViewTreeViewModelStoreOwner(o)
        frame.addView(compose)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (metrics.bounds.height() * 0.4f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            // Above the keyboard while it shows (2026-11-23, hen): the window's frame keeps clear of it, moving up with
            // it and back down; with too little room the window gets shorter and the message list gives way.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            // 2026-11-24 (hen): not fitted by the system (that moved it only once the keyboard was up); the window
            // grows by the keyboard's height as it animates and its content keeps clear of it (imePadding).
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            title = "Talk quick reply"
        }
        baseHeight = params.height
        layout = params
        try {
            wm.addView(frame, params)
            view = frame
            o.start()
            // Slides up and fades in, gently, untouchable for 1 s (OverlayArrival); the keyboard waits for a tap.
            OverlayArrival.arrive(wm, frame, params, frame, params.height.toFloat()) { a -> seeThrough.floatValue = a }
            Log.i(TAG, "shown for $which")
        } catch (e: Exception) {
            Log.w(TAG, "can't show the quick-reply window", e)
            o.stop()
            owner = null
            logic.closed()
        }
    }

    /** Closes the window, sliding it down off the screen; the next conversation waiting shows. */
    fun close(context: Context) = main.post {
        val app = context.applicationContext
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
            .withEndAction {
                runCatching { app.getSystemService(WindowManager::class.java).removeView(v) }
                owner?.stop()
                owner = null
                chat.value = null
                typed.value = ""
                answered = false
                synchronized(logic) { logic.closed(chatInFront(app)) }?.let { show(app, it) }
            }.start()
    }

    /** Follows a swipe down by [dy] pixels so far; [done]: let go (slides out past a third of its height, else back). */
    private fun drag(context: Context, dy: Float, done: Boolean) {
        val v = view ?: return
        if (!done) {
            v.translationY = dy.coerceAtLeast(0f)
        } else if (dy > v.height / 3f) {
            close(context)
        } else {
            v.animate().translationY(0f).setDuration(SLIDE_MS).start()
        }
    }

    private const val SLIDE_MS = 250L
    private var baseHeight = 0
    private var layout: WindowManager.LayoutParams? = null

    /**
     * The keyboard is [extra] pixels above the window's bottom room now: the window grows by that, upward, but stays
     * below the live row at the top of the screen ([topRoom]: its bottom edge, 0 without one; 2026-11-25, hen: the
     * pills and videos show there, not in this window).
     */
    private fun growBy(context: Context, extra: Int, topRoom: Int) {
        val v = view ?: return
        val p = layout ?: return
        val screen = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
        val height = (baseHeight + extra.coerceAtLeast(0)).coerceAtMost((screen - topRoom).coerceAtLeast(baseHeight / 2))
        if (p.height == height) return
        p.height = height
        runCatching { context.getSystemService(WindowManager::class.java).updateViewLayout(v, p) }
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

    private fun openChat(context: Context, chat: String) {
        answered = true
        notifications?.answered(chat)
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).setAction(Notifications.ACTION_OPEN).putExtra(Notifications.EXTRA_USER, chat)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        close(context)
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
                if (QuickReply.idle(System.currentTimeMillis(), touchedAt, typed.value, talking.value)) {
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
        Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(with(density) { (roomExtra - usedExtra).toDp() }))
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(LibraryColors.Background.copy(alpha = seeThrough.floatValue))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            touchedAt = System.currentTimeMillis()
                        }
                    }
                },
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
                live = com.chencohen.ptt.ui.contacts.ChatLive(
                    keeps = { m -> m.clip?.let { state.talkSettings.keeps(current, it) } ?: true },
                    // Who is live and waiting for this phone's floor, as in the chat itself.
                    liveWaiting = viewingIn(state.liveWaiting, current, System.currentTimeMillis(), Group.idOf(current) != null),
                    cameraReady = state.talkSettings.cameraReady,
                    // Live pills and videos aren't drawn in this window (2026-11-25, hen: "should appear from the top
                    // of the screen, not in the chat floating window"): the floating window's row at the top has them.
                    liveRow = { now -> TalkBubble.liveRow(context, current, now) },
                ),
                draftCarets = liveCarets(state.drafts, current, Group.idOf(current) != null),
                warmCamera = { which -> preview(context, which) },
                group = Group.idOf(current)?.let {
                    GroupChat(
                        name = title,
                        picture = group?.picture,
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
                    back = { close(context) },
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
            )
        }
        }
    }

    /** The grab bar, the sender (a tap opens the chat; typing or online under the name) and ×. */
    @Composable
    private fun WindowHeader(context: Context, current: String, title: String, typing: Boolean, online: Boolean, pulled: () -> Float, pull: (Float) -> Unit) {
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).pointerInput(Unit) {
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
            Box(Modifier.size(width = 36.dp, height = 4.dp).background(LibraryColors.Muted.copy(alpha = 0.4f), CircleShape))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = stringResource(R.string.quick_reply_open)) { openChat(context, current) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(current, size = 36.dp, name = title)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                        androidx.compose.animation.AnimatedVisibility(typing || online) {
                            Text(
                                if (typing) Gendered.string(context, R.string.chat_typing, current) else stringResource(R.string.chat_online),
                                color = LibraryColors.Accent,
                                style = LibraryType.Caption,
                            )
                        }
                    }
                }
                Box(
                    Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.quick_reply_close)) { close(context) },
                    contentAlignment = Alignment.Center,
                ) { Icon(LibraryIcons.Close, stringResource(R.string.quick_reply_close), tint = LibraryColors.Muted) }
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
