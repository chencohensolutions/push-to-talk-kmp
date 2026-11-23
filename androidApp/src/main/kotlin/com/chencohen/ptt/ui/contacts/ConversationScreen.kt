package com.chencohen.ptt.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import com.chencohen.ptt.messaging.ReadMarks
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imeAnimationSource
import com.chencohen.ptt.talk.TalkPhase
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.MessageStatus
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.messaging.UserPresence
import com.chencohen.ptt.talk.NowPlaying
import com.chencohen.ptt.talk.TalkUiState
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.talk.ClipContent
import com.chencohen.ptt.ui.talk.VoiceButton
import com.chencohen.ptt.ui.style.HomeCard
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryShapes
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.PrimaryButton
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class ConversationActions(
    val back: () -> Unit = {},
    /** The header: the contact's page (safety number, block, remove). */
    val openContact: () -> Unit = {},
    val send: (String) -> Unit = {},
    val retry: (String) -> Unit = {},
    /** Typing started (true) or stopped; already throttled here. */
    val typing: (Boolean) -> Unit = {},
    /** Live typing (the setting): the line now, as written ("" when sent or emptied). */
    val draft: (String) -> Unit = {},
    /** The Talk button in the header: Talk mode. */
    val openTalk: () -> Unit = {},
    val playClip: (String) -> Unit = {},
    /** Save as file (a long press on a burst or voice message). */
    val saveClip: ((String) -> Unit)? = null,
    val stopClip: () -> Unit = {},
    /** The composer's microphone went down (a voice message) or up ([send] false: cancelled). */
    val startVoice: () -> Unit = {},
    /** The Voice message button: a voice message starts recording (no floor); [finishVoice] sends or drops it. */
    val startVoiceMessage: () -> Unit = {},
    /** A video burst's bubble tapped: it plays full screen over the chat. */
    val playVideo: (String) -> Unit = {},
    val finishVoice: (send: Boolean) -> Unit = {},
    /** The microphone wasn't allowed. */
    val micDenied: () -> Unit = {},
    /** The call buttons in the header, and a call's entry in the chat (phase 6). */
    val call: (video: Boolean) -> Unit = {},
    /** Share a saved call (Save calls). */
    val shareCall: (String) -> Unit = {},
    /** A call the server kept: play it. */
    val playKept: (com.chencohen.ptt.messaging.TextMessage) -> Unit = {},
    /** My sent text [id] edited to the new text (2026-11-24, hen). */
    val edit: (id: String, body: String) -> Unit = { _, _ -> },
    /** Editing [id] live: the text as it stands (null: cancelled). */
    val editLive: (id: String, text: String?) -> Unit = { _, _ -> },
    /** Writing started (the draft opened): what they wrote counts as read (the quick-reply window). */
    val answering: () -> Unit = {},
    /** Push-to-talk or push-to-video for this chat (remembered per contact); null: no switch. */
    val setPushVideo: ((Boolean) -> Unit)? = null,
)

/** What the header says under the name: typing, online, last seen, or nothing known. */
data class ConversationHeader(val typing: Boolean, val presence: UserPresence?)

/**
 * A group's chat instead of a contact's: its [name] and [picture] in the header, [line] under it (who is typing, or
 * how many members), and [note] in place of the line to write in when this user can't write (an invitation, gone).
 */
data class GroupChat(val name: String, val picture: String?, val line: String, val note: String?)

/**
 * A conversation (docs/screens/conversation.md), Signal's: the contact in the header (a tap opens their page), the
 * texts in bubbles with when and how far each of ours got, a changed safety number on top, and the line to write in.
 */
