package com.chencohen.ptt.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.chencohen.ptt.ui.talk.cantHear
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
    /** The header's shield (not verified): their safety number. */
    val openSafetyNumber: () -> Unit = {},
    val send: (String) -> Unit = {},
    val retry: (String) -> Unit = {},
    /** Typing started (true) or stopped; already throttled here. */
    val typing: (Boolean) -> Unit = {},
    /** Live typing (the setting): the line now, as written ("" when sent or emptied). */
    val draft: (text: String, selStart: Int, selEnd: Int) -> Unit = { _, _, _ -> },
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
    /** The press slid onto the red bell: an urgent push (2026-11-25, hen). */
    val markUrgent: () -> Unit = {},
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
    /** Messages deleted, for everyone (mine only) or for me (sent once their Undo has passed). */
    val delete: (ids: List<String>, forEveryone: Boolean) -> Unit = { _, _ -> },
    /** Editing [id] live: the text as it stands (null: cancelled). */
    val editLive: (id: String, text: String?, selStart: Int, selEnd: Int) -> Unit = { _, _, _, _ -> },
    /** Writing started (the draft opened): what they wrote counts as read (the quick-reply window). */
    val answering: () -> Unit = {},
    /** Push-to-talk or push-to-video for this chat (remembered per contact); null: no switch. */
    val setPushVideo: ((Boolean) -> Unit)? = null,
    /** Sends one of this user's faces ([code]: Expression code; [fallback]: the emoji older apps show); null: no faces here. */
    val sendFace: ((code: Int, fallback: String) -> Unit)? = null,
    /** Reacts to a message with one of this user's faces; null code takes it back. */
    val react: ((id: String, code: Int?) -> Unit)? = null,
    /** This user is live hands-free and waiting for the floor (both live), or not any more. */
    val sendLiveWaiting: ((Boolean) -> Unit)? = null,
    /** Both live: turn it into a video call inside the chat; null: no calls here. */
    val callInChat: (() -> Unit)? = null,
    /** The live talk full screen (2026-11-27, hen: instead of "Make it a call"); null: not here. */
    val fullScreen: (() -> Unit)? = null,
)

/** A video call shown inside the chat (2026-11-24, hen): theirs under the header, mine with the call's bar at the bottom. */
class InChatCall(val top: @Composable () -> Unit, val bottom: @Composable () -> Unit)

/** What the header says under the name: typing, online, last seen, or nothing known. */
data class ConversationHeader(val typing: Boolean, val presence: UserPresence?)

/**
 * A group's chat instead of a contact's: its [name] and [picture] in the header, [line] under it (who is typing, or
 * how many members), and [note] in place of the line to write in when this user can't write (an invitation, gone).
 */
data class GroupChat(val name: String, val picture: String?, val line: String, val note: String?, val members: List<String> = emptyList())

/**
 * A conversation (docs/screens/conversation.md), Signal's: the contact in the header (a tap opens their page), the
 * texts in bubbles with when and how far each of ours got, a changed safety number on top, and the line to write in.
 */
/**
 * The chat in its own direction (2026-11-24, hen: a contact's Chat direction; follow the phone by default): which side
 * the bubbles, the writing bubble and the animations take. Each message's text keeps its own direction.
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
    talkLine: String? = null,
    drafts: List<Pair<String?, String>> = emptyList(),
    pushVideo: Boolean = false,
    warmCamera: (String?) -> Unit = {},
    presences: Map<String, UserPresence> = emptyMap(),
    embeddedHeader: (@Composable () -> Unit)? = null,
    seeThrough: Float = 1f,
    draftCarets: Map<String, Pair<Int, Int>> = emptyMap(),
    liveEdits: Map<String, com.chencohen.ptt.messaging.LiveEdit> = emptyMap(),
    viewing: Set<String> = emptySet(),
    /** This chat's direction (ChatDirection). */
    direction: Int = com.chencohen.ptt.messaging.ChatDirection.FOLLOW,
    live: ChatLive = ChatLive(),
    embeddedFull: Boolean = false,
    embeddedCalm: Boolean = false,
    /**
     * The Talk tab's stage (2026-11-26, hen: the Talk tab "in resemblance of the chat app"): drawn where the messages
     * are, with [embeddedHeader] (its row of faces) in place of the header, and no writing bubble. The bottom strip,
     * the live pills and video and the who-can't-hear chips are the chat's own.
     */
    stage: (@Composable (live: Boolean, overlay: @Composable () -> Unit) -> Unit)? = null,
    /** Who Push goes to, said beside it (the Talk tab's list: "To testbot"). */
    pushTo: String? = null,
    /** The Talk tab (2026-11-26): the writing bubble says "Message to testbot" and a tap on it opens the chat. */
    writeHint: String? = null,
    writeOpens: (() -> Unit)? = null,
    /** This chat's colours (TalkSettings.colorsOf): mine and theirs, ChatSwatches indexes, -1 the default. */
    colors: Pair<Int, Int> = -1 to -1,
    /** A group's members' colours (TalkSettings.memberHues): their bubbles, faces, names and live drafts. */
    memberHues: Map<String, Int> = emptyMap(),
) {
    val phone = androidx.compose.ui.platform.LocalLayoutDirection.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides com.chencohen.ptt.ui.talk.chatLayout(direction, phone),
    ) {
        com.chencohen.ptt.ui.style.ChatColors(colors.first, colors.second) {
          androidx.compose.runtime.CompositionLocalProvider(LocalMemberHues provides memberHues) {
            ConversationScreenIn(user, contact, messages, header, actions, group, talk, talkLine, drafts, pushVideo, warmCamera, presences, embeddedHeader, seeThrough, draftCarets, viewing, liveEdits, live, embeddedFull, embeddedCalm, stage, pushTo, writeHint, writeOpens)
          }
        }
    }
}

