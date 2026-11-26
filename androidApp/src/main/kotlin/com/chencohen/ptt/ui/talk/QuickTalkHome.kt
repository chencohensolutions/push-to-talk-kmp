package com.chencohen.ptt.ui.talk

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.ClipKind
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.talk.TalkPhase
import com.chencohen.ptt.talk.TalkUiState
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.contacts.morphRing
import com.chencohen.ptt.ui.groups.GroupAvatar
import com.chencohen.ptt.ui.style.EmptyState
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType

/** One Quick talk chat on the Talk tab, with its name and picture. */
internal data class QuickTalkPage(val chat: String, val name: String, val groupPicture: String? = null)

/**
 * The Talk tab (docs/screens/quick-talk.md; 2026-11-26, hen picked "faces row + stage"): a row of the Quick talk
 * chats' faces with their presence rings across the top, and under it the picked chat's stage (its face, what its
 * floor is doing, its last push to hear again, and Chat / Voice call / Video call), all inside the chat's own screen
 * ([chat]): the same bottom strip, Push and its slides, live pills and video, and who-can't-hear chips.
 *
 * The picked chat is kept for next time ([pageShown]); whoever starts talking in another Quick talk chat becomes the
 * picked one; while my push runs (held, waiting, or hands-free) it doesn't change.
 */
@Composable
internal fun QuickTalkHome(
    pages: List<QuickTalkPage>,
    startPage: Int,
    me: String,
    talk: TalkUiState,
    nameOf: (String) -> String,
    openChat: (String) -> Unit,
    openChats: () -> Unit,
    pageShown: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The microphone kept open while the Talk tab shows (MessagingController.warmMicrophone). */
    warmMicrophone: (Boolean) -> Unit = {},
    /** A voice or video call in a chat. */
    call: (chat: String, video: Boolean) -> Unit = { _, _ -> },
    /** The picked chat tells its user ("here"). */
    setHere: (String?) -> Unit = {},
    /** Someone has this chat on screen now (a solid ring). */
    viewing: (String) -> Boolean = { false },
    /** The chat's newest push (a burst), to hear again. */
    lastPush: (String) -> TextMessage? = { null },
    playClip: (String) -> Unit = {},
    stopClip: () -> Unit = {},
    /** The chat's own screen for [chat], with [header] in place of its header and [stage] where its messages are. */
    chat: @Composable (chat: String, header: @Composable () -> Unit, stage: @Composable () -> Unit) -> Unit = { _, _, _ -> },
) {
    if (pages.isEmpty()) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            EmptyState(stringResource(R.string.quick_talk_empty), actionLabel = stringResource(R.string.quick_talk_open_chats), onAction = openChats)
        }
        return
    }
    WarmMicrophone(warmMicrophone)
    val chats = pages.map { it.chat }
    var picked by rememberSaveable { mutableStateOf(chats[startPage.coerceIn(0, chats.size - 1)]) }
    if (picked !in chats) picked = chats.first()
    // Which way the stage came in: +1 from the end side, -1 from the start side, 0 not at all.
    var from by remember { mutableIntStateOf(0) }
    // My push runs here (held, asking, waiting, or between hands-free renewals): the picked chat stays.
    val busy = talk.own?.let { it.phase == TalkPhase.REQUESTING || it.phase == TalkPhase.TALKING || it.phase == TalkPhase.WAITING } == true
    val busyNow by rememberUpdatedState(busy)
    fun pick(next: String) {
        if (busyNow || next == picked) return
        from = if (chats.indexOf(next) > chats.indexOf(picked)) 1 else -1
        picked = next
    }
    LaunchedEffect(picked) { pageShown(picked) }
    // Whoever starts talking in another Quick talk chat becomes the picked one (not while mine runs).
    var heardTalkers by remember { mutableStateOf(talk.talkers.keys) }
    LaunchedEffect(talk.talkers.keys) {
        val started = talk.talkers.filter { (c, t) -> c !in heardTalkers && t.user != me && c in chats }.keys
        heardTalkers = talk.talkers.keys
        started.firstOrNull()?.let { pick(it) }
    }
    Here(picked, setHere)
    val context = LocalContext.current
    val live by remember { com.chencohen.ptt.liveState(context) }.collectAsState()
    fun ringOf(c: String): Int = when {
        viewing(c) -> 2
        live.room == c && (Group.idOf(c) != null && live.present.isNotEmpty() || c in live.present) -> 1
        else -> 0
    }
    val page = pages.first { it.chat == picked }
    Box(modifier.fillMaxSize()) {
        key(picked) {
            chat(
                picked,
                {
                    FacesRow(
                        pages, picked, busy, talk, me,
                        ring = ::ringOf,
                        pick = ::pick,
                        add = openChats,
                    )
                },
                {
                    Stage(
                        page = page, from = from, ring = ringOf(picked), talk = talk, me = me, nameOf = nameOf,
                        last = lastPush(picked), playClip = playClip, stopClip = stopClip,
                        openChat = { openChat(picked) }, call = { v -> call(picked, v) },
                        swipe = { step ->
                            val i = chats.indexOf(picked) + step
                            chats.getOrNull(i)?.let(::pick)
                        },
                    )
                },
            )
        }
    }
}