@Composable
internal fun ConversationScreen(
    user: String,
    contact: Contact?,
    messages: List<TextMessage>,
    header: ConversationHeader,
    actions: ConversationActions,
    group: GroupChat? = null,
    talk: TalkUiState = TalkUiState(),
    /** What the Talk button's line says ("Dana is talking"); null hides it. */
    talkLine: String? = null,
    /** Others writing live (their Live typing): who (null in a one-to-one chat) and what, as it is written. */
    drafts: List<Pair<String?, String>> = emptyList(),
    /** This chat's Voice | Video is Video: a press sends the camera too, so it is kept open here. */
    pushVideo: Boolean = false,
    warmCamera: (String?) -> Unit = {},
    /** Everyone's presence: a member who hides theirs shows no read circle. */
    presences: Map<String, UserPresence> = emptyMap(),
    /**
     * The quick-reply window (2026-11-24, hen: the same chat there): its own header in place of this one, and no
     * system bar or keyboard room (the window keeps clear of them itself).
     */
    embeddedHeader: (@Composable () -> Unit)? = null,
    /** The window's arrival (OverlayArrival): how solid all but a playing video is (0.5, then 1). */
    seeThrough: Float = 1f,
    /** Their texts being edited live (MessagingState.liveEdits). */
    liveEdits: Map<String, com.chencohen.ptt.messaging.LiveEdit> = emptyMap(),
) {
    // A video burst tapped in the chat plays full screen over it (2026-11-23, hen); Back or a tap closes it.
    var fullVideo by remember(user) { mutableStateOf<String?>(null) }
    val chatActions = actions
    val actions = ConversationActionsWith(chatActions) { id ->
        fullVideo = id
        chatActions.playClip(id)
    }
    // Send (2026-11-24, hen): the pinned draft flies into its place at the end of the chat.
    val flight = remember(user) { SendFlight() }
    // Editing one of my sent texts (2026-11-24, hen): a double tap on it.
    var editing by remember(user) { mutableStateOf<Editing?>(null) }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { flight.origin = it.positionInWindow() }.pointerInput(Unit) {
            // Every touch counts (the camera's idle time).
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (e.changes.any { it.pressed && !it.previousPressed }) flight.touches++
                }
            }
        },
    ) {
    Column(
        Modifier.fillMaxSize().background(LibraryColors.Background.copy(alpha = seeThrough))
            // The keyboard's room follows its animation frame by frame (2026-11-24, hen); the window grows with it.
            .then(if (embeddedHeader != null) Modifier.navigationBarsPadding().imePadding() else Modifier.statusBarsPadding().navigationBarsPadding().imePadding()),
    ) {
        val canTalk = if (group != null) group.note == null else contact?.paired == true && !contact.blocked
        when {
            embeddedHeader != null -> Box(Modifier.graphicsLayer { alpha = seeThrough }) { embeddedHeader() }
            group != null -> GroupHeader(group, actions, canTalk)
            else -> Header(user, contact, header, actions, canTalk)
        }
        if (talkLine != null && (talk.talkers[user] != null || talk.own?.chat == user)) {
            Text(
                talkLine,
                color = LibraryColors.Accent,
                style = LibraryType.Caption,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        if (contact?.changedKey != null) {
            HomeCard(
                title = stringResource(R.string.chat_key_changed, contact.user),
                body = stringResource(R.string.chat_key_changed_body),
                action = stringResource(R.string.chat_key_changed_action),
                onAction = actions.openContact,
                warning = true,
            )
        }
        // The newest is item 0 (the list is upside down). At the bottom (or one item from it) a new message scrolls into
        // view; scrolled up, a "New message" chip shows instead; what this phone sends always scrolls (2026-11-22, hen).
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var unseen by androidx.compose.runtime.remember(user) { androidx.compose.runtime.mutableStateOf(false) }
        val newest = messages.lastOrNull()
        // Their live drafts and the messages they became share one item (one key), so a draft turns into its message
        // in place (2026-11-24, hen).
        val live = remember(user) { LiveKeys(newest?.id) }
        live.observe(drafts, newest)
        androidx.compose.runtime.SideEffect { live.seen(drafts) }
        val shownDrafts = live.shown(drafts)
        LaunchedEffect(newest?.id) {
            if (newest == null) return@LaunchedEffect
            // A draft that became this message is in its place already: nothing scrolls.
            if (newest.id in live.morphed) return@LaunchedEffect
            if (newest.outgoing || list.firstVisibleItemIndex <= 1) {
                list.glideToEnd()
                unseen = false
            } else {
                unseen = true
            }
        }
        val atBottom by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex == 0 } }
        // How far they read: their circle after the last message of mine they read (one-to-one; not when they hide
        // their presence).
        val reader = remember(messages, header.presence, group != null) { if (group == null && ReadMarks.showsReader(header.presence)) ReadMarks.lastRead(messages) else null }
        // In a group: each member's circle under the last message of mine they read (2026-11-23, hen).
        val groupReaders = remember(messages, presences, group != null) { if (group != null) ReadMarks.groupReaders(messages, presences.filterValues { !ReadMarks.showsReader(it) }.keys) else emptyMap() }
        // Writing in the chat (2026-11-23, hen): the draft bubble at the end of the list.
        val canWrite = if (group != null) group.note == null else contact != null && !contact.blocked && contact.paired
        val draft = remember(user) { ChatDraft() }
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        // The message sent from the draft: its slot is kept, unseen, while the draft flies into it.
        flight.claim(newest)
        val sentMorph = flight.id
        LaunchedEffect(flight.body) {
            if (flight.body == null) return@LaunchedEffect
            // Its slot measured (the message is in the list), or, after a second without, no flight.
            val started = System.currentTimeMillis()
            while (flight.to == null && System.currentTimeMillis() - started < 1_000) delay(16)
            if (flight.to != null) {
                flight.progress.snapTo(0f)
                flight.progress.animateTo(1f, androidx.compose.animation.core.tween(FLIGHT_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
            // The real message and no overlay, in the same frame.
            flight.land()
        }
        // Leaving the chat ends a live draft (it fades on the other phone).
        androidx.compose.runtime.DisposableEffect(user) { onDispose { actions.draft("") } }
        // Push-to-video from the chat (2026-11-23, hen): the camera kept open while the chat shows, so a press sends
        // it at once; what this phone sends shows live in its bubble at the end of the chat, and stays as the video.
        // Released after a minute untouched (2026-11-24, hen), opened again at the next touch.
        var cameraIdle by remember(user) { mutableStateOf(false) }
        LaunchedEffect(flight.touches, pushVideo) {
            cameraIdle = false
            delay(60_000)
            cameraIdle = true
        }
        com.chencohen.ptt.ui.talk.WarmCamera(user, pushVideo && canWrite && !cameraIdle, warmCamera)
        val sendingVideo = talk.own?.takeIf { it.chat == user && pushVideo && (it.phase == TalkPhase.TALKING || it.phase == TalkPhase.REQUESTING || it.phase == TalkPhase.WAITING) }
        val recordingVoice = talk.own?.takeIf { it.chat == user && it.phase == TalkPhase.VOICE } != null
        val talkingHere = talk.own?.takeIf { it.chat == user && (it.phase == TalkPhase.TALKING || it.phase == TalkPhase.REQUESTING) } != null
        // A draft that grows (a new line) stays in view, if the list was at the bottom (2026-11-23, hen).
        // Their draft counts as a new message for scrolling: appearing or growing at the bottom, the list glides to it.
        val draftText = shownDrafts.joinToString("\n") { it.second }
        LaunchedEffect(draftText) { if (draftText.isNotEmpty() && list.firstVisibleItemIndex <= 1) list.glideToEnd() }
        LaunchedEffect(atBottom) { if (atBottom) unseen = false }
        val pairs = remember(messages, talk.own?.clip, talk.playing?.clip) { ReadMarks.overlappingVideos(messages, System.currentTimeMillis()) }
        val pairedEarlier = remember(pairs) { pairs.values.toSet() }
        val byId = remember(messages) { messages.associateBy { it.id } }
        val ticks = remember(messages, group != null) { if (group != null) messages.associate { it.id to ReadMarks.groupTick(messages, it) } else ReadMarks.ticks(messages) }
        // The draft and the buttons float over the chat (2026-11-23, hen: no band behind them): the list scrolls
        // under them to the bottom of the screen, with room at its end so its last message clears them.
        val density = androidx.compose.ui.platform.LocalDensity.current
        var floatingHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        // As measured, every frame (no animation of its own: it moves with the keyboard and the row).
        val floatingRoom = with(density) { floatingHeight.toDp() }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    // A tap on the chat puts the keyboard away (the draft stays).
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        if (draft.text.isEmpty()) draft.editing = false
                        // A tap outside the edit cancels it (it turns back unchanged).
                        editing?.let { it.leaving = true }
                    })
                },
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp + floatingRoom),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Every change eases in and out (2026-11-23, hen: "everything with a transition, no sharp changes").
                val ease = androidx.compose.animation.core.tween<Float>(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                // While a bubble changes its size in place (an edit, Send's flight, a live draft growing), the others follow
                // it in the same frame: no placement animation of their own trailing it (2026-11-24, hen: "don't make
                // the surrounding objects move with delay"). Otherwise (one added or gone) they glide.
                val morphing = editing != null || flight.body != null || shownDrafts.isNotEmpty() || liveEdits.isNotEmpty()
                val move = if (morphing) null else androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                // Both kept pushing at once: a video call may suit them (2026-11-23, hen).
                if (talk.suggestCall == user) {
                    item(key = "suggest-call") {
                        Box(Modifier.animateItem(ease, move, ease).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.chat_suggest_call),
                                color = LibraryColors.OnBackground, style = LibraryType.Small,
                                modifier = Modifier.background(LibraryColors.Raised, androidx.compose.foundation.shape.CircleShape)
                                    .clickable(role = Role.Button) { actions.call(true) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                // What this phone sends with its camera, before its message is there: the same key, so it stays in place.
                if (sendingVideo != null && messages.none { it.id == sendingVideo.clip }) {
                    item(key = sendingVideo.clip) {
                        Box(Modifier.animateItem(ease, move, ease)) {
                            LiveVideoBubble(mine = true, startedAt = sendingVideo.startedAt, waitingFor = sendingVideo.busyWith?.takeIf { sendingVideo.phase == TalkPhase.WAITING })
                        }
                    }
                }
                // Their draft, the message it became, and (one-to-one) their circle at the very end: one item each, so
                // each change morphs in place: the circle grows into the draft, the draft fills into the message.
                fun liveItem(key: String, line: LiveLine) = item(key = key) {
                    Box(Modifier.animateItem(ease, move, ease).graphicsLayer { alpha = seeThrough }) { LiveLineView(line, if (group == null) user else null) }
                }
                val circleAtEnd = reader != null && ReadMarks.atEnd(messages)
                if (group == null) {
                    val oneDraft = shownDrafts.firstOrNull()
                    if (oneDraft != null) liveItem(live.key(null), LiveLine.Draft(null, oneDraft.second))
                    else if (circleAtEnd) liveItem(live.key(null), LiveLine.Circle(reader?.readAt))
                } else {
                    shownDrafts.forEach { (who, text) -> liveItem(live.key(who), LiveLine.Draft(who, text)) }
                }
                // The messages, and their circle right after the last one of mine they read (it glides down as they read).
                // Two videos at once, one each side: side by side, the later one lower by when it started.
                messages.asReversed().forEach { message ->
                    val earlier = pairs[message.id]?.let { byId[it] }
                    if (earlier != null) {
                        item(key = "pair@" + earlier.id) {
                            Box(Modifier.animateItem(ease, move, ease).graphicsLayer { alpha = seeThrough }) { VideoPair(earlier, message) { m -> Bubble(m, actions, talk.playing, live = talk.talkers[user]?.clip == m.id || talk.own?.clip == m.id, showAuthor = group != null, tick = ticks[m.id] ?: ReadMarks.Tick.NONE, compact = true) } }
                        }
                        return@forEach
                    }
                    if (message.id in pairedEarlier) return@forEach
                    if (reader != null && message.id == reader.id && !circleAtEnd) {
                        item(key = "reader") { Box(Modifier.animateItem(ease, move, ease).graphicsLayer { alpha = seeThrough }) { ReaderCircle(user, reader.readAt, end = true) } }
                    }
                    groupReaders[message.id]?.let { readers ->
                        item(key = "readers@" + message.id) { Box(Modifier.animateItem(ease, move, ease).graphicsLayer { alpha = seeThrough }) { GroupReaders(readers) } }
                    }
                    val liveKey = live.morphed[message.id]
                    if (liveKey != null) {
                        liveItem(liveKey, LiveLine.Message(message, if (group != null) message.author else null, live.lastText[message.id]))
                        return@forEach
                    }
                    item(key = message.id) {
                        Box(Modifier.animateItem(ease, move, ease)) {
                            val edit = editing
                            if (edit != null && edit.id == message.id) {
                                EditBubble(
                                    edit,
                                    change = { v ->
                                        edit.value = v
                                        actions.editLive(message.id, v.text)
                                    },
                                    done = { save ->
                                        if (save) actions.edit(message.id, edit.value.text) else actions.editLive(message.id, null)
                                    },
                                    gone = { editing = null },
                                )
                            } else if (message.id == sentMorph) {
                                Bubble(
                                    message, actions, talk.playing, tick = ticks[message.id] ?: ReadMarks.Tick.NONE,
                                    bubbleModifier = Modifier.onGloballyPositioned { flight.to = it.boundsInWindow() }.graphicsLayer { alpha = 0f },
                                )
                            } else {
                                // Live video in the bubble: mine while I send it, theirs while it plays (not under the
                                // full-screen player).
                                val liveVideo = message.clip?.video == true && fullVideo == null && (
                                    (message.outgoing && talk.own?.clip == message.id && talk.own?.phase == TalkPhase.TALKING) ||
                                        (!message.outgoing && talk.playing?.clip == message.id && talk.playing?.video == true)
                                    )
                                // A playing video stays solid while the window arrives (never drawn see-through).
                                Bubble(
                                    message, actions, talk.playing, live = talk.talkers[user]?.clip == message.id || talk.own?.clip == message.id, showAuthor = group != null, tick = ticks[message.id] ?: ReadMarks.Tick.NONE, liveVideo = liveVideo, liveSince = talk.own?.takeIf { it.clip == message.id }?.startedAt,
                                    bubbleModifier = if (liveVideo) Modifier else Modifier.graphicsLayer { alpha = seeThrough },
                                    liveEdit = liveEdits[message.id]?.takeIf { System.currentTimeMillis() - it.at < com.chencohen.ptt.messaging.Draft.FRESH_MS },
                                    startEdit = if (message.outgoing && message.clip == null && message.call == null && canWrite && editing == null) { offset, width -> editing = Editing(message.id, message.body, offset, width) } else null,
                                )
                            }
                        }
                    }
                }
            }
            if (unseen) {
                Text(
                    stringResource(R.string.chat_new_message),
                    color = LibraryColors.OnInverse,
                    style = LibraryType.Small,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                        .background(LibraryColors.Inverse, androidx.compose.foundation.shape.CircleShape)
                        .clickable(role = Role.Button) { scope.launch { list.animateScrollToItem(0) } }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            // Floating at the bottom: this phone's draft (or a voice message's recording), then the buttons.
            // How far the keyboard is open, 0 to 1, as it animates: the button row folds away by the same amount.
            val imeNow = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            val imeFull = maxOf(
                androidx.compose.foundation.layout.WindowInsets.imeAnimationSource.getBottom(density),
                androidx.compose.foundation.layout.WindowInsets.imeAnimationTarget.getBottom(density),
                imeNow,
            )
            val rowShown = if (imeFull > 0) 1f - (imeNow.toFloat() / imeFull).coerceIn(0f, 1f) else 1f
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { floatingHeight = it.height }.graphicsLayer { alpha = seeThrough }) {
                if (canWrite) {
                    // Its place comes straight from the keyboard's animated insets (the column's imePadding): no size or
                    // position animation of its own, so it rises and sinks with the keyboard, frame by frame.
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        androidx.compose.animation.AnimatedContent(
                            recordingVoice,
                            transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)).togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))) },
                            label = "draft or recording",
                        ) { recording ->
                            if (recording) RecordingStrip { actions.finishVoice(false) } else PinnedDraft(draft, focus, actions) { body, from -> flight.start(body, from, newest?.id) }
                        }
                    }
                }
                // With the keyboard up only the chat and the draft (hen: "don't show the bottom row at all"); Back puts
                // the keyboard away (Android's own), and the row is back.
                when {
                    group != null && group.note != null -> Note(group.note)
                    group == null && contact == null -> Note(stringResource(R.string.contact_gone))
                    group == null && contact!!.blocked -> Note(stringResource(R.string.chat_blocked, contact.user))
                    group == null && !contact!!.paired -> Note(stringResource(R.string.contact_waiting))
                    else -> Box(
                        Modifier.clipToBounds().layout { measurable, constraints ->
                            val row = measurable.measure(constraints)
                            val height = (row.height * rowShown).toInt()
                            layout(row.width, height) { row.placeWithLayer(0, 0) { alpha = rowShown } }
                        },
                    ) { ChatButtons(false, talkingHere, recordingVoice, actions, video = pushVideo) }
                }
            }
        }
    }
    SendFlightOverlay(flight)
    if (editing != null && androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
        androidx.activity.compose.BackHandler { editing?.let { it.leaving = true } }
    }
    fullVideo?.let { id ->
        val playingIt = talk.playing?.clip == id
        LaunchedEffect(playingIt) { if (!playingIt) { delay(400); if (talk.playing?.clip != id) fullVideo = null } }
        // In the quick-reply window Back closes the window (no activity to ask).
        if (androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
            androidx.activity.compose.BackHandler {
                chatActions.stopClip()
                fullVideo = null
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            true,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable(role = Role.Button) {
                    chatActions.stopClip()
                    fullVideo = null
                },
                contentAlignment = Alignment.Center,
            ) { com.chencohen.ptt.ui.talk.TalkVideoView(Modifier.fillMaxSize(), cover = false) }
        }
    }
    }
}