@Composable
private fun ConversationScreenIn(
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
    /** Where each live writer's caret is (who, "" in a one-to-one chat; start to end). */
    draftCarets: Map<String, Pair<Int, Int>> = emptyMap(),
    /** Who has this chat in view now (MessagingState.viewing): the contact, or a group's members. */
    viewing: Set<String> = emptySet(),
    /** Their texts being edited live (MessagingState.liveEdits). */
    liveEdits: Map<String, com.chencohen.ptt.messaging.LiveEdit> = emptyMap(),
    chatLive: ChatLive = ChatLive(),
    /**
     * The floating chat grown over the whole screen (2026-11-26, hen: "an expanding transformation"): this screen as
     * the app shows it, its own header under the status bar, though [embeddedHeader] is given.
     */
    embeddedFull: Boolean = false,
    /** The floating chat is growing or shrinking: the keyboard goes and the draft rests, as the app's chat will show it. */
    embeddedCalm: Boolean = false,
    stage: (@Composable (live: Boolean, overlay: @Composable () -> Unit) -> Unit)? = null,
    /** Who Push goes to, said beside it (the Talk tab's list: "To testbot"). */
    pushTo: String? = null,
    /** The Talk tab (2026-11-26): the writing bubble says "Message to testbot" and a tap on it opens the chat. */
    writeHint: String? = null,
    writeOpens: (() -> Unit)? = null,
) {
    // In the floating window, as it floats: its header, no status bar room.
    val embedded = embeddedHeader != null && !embeddedFull
    val keeps = chatLive.keeps
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
    val edits = remember(user) { EditSession() }
    val editing = edits.editing
    // Selected messages (2026-11-24, hen, Signal's way: a long press): the header turns into their actions.
    val selected = remember(user) { androidx.compose.runtime.mutableStateListOf<String>() }
    var sheet by remember(user) { mutableStateOf<ChatSheet?>(null) }
    // Deleted (2026-11-24, hen): gone from the chat at once, done once its 4 s Undo has passed.
    var pendingDelete by remember(user) { mutableStateOf<PendingDelete?>(null) }
    val deleteNow by androidx.compose.runtime.rememberUpdatedState(actions.delete)
    LaunchedEffect(pendingDelete) {
        val d = pendingDelete ?: return@LaunchedEffect
        delay(4_000)
        deleteNow(d.ids, d.forEveryone)
        pendingDelete = null
    }
    androidx.compose.runtime.DisposableEffect(user) { onDispose { pendingDelete?.let { deleteNow(it.ids, it.forEveryone) } } }
    fun deleteSoon(ids: List<String>, forEveryone: Boolean) {
        // One already waiting goes now.
        pendingDelete?.let { actions.delete(it.ids, it.forEveryone) }
        pendingDelete = PendingDelete(ids, forEveryone)
        selected.clear()
        sheet = null
    }
    // Opened from the Talk tab's row (TalkTabMove): the row rises into the header, the messages come in, the bottom stays.
    val talkMove = remember(user) { if (embeddedHeader == null) TalkTabMove.take(user) else null }
    val rise = remember(user) { androidx.compose.animation.core.Animatable(if (talkMove != null) 0f else 1f) }
    LaunchedEffect(user) { if (talkMove != null) rise.animateTo(1f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    var headerTop by remember(user) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var leaving by remember(user) { mutableStateOf(false) }
    val riseScope = androidx.compose.runtime.rememberCoroutineScope()
    // Only in the app: the floating window has no Back dispatcher, and a BackHandler there crashes it even when off.
    if (talkMove != null) {
        androidx.activity.compose.BackHandler(enabled = !leaving) {
            leaving = true
            riseScope.launch {
                rise.animateTo(0f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                actions.back()
            }
        }
    }
    val allMessages = messages
    @Suppress("NAME_SHADOWING")
    val messages = remember(messages, pendingDelete) { pendingDelete?.let { d -> messages.filter { it.id !in d.ids } } ?: messages }
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
            .then(if (embedded) Modifier.navigationBarsPadding().imePadding() else Modifier.statusBarsPadding().navigationBarsPadding().imePadding()),
    ) {
        val canTalk = if (group != null) group.note == null else contact?.paired == true && !contact.blocked
        androidx.compose.animation.AnimatedContent(
            selected.isNotEmpty(),
            transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(200)) { -it / 4 })
                    .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)))
                    .using(androidx.compose.animation.SizeTransform(clip = false))
            },
            label = "header or selection",
            modifier = Modifier.onGloballyPositioned { headerTop = it.positionInWindow().y }
                .graphicsLayer { if (talkMove != null) translationY = (talkMove - headerTop) * (1f - rise.value) },
        ) { selecting ->
            if (selecting) {
                val chosen = selected.mapNotNull { id -> allMessages.firstOrNull { it.id == id } }
                val one = chosen.singleOrNull()
                androidx.compose.foundation.layout.Column {
                SelectionBar(
                    count = selected.size,
                    close = { selected.clear() },
                    edit = one?.takeIf { it.outgoing && it.face == 0 && it.clip == null && it.call == null && !it.deleted && canWriteHere(group, contact) }?.let { m ->
                        { selected.clear(); edits.start(Editing(m.id, m.body, m.body.length, 0)) }
                    },
                    info = one?.let { m -> { sheet = ChatSheet.Info(m.id) } },
                    // A burst or voice message: Save as file (it was the long press; now that selects).
                    save = one?.takeIf { it.clip != null && !it.deleted }?.let { m -> actions.saveClip?.let { s -> { selected.clear(); s(m.id) } } },
                    delete = { sheet = ChatSheet.Delete(chosen.map { it.id }, chosen.all { it.outgoing && !it.deleted }) },
                )
                // React with one of my faces (2026-11-24, hen): under the bar, for one message.
                val react = actions.react
                if (one != null && !one.deleted && react != null && com.chencohen.ptt.ui.face.faceOf("") != null) {
                    com.chencohen.ptt.ui.face.FaceTray(current = one.reactions[""]?.minus(1)) { code ->
                        react(one.id, code.takeIf { it != one.reactions[""]?.minus(1) })
                        selected.clear()
                    }
                }
                }
            } else {
                when {
                    embedded && embeddedHeader != null -> Box(Modifier.graphicsLayer { alpha = seeThrough }) { embeddedHeader() }
                    group != null -> GroupHeader(group, actions, canTalk, chat = user, here = viewing)
                    else -> Header(user, contact, header, actions, canTalk, here = viewing.isNotEmpty())
                }
            }
        }
        val videoPanel = (talk.playing?.let { it.chat == user && it.video && it.live } == true) ||
            (talk.talkers[user]?.let { t -> messages.firstOrNull { it.id == t.clip }?.clip?.video } == true) ||
            (pushVideo && talk.own?.chat == user && talk.own?.phase != TalkPhase.VOICE)
        // Talking itself shows in the live panel or the voice bar (2026-11-24, hen); the line is left for the rest
        // (busy, refused, offline, a voice message, my other phone).
        val talkingShown = talk.own?.takeIf { it.chat == user }?.phase.let { it == TalkPhase.TALKING || it == TalkPhase.REQUESTING } ||
            (talk.own?.chat != user && talk.talkers[user] != null)
        if (talkLine != null && !videoPanel && !talkingShown && (talk.talkers[user] != null || talk.own?.chat == user)) {
            Text(
                talkLine,
                color = LibraryColors.Accent,
                style = LibraryType.Caption,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        // Live in the chat (2026-11-24, hen): what this phone sends with its camera, hands-free, and standby (below).
        val sendingVideo = talk.own?.takeIf { it.chat == user && pushVideo && (it.phase == TalkPhase.TALKING || it.phase == TalkPhase.REQUESTING) }
        var handsFree by remember(user) { mutableStateOf(false) }
        // Hands-free on the urgent bell (2026-11-26, hen): voice, urgent, until Stop; each renewal urgent again.
        var urgentHandsFree by remember(user) { mutableStateOf(false) }
        var standby by remember(user) { mutableStateOf(false) }
        var standbySince by remember(user) { androidx.compose.runtime.mutableLongStateOf(0L) }
        var sliding by remember(user) { mutableStateOf(false) }
        // Get camera ready while talking (2026-11-24, hen, on by default): the camera opens, unseen, as the finger goes
        // down on Push (the slide-up-to-video target shows then), and closes as soon as it lifts without sliding up
        // (2026-11-25, hen: "the camera should open only when the slide-to-video is appearing on screen").
        var pressing by remember(user) { mutableStateOf(false) }
        val cameraHeld = pressing && chatLive.cameraReady
        // The newest actions (a video setting just changed is in them), for what runs after a delay.
        val actionsNow by androidx.compose.runtime.rememberUpdatedState(actions)
        // Both live, and the call it can become: worked out further down, shown here under the panel.
        var bothLiveNow by remember(user) { mutableStateOf(false) }
        val makeCallNow = remember(user) { arrayOf<() -> Unit>({}) }
        val fullScreenNow = remember(user) { arrayOf<() -> Unit>({}) }
        // Slid up to the lock: the voice burst ends and the video one starts a moment later; meanwhile the pill is
        // already becoming the box (2026-11-25, hen: "the voice pill should transition to video, not leave the screen
        // and enter again").
        var goingLive by remember(user) { mutableStateOf(false) }
        var goingLiveAt by remember(user) { androidx.compose.runtime.mutableLongStateOf(0L) }
        // Until the video burst itself starts (the voice one, once Video is set, briefly counts as "sending video" too:
        // that ended the hold early and the panel left and came back; 2026-11-25, hen).
        val videoBurstStarted = sendingVideo != null && sendingVideo.startedAt > goingLiveAt
        LaunchedEffect(goingLive, videoBurstStarted) {
            if (videoBurstStarted) goingLive = false
            else if (goingLive) {
                delay(4_000)
                goingLive = false
            }
        }
        // A press that waits for the floor (queued behind theirs, or standing by while they hold it) is shown as
        // waiting, never as live (2026-11-25, hen: "only when the UI says it's transmitting, then transmit"): no pill
        // or box of mine in the row and no "both live"; the Push button says "Waiting for Sharon…". Mine comes into
        // the row the moment its turn does (REQUESTING, TALKING).
        val waitingFor: String? = when {
            standby -> contact?.name ?: user
            else -> talk.own?.takeIf { it.chat == user && it.phase == TalkPhase.WAITING }?.let { o ->
                if (group == null) contact?.name ?: user else o.busyWith?.takeIf { b -> b.isNotEmpty() } ?: group.name
            }
        }
        val myPanel = waitingFor == null && (sendingVideo != null || goingLive)
        // One run of mine (voice, then video after the slide up, and its hands-free renewals): one time that keeps
        // counting.
        val ownActive = talk.own?.takeIf { it.chat == user && (it.phase == TalkPhase.TALKING || it.phase == TalkPhase.REQUESTING) }
        var runSince by remember(user) { androidx.compose.runtime.mutableLongStateOf(0L) }
        if (ownActive != null && runSince == 0L) runSince = ownActive.startedAt
        LaunchedEffect(ownActive != null || goingLive || handsFree) {
            if (ownActive == null && !goingLive && !handsFree) {
                delay(1_500)
                runSince = 0L
            }
        }
        // The panel under the header (2026-11-24, hen: "when two sides push to video show videos at top side by side;
        // when I push show the video on the top, never on bottom"), or the call in the chat in its place.
        // Floating over the chat (2026-11-24, hen: "not inside black box"): drawn over the list below, nothing behind it.
        val liveOverlay: @Composable () -> Unit = { if (chatLive.call != null) {
            chatLive.call.top()
        } else {
            // Playing here, or heard live through the room (sound there, pictures from its segments): their floor, with
            // video.
            val talker = talk.talkers[user]?.takeIf { t -> talk.own?.clip != t.clip && allMessages.firstOrNull { it.id == t.clip }?.clip?.video == true }
            val theirs = talk.playing?.takeIf { it.chat == user && it.video && it.live && fullVideo == null }
            val theirClip = theirs?.clip ?: talker?.clip
            // Their run: voice, then the video right after a slide up, is one item with one time that keeps counting
            // (2026-11-25, hen: the timer went back to 0:00 at the slide up). A burst within RUN_GAP_MS of their last
            // keeps its start.
            val theirRun = remember(user) { longArrayOf(0L, 0L) }
            talk.talkers[user]?.takeIf { talk.own?.clip != it.clip }.let { t ->
                if (t != null) {
                    if (theirRun[0] == 0L || (theirRun[1] > 0L && t.since - theirRun[1] > RUN_GAP_MS)) theirRun[0] = t.since
                    theirRun[1] = 0L
                } else if (theirRun[0] != 0L && theirRun[1] == 0L) theirRun[1] = System.currentTimeMillis()
            }
            var theirSince by remember(user) { androidx.compose.runtime.mutableLongStateOf(0L) }
            LaunchedEffect(theirClip) { if (theirClip != null) theirSince = theirRun[0].takeIf { it > 0 } ?: talk.talkers[user]?.since ?: System.currentTimeMillis() }
            // Voice push-to-talk (2026-11-24, hen, mock A): a slim live bar, under the video panel when the other
            // side is on video.
            val playingHere = talk.playing?.takeIf { it.chat == user && it.live }
            val theirTalker = talk.talkers[user]?.takeIf { talk.own?.clip != it.clip }
            val theirVoice = when {
                playingHere != null && !playingHere.video -> VoiceSide(playingHere.from, if (group != null) playingHere.from else contact?.name ?: user, false, theirTalker?.since ?: theirSince.takeIf { it > 0 } ?: System.currentTimeMillis())
                playingHere == null && theirTalker != null && allMessages.firstOrNull { it.id == theirTalker.clip }?.clip?.video != true ->
                    VoiceSide(theirTalker.user, if (group != null) theirTalker.user else contact?.name ?: user, false, theirRun[0].takeIf { it > 0 } ?: theirTalker.since)
                else -> null
            }
            val myVoice = talk.own?.takeIf { it.chat == user && !pushVideo && (it.phase == TalkPhase.TALKING || it.phase == TalkPhase.REQUESTING) }
                ?.let { VoiceSide("", youLabel(it.urgent), true, runSince.takeIf { r -> r > 0 } ?: it.startedAt) }
            // My push now is urgent (the red bell): "You · Urgent" in red, in the row and the floating window's.
            val myUrgent = talk.own?.let { it.chat == user && it.urgent } == true
            // Their voice turning into video: their pill stays a moment after the voice ends, so their video, starting
            // right after, grows out of it instead of the pill leaving and a box coming.
            var theirVoiceHeld by remember(user) { mutableStateOf<VoiceSide?>(null) }
            LaunchedEffect(theirVoice) {
                if (theirVoice != null) theirVoiceHeld = theirVoice
                else {
                    // Only long enough for their video burst to follow a slide up (2026-11-25, hen: released, and
                    // still shown live here for 3 s).
                    delay(THEIR_HOLD_MS)
                    theirVoiceHeld = null
                }
            }
            // Test builds (TestBubble): the other side shown live here without a real push.
            val pretend by LivePretend.theirs.collectAsState()
            val pretendVoice = if (pretend == LiveKind.Voice) VoiceSide(user, contact?.name ?: user, false, theirSince.takeIf { it > 0 } ?: System.currentTimeMillis()) else null
            LaunchedEffect(pretend) { if (pretend != LiveKind.None && theirSince == 0L) theirSince = System.currentTimeMillis() }
            val theirVoiceNow = theirVoice ?: pretendVoice ?: theirVoiceHeld?.takeIf { theirClip == null && pretend == LiveKind.None }
            val voicesNow = listOfNotNull(theirVoiceNow, myVoice)
            val theirVideo = (theirClip != null && fullVideo == null) || pretend == LiveKind.Video
            val theirSinceNow = theirSince.takeIf { it > 0 } ?: System.currentTimeMillis()
            val mySinceNow = runSince.takeIf { it > 0 } ?: sendingVideo?.startedAt ?: standbySince.takeIf { it > 0 } ?: System.currentTimeMillis()
            val theirUser = talk.talkers[user]?.user ?: theirs?.from ?: user
            val theirName = (talk.talkers[user]?.user ?: theirs?.from)?.let { u -> if (group != null) u else contact?.name ?: u } ?: contact?.name ?: user
            // "You're both live" only while both sides' items really show here (2026-11-25, hen: "sometimes it says that
            // we both are transmitting and I see only myself": the other was only waiting for the floor).
            val bothShown = bothLiveNow && (theirVideo || theirVoiceNow != null) && (myPanel || myVoice != null)
            val liveRow = chatLive.liveRow
            if (liveRow != null) {
                // The floating chat (2026-11-25, hen: "the video and pills should appear from the top of the screen,
                // not in the chat floating window"): nothing is drawn here; the row at the top of the screen is told.
                val theirKind = if (theirVideo) LiveKind.Video else if (theirVoiceNow != null) LiveKind.Voice else LiveKind.None
                val myKind = if (myPanel) LiveKind.Video else if (myVoice != null) LiveKind.Voice else LiveKind.None
                val now = LiveNow(
                    theirs = theirKind, mine = myKind,
                    theirUser = theirVoiceNow?.user ?: theirUser, theirName = theirVoiceNow?.name ?: theirName,
                    theirSince = if (theirKind == LiveKind.None) 0L else theirVoiceNow?.since ?: theirSinceNow,
                    mySince = if (myKind == LiveKind.None) 0L else myVoice?.since ?: mySinceNow,
                    bothLive = bothShown,
                    myUrgent = myUrgent,
                )
                LaunchedEffect(now) { liveRow(now) }
                androidx.compose.runtime.DisposableEffect(user) { onDispose { liveRow(LiveNow()) } }
            } else if (group != null) {
                // Several talking at once in a group (2026-11-25, hen): everyone live in it, mine one of them.
                val arriving by com.chencohen.ptt.talk.LiveTalkVideo.arriving.collectAsState()
                val pretendMembers by LivePretend.members.collectAsState()
                val pretendMine by LivePretend.mine.collectAsState()
                val me = youLabel(myUrgent)
                val others = talk.talking[user].orEmpty().filter { it.clip != talk.own?.clip }.map { t ->
                    val id = "${t.user}.${t.device}"
                    GroupTalker(id, t.user, t.user, allMessages.firstOrNull { it.id == t.clip }?.clip?.video == true || arriving[id] != null, t.since)
                } + pretendMembers.map { GroupTalker("${it.user}.1", it.user, it.user, it.video, it.since) }
                val mine = when {
                    myPanel || pretendMine == LiveKind.Video -> GroupTalker(com.chencohen.ptt.ui.live.LiveLayout.MINE, "", me, true, mySinceNow, mine = true)
                    myVoice != null -> GroupTalker(com.chencohen.ptt.ui.live.LiveLayout.MINE, "", me, false, myVoice.since, mine = true)
                    pretendMine == LiveKind.Voice -> GroupTalker(com.chencohen.ptt.ui.live.LiveLayout.MINE, "", me, false, mySinceNow, mine = true)
                    else -> null
                }
                if (!com.chencohen.ptt.ui.talk.LocalLiveInRows.current) GroupLivePanel(heldTalkers(user, others + listOfNotNull(mine)), null, myUrgent = myUrgent)
            } else {
                if (!com.chencohen.ptt.ui.talk.LocalLiveInRows.current) LivePanel(
                    theirVideo, if (group != null) (theirs?.from ?: talker?.user) else null, theirSinceNow,
                    myPanel, mySinceNow,
                    null, handsFree,
                    theirIdentity = talk.talkers[user]?.let { "${it.user}.${it.device}" } ?: LivePretend.IDENTITY.takeIf { pretend != LiveKind.None },
                    theirUser = theirUser,
                    theirName = theirName,
                    behindMs = theirs?.behindMs,
                    voices = voicesNow,
                    myUrgent = myUrgent,
                )
                // Full screen (2026-11-27, hen: "'Full screen' is enough"): while their video or both of us are live; my
                // push ends (full screen starts muted).
                FullScreenChip((bothShown || theirVideo) && actions.fullScreen != null) {
                    fullScreenNow[0]()
                }
            }
            if (liveRow == null) {
                // Who can hear me while I push (2026-11-25, hen): under my pill, from what they told of this push, else
                // their presence.
                val ownNow = talk.own?.takeIf { it.chat == user && (it.phase == TalkPhase.TALKING || it.phase == TalkPhase.REQUESTING) }
                val hearOf = com.chencohen.ptt.ui.talk.LocalHear.current
                val told = ownNow?.let { talk.heard[it.clip] }.orEmpty()
                val listeners = if (group == null) {
                    listOf(com.chencohen.ptt.ui.talk.PushListener(user, contact?.name ?: user, hearOf(user), told[user] ?: 0))
                } else {
                    group.members.map { m -> com.chencohen.ptt.ui.talk.PushListener(m, m, hearOf(m), told[m] ?: 0) }
                }
                com.chencohen.ptt.ui.talk.PushHearChip(ownNow?.clip, listeners, group != null)
            }
        } }
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
        // How many came in while scrolled up (the scroll-down button's badge).
        var unseenCount by remember(user) { androidx.compose.runtime.mutableIntStateOf(0) }
        val newest = messages.lastOrNull()
        // Their live drafts and the messages they became share one item (one key), so a draft turns into its message
        // in place (2026-11-24, hen).
        // A draft whose end comes before its message (2026-11-26, hen: "it's supposed to stay in place and transition
        // to a message"): it stays where it is for a moment, so the message lands in its place, the same item, instead
        // of the draft going and the message coming in anew.
        val heldDrafts = remember(user) { androidx.compose.runtime.mutableStateMapOf<String?, String>() }
        val lastDrafts = remember(user) { arrayOf<List<Pair<String?, String>>>(emptyList()) }
        val vanished = lastDrafts[0].filter { (w, txt) -> txt.isNotBlank() && drafts.none { it.first == w } }
        androidx.compose.runtime.SideEffect {
            vanished.forEach { (w, txt) -> heldDrafts[w] = txt }
            drafts.forEach { heldDrafts.remove(it.first) }
            lastDrafts[0] = drafts
        }
        LaunchedEffect(heldDrafts.toMap()) {
            if (heldDrafts.isEmpty()) return@LaunchedEffect
            delay(DRAFT_HOLD_MS)
            heldDrafts.clear()
        }
        val liveDrafts = drafts + (vanished + heldDrafts.toList()).distinctBy { it.first }.filter { (w, _) -> drafts.none { it.first == w } }
        val live = remember(user) { LiveKeys(newest?.id) }
        live.observe(liveDrafts, newest)
        androidx.compose.runtime.SideEffect { live.seen(liveDrafts) }
        val shownDrafts = live.shown(liveDrafts)
        // Their draft coming or going, or turning into its message, while the chat is at its end: the end stays in view, in
        // the same frame (2026-11-26, hen's recording at 32.4 s: as testbot started typing, the circle's item moved to the
        // end as the draft, the list kept its old anchor and jumped down two items for two seconds, the newest hidden).
        val draftsShownNow = shownDrafts.isNotEmpty()
        val draftsWere = remember(user) { booleanArrayOf(false) }
        if (draftsShownNow != draftsWere[0]) {
            draftsWere[0] = draftsShownNow
            if (androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { list.firstVisibleItemIndex } <= 1) list.requestScrollToItem(0)
        }
        LaunchedEffect(newest?.id) {
            if (newest == null) return@LaunchedEffect
            // A draft that became this message is in its place already: nothing scrolls.
            if (newest.id in live.morphed) return@LaunchedEffect
            if (newest.outgoing || list.firstVisibleItemIndex <= 1) {
                list.glideToEnd()
                unseen = false
            } else {
                unseen = true
                if (!newest.outgoing) unseenCount++
            }
        }
        val atBottom by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex == 0 } }
        // How far they read: their face beside the last message of mine they read (one-to-one; not when they hide
        // their presence).
        // Sent while they had the chat in view (2026-11-24, hen): taken as read at once, so their face is beside the new
        // message from the start; their receipt then moves nothing.
        // Held for as long as they keep the chat in view (2026-11-26, hen's recording: their receipt came 4.7 s later, so
        // after 3 s their face went back up a message and then down again), half a minute at most; never less than 3 s.
        val predicted = remember(user) { androidx.compose.runtime.mutableStateMapOf<String, Set<String>>() }
        val viewingNow by androidx.compose.runtime.rememberUpdatedState(viewing)
        // From the frame the message itself comes in, so nothing shows unread first.
        val predictedNew = newest?.takeIf { it.outgoing && viewing.isNotEmpty() && it.id !in predicted && System.currentTimeMillis() - it.at <= 5_000 }
        if (predictedNew != null) androidx.compose.runtime.SideEffect { predicted[predictedNew.id] = viewing }
        val predictedNow = if (predictedNew != null) predicted.toMap() + (predictedNew.id to viewing) else predicted.toMap()
        val messagesNow by androidx.compose.runtime.rememberUpdatedState(messages)
        LaunchedEffect(predicted.isNotEmpty()) {
            while (predicted.isNotEmpty()) {
                delay(500)
                val now = System.currentTimeMillis()
                val at = messagesNow.associate { it.id to it.at }
                predicted.keys.toList().forEach { id ->
                    val age = now - (at[id] ?: 0L)
                    if (age > 3_000 && (viewingNow.isEmpty() || age > 30_000)) predicted.remove(id)
                }
            }
        }
        val readerMessages = remember(messages, predictedNow) {
            if (predictedNow.isEmpty()) messages else messages.map { m ->
                val who = predictedNow[m.id] ?: return@map m
                if (group == null) {
                    if (m.status != MessageStatus.READ) m.copy(status = MessageStatus.READ, readAt = m.readAt ?: m.at) else m
                } else {
                    m.copy(readBy = who.associateWith { m.at } + m.readBy)
                }
            }
        }
        // How far each of mine got (2026-11-26, hen): its bubble is a dashed outline until the server has it, a whole
        // outline on the server, and fills green once every participant's phone has it. (From what really came, not
        // from what is taken as read while they watch.)
        val reaches = remember(messages, group?.members) { ReadMarks.reaches(messages, group?.members) }
        // Their faces in the gaps under my messages (2026-11-26, hen): a dashed circle under the last one their phone
        // has, a ringed face after the last one they read (not when they hide their presence). Not rows of the list
        // (rows coming and going made the chat jump): each gap is part of its message's own item, with no room at all
        // without faces, and a face that moves on glides from its gap to the next while one closes and the other opens.
        val places = remember(readerMessages, presences, header.presence, group?.members, keeps) {
            val hidden = if (group != null) presences.filterValues { !ReadMarks.showsReader(it) }.keys else if (ReadMarks.showsReader(header.presence)) emptySet() else setOf(user)
            ReadMarks.places(readerMessages, group?.members, hidden) { it.clip == null || it.deleted || keeps(it) }
        }
        val placesNow = androidx.compose.runtime.rememberUpdatedState(places)
        val homes = remember(user) { MarkHomes() }
        val marks by remember(user) { androidx.compose.runtime.derivedStateOf { homes.shown(placesNow.value) } }
        // While gaps open and close, the bubbles follow them in the same frame (no glide of their own trailing).
        val gapsNow = marks.filterValues { faces -> faces.any { !it.under } }.keys
        val gapsWere = remember(user) { arrayOf(gapsNow) }
        var gapsMoving by remember(user) { mutableStateOf(false) }
        val gapsChanged = gapsWere[0] != gapsNow
        if (gapsChanged) androidx.compose.runtime.SideEffect {
            gapsWere[0] = gapsNow
            gapsMoving = true
        }
        LaunchedEffect(gapsNow) {
            delay(LINE_MS + 150L)
            gapsMoving = false
        }
        val marksAcross = marksAcross((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 24).dp)
        val nameOfMark: (String) -> String = { who -> if (group == null) contact?.name ?: who else who }
        // Writing in the chat (2026-11-23, hen): the draft bubble at the end of the list.
        val canWrite = if (group != null) group.note == null else contact != null && !contact.blocked && contact.paired
        val draft = remember(user) { ChatDraft() }
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        // The floating chat growing into the app's chat, or shrinking back (ChatHandover; 2026-11-26, hen): both show
        // the chat the same way at the moment one takes over from the other, so nothing moves then: at its end, the
        // keyboard away, the draft resting with the same words.
        LaunchedEffect(embeddedCalm) {
            if (embeddedCalm) {
                focusManager.clearFocus()
                keyboard?.hide()
                draft.editing = false
            }
        }
        // In the floating chat the window takes the keys only at its first touch (the app under it keeps its keyboard
        // until then), so the tap that starts writing comes before the window can show a keyboard: asked again as the
        // keys arrive.
        LaunchedEffect(draft.editing) {
            if (embeddedHeader != null && draft.editing) {
                repeat(3) {
                    delay(200)
                    if (draft.editing && !embeddedCalm) keyboard?.show()
                }
            }
        }
        val landing = if (embeddedHeader == null) ChatHandover.landing.value?.takeIf { it.chat == user } else null
        LaunchedEffect(landing?.at) {
            if (landing != null) {
                ChatHandover.landing.value = null
                if (System.currentTimeMillis() - landing.at > 15_000) return@LaunchedEffect
                focusManager.clearFocus()
                keyboard?.hide()
                draft.editing = false
                if (landing.draft.isNotBlank()) draft.text = landing.draft
                // From the Talk tab's writing bubble: writing at once.
                if (landing.write) draft.editing = true
                list.scrollToItem(0)
            }
        }
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
        androidx.compose.runtime.DisposableEffect(user) { onDispose { actions.draft("", -1, -1) } }
        // Push-to-video from the chat (2026-11-23, hen): the camera kept open while the chat shows, so a press sends
        // it at once; what this phone sends shows live in its bubble at the end of the chat, and stays as the video.
        // Released after a minute untouched (2026-11-24, hen), opened again at the next touch.
        var cameraIdle by remember(user) { mutableStateOf(false) }
        LaunchedEffect(flight.touches, pushVideo) {
            cameraIdle = false
            delay(60_000)
            cameraIdle = true
        }
        val recordingVoice = talk.own?.takeIf { it.chat == user && it.phase == TalkPhase.VOICE } != null
        val talkingHere = talk.own?.takeIf { it.chat == user && (it.phase == TalkPhase.TALKING || it.phase == TalkPhase.REQUESTING) } != null
        // Hands-free (2026-11-24, hen): the Talk button slid up to the lock keeps sending until Stop. The server ends a
        // floor at 60 s, so at 55 s it is let go and taken again at once.
        // Both live, app only (2026-11-24, hen, "a"): in a one-to-one chat, a press while they hold the floor doesn't
        // wait behind it. My camera shows in my panel (nothing sent), they're told I'm live, and both phones offer the
        // call; when their floor ends while I'm still live, my burst starts.
        // (Or pretended to, in a test build: TestBubble.)
        val pretendTheirs by LivePretend.theirs.collectAsState()
        // One to one, both talk at once (2026-11-25, hen: "both parties to be able to transmit simultaneously"): a press
        // while they talk starts at once, no standing by. (An older server still answers Busy: that is shown as waiting.)
        val theyHold = false
        // Stop is final (TalkRun): what a press set going may start a burst only while its run is the current one.
        val runs = remember(user) { TalkRun() }
        fun say(what: String) = android.util.Log.i(CHAT_TALK, what)
        val chainScope = androidx.compose.runtime.rememberCoroutineScope()
        val lockContext = androidx.compose.ui.platform.LocalContext.current
        val cameraBusyNote = androidx.compose.ui.res.stringResource(R.string.talk_camera_busy)
        val handsFreeNow by androidx.compose.runtime.rememberUpdatedState(handsFree)
        val ownHere = talk.own?.takeIf { it.chat == user }
        val ownNow by androidx.compose.runtime.rememberUpdatedState(ownHere)
        // Each change of this phone's own burst here, in the log: what is sent, against what the buttons did.
        LaunchedEffect(ownHere?.clip, ownHere?.phase) {
            say("own: " + (ownHere?.let { "${it.phase} ${it.clip.takeLast(6)}" } ?: "none") + " (run ${runs.now}, hands-free $handsFree, standby $standby)")
        }
        LaunchedEffect(handsFree, ownHere?.clip, ownHere?.phase) {
            if (!handsFree) return@LaunchedEffect
            val o = ownHere
            if (o == null && standby) return@LaunchedEffect
            if (o == null) {
                // Its floor went (refused, or this phone's own end): hands-free ends unless a new press took it.
                delay(2_500)
                if (ownNow == null) {
                    say("hands-free ends: no floor for 2.5 s (run ${runs.now})")
                    handsFree = false
                    runs.end()
                    // A press already on its way (the video after the lock, asked late on a slow network) would get
                    // the floor after this and go on with no Stop (2026-11-26, seen on testbot): it is let go too.
                    actionsNow.finishVoice(true)
                }
                return@LaunchedEffect
            }
            if (o.phase == TalkPhase.TALKING) {
                delay((o.startedAt + 55_000 - System.currentTimeMillis()).coerceAtLeast(0L))
                // Outside this effect: letting go changes what it waits on, which would cancel the press that follows.
                val run = runs.now
                chainScope.launch {
                    say("hands-free renewal: let go (run $run)")
                    actions.finishVoice(true)
                    // Taken again as soon as the last one is let go (a press while one is still going is ignored).
                    var waited = 0
                    while (ownNow != null && waited < 4_000) {
                        delay(100)
                        waited += 100
                    }
                    // The camera needs a moment to be free again, or the next one goes as voice only.
                    delay(800)
                    val may = runs.may(run)
                    say("hands-free renewal: " + (if (handsFreeNow && may) "starts" else "doesn't start") + " (run $run of ${runs.now}, hands-free $handsFreeNow)")
                    if (handsFreeNow && (may || !ENFORCE_RUNS)) {
                        actionsNow.startVoice()
                        // Urgent again from its first moment.
                        if (urgentHandsFree) {
                            var w = 0
                            while (ownNow == null && w < 3_000) {
                                delay(50)
                                w += 50
                            }
                            actionsNow.markUrgent()
                        }
                    }
                }
            }
        }
        androidx.compose.runtime.DisposableEffect(user) { onDispose { if (handsFreeNow) actions.finishVoice(true) } }
        // Kept open while live hands-free or in standby (2026-11-24: after a minute untouched it was let go, so the next
        // renewal went out as voice only).
        // Hands-free video (2026-11-24, hen): the camera opens as the finger starts sliding up from the Talk button, so the
        // video starts fast at the lock.
        com.chencohen.ptt.ui.talk.WarmCamera(user, canWrite && !chatLive.anyCall && (sliding || cameraHeld || chatLive.roomCallCamera || (pushVideo && (handsFree || standby || talkingHere))), warmCamera)
        // Push is voice, except hands-free (slid up to the lock): this chat's video setting is set for it and back.
        LaunchedEffect(handsFree, standby, pushVideo, talk.own?.chat) { if (!handsFree && !standby && pushVideo && talk.own?.chat != user) actions.setPushVideo?.invoke(false) }
        // Their floor ended while I'm live in standby: mine starts.
        LaunchedEffect(standby, theyHold) {
            if (standby && !theyHold) {
                val run = runs.now
                delay(300)
                if (standby && talk.talkers[user] == null) {
                    val may = runs.may(run)
                    say("standby: their floor ended, mine " + (if (may) "starts" else "doesn't start") + " (run $run of ${runs.now})")
                    standby = false
                    if (may || !ENFORCE_RUNS) actions.startVoice()
                }
            }
        }
        // Both live: I'm live and waiting for their floor (standby, or a queued burst), or they are for mine.
        val waitingLive = standby || (handsFree && ownHere?.phase == TalkPhase.WAITING)
        var saidWaiting by remember(user) { mutableStateOf(false) }
        LaunchedEffect(waitingLive) {
            if (waitingLive || saidWaiting) actions.sendLiveWaiting?.invoke(waitingLive)
            saidWaiting = waitingLive
        }
        // Both talking at once (2026-11-25: one to one, both can): I'm live and so are they, so the room can become the
        // call (hen: "how to make it a video call?").
        val theyTalkToo = group == null && talk.talkers[user]?.let { t -> talk.own?.clip != t.clip } == true
        val bothLive = chatLive.call == null && (waitingLive || ((handsFree || talkingHere) && (theyTalkToo || chatLive.liveWaiting.isNotEmpty())))
        val makeCall: () -> Unit = {
            runs.end()
            handsFree = false
            standby = false
            if (ownNow != null) actions.finishVoice(true)
            actions.callInChat?.invoke()
        }
        val goFullScreen: () -> Unit = {
            runs.end()
            handsFree = false
            standby = false
            if (ownNow != null) actions.finishVoice(true)
            actions.fullScreen?.invoke()
        }
        androidx.compose.runtime.SideEffect {
            bothLiveNow = bothLive
            makeCallNow[0] = makeCall
            fullScreenNow[0] = goFullScreen
        }
        // A press in a one-to-one chat that the server still queued behind their floor (it came just as theirs
        // started, or a hands-free renewal): not queued, standby instead (2026-11-24, hen, "a").
        LaunchedEffect(ownHere?.phase) {
            if (group == null && ownHere?.phase == TalkPhase.WAITING) {
                say("queued behind their floor: standby instead (run ${runs.now})")
                actions.finishVoice(false)
                standby = true
                standbySince = System.currentTimeMillis()
            }
        }
        // A call in this chat (the room's or the usual): hands-free and standby end, the call carries the media now.
        LaunchedEffect(chatLive.call != null) {
            if (chatLive.call != null) {
                runs.end()
                handsFree = false
                standby = false
            }
        }
        // Home takes a video call from this chat at once while I'm live here (either side tapped Make it a call).
        LaunchedEffect(handsFree, standby) { chatLive.onBothLive(handsFree || standby) }
        LaunchedEffect(bothLive, chatLive.autoCall) {
            if (bothLive && chatLive.autoCall && actions.callInChat != null) {
                delay(10_000)
                makeCall()
            }
        }
        // A draft that grows (a new line) stays in view, if the list was at the bottom (2026-11-23, hen).
        // Their draft counts as a new message for scrolling: appearing or growing at the bottom, the list glides to it.
        val draftText = shownDrafts.joinToString("\n") { it.second }
        LaunchedEffect(draftText) { if (draftText.isNotEmpty() && list.firstVisibleItemIndex <= 1) list.glideToEnd() }
        LaunchedEffect(atBottom) {
            if (atBottom) {
                unseen = false
                unseenCount = 0
            }
        }
        val pairs = remember(messages, talk.own?.clip, talk.playing?.clip) { ReadMarks.overlappingVideos(messages.filter(keeps), System.currentTimeMillis()) }
        val pairedEarlier = remember(pairs) { pairs.values.toSet() }
        val byId = remember(messages) { messages.associateBy { it.id } }
        // Push-to-talk from both sides with nothing else between, ten minutes apart at most, is one session: one quiet
        // line (2026-11-24, hen: "Push-to-talk · testbot ×5, you ×4 · 21:40–21:45"), a tap lists them.
        val traceRuns = remember(messages, talk.own?.clip, talk.talkers[user]?.clip) {
            val shownAt = HashMap<String, List<TextMessage>>()
            val hidden = HashSet<String>()
            var run = mutableListOf<TextMessage>()
            fun close() {
                if (run.isEmpty()) return
                shownAt[run.last().id] = run
                run.dropLast(1).forEach { hidden += it.id }
                run = mutableListOf()
            }
            for (m in messages) {
                val c = m.clip
                val trace = c != null && !m.deleted && !keeps(m) && c.durationMs >= 500 && talk.own?.clip != m.id && talk.talkers[user]?.clip != m.id
                if (!trace) {
                    if (c == null || keeps(m) || m.deleted) close()
                    continue
                }
                val prev = run.lastOrNull()
                if (prev != null && m.at - prev.at > 10 * 60_000) close()
                run += m
            }
            close()
            shownAt to hidden
        }
        val openSessions = remember(user) { androidx.compose.runtime.mutableStateListOf<String>() }
        // The draft and the buttons float over the chat (2026-11-23, hen: no band behind them): the list scrolls
        // under them to the bottom of the screen, with room at its end so its last message clears them.
        val density = androidx.compose.ui.platform.LocalDensity.current
        var floatingHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        // As measured, every frame (no animation of its own: it moves with the keyboard and the row).
        val floatingRoom = with(density) { floatingHeight.toDp() }
        // The solid strip behind the buttons (2026-11-26, hen), as it is now (it folds away with the keyboard).
        var stripHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val autoplay = remember(user) { VideoAutoplay() }
        // A video playing here with sound stops (muted autoplay takes over) when another is the most visible one, or
        // it leaves the screen (2026-11-24, hen).
        val withSound = talk.playing?.takeIf { it.video && fullVideo == null }?.clip
        LaunchedEffect(withSound) {
            if (withSound == null) return@LaunchedEffect
            androidx.compose.runtime.snapshotFlow {
                val shown = list.layoutInfo.visibleItemsInfo.any { (it.key as? String)?.let { k -> k == withSound || k == "pair@$withSound" || k.endsWith(withSound) } == true }
                val other = autoplay.current?.takeIf { it != withSound }
                !shown || other != null
            }.collect { leave -> if (leave) actions.stopClip() }
        }
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { autoplay.viewport = it.boundsInWindow() }) {
          androidx.compose.runtime.CompositionLocalProvider(LocalVideoAutoplay provides autoplay) {
            // The Talk tab: its stage where the messages are, clear of the strip.
            // Live video there fills the stage, and the pills and video are drawn in it, not over the top.
            if (stage != null) Box(Modifier.fillMaxSize().padding(bottom = floatingRoom)) { stage(videoPanel || myPanel) { liveOverlay() } } else
            LazyColumn(
                Modifier.fillMaxSize()
                    .graphicsLayer { if (talkMove != null) { alpha = rise.value; translationY = 40.dp.toPx() * (1f - rise.value) } }
                    .pointerInput(Unit) {
                    // A tap on the chat puts the keyboard away (the draft stays).
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        if (draft.text.isEmpty()) draft.editing = false
                        // A tap outside the edit cancels it (it turns back unchanged).
                        edits.abort()
                        selected.clear()
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
                // (Send's flight is not one: its slot is kept unseen, so the others make room for it in one glide.)
                val morphing = editing != null || edits.closing.isNotEmpty() || shownDrafts.isNotEmpty() || liveEdits.isNotEmpty() || gapsChanged || gapsMoving
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
                // (What this phone sends with its camera shows in the panel above the buttons now.)
                // Their draft and the message it became: one item, so the draft fills into the message in place.
                fun liveItem(key: String, line: LiveLine) = item(key = key) {
                    // A draft that comes grows in from nothing as it fades in, so the messages above slide up by its height
                    // in the same 200 ms (2026-11-26, hen: "calm, one move"); the list stays pinned to its end.
                    // (Only a draft grows in: the message it became, scrolled away and back, comes whole.)
                    val who = (line as? LiveLine.Draft)?.who ?: (line as? LiveLine.Message)?.who
                    Box(Modifier.animateItem(ease, move, ease).growIn(line is LiveLine.Draft).graphicsLayer { alpha = seeThrough }) { MemberHue(who) { LiveBubble(line, if (group == null) user else null) } }
                }
                val oneDraft = if (group == null) shownDrafts.firstOrNull() else null
                if (group == null) {
                    if (oneDraft != null) liveItem(live.key(null), LiveLine.Draft(null, oneDraft.second, draftCarets[""]))
                } else {
                    shownDrafts.forEach { (who, text) -> liveItem(live.key(who), LiveLine.Draft(who, text, draftCarets[who.orEmpty()])) }
                }
                // The messages; their face beside the last one of mine they read.
                // Two videos at once, one each side: side by side, the later one lower by when it started.
                // A quiet divider where the day changes (2026-11-24, hen), above each day's first message.
                var newerDay: Long? = null
                fun divider(day: Long) = item(key = "day@$day") {
                    Box(Modifier.animateItem(ease, move, ease).fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(dayLabel(day), color = LibraryColors.Muted, style = LibraryType.Tiny)
                    }
                }
                // A group sender's name only on the first of their messages in a row, until someone else's comes
                // (2026-11-27, hen).
                val nameShown = if (group == null) emptySet() else buildSet {
                    var prev: TextMessage? = null
                    for (m in messages) {
                        if (!m.outgoing && (prev == null || prev.outgoing || prev.author != m.author)) add(m.id)
                        prev = m
                    }
                }
                messages.asReversed().forEach { message ->
                    val day = dayOf(message.at)
                    newerDay?.let { if (it != day) divider(it) }
                    newerDay = day
                    val earlier = pairs[message.id]?.let { byId[it] }
                    if (earlier != null) {
                        item(key = "pair@" + earlier.id) {
                            val mineOf = if (earlier.outgoing) earlier else message
                            val faces = marks[mineOf.id].orEmpty()
                            Column(Modifier.animateItem(ease, move, ease).zIndex(if (faces.isEmpty()) 0f else 1f)) {
                                Box(Modifier.graphicsLayer { alpha = seeThrough }) { VideoPair(earlier, message) { m -> MemberHue(if (m.outgoing) null else m.author) { Bubble(m, actions, talk.playing, live = talk.talkers[user]?.clip == m.id || talk.own?.clip == m.id, showAuthor = m.id in nameShown, group = group != null, reach = reaches[m.id], compact = true) } } }
                                if (mineOf.outgoing) MarkGap(mineOf.id, faces, homes, marksAcross, nameOfMark)
                            }
                        }
                        return@forEach
                    }
                    if (message.id in pairedEarlier) return@forEach
                    // A burst the chat doesn't keep: nothing while it is live (the panels show it), then a muted line.
                    if (message.clip != null && !message.deleted && !keeps(message)) {
                        // Still being recorded (waiting for the floor) or live: nothing yet.
                        val liveNow = message.clip!!.durationMs < 500 || talk.talkers[user]?.clip == message.id || talk.own?.clip == message.id || (talk.playing?.clip == message.id && talk.playing?.live == true)
                        val runOf = traceRuns.first[message.id] ?: listOf(message)
                        val nameOf = { m: TextMessage -> if (group != null) m.author ?: m.user else contact?.name ?: user }
                        if (!liveNow && message.id !in traceRuns.second) item(key = message.id) {
                            Box(Modifier.animateItem(ease, move, ease).graphicsLayer { alpha = seeThrough }) {
                                if (runOf.size == 1) {
                                    TalkTrace(message, nameOf(message))
                                } else {
                                    val open = message.id in openSessions
                                    TalkSession(runOf, nameOf, open) { if (open) openSessions -= message.id else openSessions += message.id }
                                }
                            }
                        }
                        return@forEach
                    }
                    // The floating chat: a burst is at the top of the screen while it is live, and lands here once said.
                    if (chatLive.liveRow != null && message.clip != null && !message.deleted &&
                        (talk.talkers[user]?.clip == message.id || talk.own?.clip == message.id || (talk.playing?.clip == message.id && talk.playing?.live == true))
                    ) return@forEach
                    val liveKey = live.morphed[message.id]
                    if (liveKey != null) {
                        liveItem(liveKey, LiveLine.Message(message, if (group != null) message.author else null, live.lastText[message.id]))
                        return@forEach
                    }
                    item(key = message.id) {
                        // Sent while their draft is at the end (2026-11-26, hen's recording at 1.8 s: the chat above jumped up
                        // by the new message's height in one frame while the draft flew): its slot grows in over the
                        // flight's time, so the messages above rise with the flight. (With no draft the list glides instead.)
                        val slot = message.id == sentMorph
                        val grows = remember(slot) { slot && shownDrafts.isNotEmpty() }
                        val faces = if (message.outgoing) marks[message.id].orEmpty() else emptyList()
                        // Mine doesn't fade in as a whole item (a fading item is cut at its own edges, and a face gliding
                        // into its gap starts outside them): only its bubble does, when it is new.
                        val fresh = remember { message.outgoing && !slot && System.currentTimeMillis() - message.at < 2_000 }
                        // (Above the other items while it has faces: one gliding in passes over the bubbles between.)
                        Column(Modifier.animateItem(if (message.outgoing) null else ease, move, ease).zIndex(if (faces.isEmpty()) 0f else 1f)) {
                          Box((if (grows) Modifier.growIn(true, FLIGHT_MS) else Modifier).then(if (fresh) Modifier.fadeIn() else Modifier)) {
                            val edit = edits.of(message.id)
                            if (edit != null) {
                                EditBubble(
                                    edit,
                                    change = { v ->
                                        edit.value = v
                                        actions.editLive(message.id, v.text, v.selection.start, v.selection.end)
                                    },
                                    done = { save ->
                                        if (save) actions.edit(message.id, edit.value.text) else actions.editLive(message.id, null, -1, -1)
                                    },
                                    gone = { edits.gone(edit) },
                                    filled = reaches[message.id] == ReadMarks.Reach.RECEIVED,
                                )
                            } else {
                                // Live video in the bubble: mine while I send it, theirs while it plays (not under the
                                // full-screen player).
                                // Live video shows in the panels now (2026-11-24, hen), not in its bubble.
                                val liveVideo = false
                                // A playing video stays solid while the window arrives (never drawn see-through).
                                MemberHue(if (message.outgoing) null else message.author) {
                                Bubble(
                                    message, actions, talk.playing, live = talk.talkers[user]?.clip == message.id || talk.own?.clip == message.id, showAuthor = message.id in nameShown, group = group != null, reach = reaches[message.id], held = slot, liveVideo = liveVideo, liveSince = talk.own?.takeIf { it.clip == message.id }?.startedAt,
                                    // Send's slot: kept unseen while the draft flies into it; where it lies, whole (not as
                                    // clipped while it grows in).
                                    bubbleModifier = if (slot) Modifier.onGloballyPositioned { flight.to = androidx.compose.ui.geometry.Rect(it.positionInWindow(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }.graphicsLayer { alpha = 0f }
                                    else Modifier.graphicsLayer { alpha = seeThrough },
                                    liveEdit = liveEdits[message.id]?.takeIf { System.currentTimeMillis() - it.at < com.chencohen.ptt.messaging.Draft.FRESH_MS },
                                    startEdit = if (!slot && message.outgoing && message.clip == null && message.call == null && !message.deleted && canWrite && selected.isEmpty()) { offset, width -> edits.start(Editing(message.id, message.body, offset, width)) } else null,
                                    doubleTap = if (message.clip?.video == true && selected.isEmpty()) { { actions.playClip(message.id) } } else null,
                                    selected = message.id in selected,
                                    select = { if (message.id in selected) selected.remove(message.id) else selected.add(message.id) },
                                    selecting = selected.isNotEmpty(),
                                )
                                }
                            }
                          }
                          if (message.outgoing) MarkGap(message.id, faces, homes, marksAcross, nameOfMark)
                        }
                    }
                }
                newerDay?.let { divider(it) }
            }
          }
            // The day of the topmost message on screen, floating under the header when it isn't today (2026-11-24, hen).
            val topDay by androidx.compose.runtime.remember(messages) {
                androidx.compose.runtime.derivedStateOf {
                    val top = list.layoutInfo.visibleItemsInfo.maxByOrNull { it.index }?.key as? String ?: return@derivedStateOf null
                    when {
                        top.startsWith("day@") -> top.removePrefix("day@").toLongOrNull()
                        top.startsWith("pair@") -> byId[top.removePrefix("pair@")]?.let { dayOf(it.at) }
                        else -> byId[top]?.let { dayOf(it.at) }
                    }
                }
            }
            // Messages scrolled under the header (the list is not at its oldest end), and under the buttons' strip
            // (the newest has passed the writing bubble on its way down).
            val under by remember { androidx.compose.runtime.derivedStateOf { list.canScrollForward } }
            val gapPx = with(density) { 16.dp.roundToPx() }
            val underStrip by remember {
                androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > floatingHeight - stripHeight + gapPx }
            }
            // The header's line (2026-11-26, hen): at the very top of the list, only while messages are beneath it.
            Box(Modifier.align(Alignment.TopCenter)) { HeaderHairline(under) }
            val shownDay = topDay?.takeIf { it != dayOf(System.currentTimeMillis()) }
            androidx.compose.animation.AnimatedVisibility(
                shownDay != null,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
            ) {
                var last by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
                if (shownDay != null) last = shownDay
                androidx.compose.animation.Crossfade(last, animationSpec = androidx.compose.animation.core.tween(200), label = "day") { d ->
                    Text(
                        dayLabel(d), color = LibraryColors.OnBackground, style = LibraryType.Small,
                        modifier = Modifier.shadow(3.dp, androidx.compose.foundation.shape.CircleShape)
                            .background(LibraryColors.Raised, androidx.compose.foundation.shape.CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
            // Scrolled up more than a screen (2026-11-24, hen): a round ⌄ above the writing bubble, on their side (the
            // right in Hebrew), with how many came in meanwhile; a tap glides to the end.
            val farUp by androidx.compose.runtime.remember {
                androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > list.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) }
            }
            androidx.compose.animation.AnimatedVisibility(
                farUp || unseen,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(180), initialScale = 0.7f),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) + androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(150), targetScale = 0.7f),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = floatingRoom + 12.dp),
            ) {
                val label = stringResource(R.string.chat_new_message)
                Box {
                    Box(
                        Modifier.size(44.dp).shadow(6.dp, androidx.compose.foundation.shape.CircleShape)
                            .background(LibraryColors.Raised, androidx.compose.foundation.shape.CircleShape)
                            .clickable(role = Role.Button, onClickLabel = label) {
                                scope.launch { list.animateScrollToItem(0) }
                                unseenCount = 0
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(LibraryIcons.ChevronDown, label, tint = LibraryColors.OnBackground) }
                    if (unseenCount > 0) {
                        Text(
                            unseenCount.toString(), color = Color.White, style = LibraryType.Tiny,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)
                                .background(com.chencohen.ptt.ui.style.Pink, androidx.compose.foundation.shape.CircleShape)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            // The live pills and boxes, floating over the chat under the header.
            // (2026-11-25, hen: "just below the header"; they come out from under its edge.)
            // The live pills and boxes, floating over the chat under the header.
            // (2026-11-25, hen: "just below the header"; they come out from under its edge.)
            if (stage == null) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) { liveOverlay() }
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
                // The call's bar and buttons (its videos are in the top panel).
                if (chatLive.call != null) chatLive.call.bottom()
                androidx.compose.animation.AnimatedVisibility(
                    pendingDelete != null,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(180)),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) + androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(150)),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Row(
                        Modifier.padding(top = 6.dp).shadow(4.dp, androidx.compose.foundation.shape.CircleShape)
                            .background(LibraryColors.Inverse, androidx.compose.foundation.shape.CircleShape)
                            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val n = pendingDelete?.ids?.size ?: 1
                        Text(if (n > 1) stringResource(R.string.chat_deleted_several, n) else stringResource(R.string.chat_deleted), color = LibraryColors.OnInverse, style = LibraryType.Small)
                        Text(
                            stringResource(R.string.chat_undo), color = com.chencohen.ptt.ui.style.Pink, style = LibraryType.Small,
                            modifier = Modifier.padding(start = 8.dp).clickable(role = Role.Button) { pendingDelete = null }.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (canWrite) {
                    // Its place comes straight from the keyboard's animated insets (the column's imePadding): no size or
                    // position animation of its own, so it rises and sinks with the keyboard, frame by frame.
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        androidx.compose.animation.AnimatedContent(
                            recordingVoice,
                            transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)).togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))) },
                            label = "draft or recording",
                        ) { recording ->
                            if (recording) RecordingStrip { actions.finishVoice(false) } else PinnedDraft(draft, focus, actions, writing = { edits.abort() }, hint = writeHint, opens = writeOpens) { body, from -> flight.start(body, from, newest?.id) }
                        }
                    }
                }
                // With the keyboard up only the chat and the draft (hen: "don't show the bottom row at all"); Back puts
                // the keyboard away (Android's own), and the row is back.
                // Behind the buttons only, a solid strip in the page's colour (2026-11-26, hen picked "D · Strip for
                // the buttons only"): as tall as the row, with a 1-px line on its top edge only while messages are
                // scrolled under it (200 ms). The writing bubble and my face float above it; messages pass beside them.
                val page = LibraryColors.Background
                val stripLine = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
                val stripLineShown by androidx.compose.animation.core.animateFloatAsState(if (underStrip) 1f else 0f, androidx.compose.animation.core.tween(200), label = "strip line")
                val strip = Modifier.fillMaxWidth().drawBehind {
                    drawRect(page)
                    drawRect(stripLine.copy(alpha = stripLine.alpha * stripLineShown), size = androidx.compose.ui.geometry.Size(size.width, 1f))
                }
                Box(Modifier.zIndex(1f).onSizeChanged { stripHeight = it.height }) {
                when {
                    group != null && group.note != null -> Box(strip) { Note(group.note) }
                    group == null && contact == null -> Box(strip) { Note(stringResource(R.string.contact_gone)) }
                    group == null && contact!!.blocked -> Box(strip) { Note(stringResource(R.string.chat_blocked, contact.user)) }
                    group == null && !contact!!.paired -> Box(strip) { Note(stringResource(R.string.contact_waiting)) }
                    else -> Box(
                        // Clipped only while folding away for the keyboard: otherwise the slide-up target above the Talk
                        // button shows whole, over the draft. The strip folds and fades with the row.
                        Modifier.then(if (rowShown < 1f) Modifier.clipToBounds() else Modifier).layout { measurable, constraints ->
                            val row = measurable.measure(constraints)
                            val height = (row.height * rowShown).toInt()
                            layout(row.width, height) { row.placeWithLayer(0, 0) { alpha = rowShown } }
                        }.then(if (chatLive.call == null) strip else Modifier),
                    ) {
                        if (chatLive.call == null) {
                            // Someone here can't hear a push now (as the grey chip under my pill says it): Push's side
                            // target is then the Urgent bell instead of the hands-free microphone.
                            val hearNow = com.chencohen.ptt.ui.talk.LocalHear.current
                            val toldNow = talk.own?.takeIf { it.chat == user }?.let { talk.heard[it.clip] }.orEmpty()
                            val someoneCantHear = (if (group == null) listOf(user) else group.members).any { who ->
                                com.chencohen.ptt.ui.talk.PushListener(who, who, hearNow(who), toldNow[who] ?: 0).cantHear()
                            }
                            ChatButtons(
                                false, talkingHere, recordingVoice, actions, video = false, handsFree = handsFree,
                                waitingFor = waitingFor,
                                pushTo = pushTo,
                                urgent = talk.own?.let { it.chat == user && it.urgent } == true,
                                cantHear = someoneCantHear,
                                // At the lock: the voice so far is sent, and a video burst follows, hands-free until Stop.
                                lock = {
                                    val run = runs.now
                                    say("lock: hands-free video (run $run, standby $standby)")
                                    // The camera is another app's: voice only, and said so (2026-11-25, hen: don't take it).
                                    if (com.chencohen.ptt.talkVideoCapture?.cameraBusy?.value == true) {
                                        android.widget.Toast.makeText(lockContext, cameraBusyNote, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    goingLiveAt = System.currentTimeMillis()
                                    goingLive = true
                                    actions.setPushVideo?.invoke(true)
                                    handsFree = true
                                    val wasStandby = standby
                                    if (!wasStandby) actions.finishVoice(true)
                                    chainScope.launch {
                                        var waited = 0
                                        while (ownNow != null && waited < 3_000) {
                                            delay(100)
                                            waited += 100
                                        }
                                        delay(100)
                                        val may = runs.may(run)
                                        say("after the lock: the video " + (if (handsFreeNow && !standby && may) "starts" else "doesn't start") + " (run $run of ${runs.now}, hands-free $handsFreeNow, standby $standby)")
                                        if (handsFreeNow && !standby && (may || !ENFORCE_RUNS)) actionsNow.startVoice()
                                    }
                                },
                                sliding = { sliding = it },
                                pressed = { pressing = it },
                                // One preview at a time: while the panel shows my camera, the button's corner doesn't.
                                thumb = !myPanel,
                                stopLive = {
                                    say("Stop (run ${runs.now}, own ${ownNow?.phase})")
                                    runs.end()
                                    val stoppedRun = runs.now
                                    handsFree = false
                                    urgentHandsFree = false
                                    standby = false
                                    // Always: a burst whose start is still on its way shows no own yet, and was left
                                    // running (2026-11-25: 20 s of voice after Stop).
                                    actions.finishVoice(true)
                                    chainScope.launch {
                                        delay(700)
                                        if (runs.may(stoppedRun) && ownNow != null) {
                                            say("Stop: a burst still going after Stop, let go")
                                            actions.finishVoice(true)
                                        }
                                    }
                                },
                                press = {
                                    val run = runs.fingerDown()
                                    say("finger down (run $run): " + (if (theyHold) "standby, they hold the floor" else "starts") + " (hands-free $handsFree, own ${ownNow?.phase})")
                                    if (theyHold) {
                                        standby = true
                                        standbySince = System.currentTimeMillis()
                                    } else {
                                        actions.startVoice()
                                    }
                                },
                                release = { lock ->
                                    say("finger up (run ${runs.now}, lock $lock, standby $standby, own ${ownNow?.phase})")
                                    when {
                                        // Let go on the side target (2026-11-26, hen): the voice push goes on until Stop;
                                        // on the bell (someone can't hear now) it is urgent, and so are its renewals.
                                        lock != PushLock.NONE -> {
                                            say("side lock: hands-free ${if (lock == PushLock.URGENT) "urgent " else ""}voice until Stop (run ${runs.now})")
                                            handsFree = true
                                            urgentHandsFree = lock == PushLock.URGENT
                                            if (lock == PushLock.URGENT) actions.markUrgent()
                                        }
                                        standby -> {
                                            standby = false
                                            runs.end()
                                        }
                                        else -> {
                                            actions.finishVoice(true)
                                            runs.end()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                }
            }
        }
    }
    SendFlightOverlay(flight)
    if (editing != null && androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
        androidx.activity.compose.BackHandler { edits.abort() }
    }
    if ((selected.isNotEmpty() || sheet != null) && androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
        androidx.activity.compose.BackHandler { if (sheet != null) sheet = null else selected.clear() }
    }
    ChatSheetView(sheet, allMessages, group != null, close = { sheet = null }, delete = { ids, everyone -> deleteSoon(ids, everyone) })
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
    back = a.back, openContact = a.openContact, openSafetyNumber = a.openSafetyNumber, send = a.send, retry = a.retry, typing = a.typing, draft = a.draft, openTalk = a.openTalk,
    playClip = a.playClip, saveClip = a.saveClip, stopClip = a.stopClip, startVoice = a.startVoice, startVoiceMessage = a.startVoiceMessage, markUrgent = a.markUrgent,
    playVideo = playVideo, finishVoice = a.finishVoice, micDenied = a.micDenied, call = a.call, shareCall = a.shareCall, playKept = a.playKept,
    setPushVideo = a.setPushVideo, answering = a.answering, edit = a.edit, editLive = a.editLive, delete = a.delete,
    sendFace = a.sendFace, react = a.react, sendLiveWaiting = a.sendLiveWaiting, callInChat = a.callInChat, fullScreen = a.fullScreen,
)


/** Their next burst within this of their last is the same run: its time keeps counting. */
private const val RUN_GAP_MS = 3_000L

/** How long their pill stays after their burst ends, so a video right after grows out of it. */
private const val THEIR_HOLD_MS = 1_000L

/**
 * [now] with whoever just went kept for [THEIR_HOLD_MS] as they were: a member whose voice turns into video (a slide up:
 * one burst ends, the next starts) stays one item whose pill grows into the box, as one to one.
 */
@Composable
private fun heldTalkers(chat: String, now: List<GroupTalker>): List<GroupTalker> {
    val seen = remember(chat) { LinkedHashMap<String, GroupTalker>() }
    var held by remember(chat) { mutableStateOf<Map<String, GroupTalker>>(emptyMap()) }
    val keys = now.map { it.key }.toSet()
    LaunchedEffect(keys) {
        held = held.filterKeys { it !in keys } + seen.filterKeys { it !in keys }
        seen.keys.retainAll(keys)
        if (held.isEmpty()) return@LaunchedEffect
        delay(THEIR_HOLD_MS)
        held = emptyMap()
    }
    now.forEach { seen[it.key] = it }
    return now + held.values.filter { it.key !in keys }
}

/** The log's tag for what the chat's Push button does and what this phone then sends. */
private const val CHAT_TALK = "ChatTalk"

/** Stop is final (TalkRun). Off only to see, in the log, what would start without it. */
private const val ENFORCE_RUNS = true

/**
 * A live draft growing a line (2026-11-26, hen: smooth, the bubble's bottom anchored by the list): only its height eases
 * (160 ms), the text laid out at once at its full width and kept at the top, so the lines already there stay in view and
 * ride up with the bubble's top while the new line is revealed under them. (Kept at the bottom, the first line was hidden
 * for three frames at every wrap: hen's recording at 58.2 s, only "onto" in the bubble.) A line fewer: the same, back.
 * The width is never eased or clipped (easing it cut a Hebrew first letter as each new one came).
 */
@Composable
private fun Modifier.smoothHeight(): Modifier {
    val height = remember { androidx.compose.animation.core.Animatable(-1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var target by remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
    LaunchedEffect(target) {
        if (target < 0f) return@LaunchedEffect
        if (height.value < 0f) height.snapTo(target) else height.animateTo(target, androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    return this.clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        val full = placeable.height.toFloat()
        if (full != target) scope.launch { target = full }
        val shown = (if (height.value < 0f) full else height.value).toInt().coerceAtLeast(0)
        layout(placeable.width, shown) { placeable.place(0, 0) }
    }
}

/**
 * Grows in from no height to its own, once, over 200 ms (the item's fade in): its content measured at once and kept at the
 * bottom, so in the upside-down list the messages above slide up with it. Its width is never eased.
 */
@Composable
private fun Modifier.growIn(grow: Boolean = true, ms: Int = 200): Modifier {
    val shown = remember { androidx.compose.animation.core.Animatable(if (grow) 0f else 1f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    return this.clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val h = (placeable.height * shown.value).toInt()
        layout(placeable.width, h) { placeable.place(0, h - placeable.height) }
    }
}

/** Fades in once, as it comes (a new bubble of mine: its item doesn't fade as a whole, see the list). */
@Composable
private fun Modifier.fadeIn(): Modifier {
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    return graphicsLayer { alpha = shown.value }
}

/** How long a draft stays after its end, waiting for the message it became (it lands in the draft's place). */
private const val DRAFT_HOLD_MS = 1_500L

/** The chat header's height under the status bar, and its face (2026-11-25, hen: slimmer; the live row rests below it). */
private val HEADER_HEIGHT = 48.dp

/**
 * [ring]: 2 a whole teal ring (on this chat's screen), 1 a dashed one (in its talk room), 0 none. From one to another
 * the ring itself moves (2026-11-26, hen: "a continuous animation, not a crossfade"): dashes grow out of nothing, and
 * close into the whole ring ([morphRing], the line my bubble's outline is drawn with).
 */
@Composable
internal fun Modifier.faceRing(ring: Int): Modifier = morphRing(ring, LibraryColors.TheirLine)

/**
 * The header's own edge (2026-11-25, hen chose "A · Hairline"; 2026-11-26, hen: only while messages are scrolled under
 * it): one 1-px line in the palette's outline variant at the top of the list, under the header, fading in and out over
 * 200 ms. At the chat's oldest end there is no line.
 */
@Composable
private fun HeaderHairline(shown: Boolean) {
    val line = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
    val a by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(200), label = "header hairline")
    Box(Modifier.fillMaxWidth().height(with(androidx.compose.ui.platform.LocalDensity.current) { 1.toDp() }).drawBehind { drawRect(line.copy(alpha = line.alpha * a)) })
}

private val HEADER_FACE = 32.dp

@Composable
private fun Header(user: String, contact: Contact?, header: ConversationHeader, actions: ConversationActions, canTalk: Boolean, here: Boolean = false) {
    // Their face's ring (2026-11-25, hen: "ring only, no new words"): solid while they're on this chat's screen, dashed
    // while they're in its talk room (ready to hear) without it on screen, none otherwise.
    val context = androidx.compose.ui.platform.LocalContext.current
    val live by remember { com.chencohen.ptt.liveState(context) }.collectAsState()
    val inRoom = live.room == user && user in live.present
    val ring = when {
        here -> 2
        inRoom -> 1
        else -> 0
    }
    // Slim (2026-11-25, hen: "shrink the header height"): 48 dp under the status bar, the touch targets still 48 dp.
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClick = actions.back),
            contentAlignment = Alignment.Center,
        ) { Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground) }
        Row(
            Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button, onClick = actions.openContact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(HEADER_FACE + 8.dp).faceRing(ring), contentAlignment = Alignment.Center) { com.chencohen.ptt.ui.talk.HearFace(user, HEADER_FACE) { Avatar(user, HEADER_FACE) } }
            Column(Modifier.padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact?.name ?: user, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                    // A shield only while their safety number isn't verified (2026-11-26, hen); a tap opens it.
                    if (contact != null && contact.paired && !contact.verified) {
                        Box(
                            Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(role = Role.Button, onClick = actions.openSafetyNumber),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(LibraryIcons.Shield, stringResource(R.string.contact_safety_unverified), tint = LibraryColors.Warning, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                // No general "online" or "last seen" (2026-11-24, hen: "useless"): whether they are in this chat shows
                // at its end, their face with a ring (ReaderCircle). Only typing is said here.
                if (header.typing) {
                    Text(com.chencohen.ptt.genderedString(R.string.chat_typing, user), color = LibraryColors.Accent, style = LibraryType.Caption)
                } else {
                    // Can't hear now (2026-11-25, hen): "Do Not Disturb", "Silent · hears it later", "In a call".
                    val hearNow = com.chencohen.ptt.ui.talk.hearLine(com.chencohen.ptt.ui.talk.LocalHear.current(user))
                    androidx.compose.animation.AnimatedVisibility(hearNow != null, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)), exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(220))) {
                        val shown = remember { arrayOf("") }
                        if (hearNow != null) shown[0] = hearNow
                        Text(shown[0], color = LibraryColors.Muted, style = LibraryType.Caption, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Voice call and video call, Signal's way, before Talk. */

@Composable
private fun GroupHeader(group: GroupChat, actions: ConversationActions, canTalk: Boolean, chat: String = "", here: Set<String> = emptySet()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val live by remember { com.chencohen.ptt.liveState(context) }.collectAsState()
    val inRoom = if (live.room == chat) live.present else emptySet()
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = actions.back), contentAlignment = Alignment.Center) {
            Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground)
        }
        Row(
            Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button, onClick = actions.openContact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.chencohen.ptt.ui.groups.GroupAvatar(group.name, group.picture, HEADER_FACE)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(group.name, color = LibraryColors.OnBackground, style = LibraryType.RowTitle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                // "2 of 4 can hear now" while someone can't (2026-11-25, hen), else the group's own line.
                val hearOf = com.chencohen.ptt.ui.talk.LocalHear.current
                val hears = group.members.map(hearOf)
                val cant = hears.any { it == com.chencohen.ptt.talk.Hear.DND || it == com.chencohen.ptt.talk.Hear.SILENT || it == com.chencohen.ptt.talk.Hear.IN_CALL }
                val (can, all) = com.chencohen.ptt.talk.CanHearRules.canHearCount(hears)
                Text(if (cant) stringResource(R.string.hear_group_count, can, all) else group.line, color = LibraryColors.Muted, style = LibraryType.Caption, maxLines = 1)
            }
            MemberFaces(group.members, here, inRoom)
        }
    }
}

/**
 * The group's other members' faces in the header (2026-11-25, hen chose "B"): each with its ring (solid: on this
 * chat's screen; dashed: in its talk room; none: away), those here first, then those in the room, then the rest, each
 * group in its own steady order; as many as fit, then "+N". A change of state moves every face to its new place
 * together, in one move.
 */
@Composable
private fun MemberFaces(members: List<String>, here: Set<String>, inRoom: Set<String>) {
    if (members.isEmpty()) return
    val sorted = members.sortedBy { if (it in here) 0 else if (it in inRoom) 1 else 2 }
    // A little bigger, with a little room between them (2026-11-25, hen).
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.widthIn(max = 196.dp).fillMaxHeight()) {
        val step = 46.dp
        val face = 36.dp
        val fit = ((maxWidth - 8.dp) / step).toInt().coerceAtLeast(1)
        val shown = if (sorted.size <= fit) sorted else sorted.take((fit - 1).coerceAtLeast(1))
        val more = sorted.size - shown.size
        val count = shown.size + if (more > 0) 1 else 0
        Box(Modifier.width(step * count + 4.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            shown.forEachIndexed { i, member ->
                androidx.compose.runtime.key(member) {
                    val x by androidx.compose.animation.core.animateDpAsState(step * i, androidx.compose.animation.core.tween(300), label = "face $member")
                    // Each member's face and ring in their own colour (2026-11-27, hen).
                    MemberHue(member) {
                    Box(Modifier.offset(x = x).size(face + 4.dp).faceRing(if (member in here) 2 else if (member in inRoom) 1 else 0), contentAlignment = Alignment.Center) {
                        com.chencohen.ptt.ui.talk.HearFace(member, face - 2.dp) { Avatar(member, face - 2.dp) }
                    }
                    }
                }
            }
            if (more > 0) {
                Box(Modifier.offset(x = step * shown.size).size(face + 4.dp), contentAlignment = Alignment.Center) {
                    Text("+$more", color = LibraryColors.Muted, style = LibraryType.Caption)
                }
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
private fun Bubble(message: TextMessage, actions: ConversationActions, playing: NowPlaying?, live: Boolean = false, showAuthor: Boolean = false, group: Boolean = false, reach: ReadMarks.Reach? = null, liveVideo: Boolean = false, liveSince: Long? = null, compact: Boolean = false, bubbleModifier: Modifier = Modifier,
    /** Their text being edited live: its text as it stands (null: hidden), shown in place with a soft mark. */
    liveEdit: com.chencohen.ptt.messaging.LiveEdit? = null,
    /** Mine: a double tap edits it, the caret at the tapped character ([Int] the bubble's width now, in pixels). */
    startEdit: ((Int, Int) -> Unit)? = null,
    /** A double tap on it (a video burst: play it here with sound). */
    doubleTap: (() -> Unit)? = null,
    /** Selected (a long press; then a tap adds or removes it). */
    selected: Boolean = false,
    select: (() -> Unit)? = null,
    selecting: Boolean = false,
    /** Mine, in Send's slot while the draft flies into it: shown as still on its way, so what came meanwhile is seen once it lands. */
    held: Boolean = false,
) {
    var bubbleWidth by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Where the text lies in the bubble and its layout: a double tap anywhere on the bubble finds its character.
    val textPlace = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(2) }
    var textLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val retry = actions.retry
    val mine = message.outgoing
    // One of the author's faces instead of words (2026-11-24, hen): no bubble around it, and nothing to edit.
    val isFace = message.face > 0 && !message.deleted && message.clip == null && message.call == null
    @Suppress("NAME_SHADOWING") val startEdit = startEdit?.takeIf { !isFace }
    val highlight by androidx.compose.animation.animateColorAsState(if (selected) LibraryColors.Accent.copy(alpha = 0.16f) else Color.Transparent, androidx.compose.animation.core.tween(180), label = "selected")
    // Mine: how far it got is the bubble itself (2026-11-26, hen), no mark beside the time. A dashed outline until the
    // server has it (and when it failed, with the time in red), a whole outline on the server, whole green once every
    // participant's phone has it; the text is the page's on an outline and the bubble's on the green.
    val shownReach = if (mine) (if (held) ReadMarks.Reach.SENDING else reach ?: ReadMarks.Reach.RECEIVED) else null
    val look = shownReach?.let { rememberBubbleLook(it) }
    val reachLabel = when (shownReach) {
        ReadMarks.Reach.SENDING -> stringResource(R.string.chat_status_pending)
        ReadMarks.Reach.ON_SERVER -> stringResource(R.string.chat_status_sent)
        ReadMarks.Reach.RECEIVED -> stringResource(R.string.chat_status_delivered)
        ReadMarks.Reach.FAILED -> stringResource(R.string.chat_status_failed)
        null -> null
    }
    Box(
        Modifier.fillMaxWidth().background(highlight, RoundedCornerShape(10.dp))
            .then(if (selecting && select != null) Modifier.clickable(role = Role.Checkbox, onClick = select) else Modifier),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            bubbleModifier
                .widthIn(max = bubbleMaxWidth())
                .onSizeChanged { bubbleWidth = it.width }
                .onGloballyPositioned { textPlace[0] = it }
                .then(
                    if (startEdit != null || select != null || doubleTap != null) Modifier.pointerInput(message.id, startEdit != null, selecting, doubleTap != null) {
                        // Anywhere on the bubble (2026-11-24, hen: a one-letter message is a small target): a long
                        // press selects it; a double tap on mine edits it, on the text the caret at that character,
                        // elsewhere the nearest place, its end; while selecting, a tap adds or removes it.
                        detectTapGestures(
                            onLongPress = if (select != null && !selecting) { _ -> select() } else null,
                            onTap = if (select != null && selecting) { _ -> select() } else null,
                            onDoubleTap = if (startEdit == null) doubleTap?.let { d -> { _: androidx.compose.ui.geometry.Offset -> d() } } else { at ->
                            val bubble = textPlace[0]
                            val text = textPlace[1]
                            val layout = textLayout
                            val offset = if (bubble != null && text != null && layout != null && bubble.isAttached && text.isAttached) {
                                val p = text.localPositionOf(bubble, at)
                                val inside = p.x >= 0f && p.y >= 0f && p.x <= text.size.width && p.y <= text.size.height
                                if (inside) layout.getOffsetForPosition(p) else message.body.length
                            } else message.body.length
                            startEdit(offset, bubbleWidth)
                        },
                        )
                    } else Modifier,
                )
                .animateContentSize(androidx.compose.animation.core.tween(MORPH_MS))
                .then(if (liveEdit != null) Modifier.border(1.dp, LibraryColors.Accent.copy(alpha = 0.6f), LibraryShapes.Card) else Modifier)
                .then(if (isFace) Modifier else if (look != null) Modifier.myBubble(look) else Modifier.background(LibraryColors.Theirs, LibraryShapes.Card))
                .then(if (reachLabel != null) Modifier.semantics { stateDescription = reachLabel } else Modifier)
                .then(if (message.status == MessageStatus.FAILED) Modifier.clickable(role = Role.Button) { retry(message.id) } else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
          com.chencohen.ptt.ui.style.MineInk(if (look != null && !isFace) look.ink() else LibraryColors.OnMine) {
            if (showAuthor && !mine && message.author != null) {
                Text(message.author!!, color = if (LocalMemberHues.current.isEmpty()) LibraryColors.Accent else LibraryColors.OnTheirs, style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp))
            }
            if (message.deleted) {
                // Deleted by its author: only a faint mark is left.
                Text(
                    stringResource(R.string.chat_deleted), color = LibraryColors.Muted.copy(alpha = 0.8f),
                    style = LibraryType.Small.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                )
            } else if (message.call != null) {
                com.chencohen.ptt.ui.calls.CallEntryContent(message, onInverse = mine, share = actions.shareCall, playKept = actions.playKept) { actions.call(message.call!!.video) }
            } else if (message.clip?.video == true) {
                // Live, then the video it leaves (first picture, length), in the same place.
                androidx.compose.animation.AnimatedContent(
                    liveVideo,
                    transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)).togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))) },
                    label = "live video",
                ) { isLive ->
                    if (isLive) {
                        // Playing with sound here: a tap opens it full screen.
                        Box(Modifier.clickable(role = Role.Button) { actions.playVideo(message.id) }) { LiveVideoBubble(mine, liveSince ?: message.at, framed = false) }
                    } else {
                        VideoClipContent(message, live, onInverse = mine, play = actions.playVideo, save = actions.saveClip, compact = compact, playHere = actions.playClip)
                    }
                }
            } else if (message.clip != null) {
                ClipContent(message, playing, live, actions.playClip, actions.stopClip, onInverse = mine, save = actions.saveClip)
            } else if (isFace) {
                val author = if (mine) "" else message.author ?: message.user
                com.chencohen.ptt.ui.face.FaceSticker(author, message.face - 1, message.body)
            } else {
                if (liveEdit != null) Text(stringResource(R.string.chat_editing), color = LibraryColors.Accent, style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp))
                Text(
                    liveEdit?.text ?: message.body,
                    color = if (mine) LibraryColors.OnMine else LibraryColors.OnTheirs, style = LibraryType.Message,
                    onTextLayout = { textLayout = it },
                    modifier = Modifier.onGloballyPositioned { textPlace[1] = it }
                        .then(if (liveEdit?.text != null && liveEdit.selEnd >= 0) Modifier.liveCaret(textLayout, liveEdit.selStart, liveEdit.selEnd) else Modifier),
                )
            }
            // What became of my push at each listener (2026-11-25, hen): "Saved: Do Not Disturb", "URGENT ✓ Heard…".
            if (mine && !message.deleted) message.clip?.let { c ->
                com.chencohen.ptt.ui.talk.HeardLabels(c, com.chencohen.ptt.messaging.Group.idOf(message.user) != null, { it }, onInverse = true)
            }
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at))
            // The time, and for mine how far it got: no "read" mark (their circle says it).
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (message.editedAt != null) {
                    Text(stringResource(R.string.chat_edited), color = if (mine) LibraryColors.OnMineMuted else LibraryColors.OnTheirsMuted, style = LibraryType.Tiny, modifier = Modifier.padding(end = 4.dp))
                }
                Text(
                    time,
                    color = when {
                        message.status == MessageStatus.FAILED && mine -> LibraryColors.Warning
                        mine -> LibraryColors.OnMineMuted
                        // In a group: the same colour as the sender's name above (2026-11-27, hen).
                        group -> LibraryColors.OnTheirs
                        else -> LibraryColors.OnTheirsMuted
                    },
                    style = LibraryType.Tiny,
                )
            }
            if (message.reactions.isNotEmpty() && !message.deleted) {
                com.chencohen.ptt.ui.face.Reactions(message.reactions, Modifier.align(if (mine) Alignment.Start else Alignment.End).padding(top = 2.dp))
            }
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
                .border(1.dp, LibraryColors.TheirLine.copy(alpha = 0.5f * (1f - settled)), shape)
                .background(LibraryColors.Theirs.copy(alpha = 0.5f + 0.5f * settled), shape)
                .padding(padding),
        ) {
            if (settled < 1f) {
                Text(
                    if (who != null) com.chencohen.ptt.genderedString(R.string.chat_draft_by, who, who) else com.chencohen.ptt.genderedString(R.string.chat_draft, draftUser),
                    color = LibraryColors.TheirLine.copy(alpha = 1f - settled), style = LibraryType.Small,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                if (settled > 0f) text else "$text\u258F",
                color = LibraryColors.OnTheirs.copy(alpha = 0.75f + 0.25f * settled), style = LibraryType.Message,
            )
            if (time != null && settled > 0f) {
                Text(time, color = LibraryColors.OnTheirsMuted.copy(alpha = 0.6f * settled), style = LibraryType.Tiny, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
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

/** What their live item shows: their draft, or the message the draft became. */
internal sealed interface LiveLine {
    data class Draft(val who: String?, val text: String, val caret: Pair<Int, Int>? = null) : LiveLine
    data class Message(val message: TextMessage, val who: String?, val lastDraft: String?) : LiveLine
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
                .border(1.dp, LibraryColors.TheirLine.copy(alpha = 0.5f * (1f - s)), shape)
                .background(LibraryColors.Theirs.copy(alpha = 0.5f + 0.5f * s), shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            // In a group the author's line stays on top ("Dana is writing…", then "Dana"): the same row, its words change.
            // One to one, "Writing…" is in the footer, where the time comes (2026-11-26, hen: "it's jumping, it's
            // supposed to feel calm"): it was a line on top that went as the time's line came, so the bubble grew, then
            // shrank, and the list moved up and back.
            if (who != null) {
                Text(
                    when {
                        message != null && who != null -> who
                        who != null -> com.chencohen.ptt.genderedString(R.string.chat_draft_by, who, who)
                        else -> com.chencohen.ptt.genderedString(R.string.chat_draft, draftUser)
                    },
                    color = LibraryColors.TheirLine.copy(alpha = if (message != null && who != null) 1f else 1f - s), style = LibraryType.Small,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            // While they write: the text itself, nothing around it that measures, eases or clips (2026-11-24, hen: the
            // first Hebrew letter flashed at the edge on every keystroke). Then the message: if it differs from the last
            // draft, it crossfades.
            // Their caret where it is, or, not said (or the draft ended and waits for its message), at the end: always
            // drawn over the text, never a character in it (2026-11-26, hen's recording at 23.35 s: the bar added at the
            // draft's end pushed a full line's last word onto a second line for three frames as the message landed).
            val caretNow = (line as? LiveLine.Draft)?.caret?.takeIf { it.second >= 0 } ?: (text.length to text.length)
            if (message == null) {
                var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                Text(
                    text,
                    color = LibraryColors.OnTheirs.copy(alpha = 0.75f + 0.25f * s), style = LibraryType.Message,
                    onTextLayout = { layout = it },
                    modifier = Modifier.smoothHeight().liveCaret(layout, caretNow.first, caretNow.second),
                )
            } else {
            val differs = message.body != (line as LiveLine.Message).lastDraft
            androidx.compose.animation.AnimatedContent(
                differs to text,
                contentKey = { it.first },
                // Its size follows the text at once, never eased and never clipped (2026-11-24, hen: with Hebrew the
                // bubble's eased width cut the first letter as each new one came): the first letter stays put, the
                // bubble's end grows where the new letter is.
                transitionSpec = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)).togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
                        .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
                },
                label = "draft text",
            ) { (_, t) ->
                // Their caret where it is (2026-11-24, hen), or, not said, a bar at the end.
                val caret = (line as? LiveLine.Draft)?.caret?.takeIf { it.second >= 0 }
                var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                Text(
                    if (message == null && caret == null) "$t\u258F" else t,
                    color = LibraryColors.OnTheirs.copy(alpha = 0.75f + 0.25f * s), style = LibraryType.Message,
                    onTextLayout = { layout = it },
                    modifier = if (message == null && caret != null) Modifier.liveCaret(layout, caret.first, caret.second) else Modifier,
                )
            }
            }
            // The footer, from the first letter: one to one "Writing…" fading out as the time fades in, in the same place;
            // both are always laid out, so the bubble's size doesn't change when the draft becomes the message.
            Box(Modifier.align(Alignment.End).padding(top = 2.dp)) {
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message?.at ?: System.currentTimeMillis())),
                    color = LibraryColors.OnTheirsMuted.copy(alpha = 0.6f * s), style = LibraryType.Tiny,
                )
                if (who == null) {
                    Text(
                        com.chencohen.ptt.genderedString(R.string.chat_draft, draftUser),
                        color = LibraryColors.TheirLine.copy(alpha = 1f - s), style = LibraryType.Tiny, maxLines = 1,
                    )
                }
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

/** The local day [at] falls on, as its midnight (the phone's own time zone). */
internal fun dayOf(at: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
    val c = java.util.Calendar.getInstance(zone)
    c.timeInMillis = at
    c.set(java.util.Calendar.HOUR_OF_DAY, 0)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** How far back [day] is: 0 today, 1 yesterday (calendar days, the phone's time zone). */
internal fun daysAgo(day: Long, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
    val a = java.util.Calendar.getInstance(zone).apply { timeInMillis = day }
    val b = java.util.Calendar.getInstance(zone).apply { timeInMillis = dayOf(now, zone) }
    var n = 0
    while (a.before(b) && n < 400) {
        a.add(java.util.Calendar.DAY_OF_MONTH, 1)
        n++
    }
    return n
}

/** A day as the chat says it: Today, Yesterday, a weekday within the last 6 days, a date this year, else a full date. */
@Composable
internal fun dayLabel(day: Long): String {
    val now = System.currentTimeMillis()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val ago = daysAgo(day, now)
    val thisYear = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.YEAR) ==
        java.util.Calendar.getInstance().apply { timeInMillis = day }.get(java.util.Calendar.YEAR)
    return when {
        ago <= 0 -> stringResource(R.string.chat_today)
        ago == 1 -> stringResource(R.string.chat_yesterday)
        ago <= 6 -> java.text.SimpleDateFormat("EEEE", locale).format(Date(day))
        thisYear -> java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale).format(Date(day))
        else -> java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMyyyy"), locale).format(Date(day))
    }
}

/**
 * Their caret, live (2026-11-24, hen): a thin blinking bar at [end] in the text laid out as [layout] (its own layout,
 * so right to left and mixed text land right), gliding between places; [start]..[end] selected: a soft highlight.
 */
@Composable
internal fun Modifier.liveCaret(layout: androidx.compose.ui.text.TextLayoutResult?, start: Int, end: Int): Modifier {
    if (layout == null || end < 0) return this
    val length = layout.layoutInput.text.length
    val at = end.coerceIn(0, length)
    val rect = layout.getCursorRect(at)
    val glide = androidx.compose.animation.core.tween<Float>(120)
    val x by androidx.compose.animation.core.animateFloatAsState(rect.left, glide, label = "caret x")
    val y by androidx.compose.animation.core.animateFloatAsState(rect.top, glide, label = "caret y")
    val blink by androidx.compose.animation.core.rememberInfiniteTransition(label = "caret").animateFloat(
        1f, 0.15f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(530), androidx.compose.animation.core.RepeatMode.Reverse), label = "blink",
    )
    val from = start.coerceIn(0, length)
    val selection = if (start >= 0 && from != at) layout.getPathForRange(minOf(from, at), maxOf(from, at)) else null
    val color = LibraryColors.Accent
    return this.drawWithContent {
        if (selection != null) drawPath(selection, color.copy(alpha = 0.25f))
        drawContent()
        drawRect(color.copy(alpha = blink), androidx.compose.ui.geometry.Offset(x - 1.dp.toPx(), y), androidx.compose.ui.geometry.Size(2.dp.toPx(), rect.height))
    }
}

/** Messages waiting for their Undo to pass before they are deleted. */
internal data class PendingDelete(val ids: List<String>, val forEveryone: Boolean)

/** A sheet over the chat: a message's Info, or how to delete the selected ones. */
internal sealed interface ChatSheet {
    data class Info(val id: String) : ChatSheet
    data class Delete(val ids: List<String>, val allMine: Boolean) : ChatSheet
}

private fun canWriteHere(group: GroupChat?, contact: Contact?) = if (group != null) group.note == null else contact != null && !contact.blocked && contact.paired

/**
 * The header while messages are selected (2026-11-24, hen, Signal's way): close and how many on the start side;
 * Edit (one text of mine), Info (one) and Delete on the end side. Icons follow the reading direction.
 */
@Composable
private fun SelectionBar(count: Int, close: () -> Unit, edit: (() -> Unit)?, info: (() -> Unit)?, delete: () -> Unit, save: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val closeLabel = stringResource(R.string.chat_close_selection)
        Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = closeLabel, onClick = close), contentAlignment = Alignment.Center) {
            Icon(LibraryIcons.Close, closeLabel, tint = LibraryColors.OnBackground)
        }
        Text(stringResource(R.string.chat_selected, count), color = LibraryColors.OnBackground, style = LibraryType.RowTitle, modifier = Modifier.weight(1f).padding(start = 4.dp))
        @Composable
        fun action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = LibraryColors.OnBackground, key: Color? = null, onClick: () -> Unit) =
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
                com.chencohen.ptt.ui.style.FineIcon(icon, label, tint = tint, key = key)
            }
        androidx.compose.animation.AnimatedVisibility(edit != null) { action(LibraryIcons.Edit, stringResource(R.string.chat_edit)) { edit?.invoke() } }
        androidx.compose.animation.AnimatedVisibility(save != null) { action(LibraryIcons.Download, stringResource(R.string.kept_save)) { save?.invoke() } }
        androidx.compose.animation.AnimatedVisibility(info != null) { action(LibraryIcons.Info, stringResource(R.string.chat_info)) { info?.invoke() } }
        action(LibraryIcons.Trash, stringResource(R.string.chat_delete), LibraryColors.Warning, key = LibraryColors.Error, onClick = delete)
    }
}

