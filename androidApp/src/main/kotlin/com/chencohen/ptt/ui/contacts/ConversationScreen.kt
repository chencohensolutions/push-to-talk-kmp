package com.chencohen.ptt.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import com.chencohen.ptt.messaging.ReadMarks
import androidx.compose.foundation.layout.ime
import com.chencohen.ptt.talk.TalkPhase
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.animateContentSize
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
) {
    // A video burst tapped in the chat plays full screen over it (2026-11-23, hen); Back or a tap closes it.
    var fullVideo by remember(user) { mutableStateOf<String?>(null) }
    val chatActions = actions
    val actions = ConversationActionsWith(chatActions) { id ->
        fullVideo = id
        chatActions.playClip(id)
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(LibraryColors.Background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        val canTalk = if (group != null) group.note == null else contact?.paired == true && !contact.blocked
        if (group != null) GroupHeader(group, actions, canTalk) else Header(user, contact, header, actions, canTalk)
        if (talkLine != null && (talk.talkers[user] != null || talk.own?.chat == user)) {
            Text(
                talkLine,
                color = LibraryColors.Accent,
                style = LibraryType.Caption,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = actions.openTalk).padding(vertical = 4.dp),
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
        LaunchedEffect(newest?.id) {
            if (newest == null) return@LaunchedEffect
            if (newest.outgoing || list.firstVisibleItemIndex <= 1) {
                list.animateScrollToItem(0)
                unseen = false
            } else {
                unseen = true
            }
        }
        val atBottom by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex == 0 } }
        val morph = rememberDraftMorph(user, drafts, newest)
        // How far they read: their circle after the last message of mine they read (one-to-one; not when they hide
        // their presence).
        val reader = if (group == null && ReadMarks.showsReader(header.presence)) ReadMarks.lastRead(messages) else null
        // Writing in the chat (2026-11-23, hen): the draft bubble at the end of the list.
        val canWrite = if (group != null) group.note == null else contact != null && !contact.blocked && contact.paired
        var text by rememberSaveable(user) { mutableStateOf("") }
        var editing by rememberSaveable(user) { mutableStateOf(false) }
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        var lastTyping by remember { mutableLongStateOf(0L) }
        var sentBody by remember(user) { mutableStateOf<String?>(null) }
        var sentMorph by remember(user) { mutableStateOf<String?>(null) }
        if (newest != null && newest.outgoing && sentBody != null && newest.body == sentBody && sentMorph != newest.id) {
            sentMorph = newest.id
            sentBody = null
        }
        LaunchedEffect(sentMorph) {
            if (sentMorph != null) {
                delay(MORPH_MS + 50L)
                sentMorph = null
            }
        }
        LaunchedEffect(editing) { if (editing) runCatching { focus.requestFocus() } }
        // Leaving the chat ends a live draft (it fades on the other phone).
        androidx.compose.runtime.DisposableEffect(user) { onDispose { actions.draft("") } }
        LaunchedEffect(text) {
            if (text.isNotEmpty()) {
                // Stopped after a pause in typing.
                delay(6_000)
                if (lastTyping != 0L) {
                    actions.typing(false)
                    lastTyping = 0L
                }
            }
        }
        // The draft stays in view as it grows, if the list was at the bottom.
        LaunchedEffect(text, editing) { if (editing && list.firstVisibleItemIndex == 0) list.scrollToItem(0) }
        fun change(value: String) {
            text = value
            actions.draft(text)
            val now = System.currentTimeMillis()
            if (text.isBlank()) {
                if (lastTyping != 0L) actions.typing(false)
                lastTyping = 0L
            } else if (now - lastTyping > 4_000) {
                actions.typing(true)
                lastTyping = now
            }
        }
        fun send() {
            val body = text.trim()
            if (body.isEmpty()) return
            sentBody = body
            actions.send(body)
            actions.draft("")
            text = ""
            lastTyping = 0L
        }
        val recordingVoice = talk.own?.takeIf { it.chat == user && it.phase == TalkPhase.VOICE } != null
        val talkingHere = talk.own?.takeIf { it.chat == user && (it.phase == TalkPhase.TALKING || it.phase == TalkPhase.REQUESTING) } != null
        // A draft that grows (a new line) stays in view, if the list was at the bottom (2026-11-23, hen).
        val draftText = drafts.joinToString("\n") { it.second }
        LaunchedEffect(draftText) { if (draftText.isNotEmpty() && list.firstVisibleItemIndex == 0) list.scrollToItem(0) }
        LaunchedEffect(atBottom) { if (atBottom) unseen = false }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    // A tap on the chat puts the keyboard away (the draft stays).
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        if (text.isEmpty()) editing = false
                    })
                },
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Every change eases in and out (2026-11-23, hen: "everything with a transition, no sharp changes").
                val ease = androidx.compose.animation.core.tween<Float>(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                val move = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                // The end of the chat: a voice message recording, or this phone's draft (the empty bubble at rest).
                if (canWrite) {
                    if (recordingVoice) {
                        item(key = "recording") { Box(Modifier.animateItem(ease, move, ease)) { RecordingStrip { actions.finishVoice(false) } } }
                    } else {
                        item(key = "compose") { Box(Modifier.animateItem(ease, move, ease)) { ComposeBubble(text, editing, focus, { editing = true }, ::change, ::send) } }
                    }
                }
                // One-to-one: their circle at the very end (they read everything) and their live draft share one place:
                // the circle grows into the draft, and back if they clear it; the draft turns into the message.
                val oneDraft = if (group == null) drafts.firstOrNull() else null
                val circleAtEnd = reader != null && ReadMarks.atEnd(messages)
                if (group == null && morph?.key != "draft/" && (oneDraft != null || circleAtEnd)) {
                    item(key = "draft/") {
                        Box(Modifier.animateItem(ease, move, ease)) {
                            androidx.compose.animation.AnimatedContent(
                                oneDraft,
                                transitionSpec = {
                                    (androidx.compose.animation.fadeIn(ease) + androidx.compose.animation.scaleIn(ease, initialScale = 0.6f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)))
                                        .togetherWith(androidx.compose.animation.fadeOut(ease))
                                        .using(androidx.compose.animation.SizeTransform(clip = false))
                                },
                                contentKey = { it != null },
                                label = "circle and draft",
                            ) { d ->
                                if (d != null) DraftBubble(d.first, d.second) else ReaderCircle(user, reader?.readAt, end = false)
                            }
                        }
                    }
                }
                // Drafts written live in a group, each at the bottom until its message comes; then it turns into it.
                if (group != null) {
                    items(drafts.filter { "draft/" + (it.first ?: "") != morph?.key }, key = { "draft/" + (it.first ?: "") }) { (who, text) -> Box(Modifier.animateItem(ease, move, ease)) { DraftBubble(who, text) } }
                }
                // The messages, and their circle right after the last one of mine they read (it glides down as they read).
                messages.asReversed().forEach { message ->
                    if (reader != null && message.id == reader.id && !circleAtEnd) {
                        item(key = "reader") { Box(Modifier.animateItem(ease, move, ease)) { ReaderCircle(user, reader.readAt, end = true) } }
                    }
                    item(key = if (message.id == morph?.id) morph.key else message.id) {
                        Box(Modifier.animateItem(ease, move, ease)) {
                            if (message.id == morph?.id) {
                                MorphingBubble(message, if (group != null) message.author else null)
                            } else if (message.id == sentMorph) {
                                SentMorphBubble(message)
                            } else {
                                Bubble(message, actions, talk.playing, live = talk.talkers[user]?.clip == message.id || talk.own?.clip == message.id, showAuthor = group != null, tick = ReadMarks.tick(messages, message))
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
        }
        // With the keyboard up only the chat and the draft (hen: "don't show the bottom row at all"); Back puts the
        // keyboard away (Android's own), and the row is back.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val keyboardUp = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density) > 0
        when {
            group != null && group.note != null -> Note(group.note)
            group == null && contact == null -> Note(stringResource(R.string.contact_gone))
            group == null && contact!!.blocked -> Note(stringResource(R.string.chat_blocked, contact.user))
            group == null && !contact!!.paired -> Note(stringResource(R.string.contact_waiting))
            else -> androidx.compose.animation.AnimatedVisibility(
                !keyboardUp,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
            ) { ChatButtons(false, talkingHere, recordingVoice, actions) }
        }
    }
    fullVideo?.let { id ->
        val playingIt = talk.playing?.clip == id
        LaunchedEffect(playingIt) { if (!playingIt) { delay(400); if (talk.playing?.clip != id) fullVideo = null } }
        androidx.activity.compose.BackHandler {
            chatActions.stopClip()
            fullVideo = null
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
)

/** The Talk button of the header: Talk mode. */
@Composable
private fun TalkButton(actions: ConversationActions) {
    Box(
        Modifier.size(48.dp).clickable(role = Role.Button, onClick = actions.openTalk),
        contentAlignment = Alignment.Center,
    ) { Icon(LibraryIcons.Talk, stringResource(R.string.talk_open), tint = LibraryColors.OnBackground) }
}

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
                    header.typing -> stringResource(R.string.chat_typing)
                    header.presence?.online == true -> stringResource(R.string.chat_online)
                    header.presence?.lastSeen != null -> stringResource(R.string.chat_last_seen, lastSeen(header.presence.lastSeen!!))
                    else -> null
                }
                if (line != null) {
                    Text(line, color = if (header.typing || header.presence?.online == true) LibraryColors.Accent else LibraryColors.Muted, style = LibraryType.Caption)
                }
            }
        }
        if (canTalk) TalkButton(actions)
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
        if (canTalk) TalkButton(actions)
    }
}