/** [actions] with the video bubble's tap playing it full screen. */
private fun ConversationActionsWith(a: ConversationActions, playVideo: (String) -> Unit) = ConversationActions(
    back = a.back, openContact = a.openContact, send = a.send, retry = a.retry, typing = a.typing, draft = a.draft, openTalk = a.openTalk,
    playClip = a.playClip, saveClip = a.saveClip, stopClip = a.stopClip, startVoice = a.startVoice, startVoiceMessage = a.startVoiceMessage,
    playVideo = playVideo, finishVoice = a.finishVoice, micDenied = a.micDenied, call = a.call, shareCall = a.shareCall, playKept = a.playKept,
    setPushVideo = a.setPushVideo, answering = a.answering, edit = a.edit, editLive = a.editLive,
)


@Composable
private fun Header(user: String, contact: Contact?, header: ConversationHeader, actions: ConversationActions, canTalk: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClick = actions.back),
            contentAlignment = Alignment.Center,
        ) { Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground) }
        Row(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = actions.openContact).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(user, 40.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact?.name ?: user, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                    if (contact?.verified == true) {
                        Icon(LibraryIcons.Shield, stringResource(R.string.contact_safety_verified), tint = LibraryColors.Muted, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                    }
                }
                val line = when {
                    header.typing -> com.chencohen.ptt.genderedString(R.string.chat_typing, user)
                    header.presence?.online == true -> stringResource(R.string.chat_online)
                    header.presence?.lastSeen != null -> stringResource(R.string.chat_last_seen, lastSeen(header.presence.lastSeen!!))
                    else -> null
                }
                if (line != null) {
                    Text(line, color = if (header.typing || header.presence?.online == true) LibraryColors.Accent else LibraryColors.Muted, style = LibraryType.Caption)
                }
            }
        }
    }
}