/** The sheet over the chat (no dialog window: the quick-reply window has none to give). */
@Composable
private fun ChatSheetView(sheet: ChatSheet?, messages: List<TextMessage>, group: Boolean, close: () -> Unit, delete: (List<String>, Boolean) -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        sheet != null,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
    ) {
        var shown by remember { mutableStateOf(sheet) }
        if (sheet != null) shown = sheet
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClickLabel = null, role = null, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = close),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
                    .background(LibraryColors.Raised, LibraryShapes.Card)
                    .clickable(enabled = false) {}
                    .padding(vertical = 8.dp),
            ) {
                when (val s = shown) {
                    is ChatSheet.Info -> {
                        val m = messages.firstOrNull { it.id == s.id }
                        if (m != null) {
                            val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            @Composable
                            fun line(label: String, at: Long?) {
                                if (at == null) return
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                                    Text(label, color = LibraryColors.Muted, style = LibraryType.Body, modifier = Modifier.weight(1f))
                                    Text(fmt.format(Date(at)), color = LibraryColors.OnBackground, style = LibraryType.Body)
                                }
                            }
                            line(stringResource(if (m.outgoing) R.string.chat_info_sent else R.string.chat_info_received), m.at)
                            if (m.outgoing) {
                                if (group) {
                                    m.deliveredTo.entries.sortedBy { it.value }.forEach { (who, at) -> line(stringResource(R.string.chat_info_delivered) + " · " + who, at) }
                                    m.readBy.entries.sortedBy { it.value }.forEach { (who, at) -> line(stringResource(R.string.chat_info_read) + " · " + who, at) }
                                } else {
                                    line(stringResource(R.string.chat_info_delivered), m.deliveredAt)
                                    line(stringResource(R.string.chat_info_read), m.readAt)
                                }
                            }
                            line(stringResource(R.string.chat_info_edited), m.editedAt)
                        }
                    }
                    is ChatSheet.Delete -> {
                        @Composable
                        fun choice(label: String, tint: Color, onClick: () -> Unit) = Text(
                            label, color = tint, style = LibraryType.Body,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                        if (s.allMine) choice(stringResource(R.string.chat_delete), LibraryColors.Warning) { delete(s.ids, true) }
                        choice(stringResource(R.string.chat_delete_for_me), LibraryColors.Warning) { delete(s.ids, false) }
                        choice(stringResource(R.string.chat_undo), LibraryColors.OnBackground, close)
                    }
                    null -> {}
                }
            }
        }
    }
}