/** A chat's face: a group's picture or letter, or the contact's, with their can't-hear badge. */
@Composable
private fun ChatFace(page: QuickTalkPage, size: Dp) {
    if (Group.idOf(page.chat) != null) GroupAvatar(page.name, page.groupPicture, size)
    else HearFace(page.chat, size) { Avatar(page.chat, size) }
}

/**
 * The Quick talk chats' faces in a row, each with its presence ring (solid: on this chat's screen; dashed: in its talk
 * room; none: away) and its name; the picked one's name bold with a short line under it. Someone talking there: their
 * name in the accent colour. At the row's end, Add (to Chats, where Quick talk is turned on).
 */
@Composable
private fun FacesRow(
    pages: List<QuickTalkPage>,
    picked: String,
    busy: Boolean,
    talk: TalkUiState,
    me: String,
    ring: (String) -> Int,
    pick: (String) -> Unit,
    add: () -> Unit,
) {
    Row(
        // Clear of my profile circle in the top start corner (home's own).
        Modifier.fillMaxWidth().padding(start = 60.dp).horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        pages.forEach { p ->
            val on = p.chat == picked
            val talking = talk.talkers[p.chat]?.takeIf { it.user != me } != null
            // While my push runs, the others can't be picked: they rest a little dimmed.
            val dim by androidx.compose.animation.core.animateFloatAsState(if (busy && !on) 0.4f else 1f, tween(200), label = "face dim")
            Column(
                Modifier.width(72.dp).graphicsLayer { alpha = dim }
                    .semantics { selected = on }
                    .clickable(enabled = !busy, role = Role.Tab) { pick(p.chat) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(56.dp).morphRing(ring(p.chat), LibraryColors.Accent), contentAlignment = Alignment.Center) { ChatFace(p, 48.dp) }
                Text(
                    p.name,
                    color = if (talking) LibraryColors.Accent else if (on) LibraryColors.OnBackground else LibraryColors.Muted,
                    style = LibraryType.Small.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val line by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, tween(200), label = "picked line")
                Box(
                    Modifier.padding(top = 3.dp).size(16.dp, 3.dp)
                        .graphicsLayer { alpha = line; scaleX = 0.4f + 0.6f * line }
                        .background(com.chencohen.ptt.ui.style.Pink, CircleShape),
                )
            }
        }
        val addLabel = stringResource(R.string.quick_talk_add)
        Column(
            Modifier.width(72.dp).clickable(role = Role.Button, onClickLabel = addLabel, onClick = add).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(56.dp).morphRing(1, LibraryColors.Muted.copy(alpha = 0.5f), width = 1.5.dp, dash = 5.dp),
                contentAlignment = Alignment.Center,
            ) { Icon(LibraryIcons.Plus, null, tint = LibraryColors.Muted, modifier = Modifier.size(22.dp)) }
            Text(addLabel, color = LibraryColors.Muted, style = LibraryType.Small, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * The picked chat's stage: a card with its face (and ring) large, its name (a tap opens the chat), what its floor is
 * doing, its last push with Play, and at its foot Chat / Voice call / Video call. It slides in a little from the side
 * it came from (250 ms) and fades in. A sideways swipe on it goes to the next or previous chat.
 */
@Composable
private fun Stage(
    page: QuickTalkPage,
    from: Int,
    ring: Int,
    talk: TalkUiState,
    me: String,
    nameOf: (String) -> String,
    last: TextMessage?,
    playClip: (String) -> Unit,
    stopClip: () -> Unit,
    openChat: () -> Unit,
    call: (Boolean) -> Unit,
    swipe: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Coming in: from the side of the chat it was (the end side in reading order is the left in Hebrew).
    val shift = remember { Animatable(if (from == 0) 0f else 1f) }
    LaunchedEffect(Unit) { shift.animateTo(0f, tween(250)) }
    val startX = with(density) { 32.dp.toPx() } * from * (if (rtl) -1 else 1)
    val swipeAt = with(density) { 72.dp.toPx() }
    val onSwipe by rememberUpdatedState(swipe)
    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .graphicsLayer {
                translationX = startX * shift.value
                alpha = 1f - shift.value
            }
            .background(LibraryColors.Raised, RoundedCornerShape(28.dp))
            .pointerInput(rtl) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        // Towards the reading end (left in Hebrew): the next chat.
                        if (kotlin.math.abs(dx) > swipeAt) onSwipe(if ((dx < 0) != rtl) 1 else -1)
                    },
                ) { _, d -> dx += d }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(132.dp).morphRing(ring, LibraryColors.Accent, width = 3.dp, dash = 10.dp), contentAlignment = Alignment.Center) {
            // On the card a letter circle in the card's own colour would vanish: the page's colour behind it.
            Box(Modifier.size(120.dp).background(LibraryColors.Background, CircleShape), contentAlignment = Alignment.Center) {
                if (Group.idOf(page.chat) != null && page.groupPicture == null && page.name.isNotBlank()) {
                    Text(page.name.first().uppercaseChar().toString(), color = LibraryColors.OnBackground, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                } else ChatFace(page, 120.dp)
            }
        }
        Text(
            page.name,
            color = LibraryColors.OnBackground,
            style = LibraryType.PageTitle,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp).semantics { heading() }
                .clickable(role = Role.Button, onClick = openChat),
        )
        val line = talkLine(page.chat, talk, nameOf, me)
        val someone = talk.talkers[page.chat]?.takeIf { it.user != me } != null
        Text(
            line,
            color = if (someone || talk.own?.chat == page.chat) LibraryColors.Accent else LibraryColors.Muted,
            style = LibraryType.Caption,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp).height(36.dp),
        )
        if (last != null) LastPush(last, talk, me, nameOf, playClip, stopClip)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LibraryColors.Muted.copy(alpha = 0.15f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            StageAction(LibraryIcons.Chat, stringResource(R.string.talk_action_chat), Modifier.weight(1f), openChat)
            StageAction(LibraryIcons.Call, stringResource(R.string.call_voice), Modifier.weight(1f)) { call(false) }
            StageAction(LibraryIcons.Video, stringResource(R.string.call_video), Modifier.weight(1f)) { call(true) }
        }
    }
}

