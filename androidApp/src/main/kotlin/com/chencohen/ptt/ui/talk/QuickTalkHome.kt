package com.chencohen.ptt.ui.talk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.talk.TalkList
import com.chencohen.ptt.talk.TalkPhase
import com.chencohen.ptt.talk.TalkUiState
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.contacts.morphRing
import com.chencohen.ptt.ui.contacts.previewOf
import com.chencohen.ptt.ui.groups.GroupAvatar
import com.chencohen.ptt.ui.style.EmptyState
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType

/** One chat on the Talk tab's list: its row's facts, its name and picture, and its newest message. */
internal data class TalkListItem(val row: TalkList.Row, val name: String, val groupPicture: String? = null, val last: TextMessage? = null)

/**
 * The Talk tab as a live list (docs/screens/quick-talk.md; 2026-11-26, hen: "the idea is to contact very quickly
 * coworkers, also with messages"; concept 1 of docs/screens/mocks/talk-home-mock.png): every person and group as a
 * row (its ring, its last message or push, its unread count), who is talking first, then the newest; filters on top
 * (All / Hearing now / Unread / Groups). One row is open in place: its last messages, one-touch replies and a line to
 * write, with Full chat. All of it inside the open chat's own screen ([chat]): its strip, Push and its slides, the
 * live pills and video. Push goes to the open row ("To testbot" beside it); whoever starts talking opens, so Push
 * answers them, never while my push runs.
 */
@Composable
internal fun QuickTalkHome(
    items: List<TalkListItem>,
    startChat: String?,
    me: String,
    talk: TalkUiState,
    nameOf: (String) -> String,
    /** The open chat's newest messages, oldest first. */
    recent: (String) -> List<TextMessage>,
    openChat: (String) -> Unit,
    openChats: () -> Unit,
    pageShown: (String) -> Unit,
    send: (chat: String, body: String) -> Unit,
    modifier: Modifier = Modifier,
    warmMicrophone: (Boolean) -> Unit = {},
    setHere: (String?) -> Unit = {},
    viewing: (String) -> Boolean = { false },
    playClip: (String) -> Unit = {},
    /** Holding a row's face: a push to that chat until the finger lifts (2026-11-26). */
    press: (String) -> Unit = {},
    release: () -> Unit = {},
    micDenied: () -> Unit = {},
    chat: @Composable (chat: String, header: @Composable () -> Unit, stage: @Composable (live: Boolean, overlay: @Composable () -> Unit) -> Unit) -> Unit = { _, _, _ -> },
) {
    if (items.isEmpty()) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            EmptyState(stringResource(R.string.chats_empty), actionLabel = stringResource(R.string.quick_talk_open_chats), onAction = openChats)
        }
        return
    }
    WarmMicrophone(warmMicrophone)
    // The filter and search (2026-11-26: by name, over the filter), as state the header reads itself: the header is
    // drawn inside the chat's screen, which may hold on to an older copy of it.
    val filterState = rememberSaveable { mutableStateOf(TalkList.Filter.ALL) }
    val searchingState = rememberSaveable { mutableStateOf(false) }
    val queryState = rememberSaveable { mutableStateOf("") }
    val filter = filterState.value
    val query = queryState.value
    var asked by rememberSaveable { mutableStateOf(startChat) }
    val ordered = TalkList.order(items.map { it.row })
    val byChat = items.associateBy { it.row.chat }
    val shown = TalkList.filter(ordered, filter).filter { r -> query.isBlank() || byChat[r.chat]?.name?.contains(query.trim(), ignoreCase = true) == true }
    // My push runs (held, asking, waiting, or between hands-free renewals): the open row stays.
    val busy = talk.own?.let { it.phase == TalkPhase.REQUESTING || it.phase == TalkPhase.TALKING || it.phase == TalkPhase.WAITING } == true
    // Whoever starts talking opens (Push answers them).
    var heardTalkers by remember { mutableStateOf(talk.talkers.keys) }
    var started by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(talk.talkers.keys) {
        started = talk.talkers.filter { (c, t) -> c !in heardTalkers && t.user != me }.keys.firstOrNull()
        heardTalkers = talk.talkers.keys
    }
    // From all rows, not only those shown: a filter or a search doesn't move where Push goes.
    val open = TalkList.open(asked, ordered, started, busy) ?: return
    LaunchedEffect(open) {
        asked = open
        started = null
        pageShown(open)
    }
    Here(open, setHere)
    val context = LocalContext.current
    val live by remember { com.chencohen.ptt.liveState(context) }.collectAsState()
    fun ringOf(c: String): Int = when {
        viewing(c) -> 2
        live.room == c && (Group.idOf(c) != null && live.present.isNotEmpty() || c in live.present) -> 1
        else -> 0
    }
    val list = rememberLazyListState()
    val microphone = rememberMicrophone(micDenied)
    val unread = items.count { it.row.unread > 0 }
    Box(modifier.fillMaxSize()) {
        key(open) {
            chat(
                open,
                { Filters(filterState, unread, searchingState, queryState) },
                { _, overlay ->
                    Box(Modifier.fillMaxSize()) {
                        TalkRows(
                            shown.mapNotNull { byChat[it.chat] }, open, busy, list, talk, me, nameOf, ::ringOf,
                            recent = recent, playClip = playClip, openChat = openChat, send = send,
                            pick = { c -> if (!busy) asked = c },
                            // The open row moves to it only after the finger lifts: changing it under the finger would
                            // end the gesture with the push still on.
                            hold = { c -> microphone { press(c) } },
                            release = { c ->
                                release()
                                asked = c
                            },
                        )
                        // Live pills and video float at the top, as in the chat.
                        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) { overlay() }
                    }
                },
            )
        }
    }
}