/**
 * The chat's edits (2026-11-24, hen): the one in progress ([editing]) and those turning back ([closing]). A new
 * double tap, a tap or focus on the writing bubble, Back or a tap outside end the one in progress unchanged (it turns
 * back, and its cancel goes to them).
 */
internal class EditSession {
    var editing by mutableStateOf<Editing?>(null)
        private set
    val closing = androidx.compose.runtime.mutableStateListOf<Editing>()

    /** Edit [next]; one in progress turns back first. */
    fun start(next: Editing) {
        if (editing?.id == next.id) return
        abort()
        editing = next
    }

    /** The edit in progress ends unchanged (it turns back; its cancel is sent as it does). */
    fun abort() {
        val e = editing ?: return
        e.leaving = true
        closing += e
        editing = null
    }

    /** The edit for message [id], in progress or turning back. */
    fun of(id: String): Editing? = editing?.takeIf { it.id == id } ?: closing.lastOrNull { it.id == id }

    /** [e] has turned back (or was saved). */
    fun gone(e: Editing) {
        closing.remove(e)
        if (editing === e) editing = null
    }
}

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
private fun EditBubble(edit: Editing, change: (androidx.compose.ui.text.input.TextFieldValue) -> Unit, done: (save: Boolean) -> Unit, gone: () -> Unit, /** The bubble it opens from is whole green (every phone has it), not an outline. */ filled: Boolean = true) {
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
    val coral = LibraryColors.Writing
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pad = 14.dp
    val from = with(density) { edit.width.toDp() }.coerceAtLeast(pad * 2 + 24.dp)
    // The box keeps the bubble's bounds (2026-11-24, hen): its width until the typed text wouldn't fit, then it widens
    // toward the middle, up to 85% of the row, before it wraps (editBoxWidth).
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val longest = remember(edit.value.text) {
        with(density) {
            val r = measurer.measure(edit.value.text.ifEmpty { " " }, LibraryType.Message)
            (0 until r.lineCount).maxOf { r.getLineRight(it) - r.getLineLeft(it) }.toDp()
        }
    }
    val target = editBoxWidth(from, longest + pad * 2 + 2.dp, chatRowWidth() * DRAFT_MAX)
    val boxWidth by androidx.compose.animation.core.animateDpAsState(target, androidx.compose.animation.core.tween(150), label = "edit width")
    Box(Modifier.fillMaxWidth(), contentAlignment = MySide) {
        Column(
            Modifier.width(boxWidth)
                .border(1.5.dp, coral.copy(alpha = p), LibraryShapes.Card)
                .background(androidx.compose.ui.graphics.lerp(if (filled) LibraryColors.Mine else LibraryColors.Background, LibraryColors.Background, p), LibraryShapes.Card)
                .background(coral.copy(alpha = 0.08f * p), LibraryShapes.Card)
                .padding(start = pad, end = pad, top = 8.dp, bottom = 6.dp),
        ) {
            // The text exactly where the bubble's was: same style, padding and width (its lines unchanged).
            androidx.compose.foundation.text.BasicTextField(
                value = edit.value,
                onValueChange = { v ->
                    if (v.text != edit.value.text) edit.changed = true
                    change(v.copy(text = v.text.take(4_000)))
                },
                textStyle = LibraryType.Message.copy(color = androidx.compose.ui.graphics.lerp(if (filled) LibraryColors.OnMine else LibraryColors.OnBackground, LibraryColors.OnBackground, p)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(coral),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            // ✓ in the place of the time and its mark, at the end of that line (2026-11-24, hen): 6 dp under the text,
            // the box's own inset from its outline, 18 dp with a 48 dp touch area around it (the box grows a little at
            // its bottom; the text stays).
            Row(
                Modifier.align(Alignment.End).padding(top = 6.dp).height(18.dp).graphicsLayer { alpha = p },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val save = stringResource(R.string.chat_edit_save)
                val can = edit.value.text.isNotBlank()
                SmallAction(save, enabled = can && p > 0.5f, onClick = { if (saving == null) saving = true }) {
                    Box(
                        Modifier.size(18.dp).graphicsLayer { scaleX = 0.6f + 0.4f * p; scaleY = 0.6f + 0.4f * p }
                            .background(if (can) coral else coral.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(LibraryIcons.Check, save, tint = Color.White, modifier = Modifier.size(12.dp)) }
                }
            }
        }
    }
}

/** A small control drawn at its own size, its touch area 48 dp around it (not taking room). */
@Composable
private fun SmallAction(label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.layout { m, c ->
            val touch = 48.dp.roundToPx()
            val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed(touch, touch))
            val shown = 18.dp.roundToPx()
            layout(shown, shown) { pl.place((shown - touch) / 2, (shown - touch) / 2) }
        }.clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
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
    /** A new one for each flight: the copy waits where the draft was, never as the last flight ended. */
    var progress by mutableStateOf(androidx.compose.animation.core.Animatable(0f))
        private set

    /** What was newest in the chat as Send was pressed: the sent message comes after it (never matched by text). */
    private var before: String? = null

    fun start(text: String, bounds: androidx.compose.ui.geometry.Rect?, newestNow: String?) {
        progress = androidx.compose.animation.core.Animatable(0f)
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
    val coral = LibraryColors.Writing
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
            // It lands as a message on its way (2026-11-26, hen): the draft's whole outline turns green as it opens
            // into dashes (the line itself moves, [drawMorphLine]); no fill.
            .background(LibraryColors.Background, LibraryShapes.Card)
            .background(coral.copy(alpha = 0.08f * (1f - p)), LibraryShapes.Card)
            .then(
                run {
                    val line = androidx.compose.ui.graphics.lerp(coral, LibraryColors.MineLine, p)
                    Modifier.drawBehind {
                        val w = 1.5.dp.toPx()
                        val r = 16.dp.toPx()
                        val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(w / 2f, w / 2f, size.width - w / 2f, size.height - w / 2f, androidx.compose.ui.geometry.CornerRadius(r - w / 2f))) }
                        val length = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, true) }.length
                        drawMorphLine(path, length, line, w, androidx.compose.ui.util.lerp(1f, LINE_DASHED, p), 9.dp.toPx())
                    }
                },
            )
            .padding(start = start, end = end, top = 8.dp, bottom = 8.dp),
    ) {
        // The label folds away above the text, which keeps its size and moves with the box.
        Column {
            Text(text, color = LibraryColors.OnBackground, style = LibraryType.Message)
        }
        Row(Modifier.align(Alignment.BottomEnd).graphicsLayer { alpha = p }, verticalAlignment = Alignment.CenterVertically) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis())), color = LibraryColors.OnBackground.copy(alpha = 0.6f), style = LibraryType.Tiny)
        }
    }
}