/** The chat's last push, to hear again: Play (Pause while it plays), who said it, and how long. */
@Composable
private fun LastPush(m: TextMessage, talk: TalkUiState, me: String, nameOf: (String) -> String, playClip: (String) -> Unit, stopClip: () -> Unit) {
    val playing = talk.playing?.clip == m.id
    val who = if (m.outgoing) stringResource(R.string.talk_you) else nameOf(m.author ?: m.user)
    val ms = m.clip?.durationMs ?: 0L
    val length = if (ms > 0) " · %d:%02d".format(ms / 60_000, (ms / 1_000) % 60) else ""
    val label = stringResource(R.string.clip_play)
    Row(
        Modifier.padding(top = 12.dp)
            .background(LibraryColors.Accent.copy(alpha = 0.14f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) { if (playing) stopClip() else playClip(m.id) }
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).background(LibraryColors.OnBackground, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (playing) LibraryIcons.Pause else LibraryIcons.Play, null, tint = LibraryColors.Background, modifier = Modifier.size(14.dp))
        }
        Text(
            android.text.BidiFormatter.getInstance().unicodeWrap(who) + length,
            color = LibraryColors.OnBackground, style = LibraryType.Small,
            maxLines = 1, modifier = Modifier.padding(start = 8.dp),
        )
        if (m.clip?.kind == ClipKind.BURST && !m.outgoing && m.clip?.heard == false) {
            Box(Modifier.padding(start = 8.dp).size(8.dp).background(Color(0xFF2BB3A3), CircleShape))
        }
    }
}

@Composable
private fun StageAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(22.dp))
        Text(label, color = LibraryColors.Muted, style = LibraryType.Small, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}