/** Voice call and video call, Signal's way, before Talk. */

@Composable
private fun GroupHeader(group: GroupChat, actions: ConversationActions, canTalk: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = actions.back), contentAlignment = Alignment.Center) {
            Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground)
        }
        Row(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = actions.openContact).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.chencohen.ptt.ui.groups.GroupAvatar(group.name, group.picture, 40.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(group.name, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                Text(group.line, color = LibraryColors.Muted, style = LibraryType.Caption)
            }
        }
    }
}

/** "14:05" today, else the date and time. */
private fun lastSeen(at: Long): String {
    val today = System.currentTimeMillis() - at < 20 * 3600_000L
    val format = if (today) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return format.format(Date(at))
}

@Composable
private fun Bubble(message: TextMessage, actions: ConversationActions, playing: NowPlaying?, live: Boolean = false, showAuthor: Boolean = false, tick: ReadMarks.Tick = ReadMarks.Tick.NONE, liveVideo: Boolean = false, liveSince: Long? = null, compact: Boolean = false, bubbleModifier: Modifier = Modifier,
    /** Their text being edited live: its text as it stands (null: hidden), shown in place with a soft mark. */
    liveEdit: com.chencohen.ptt.messaging.LiveEdit? = null,
    /** Mine: a double tap edits it, the caret at the tapped character ([Int] the bubble's width now, in pixels). */
    startEdit: ((Int, Int) -> Unit)? = null,
) {
    var bubbleWidth by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val retry = actions.retry
    val mine = message.outgoing
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            bubbleModifier
                .widthIn(max = bubbleMaxWidth())
                .onSizeChanged { bubbleWidth = it.width }
                .animateContentSize(androidx.compose.animation.core.tween(MORPH_MS))
                .then(if (liveEdit != null) Modifier.border(1.dp, LibraryColors.Accent.copy(alpha = 0.6f), LibraryShapes.Card) else Modifier)
                .background(if (mine) LibraryColors.Mine else LibraryColors.Raised, LibraryShapes.Card)
                .then(if (message.status == MessageStatus.FAILED) Modifier.clickable(role = Role.Button) { retry(message.id) } else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (showAuthor && !mine && message.author != null) {
                Text(message.author!!, color = LibraryColors.Accent, style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp))
            }
            if (message.call != null) {
                com.chencohen.ptt.ui.calls.CallEntryContent(message, onInverse = mine, share = actions.shareCall, playKept = actions.playKept) { actions.call(message.call!!.video) }
            } else if (message.clip?.video == true) {
                // Live, then the video it leaves (first picture, length), in the same place.
                androidx.compose.animation.AnimatedContent(
                    liveVideo,
                    transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)).togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))) },
                    label = "live video",
                ) { isLive ->
                    if (isLive) LiveVideoBubble(mine, liveSince ?: message.at, framed = false) else VideoClipContent(message, live, onInverse = mine, play = actions.playVideo, save = actions.saveClip, compact = compact)
                }
            } else if (message.clip != null) {
                ClipContent(message, playing, live, actions.playClip, actions.stopClip, onInverse = mine, save = actions.saveClip)
            } else {
                if (liveEdit != null) Text(stringResource(R.string.chat_editing), color = LibraryColors.Accent, style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp))
                var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                Text(
                    liveEdit?.text ?: message.body,
                    color = if (mine) LibraryColors.OnMine else LibraryColors.OnBackground, style = LibraryType.Body,
                    onTextLayout = { layout = it },
                    modifier = if (startEdit != null) Modifier.pointerInput(message.id) {
                        // The caret where the finger was (the text's own layout; right to left too).
                        detectTapGestures(onDoubleTap = { at -> startEdit(layout?.getOffsetForPosition(at) ?: message.body.length, bubbleWidth) })
                    } else Modifier,
                )
            }
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at))
            // The time, and for mine how far it got: no "read" mark (their circle says it).
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (message.editedAt != null) {
                    Text(stringResource(R.string.chat_edited), color = if (mine) LibraryColors.OnMineMuted else LibraryColors.Muted, style = LibraryType.Tiny, modifier = Modifier.padding(end = 4.dp))
                }
                Text(
                    time,
                    color = when {
                        message.status == MessageStatus.FAILED && mine -> LibraryColors.Warning
                        mine -> LibraryColors.OnMineMuted
                        else -> LibraryColors.Muted
                    },
                    style = LibraryType.Tiny,
                )
                if (mine) StatusMark(tick, Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
}