/** The pinned draft: the empty bubble or the one being written; typing and live typing told from here. */
@Composable
private fun PinnedDraft(
    draft: ChatDraft,
    focus: androidx.compose.ui.focus.FocusRequester,
    actions: ConversationActions,
    /** The writing bubble was tapped or took the focus: an edit in progress ends unchanged. */
    writing: () -> Unit = {},
    hint: String? = null,
    opens: (() -> Unit)? = null,
    sent: (String, androidx.compose.ui.geometry.Rect?) -> Unit,
) {
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
            writing()
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
    var faces by remember { mutableStateOf(false) }
    val sendFace = actions.sendFace
    val myFace = com.chencohen.ptt.ui.face.faceOf("")
    androidx.compose.foundation.layout.Column {
    if (sendFace != null && myFace != null) {
        androidx.compose.animation.AnimatedVisibility(faces) {
            com.chencohen.ptt.ui.face.FaceTray(current = null) { code ->
                faces = false
                sendFace(code, com.chencohen.ptt.face.Expression.of(code)?.emoji ?: "")
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
    if (sendFace != null && myFace != null) {
        // My faces to send (2026-11-24, hen): my own face, a tap opens them above.
        val label = stringResource(R.string.face_send)
        androidx.compose.foundation.Image(
            myFace, label, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.padding(start = 8.dp).size(40.dp).clickable(role = Role.Button, onClickLabel = label) { faces = !faces },
        )
    }
    Box(Modifier.weight(1f)) {
    ComposeBubble(
        draft.text, draft.editing, focus, {
            if (opens != null) opens() else {
                writing()
                draft.editing = true
            }
        },
        hint = hint,
        opens = opens != null,
        focused = writing,
        modifier = Modifier.graphicsLayer {
            alpha = appear.value
            val scale = 0.85f + 0.15f * appear.value
            scaleX = scale
            scaleY = scale
            // From my side: the end, the left in Hebrew.
            transformOrigin = sideOrigin(mine = true, rtl = rtl, y = 0.5f)
        },
        bubbleModifier = Modifier.onGloballyPositioned { draft.bounds = it.boundsInWindow() },
        caret = { value, range -> actions.draft(value, range.start, range.end) },
        change = { value ->
            draft.text = value
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
                actions.draft("", -1, -1)
                draft.text = ""
                draft.lastTyping = 0L
            }
        },
    )
    }
    }
    }
}

/**
 * The chat's live side (2026-11-24, hen): which bursts it keeps as messages, who is live and waiting for this user's
 * floor, whether both live becomes a call by itself, and a video call shown inside it.
 */
class ChatLive(
    val keeps: (TextMessage) -> Boolean = { true },
    val liveWaiting: Set<String> = emptySet(),
    val autoCall: Boolean = false,
    val call: InChatCall? = null,
    val onBothLive: (Boolean) -> Unit = {},
    /** A call is on (any chat): the camera is the call's; push-to-video takes it back when it ends. */
    val anyCall: Boolean = false,
    /** Settings › Talk › Get camera ready while talking: the camera opens, unseen, at finger-down on Push. */
    val cameraReady: Boolean = true,
    /** A call in this chat's talk room with this phone's camera on: the camera stays open for it. */
    val roomCallCamera: Boolean = false,
    /**
     * The live row (voice pills, video boxes) is drawn elsewhere, not in the chat: told here what is live, each
     * time it changes (the floating chat: TalkBubble's row at the top of the screen).
     */
    val liveRow: ((LiveNow) -> Unit)? = null,
)

/** A group's members' colours (hue indexes by user), for their bubbles, faces, names and live drafts. */
internal val LocalMemberHues = androidx.compose.runtime.compositionLocalOf<Map<String, Int>> { emptyMap() }

/** [content] in member [who]'s colour, when the chat gives them one (a group). */
@Composable
internal fun MemberHue(who: String?, content: @Composable () -> Unit) {
    val hue = who?.let { LocalMemberHues.current[it] }
    if (hue == null) content() else com.chencohen.ptt.ui.style.TheirHue(hue, content)
}