/** "14:05" today, else the date and time. */
private fun lastSeen(at: Long): String {
    val today = System.currentTimeMillis() - at < 20 * 3600_000L
    val format = if (today) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return format.format(Date(at))
}

@Composable
private fun Bubble(message: TextMessage, actions: ConversationActions, playing: NowPlaying?, live: Boolean = false, showAuthor: Boolean = false, tick: ReadMarks.Tick = ReadMarks.Tick.NONE) {
    val retry = actions.retry
    val mine = message.outgoing
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
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
                VideoClipContent(message, live, onInverse = mine, play = actions.playVideo, save = actions.saveClip)
            } else if (message.clip != null) {
                ClipContent(message, playing, live, actions.playClip, actions.stopClip, onInverse = mine, save = actions.saveClip)
            } else {
                Text(message.body, color = if (mine) LibraryColors.OnMine else LibraryColors.OnBackground, style = LibraryType.Body)
            }
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at))
            // The time, and for mine how far it got: no "read" mark (their circle says it).
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
    settled: Float = 0f,
    time: String? = null,
    shape: androidx.compose.ui.graphics.Shape = LibraryShapes.Card,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .animateContentSize(androidx.compose.animation.core.tween(250))
                .border(1.dp, LibraryColors.Accent.copy(alpha = 0.5f * (1f - settled)), shape)
                .background(LibraryColors.Raised.copy(alpha = 0.5f + 0.5f * settled), shape)
                .padding(padding),
        ) {
            if (settled < 1f) {
                Text(
                    if (who != null) stringResource(R.string.chat_draft_by, who) else stringResource(R.string.chat_draft),
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
 * The newest message just replaced a live draft that was on screen: it turns into the message in place (250 ms,
 * keeping the draft's place in the list), then the ordinary bubble takes over. Null: no draft to turn.
 */
internal class DraftMorph(val id: String, val key: String)

/** Remembers which new message comes in where a draft was ([drafts] now; [newest] the newest message). */
@Composable
internal fun rememberDraftMorph(chat: String, drafts: List<Pair<String?, String>>, newest: TextMessage?): DraftMorph? {
    var lastDrafts by remember(chat) { mutableStateOf(drafts) }
    var lastNewest by remember(chat) { mutableStateOf(newest?.id) }
    var morph by remember(chat) { mutableStateOf<DraftMorph?>(null) }
    if (newest != null && newest.id != lastNewest) {
        val from = lastDrafts.firstOrNull { (who, _) -> !newest.outgoing && (who == null || who == newest.author) }
        if (from != null && newest.clip == null && newest.call == null) morph = DraftMorph(newest.id, "draft/" + (from.first ?: ""))
        lastNewest = newest.id
    }
    androidx.compose.runtime.SideEffect { lastDrafts = drafts }
    LaunchedEffect(morph?.id) {
        if (morph != null) {
            delay(MORPH_MS + 50L)
            morph = null
        }
    }
    return morph
}

internal const val MORPH_MS = 250

/** A message turning from its draft: [settled] goes 0 to 1 in [MORPH_MS]. */
@Composable
internal fun MorphingBubble(message: TextMessage, who: String?, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = LibraryShapes.Card, padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
    val settled = remember(message.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(message.id) { settled.animateTo(1f, androidx.compose.animation.core.tween(MORPH_MS)) }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at))
    DraftBubble(who, message.body, modifier, settled.value, time, shape, padding)
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