/**
 * A draft someone is writing live (their Live typing; 2026-11-23, hen): their side, dimmed and outlined, the text as
 * it is now with a caret, "Writing…" above it. [settled] 0 is the draft; towards 1 it becomes the message where it is
 * (hen: "a nice transition"): the outline fills with the bubble's colour, "Writing…" fades and folds away, the time
 * fades in; the text stays put.
 */
@Composable
internal fun DraftBubble(
    who: String?,
    text: String,
    modifier: Modifier = Modifier,
    /** In a one-to-one chat: whose draft it is (their form of "Writing…"). */
    draftUser: String? = null,
    settled: Float = 0f,
    time: String? = null,
    shape: androidx.compose.ui.graphics.Shape = LibraryShapes.Card,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = bubbleMaxWidth())
                .then(if (settled > 0f) Modifier.animateContentSize(androidx.compose.animation.core.tween(250)) else Modifier)
                .border(1.dp, LibraryColors.Accent.copy(alpha = 0.5f * (1f - settled)), shape)
                .background(LibraryColors.Raised.copy(alpha = 0.5f + 0.5f * settled), shape)
                .padding(padding),
        ) {
            if (settled < 1f) {
                Text(
                    if (who != null) com.chencohen.ptt.genderedString(R.string.chat_draft_by, who, who) else com.chencohen.ptt.genderedString(R.string.chat_draft, draftUser),
                    color = LibraryColors.Accent.copy(alpha = 1f - settled), style = LibraryType.Small,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                if (settled > 0f) text else "$text\u258F",
                color = LibraryColors.OnBackground.copy(alpha = 0.75f + 0.25f * settled), style = LibraryType.Body,
            )
            if (time != null && settled > 0f) {
                Text(time, color = LibraryColors.Muted.copy(alpha = settled), style = LibraryType.Tiny, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
            }
        }
    }
}

/**
 * Keys for their live drafts (2026-11-24, hen: a draft becomes its message in place): each draft's item key, kept by
 * the message it turns into, and a fresh one for their next draft.
 */
internal class LiveKeys(private var lastNewest: String?) {
    private val gen = HashMap<String, Int>()
    /** Messages that were drafts on screen: their item's key. */
    val morphed = HashMap<String, String>()
    /** Each such message's last draft text (its text crossfades if the message differs). */
    val lastText = HashMap<String, String>()
    private var lastDrafts: List<Pair<String?, String>> = emptyList()
    /** The draft just turned into a message, until the draft itself is gone: not shown twice. */
    private val landed = HashMap<String, Set<String>>()

    fun key(who: String?) = "draft/" + (who ?: "") + "/" + (gen[who ?: ""] ?: 0)

    fun observe(drafts: List<Pair<String?, String>>, newest: TextMessage?) {
        if (newest == null || newest.id == lastNewest) return
        lastNewest = newest.id
        if (newest.outgoing || newest.clip != null || newest.call != null) return
        val from = lastDrafts.firstOrNull { (who, _) -> who == null || who == newest.author } ?: return
        val who = from.first ?: ""
        morphed[newest.id] = key(from.first)
        lastText[newest.id] = from.second
        gen[who] = (gen[who] ?: 0) + 1
        landed[who] = setOf(from.second, newest.body)
    }

    fun seen(drafts: List<Pair<String?, String>>) {
        lastDrafts = drafts
        // A draft gone (or new text) ends the guard.
        landed.keys.toList().forEach { who -> if (drafts.none { (w, t) -> (w ?: "") == who && t in landed[who].orEmpty() }) landed.remove(who) }
    }

    /** [drafts] without one that already became its message (its "done" may come a moment after the message). */
    fun shown(drafts: List<Pair<String?, String>>) = drafts.filter { (w, t) -> t !in landed[w ?: ""].orEmpty() }
}

