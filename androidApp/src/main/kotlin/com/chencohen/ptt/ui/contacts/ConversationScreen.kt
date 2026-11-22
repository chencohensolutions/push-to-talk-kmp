package com.chencohen.ptt.ui.contacts

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    /** The Talk button in the header: Talk mode. */
    val openTalk: () -> Unit = {},
    val playClip: (String) -> Unit = {},
    val stopClip: () -> Unit = {},
    /** The composer's microphone went down (a voice message) or up ([send] false: cancelled). */
    val startVoice: () -> Unit = {},
    val finishVoice: (send: Boolean) -> Unit = {},
    /** The microphone wasn't allowed. */
    val micDenied: () -> Unit = {},
    /** The call buttons in the header, and a call's entry in the chat (phase 6). */
    val call: (video: Boolean) -> Unit = {},
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
) {
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
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages.asReversed(), key = { it.id }) { message -> Bubble(message, actions, talk.playing, showAuthor = group != null) }
        }
        when {
            group != null -> if (group.note != null) Note(group.note) else Composer(user, actions, talk)
            contact == null -> Note(stringResource(R.string.contact_gone))
            contact.blocked -> Note(stringResource(R.string.chat_blocked, contact.user))
            !contact.paired -> Note(stringResource(R.string.contact_waiting))
            else -> Composer(user, actions, talk)
        }
    }
}

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
                    Text(contact?.displayName ?: user, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
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
        if (canTalk) CallButtons(actions)
        if (canTalk) TalkButton(actions)
    }
}

/** Voice call and video call, Signal's way, before Talk. */
@Composable
private fun CallButtons(actions: ConversationActions) {
    val voice = stringResource(R.string.call_voice)
    val video = stringResource(R.string.call_video)
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = video) { actions.call(true) }, contentAlignment = Alignment.Center) {
        Icon(LibraryIcons.Video, video, tint = LibraryColors.OnBackground)
    }
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = voice) { actions.call(false) }, contentAlignment = Alignment.Center) {
        Icon(LibraryIcons.Call, voice, tint = LibraryColors.OnBackground)
    }
}

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
        if (canTalk) CallButtons(actions)
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
private fun Bubble(message: TextMessage, actions: ConversationActions, playing: NowPlaying?, showAuthor: Boolean = false) {
    val retry = actions.retry
    val mine = message.outgoing
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(if (mine) LibraryColors.Inverse else LibraryColors.Raised, LibraryShapes.Card)
                .then(if (message.status == MessageStatus.FAILED) Modifier.clickable(role = Role.Button) { retry(message.id) } else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (showAuthor && !mine && message.author != null) {
                Text(message.author!!, color = LibraryColors.Accent, style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp))
            }
            if (message.call != null) {
                com.chencohen.ptt.ui.calls.CallEntryContent(message, onInverse = mine) { actions.call(message.call!!.video) }
            } else if (message.clip != null) {
                ClipContent(message, playing, actions.playClip, actions.stopClip, onInverse = mine)
            } else {
                Text(message.body, color = if (mine) LibraryColors.OnInverse else LibraryColors.OnBackground, style = LibraryType.Body)
            }
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at))
            val status = if (!mine) null else when (message.status) {
                MessageStatus.PENDING -> stringResource(R.string.chat_status_pending)
                MessageStatus.SENT -> stringResource(R.string.chat_status_sent)
                MessageStatus.DELIVERED -> stringResource(R.string.chat_status_delivered)
                MessageStatus.READ -> stringResource(R.string.chat_status_read)
                MessageStatus.FAILED -> stringResource(R.string.chat_status_failed)
            }
            Text(
                listOfNotNull(time, status).joinToString(" · "),
                color = when {
                    message.status == MessageStatus.FAILED && mine -> LibraryColors.Warning
                    mine -> LibraryColors.OnInverseMuted
                    else -> LibraryColors.Muted
                },
                style = LibraryType.Tiny,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
}

/** The line to write in, and Send. Typing is told at most every 4 seconds, and stopped when the line empties or goes. */
@Composable
private fun Composer(user: String, actions: ConversationActions, talk: TalkUiState) {
    var text by rememberSaveable(user) { mutableStateOf("") }
    var lastTyping by remember { mutableLongStateOf(0L) }
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value.take(4000)
                val now = System.currentTimeMillis()
                if (text.isBlank()) {
                    if (lastTyping != 0L) actions.typing(false)
                    lastTyping = 0L
                } else if (now - lastTyping > 4_000) {
                    actions.typing(true)
                    lastTyping = now
                }
            },
            placeholder = { Text(stringResource(R.string.chat_hint)) },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
            modifier = Modifier.weight(1f),
        )
        // Signal's way: the microphone while the line is empty, Send once there is text.
        if (text.isBlank()) {
            VoiceButton(talk.own?.takeIf { it.chat == user }, actions.startVoice, actions.finishVoice, actions.micDenied, Modifier.padding(start = 8.dp))
        } else {
            PrimaryButton(
                stringResource(R.string.chat_send),
                {
                    actions.send(text.trim())
                    text = ""
                    lastTyping = 0L
                },
                Modifier.padding(start = 8.dp),
            )
        }
    }
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