/**
 * The filters (All / Hearing now / Unread · 3 / Groups), the chosen one in ink, and Search before them: a tap turns
 * the row into a line to write a name in, with Close. Clear of my profile circle.
 */
@Composable
private fun Filters(
    filterState: androidx.compose.runtime.MutableState<TalkList.Filter>,
    unread: Int,
    searchingState: androidx.compose.runtime.MutableState<Boolean>,
    queryState: androidx.compose.runtime.MutableState<String>,
) {
    val filter = filterState.value
    val choose: (TalkList.Filter) -> Unit = { filterState.value = it }
    val setSearching: (Boolean) -> Unit = { on ->
        searchingState.value = on
        if (!on) queryState.value = ""
    }
    androidx.compose.animation.AnimatedContent(
        searchingState.value,
        transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(150))) },
        label = "filters or search",
        modifier = Modifier.fillMaxWidth().padding(start = 60.dp),
    ) { on ->
        if (on) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Row(
                Modifier.fillMaxWidth().padding(end = 12.dp, top = 6.dp, bottom = 6.dp).background(LibraryColors.Raised, CircleShape).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LibraryIcons.Search, null, tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                    // Its own value (with the caret), told outward as it changes.
                    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(queryState.value)) }
                    if (field.text.isEmpty()) Text(stringResource(R.string.talk_search_hint), color = LibraryColors.Muted, style = LibraryType.Body, maxLines = 1)
                    BasicTextField(
                        field, { field = it; queryState.value = it.text }, singleLine = true,
                        textStyle = LibraryType.Body.copy(color = LibraryColors.OnBackground),
                        cursorBrush = SolidColor(LibraryColors.Accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                val close = stringResource(R.string.talk_search_close)
                Box(Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = close) { setSearching(false) }, contentAlignment = Alignment.Center) {
                    Icon(LibraryIcons.Close, close, tint = LibraryColors.OnBackground, modifier = Modifier.size(18.dp))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Search first, next to my profile circle, so it never scrolls out of reach.
                val search = stringResource(R.string.talk_search)
                Box(
                    Modifier.size(36.dp).background(LibraryColors.Raised, CircleShape).clickable(role = Role.Button, onClickLabel = search) { setSearching(true) },
                    contentAlignment = Alignment.Center,
                ) { Icon(LibraryIcons.Search, search, tint = LibraryColors.OnBackground, modifier = Modifier.size(18.dp)) }
                listOf(
                    TalkList.Filter.ALL to stringResource(R.string.talk_filter_all),
                    TalkList.Filter.HEARING to stringResource(R.string.talk_filter_hearing),
                    TalkList.Filter.UNREAD to stringResource(R.string.talk_filter_unread) + if (unread > 0) " · $unread" else "",
                    TalkList.Filter.GROUPS to stringResource(R.string.talk_filter_groups),
                ).forEach { (f, label) ->
                    val chosen = f == filter
                    Text(
                        label,
                        color = if (chosen) LibraryColors.OnInverse else LibraryColors.OnBackground,
                        style = LibraryType.Small,
                        maxLines = 1,
                        modifier = Modifier.semantics { selected = chosen }
                            .background(if (chosen) LibraryColors.Inverse else LibraryColors.Raised, CircleShape)
                            .clickable(role = Role.Tab) { choose(f) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TalkRows(
    rows: List<TalkListItem>,
    open: String,
    busy: Boolean,
    list: LazyListState,
    talk: TalkUiState,
    me: String,
    nameOf: (String) -> String,
    ring: (String) -> Int,
    recent: (String) -> List<TextMessage>,
    playClip: (String) -> Unit,
    openChat: (String) -> Unit,
    send: (String, String) -> Unit,
    pick: (String) -> Unit,
    hold: (String) -> Unit,
    release: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.talk_list_empty), color = LibraryColors.Muted, style = LibraryType.Body)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
        items(rows, key = { it.row.chat }) { item ->
            val isOpen = item.row.chat == open
            Column(
                Modifier.animateItem(fadeInSpec = tween(200), placementSpec = tween(250), fadeOutSpec = tween(150))
                    .fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
                    .background(if (isOpen) LibraryColors.Raised else Color.Transparent, RoundedCornerShape(20.dp))
                    .animateContentSize(tween(220)),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !isOpen && !busy, role = Role.Button) { pick(item.row.chat) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Held (a moment, so a scroll isn't a push): a push to this chat until the finger lifts; the row opens.
                    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                    val onHold by androidx.compose.runtime.rememberUpdatedState(hold)
                    val onRelease by androidx.compose.runtime.rememberUpdatedState(release)
                    val busyNow by androidx.compose.runtime.rememberUpdatedState(busy)
                    var holding by remember { mutableStateOf(false) }
                    val grow by androidx.compose.animation.core.animateFloatAsState(if (holding) 1.12f else 1f, tween(150), label = "face held")
                    Box(
                        Modifier.size(52.dp).graphicsLayer { scaleX = grow; scaleY = grow }
                            .morphRing(if (holding) 2 else ring(item.row.chat), if (holding) com.chencohen.ptt.ui.style.Pink else LibraryColors.Accent)
                            .pointerInput(item.row.chat) {
                                detectTapGestures(
                                    onLongPress = {
                                        // Not while my push runs (the open row's own).
                                        if (busyNow) return@detectTapGestures
                                        holding = true
                                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        onHold(item.row.chat)
                                    },
                                    onPress = {
                                        tryAwaitRelease()
                                        if (holding) {
                                            holding = false
                                            onRelease(item.row.chat)
                                        }
                                    },
                                    onTap = { pick(item.row.chat) },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) { RowFace(item, 44.dp) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            item.name,
                            color = if (item.row.talking) LibraryColors.Accent else LibraryColors.OnBackground,
                            style = LibraryType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val line = if (item.row.talking) talkLine(item.row.chat, talk, nameOf, me) else item.last?.let { previewOf(it) }.orEmpty()
                        if (line.isNotEmpty()) {
                            Text(
                                line,
                                color = if (item.row.talking) LibraryColors.Accent else LibraryColors.Muted,
                                style = LibraryType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isOpen) {
                        Text(
                            stringResource(R.string.talk_full_chat),
                            color = com.chencohen.ptt.ui.style.Pink, style = LibraryType.Small,
                            modifier = Modifier.clickable(role = Role.Button) { openChat(item.row.chat) }.padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            item.last?.let { Text(timeOf(it.at), color = LibraryColors.Muted, style = LibraryType.Tiny) }
                            if (item.row.unread > 0) {
                                Text(
                                    item.row.unread.toString(), color = Color.White, style = LibraryType.Tiny,
                                    modifier = Modifier.padding(top = 4.dp).background(com.chencohen.ptt.ui.style.Pink, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    isOpen,
                    enter = fadeIn(tween(200)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
                ) {
                    OpenRow(item, recent(item.row.chat), nameOf, playClip) { body -> send(item.row.chat, body) }
                }
            }
        }
    }
}

/** The open row's inside: its last few messages, one-touch replies, and a line to write. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenRow(item: TalkListItem, messages: List<TextMessage>, nameOf: (String) -> String, playClip: (String) -> Unit, send: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        val group = item.row.group
        messages.forEach { m ->
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = if (m.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
                Column(
                    Modifier.widthIn(max = 280.dp)
                        .background(if (m.outgoing) LibraryColors.Accent.copy(alpha = 0.16f) else LibraryColors.Background, RoundedCornerShape(14.dp))
                        .then(if (m.clip != null) Modifier.clickable(role = Role.Button) { playClip(m.id) } else Modifier)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    if (group && !m.outgoing) Text(nameOf(m.author ?: m.user), color = LibraryColors.Accent, style = LibraryType.Tiny)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.clip != null) Icon(LibraryIcons.Play, null, tint = LibraryColors.OnBackground, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                        Text(previewOf(m), color = LibraryColors.OnBackground, style = LibraryType.Small, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.talk_quick_got_it, R.string.talk_quick_on_my_way, R.string.talk_quick_five, R.string.talk_quick_moment).forEach { id ->
                val words = stringResource(id)
                Text(
                    words, color = LibraryColors.OnBackground, style = LibraryType.Small,
                    modifier = Modifier.background(LibraryColors.Background, CircleShape)
                        .clickable(role = Role.Button) { send(words) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        var text by rememberSaveable(item.row.chat) { mutableStateOf("") }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).background(LibraryColors.Background, RoundedCornerShape(22.dp)).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (text.isEmpty()) Text(stringResource(R.string.talk_reply_to, item.name), color = LibraryColors.Muted, style = LibraryType.Body, maxLines = 1)
                BasicTextField(
                    text, { text = it },
                    textStyle = LibraryType.Body.copy(color = LibraryColors.OnBackground),
                    cursorBrush = SolidColor(LibraryColors.Accent),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val label = stringResource(R.string.voice_send)
            AnimatedVisibility(text.isNotBlank(), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                Box(
                    Modifier.size(36.dp).background(com.chencohen.ptt.ui.style.Pink, CircleShape)
                        .clickable(role = Role.Button, onClickLabel = label) {
                            send(text.trim())
                            text = ""
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(LibraryIcons.ArrowUp, label, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun RowFace(item: TalkListItem, size: Dp) {
    if (item.row.group) GroupAvatar(item.name, item.groupPicture, size)
    else HearFace(item.row.chat, size) { Avatar(item.row.chat, size) }
}

private fun timeOf(at: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) android.text.format.DateFormat.format("HH:mm", then).toString()
    else android.text.format.DateFormat.format("d.M", then).toString()
}