/** What their live item shows: their circle, their draft, or the message the draft became. */
internal sealed interface LiveLine {
    data class Circle(val readAt: Long?) : LiveLine
    data class Draft(val who: String?, val text: String) : LiveLine
    data class Message(val message: TextMessage, val who: String?, val lastDraft: String?) : LiveLine
}

@Composable
private fun LiveLineView(line: LiveLine, draftUser: String?) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    var bubble by remember { mutableStateOf(line.takeIf { it !is LiveLine.Circle }) }
    if (line !is LiveLine.Circle) bubble = line
    val ease = androidx.compose.animation.core.tween<Float>(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    androidx.compose.animation.AnimatedContent(
        line is LiveLine.Circle,
        transitionSpec = {
            // From their side's bottom corner: the start, which is the right in Hebrew (TransformOrigin isn't mirrored).
            (androidx.compose.animation.fadeIn(ease) + androidx.compose.animation.scaleIn(ease, initialScale = 0.6f, transformOrigin = sideOrigin(mine = false, rtl = rtl, y = 1f)))
                .togetherWith(androidx.compose.animation.fadeOut(ease))
                .using(androidx.compose.animation.SizeTransform(clip = false))
        },
        label = "circle and draft",
    ) { circle ->
        if (circle) ReaderCircle(draftUser.orEmpty(), (line as? LiveLine.Circle)?.readAt, end = false)
        else bubble?.let { LiveBubble(it, draftUser) }
    }
}

/** A draft written live, filling into the message it becomes: outline to fill, label out, time in, size eased. */
@Composable
private fun LiveBubble(line: LiveLine, draftUser: String?) {
    val message = (line as? LiveLine.Message)?.message
    val settled = remember { androidx.compose.animation.core.Animatable(if (message != null) 1f else 0f) }
    LaunchedEffect(message != null) { settled.animateTo(if (message != null) 1f else 0f, androidx.compose.animation.core.tween(MORPH_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val s = settled.value
    val who = (line as? LiveLine.Draft)?.who ?: (line as? LiveLine.Message)?.who
    val text = message?.body ?: (line as? LiveLine.Draft)?.text.orEmpty()
    val shape = LibraryShapes.Card
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            // Their writing follows the text at once; only the turn into the message eases its size.
            Modifier.widthIn(max = bubbleMaxWidth())
                .then(if (message != null) Modifier.animateContentSize(androidx.compose.animation.core.tween(MORPH_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)) else Modifier)
                .border(1.dp, LibraryColors.Accent.copy(alpha = 0.5f * (1f - s)), shape)
                .background(LibraryColors.Raised.copy(alpha = 0.5f + 0.5f * s), shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            // "Writing…" fades out; in a group the author's name stays, as on any message.
            if (message == null || s < 1f || who != null) {
                Text(
                    when {
                        message != null && who != null -> who
                        who != null -> com.chencohen.ptt.genderedString(R.string.chat_draft_by, who, who)
                        else -> com.chencohen.ptt.genderedString(R.string.chat_draft, draftUser)
                    },
                    color = LibraryColors.Accent.copy(alpha = if (message != null && who != null) 1f else 1f - s), style = LibraryType.Small,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            // The text stays; if the message differs from the last draft, it crossfades.
            val differs = message != null && message.body != (line as LiveLine.Message).lastDraft
            androidx.compose.animation.AnimatedContent(
                differs to text,
                contentKey = { it.first },
                transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)).togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))) },
                label = "draft text",
            ) { (_, t) ->
                Text(
                    if (message == null) "$t\u258F" else t,
                    color = LibraryColors.OnBackground.copy(alpha = 0.75f + 0.25f * s), style = LibraryType.Body,
                )
            }
            if (message != null) {
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at)),
                    color = LibraryColors.Muted.copy(alpha = s), style = LibraryType.Tiny,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The list (upside down) glides to its end (2026-11-24, hen): after the new item is laid out, eased over about
 * 300 ms, landing exactly at the bottom.
 */
internal suspend fun androidx.compose.foundation.lazy.LazyListState.glideToEnd() {
    androidx.compose.runtime.withFrameNanos { }
    val first = firstVisibleItemIndex
    if (first == 0 && firstVisibleItemScrollOffset == 0) return
    if (first > 1) {
        animateScrollToItem(0)
        return
    }
    // The new item sits just past the end, unmeasured: a pixel's scroll lays it out (the list is then within it, by
    // its size), and the rest glides.
    if (first == 1) scroll { scrollBy(-1f) }
    if (firstVisibleItemIndex != 0) {
        animateScrollToItem(0)
        return
    }
    val distance = firstVisibleItemScrollOffset.toFloat()
    animateScrollBy(-distance, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    if (firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset != 0) scrollToItem(0)
}

internal const val MORPH_MS = 250

/** A message turning from its draft: [settled] goes 0 to 1 in [MORPH_MS]. */
@Composable
internal fun MorphingBubble(message: TextMessage, who: String?, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = LibraryShapes.Card, padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
    val settled = remember(message.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(message.id) { settled.animateTo(1f, androidx.compose.animation.core.tween(MORPH_MS)) }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at))
    DraftBubble(who, message.body, modifier, null, settled.value, time, shape, padding)
}

@Preview
@Composable
private fun ConversationPreview() {
    TalkTheme {
        ConversationScreen(
            "dana",
            Contact("dana", "", paired = true, verified = true, devices = 1),
            listOf(
                TextMessage("1", "dana", outgoing = false, body = "Are you coming?", at = 1_800_000_000_000),
                TextMessage("2", "dana", outgoing = true, body = "On my way", at = 1_800_000_060_000, status = MessageStatus.READ),
                TextMessage("3", "dana", outgoing = true, body = "Ten minutes", at = 1_800_000_090_000, status = MessageStatus.PENDING),
            ),
            ConversationHeader(typing = true, presence = UserPresence("dana", true, null)),
            ConversationActions(),
        )
    }
}


/** This phone's draft: what is written, whether it is open, and when typing was last told (no recomposition of the chat). */
internal class ChatDraft {
    var text by mutableStateOf("")
    var editing by mutableStateOf(false)
    var lastTyping = 0L
    /** Where the draft bubble is on the screen (for the send's flight). */
    var bounds: androidx.compose.ui.geometry.Rect? = null
}

internal const val FLIGHT_MS = 300

