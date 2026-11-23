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
import com.chencohen.ptt.ui.contacts.Avatar
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
 * bottom of the screen, 40% of its height: the sender (a tap opens the chat), the last messages, a field to type a
 * reply (Send, end to end like any text) and, while it is empty, a button to hold and talk to them (let go to send,
 * slide away to cancel). A swipe down or × closes it; untouched and empty for 20 s it closes by itself. Which
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

    /** A message came into [chat] (Notifications): shows, updates or queues the window. */
    fun arrived(context: Context, chat: String) = main.post {
        val app = context.applicationContext
        val on = messagingState(app).value.talkSettings.quickReply && android.provider.Settings.canDrawOverlays(app) &&
            app.getSystemService(android.os.PowerManager::class.java).isInteractive
        val busy = typed.value.isNotBlank() || talking.value
        when (val step = logic.arrived(chat, on, chatInFront(app), busy)) {
            is QuickReply.Step.Show -> show(app, step.chat)
            else -> Log.i(TAG, "$chat: $step")
        }
    }

    private fun show(context: Context, which: String) {
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
        frame.addView(compose)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (metrics.bounds.height() * 0.4f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            title = "Talk quick reply"
        }
        try {
            wm.addView(frame, params)
            view = frame
            o.start()
            Log.i(TAG, "shown for $which")
        } catch (e: Exception) {
            Log.w(TAG, "can't show the quick-reply window", e)
            o.stop()
            owner = null
            logic.closed()
        }
    }

    /** Closes the window; the next conversation waiting shows. */
    fun close(context: Context) = main.post {
        val app = context.applicationContext
        if (talking.value) endTalk(app, send = false)
        view?.let { v -> runCatching { app.getSystemService(WindowManager::class.java).removeView(v) } }
        view = null
        owner?.stop()
        owner = null
        chat.value = null
        typed.value = ""
        logic.closed(chatInFront(app))?.let { show(app, it) }
    }

    private fun startTalk(context: Context, chat: String) {
        talking.value = true
        runCatching {
            context.startForegroundService(Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_TALK_BACK).putExtra(TalkReadyService.EXTRA_CHAT, chat))
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
        val messages = remember(current, state) { recentMessages(context, current) }
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
        var pulled by remember { mutableStateOf(0f) }
        Column(
            Modifier.fillMaxSize()
                .background(LibraryColors.Background, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            touchedAt = System.currentTimeMillis()
                        }
                    }
                }
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        ) {
            // The grab bar and the header: a swipe down closes it; the name opens the chat.
            Column(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (pulled > 120f) close(context)
                            pulled = 0f
                        },
                    ) { _, dy -> pulled = (pulled + dy).coerceAtLeast(0f) }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(LibraryColors.Muted.copy(alpha = 0.4f), CircleShape))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = stringResource(R.string.quick_reply_open)) { openChat(context, current) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(title, size = 36.dp)
                        Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle, modifier = Modifier.padding(start = 10.dp))
                    }
                    Box(
                        Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.quick_reply_close)) { close(context) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(LibraryIcons.Close, stringResource(R.string.quick_reply_close), tint = LibraryColors.Muted) }
                }
            }
            val list = rememberLazyListState()
            LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.scrollToItem(0) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
                state = list,
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { m -> Line(context, m) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed.value,
                    onValueChange = { typed.value = it.take(4_000) },
                    placeholder = { Text(stringResource(R.string.quick_reply_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                Spacer(Modifier.size(8.dp))
                if (typed.value.isNotBlank()) {
                    Box(
                        Modifier.size(52.dp).background(LibraryColors.Accent, CircleShape).clickable(role = Role.Button, onClickLabel = stringResource(R.string.quick_reply_send)) {
                            val body = typed.value.trim()
                            typed.value = ""
                            CoroutineScope(Dispatchers.Default).launch { runCatching { replyFromNotification(context, current, body) } }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(LibraryIcons.ArrowUp, stringResource(R.string.quick_reply_send), tint = LibraryColors.OnInverse) }
                } else {
                    // Hold to talk to them; let go to send; slide away (up or aside) to cancel.
                    var away by remember { mutableStateOf(false) }
                    Box(
                        Modifier.size(52.dp).background(if (talking.value) LibraryColors.Accent else LibraryColors.Inverse, CircleShape)
                            .pointerInput(current) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent()
                                        if (!down.changes.any { it.pressed }) continue
                                        val start = down.changes.first().position
                                        away = false
                                        startTalk(context, current)
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            val c = e.changes.firstOrNull() ?: break
                                            val d = c.position - start
                                            away = d.getDistance() > 160f
                                            if (!c.pressed) break
                                        }
                                        endTalk(context, send = !away)
                                        away = false
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (away) LibraryIcons.Close else LibraryIcons.Mic,
                            stringResource(R.string.quick_reply_talk),
                            tint = LibraryColors.OnInverse,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Line(context: Context, m: TextMessage) {
        val text = when {
            m.call != null -> context.getString(if (m.call!!.video) R.string.call_video else R.string.call_voice)
            m.clip?.kind == ClipKind.VOICE -> context.getString(R.string.chats_voice)
            m.clip?.kind == ClipKind.BURST -> context.getString(R.string.chats_burst)
            else -> m.body
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = if (m.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .background(if (m.outgoing) LibraryColors.Inverse else LibraryColors.Raised, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                if (!m.outgoing && m.author != null) Text(m.author!!, color = LibraryColors.Accent, style = LibraryType.Small)
                Text(text, color = if (m.outgoing) LibraryColors.OnInverse else LibraryColors.OnBackground, style = LibraryType.Body)
            }
        }
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