/**
 * One of my texts being edited: [value] as it stands, the caret first at [caret]; [width] the bubble's width as it
 * was (its text keeps that wrap until the first change); [leaving] asks it to turn back.
 */
internal class Editing(val id: String, val original: String, caret: Int, val width: Int) {
    var value by mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(original, androidx.compose.ui.text.TextRange(caret.coerceIn(0, original.length))))
    var leaving by mutableStateOf(false)
    /** Typed in: the text may take the box's whole width. */
    var changed by mutableStateOf(false)
}

/**
 * My sent text, edited in place (2026-11-24, hen): the words don't move. The edit field lies exactly where the
 * bubble's text was (same style, padding and wrap width), and only the container turns: teal to the writing bubble's
 * look (coral outline, no label), in the bubble's own bounds, ✓ where the time and its mark were; it widens toward
 * the middle only when the typed text no longer fits, up to 85% of the row.
 * ✓ saves; Back or a tap outside turn it back unchanged (no ✕). 220 ms each way, eased.
 */
@Composable
private fun EditBubble(edit: Editing, change: (androidx.compose.ui.text.input.TextFieldValue) -> Unit, done: (save: Boolean) -> Unit, gone: () -> Unit) {
    val open = remember(edit.id) { androidx.compose.animation.core.Animatable(0f) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var saving by remember(edit.id) { mutableStateOf<Boolean?>(null) }
    val ease = androidx.compose.animation.core.tween<Float>(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    LaunchedEffect(edit.id) {
        // They see it is being edited from the first moment.
        change(edit.value)
        runCatching { focus.requestFocus() }
        open.animateTo(1f, ease)
    }
    LaunchedEffect(edit.leaving) { if (edit.leaving && saving == null) saving = false }
    LaunchedEffect(saving) {
        val save = saving ?: return@LaunchedEffect
        done(save)
        open.animateTo(0f, ease)
        gone()
    }
    val p = open.value
    val coral = com.chencohen.ptt.ui.style.Pink
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pad = 14.dp
    val from = with(density) { edit.width.toDp() }.coerceAtLeast(pad * 2 + 24.dp)
    // The box keeps the bubble's bounds (2026-11-24, hen): its width until the typed text wouldn't fit, then it widens
    // toward the middle, up to 85% of the row, before it wraps (editBoxWidth).
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val longest = remember(edit.value.text) {
        with(density) {
            val r = measurer.measure(edit.value.text.ifEmpty { " " }, LibraryType.Body)
            (0 until r.lineCount).maxOf { r.getLineRight(it) - r.getLineLeft(it) }.toDp()
        }
    }
    val target = editBoxWidth(from, longest + pad * 2 + 2.dp, chatRowWidth() * DRAFT_MAX)
    val boxWidth by androidx.compose.animation.core.animateDpAsState(target, androidx.compose.animation.core.tween(150), label = "edit width")
    Box(Modifier.fillMaxWidth(), contentAlignment = MySide) {
        Column(
            Modifier.width(boxWidth)
                .border(1.5.dp, coral.copy(alpha = p), LibraryShapes.Card)
                .background(androidx.compose.ui.graphics.lerp(LibraryColors.Mine, LibraryColors.Background, p), LibraryShapes.Card)
                .background(coral.copy(alpha = 0.08f * p), LibraryShapes.Card)
                .padding(horizontal = pad, vertical = 8.dp),
        ) {
            // The text exactly where the bubble's was: same style, padding and width (its lines unchanged).
            androidx.compose.foundation.text.BasicTextField(
                value = edit.value,
                onValueChange = { v ->
                    if (v.text != edit.value.text) edit.changed = true
                    change(v.copy(text = v.text.take(4_000)))
                },
                textStyle = LibraryType.Body.copy(color = androidx.compose.ui.graphics.lerp(LibraryColors.OnMine, LibraryColors.OnBackground, p)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(coral),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            // ✓ in the place of the time and its mark, at the end of that line: the height and lines don't change.
            Box(Modifier.align(Alignment.End).padding(top = 2.dp).height(18.dp), contentAlignment = Alignment.Center) {
                val save = stringResource(R.string.chat_edit_save)
                val can = edit.value.text.isNotBlank()
                Box(
                    Modifier.requiredSize(26.dp).graphicsLayer { alpha = p; scaleX = 0.6f + 0.4f * p; scaleY = 0.6f + 0.4f * p }
                        .background(if (can) coral else coral.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape)
                        .clickable(enabled = can && p > 0.5f, role = Role.Button, onClickLabel = save) { if (saving == null) saving = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(LibraryIcons.Check, save, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

/**
 * The edit box's width: the bubble's own ([bubble]) while the text fits in it, so its words and lines stay; wider as
 * the text needs ([needed]: its longest line with the padding), up to [most], then it wraps.
 */
internal fun editBoxWidth(bubble: androidx.compose.ui.unit.Dp, needed: androidx.compose.ui.unit.Dp, most: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    if (needed <= bubble) bubble else minOf(needed, maxOf(most, bubble))

/** Bubbles, mine and theirs and the one being written: up to 78% of the screen's width (2026-11-24, hen). */
@Composable
internal fun bubbleMaxWidth(): androidx.compose.ui.unit.Dp = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.78f).dp

/** My side of the chat: the end (the left in Hebrew), where my bubbles and the one I write are anchored. */
internal val MySide = Alignment.CenterEnd
internal val TheirSide = Alignment.CenterStart

/**
 * Send's flight (2026-11-24, hen): a copy of the draft drawn over the chat moves and resizes from the draft's place
 * to its message's slot (kept unseen), the coral outline giving way to the teal fill, the label out and the time and
 * circle in; at the end the message shows and the copy goes in the same frame.
 */
internal class SendFlight {
    var body by mutableStateOf<String?>(null)
    var from by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** The message's slot, as the list lays it out (it follows the list's scroll and the keyboard). */
    var to by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** The sent message, once it is in the chat. */
    var id by mutableStateOf<String?>(null)
    var origin = androidx.compose.ui.geometry.Offset.Zero
    /** Touches on the chat, counted (the camera's idle release). */
    var touches by androidx.compose.runtime.mutableIntStateOf(0)
    val progress = androidx.compose.animation.core.Animatable(0f)

    /** What was newest in the chat as Send was pressed: the sent message comes after it (never matched by text). */
    private var before: String? = null

    fun start(text: String, bounds: androidx.compose.ui.geometry.Rect?, newestNow: String?) {
        to = null
        id = null
        from = bounds
        before = newestNow
        body = text
    }

    /**
     * [newest] is the chat's newest message now: the first one of mine to come after Send (a new id, not the one that
     * was newest then) is the sent one, its slot the flight's target (2026-11-24, hen: the same text sent twice flew to
     * the earlier one).
     */
    fun claim(newest: TextMessage?) {
        if (body == null || id != null || newest == null) return
        if (newest.id == before) return
        if (newest.outgoing && newest.clip == null && newest.call == null) id = newest.id
    }

    fun land() {
        body = null
        from = null
        to = null
        id = null
    }
}

/**
 * The pivot on a bubble's own side (TransformOrigin is left-based, not mirrored): theirs at the start, mine at the
 * end; in Hebrew the start is the right.
 */
internal fun sideOrigin(mine: Boolean, rtl: Boolean, y: Float) = androidx.compose.ui.graphics.TransformOrigin(if (mine == rtl) 0f else 1f, y)

/** Where the flying copy goes in the chat's box: window positions are left-based in either direction. */
internal fun flightOffset(box: androidx.compose.ui.geometry.Rect, origin: androidx.compose.ui.geometry.Offset) =
    androidx.compose.ui.unit.IntOffset((box.left - origin.x).toInt(), (box.top - origin.y).toInt())

@Composable
private fun SendFlightOverlay(flight: SendFlight) {
    val text = flight.body ?: return
    val from = flight.from ?: return
    // Until its slot is laid out, the copy stays where the draft was.
    val to = flight.to ?: from
    val p = flight.progress.value
    val density = androidx.compose.ui.platform.LocalDensity.current
    val box = androidx.compose.ui.geometry.Rect(
        androidx.compose.ui.util.lerp(from.left, to.left, p), androidx.compose.ui.util.lerp(from.top, to.top, p),
        androidx.compose.ui.util.lerp(from.right, to.right, p), androidx.compose.ui.util.lerp(from.bottom, to.bottom, p),
    )
    val coral = com.chencohen.ptt.ui.style.Pink
    val start = with(density) { androidx.compose.ui.util.lerp(14.dp.toPx(), 14.dp.toPx(), p).toDp() }
    val end = with(density) { androidx.compose.ui.util.lerp(8.dp.toPx(), 14.dp.toPx(), p).toDp() }
    Box(
        Modifier
            // The whole screen, so the parent's alignment (mirrored in Hebrew) plays no part; the copy is placed at the
            // window's own (left-based) position with place(), which isn't mirrored either.
            .layout { measurable, constraints ->
                val w = box.width.toInt().coerceAtLeast(1)
                val h = box.height.toInt().coerceAtLeast(1)
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(flightOffset(box, flight.origin)) }
            }
            .background(LibraryColors.Background.copy(alpha = 1f - p), LibraryShapes.Card)
            .background(coral.copy(alpha = 0.08f * (1f - p)), LibraryShapes.Card)
            .background(LibraryColors.Mine.copy(alpha = p), LibraryShapes.Card)
            .border(1.5.dp, coral.copy(alpha = 1f - p), LibraryShapes.Card)
            .padding(start = start, end = end, top = 8.dp, bottom = 8.dp),
    ) {
        // The label folds away above the text, which keeps its size and moves with the box.
        Column {
            Box(Modifier.layout { m, c ->
                val pl = m.measure(c)
                val h = (pl.height * (1f - p)).toInt()
                layout(pl.width, h) { pl.placeWithLayer(0, 0) { alpha = 1f - p } }
            }) { Text(stringResource(R.string.chat_my_draft), color = coral, style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp)) }
            Text(text, color = androidx.compose.ui.graphics.lerp(LibraryColors.OnBackground, LibraryColors.OnMine, p), style = LibraryType.Body)
        }
        Row(Modifier.align(Alignment.BottomEnd).graphicsLayer { alpha = p }, verticalAlignment = Alignment.CenterVertically) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis())), color = LibraryColors.OnMineMuted, style = LibraryType.Tiny)
            StatusMark(ReadMarks.Tick.SENDING, Modifier.padding(start = 4.dp))
        }
    }
}

/** The pinned draft: the empty bubble or the one being written; typing and live typing told from here. */
@Composable
private fun PinnedDraft(draft: ChatDraft, focus: androidx.compose.ui.focus.FocusRequester, actions: ConversationActions, sent: (String, androidx.compose.ui.geometry.Rect?) -> Unit) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    // After a send the bubble written in flies off as the message, and a new empty one pops up here (2026-11-24,
    // hen): from 0.85 with a fade, 200 ms; the keyboard stays and so does the focus.
    val appear = remember { androidx.compose.animation.core.Animatable(1f) }
    var sends by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(sends) {
        if (sends > 0) {
            appear.snapTo(0f)
            appear.animateTo(1f, androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    LaunchedEffect(draft.editing) {
        if (draft.editing) {
            actions.answering()
            runCatching { focus.requestFocus() }
        }
    }
    LaunchedEffect(draft.text) {
        if (draft.text.isNotEmpty()) {
            // Stopped after a pause in typing.
            delay(6_000)
            if (draft.lastTyping != 0L) {
                actions.typing(false)
                draft.lastTyping = 0L
            }
        }
    }
    ComposeBubble(
        draft.text, draft.editing, focus, { draft.editing = true },
        modifier = Modifier.graphicsLayer {
            alpha = appear.value
            val scale = 0.85f + 0.15f * appear.value
            scaleX = scale
            scaleY = scale
            // From my side: the end, the left in Hebrew.
            transformOrigin = sideOrigin(mine = true, rtl = rtl, y = 0.5f)
        },
        bubbleModifier = Modifier.onGloballyPositioned { draft.bounds = it.boundsInWindow() },
        change = { value ->
            draft.text = value
            actions.draft(value)
            val now = System.currentTimeMillis()
            if (value.isBlank()) {
                if (draft.lastTyping != 0L) actions.typing(false)
                draft.lastTyping = 0L
            } else if (now - draft.lastTyping > 4_000) {
                actions.typing(true)
                draft.lastTyping = now
            }
        },
        send = {
            val body = draft.text.trim()
            if (body.isNotEmpty()) {
                sent(body, draft.bounds)
                sends++
                actions.send(body)
                actions.draft("")
                draft.text = ""
                draft.lastTyping = 0L
            }
        },
    )
}
