package com.chencohen.ptt.ui.talk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.talk.TalkPhase
import com.chencohen.ptt.talk.TalkUiState
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.contacts.morphRing
import com.chencohen.ptt.ui.contacts.previewOf
import com.chencohen.ptt.ui.groups.GroupAvatar
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import kotlinx.coroutines.delay

/**
 * The chat's live pills and video boxes are drawn in the Talk tab's rows (the talker's row, my end of the chosen row),
 * not floating over them (2026-11-26, hen: "the video and voice do not integrate nicely").
 */
internal val LocalLiveInRows = compositionLocalOf { false }

/** One Quick talk chat on the Talk tab. */
internal data class TalkListItem(
    val chat: String,
    val name: String,
    val group: Boolean,
    val groupPicture: String? = null,
    val last: TextMessage? = null,
    val unread: Int = 0,
    /** A group's other members (who can't hear my push). */
    val members: List<String> = emptyList(),
)

/** A chat for the "+" list: on the Talk tab or not. */
internal data class TalkChoice(val chat: String, val name: String, val group: Boolean, val groupPicture: String?, val on: Boolean)

/**
 * The Talk tab, made simple (docs/screens/quick-talk.md; 2026-11-26, hen: "its too complicated", "only chats marked as
 * quick chat should appear there", "the chat ux and animation should resemble the chat screen"; option A of
 * docs/screens/mocks/talk-live-list-review.png). Three rules: touch a name to choose it, hold Push to talk, touch the
 * writing bubble to write. Only the Quick talk chats, as big rows in the order they were marked (never re-sorted); the
 * chosen one raised. Under them the chosen chat's own bottom (its screen is under the tab): the writing bubble
 * ("Message to testbot", a tap opens the chat to write) and its strip. Live voice and video are in the talker's row,
 * and mine at the end of the chosen row. Whoever starts talking is chosen, never while my push runs.
 */
@Composable
internal fun QuickTalkHome(
    items: List<TalkListItem>,
    startChat: String?,
    me: String,
    talk: TalkUiState,
    nameOf: (String) -> String,
    /** The chat to write in: the app's chat opens, writing. */
    write: (String) -> Unit,
    pageShown: (String) -> Unit,
    /** Everyone who may be on the tab ("+"), and turning one on or off. */
    choices: List<TalkChoice>,
    setOn: (chat: String, on: Boolean) -> Unit,
    /** The contacts and groups have come (no "empty" before they have). */
    loaded: Boolean,
    modifier: Modifier = Modifier,
    warmMicrophone: (Boolean) -> Unit = {},
    setHere: (String?) -> Unit = {},
    viewing: (String) -> Boolean = { false },
    pushVideoFor: (String) -> Boolean = { false },
    chat: @Composable (chat: String, header: @Composable () -> Unit, stage: @Composable (live: Boolean, overlay: @Composable () -> Unit) -> Unit) -> Unit = { _, _, _ -> },
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding) AddSheet(choices, setOn) { adding = false }
    if (items.isEmpty()) {
        if (loaded) Empty(modifier) { adding = true } else Box(modifier.fillMaxSize())
        return
    }
    WarmMicrophone(warmMicrophone)
    var asked by rememberSaveable { mutableStateOf(startChat) }
    // My push runs (held, asking, waiting, or between hands-free renewals): the chosen row stays.
    val busy = talk.own?.let { it.phase == TalkPhase.REQUESTING || it.phase == TalkPhase.TALKING || it.phase == TalkPhase.WAITING } == true
    var heardTalkers by remember { mutableStateOf(talk.talkers.keys) }
    var started by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(talk.talkers.keys) {
        started = talk.talkers.filter { (c, t) -> c !in heardTalkers && t.user != me }.keys.firstOrNull()
        heardTalkers = talk.talkers.keys
    }
    val chats = items.map { it.chat }
    val chosen = when {
        !busy && started != null && started in chats -> started!!
        asked != null && asked in chats -> asked!!
        else -> chats.first()
    }
    LaunchedEffect(chosen) {
        asked = chosen
        started = null
        pageShown(chosen)
    }
    Here(chosen, setHere)
    val context = LocalContext.current
    val live by remember { com.chencohen.ptt.liveState(context) }.collectAsState()
    fun ringOf(c: String): Int = when {
        viewing(c) -> 2
        live.room == c && (items.firstOrNull { it.chat == c }?.group == true && live.present.isNotEmpty() || c in live.present) -> 1
        else -> 0
    }
    val list = rememberLazyListState()
    // What the rows show, read where they are drawn: they are drawn inside the chat's screen, which may hold on to an
    // older copy of the lambda that draws them.
    val ui = androidx.compose.runtime.rememberUpdatedState(
        RowsUi(items, chosen, busy, talk, items.associate { it.chat to ringOf(it.chat) }, items.associate { it.chat to pushVideoFor(it.chat) }),
    )
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalLiveInRows provides true) {
            key(chosen) {
                chat(
                    chosen,
                    { Header { adding = true } },
                    { _, overlay ->
                        Box(Modifier.fillMaxSize()) {
                            val u = ui.value
                            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                                items(u.items, key = { it.chat }) { item ->
                                    TalkRow(
                                        item, item.chat == u.chosen, u.busy, u.talk, me, nameOf, u.rings[item.chat] ?: 0,
                                        myVideo = u.myVideo[item.chat] == true,
                                        choose = { if (!ui.value.busy) asked = item.chat },
                                        open = {
                                            com.chencohen.ptt.ui.contacts.TalkTabMove.go(item.chat)
                                            write(item.chat)
                                        },
                                    )
                                }
                            }
                            // What's left of the chat's live row here: who can't hear me, "Make it a call".
                            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { overlay() }
                        }
                    },
                )
            }
        }
    }
}

private class RowsUi(
    val items: List<TalkListItem>,
    val chosen: String,
    val busy: Boolean,
    val talk: TalkUiState,
    val rings: Map<String, Int>,
    val myVideo: Map<String, Boolean>,
)

/** "דיבור" next to my profile circle (home's own, in the corner), and "+" at the other end. */
@Composable
private fun Header(add: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 64.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.quick_talk_title), color = LibraryColors.OnBackground, style = LibraryType.PageTitle, modifier = Modifier.weight(1f).semantics { heading() })
        val label = stringResource(R.string.quick_talk_add)
        Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = add), contentAlignment = Alignment.Center) {
            Icon(LibraryIcons.Plus, label, tint = LibraryColors.OnBackground, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * One Quick talk chat: its face (56 dp) with its ring and can't-hear badge, its name, one line, its unread count; the
 * chosen one raised. Someone talking there: the ring glows with it and the line says so, in teal; their video grows
 * out of their face inside the row (one move, 300 ms). Mine, in the chosen row: my pill at its end while I talk, my
 * camera's box there while I send video, and who can't hear me under the name.
 */
@Composable
private fun TalkRow(
    item: TalkListItem,
    chosen: Boolean,
    busy: Boolean,
    talk: TalkUiState,
    me: String,
    nameOf: (String) -> String,
    ring: Int,
    myVideo: Boolean,
    choose: () -> Unit,
    open: () -> Unit,
) {
    val talker = talk.talkers[item.chat]?.takeIf { it.user != me }
    val own = talk.own?.takeIf { it.chat == item.chat && (it.phase == TalkPhase.TALKING || it.phase == TalkPhase.REQUESTING) }
    // Their video: through the talk room while its pictures come, or the clip playing here.
    val arriving by com.chencohen.ptt.talk.LiveTalkVideo.arriving.collectAsState()
    val roomVideo = talker?.let { arriving["${it.user}.${it.device}"] }
    val clipVideo = talk.playing?.takeIf { it.chat == item.chat && it.video && it.from != me } != null
    val theirVideoNow = roomVideo != null || clipVideo
    // Mine: kept a moment over a hands-free renewal's gap, so the box doesn't leave and come back.
    val myVideoNow = own != null && myVideo
    var myVideoShown by remember { mutableStateOf(false) }
    // One time for the whole run, its renewals included.
    var mySince by remember { mutableLongStateOf(0L) }
    if (myVideoNow && mySince == 0L) mySince = own?.startedAt ?: System.currentTimeMillis()
    LaunchedEffect(myVideoNow) {
        if (myVideoNow) myVideoShown = true else {
            delay(1_500)
            myVideoShown = false
            mySince = 0L
        }
    }
    val bg by animateFloatAsState(if (chosen) 1f else 0f, tween(200), label = "chosen")
    // A row grown with video is scrolled whole into view once it has grown.
    val inView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(theirVideoNow || myVideoShown) {
        if (theirVideoNow || myVideoShown) {
            delay(320)
            inView.bringIntoView()
        }
    }
    Column(
        Modifier.bringIntoViewRequester(inView)
            .onGloballyPositioned { com.chencohen.ptt.ui.contacts.TalkTabMove.rowTops[item.chat] = it.positionInWindow().y }
            .fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
            .shadow((3 * bg).dp, RoundedCornerShape(20.dp))
            .background(LibraryColors.Raised.copy(alpha = bg), RoundedCornerShape(20.dp))
            .semantics { selected = chosen }
            .clickable(enabled = !busy || chosen, role = Role.Button) { if (chosen) open() else choose() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The talking ring: the face's ring whole and glowing while they talk.
            val glow = rememberInfiniteTransition(label = "talking")
            val pulse by glow.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "talking pulse")
            Box(
                Modifier.size(64.dp)
                    .then(if (talker != null) Modifier.background(LibraryColors.Accent.copy(alpha = 0.18f * pulse), CircleShape) else Modifier)
                    .morphRing(if (talker != null) 2 else ring, LibraryColors.Accent, width = if (talker != null) 3.dp else 2.dp),
                contentAlignment = Alignment.Center,
            ) { RowFace(item, 56.dp) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.name, color = if (talker != null) LibraryColors.Accent else LibraryColors.OnBackground, style = LibraryType.RowTitle.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = when {
                    talker != null -> talkLine(item.chat, talk, nameOf, me)
                    else -> item.last?.let { previewOf(it) }.orEmpty()
                }
                if (line.isNotEmpty()) {
                    Text(
                        line,
                        color = if (talker != null) LibraryColors.Accent else if (item.unread > 0) LibraryColors.OnBackground else LibraryColors.Muted,
                        style = LibraryType.Small.copy(fontSize = 13.sp, fontWeight = if (item.unread > 0) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // Who can't hear my push now, under the name (the chat's own chip).
                if (chosen && own != null) {
                    val hearOf = LocalHear.current
                    val told = talk.heard[own.clip].orEmpty()
                    val listeners = (if (item.group) item.members else listOf(item.chat)).map { u -> PushListener(u, nameOf(u), hearOf(u), told[u] ?: 0) }
                    PushHearChip(own.clip, listeners, item.group)
                }
            }
            when {
                own != null && !myVideoShown -> MyPill(own.urgent, own.startedAt)
                item.unread > 0 -> Text(
                    item.unread.toString(), color = Color.White, style = LibraryType.Tiny,
                    modifier = Modifier.background(com.chencohen.ptt.ui.style.Pink, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        // Video in the row: theirs at the start side, grown out of their face; mine at the end.
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        AnimatedVisibility(
            theirVideoNow || myVideoShown,
            enter = fadeIn(tween(300)) + expandVertically(tween(300), expandFrom = Alignment.Top),
            exit = fadeOut(tween(250)) + shrinkVertically(tween(300), shrinkTowards = Alignment.Top),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedVisibility(
                    theirVideoNow,
                    enter = scaleIn(tween(300), initialScale = 0.3f, transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0f)) + fadeIn(tween(300)),
                    exit = scaleOut(tween(300), targetScale = 0.3f, transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0f)) + fadeOut(tween(250)),
                ) {
                    VideoTile(nameOf(talker?.user ?: item.chat), talker?.since ?: System.currentTimeMillis(), mine = false) {
                        if (roomVideo != null) RoomVideoView(roomVideo, Modifier.fillMaxSize()) else TalkVideoView(Modifier.fillMaxSize(), cover = true)
                    }
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    myVideoShown,
                    enter = scaleIn(tween(300), initialScale = 0.3f, transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 0f)) + fadeIn(tween(300)),
                    exit = scaleOut(tween(300), targetScale = 0.3f, transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 0f)) + fadeOut(tween(250)),
                ) {
                    VideoTile(stringResource(R.string.talk_you), mySince.takeIf { it > 0 } ?: System.currentTimeMillis(), mine = true) {
                        PushVideoPreview(Modifier.fillMaxSize(), cover = true)
                    }
                }
            }
        }
    }
}

/** The chat's video box look (hen likes it): a rounded portrait tile, a soft shadow, a small pill on top. */
@Composable
private fun VideoTile(who: String, since: Long, mine: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.height(176.dp).aspectRatio(3f / 4f)
            .shadow(8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1B2120)),
    ) {
        content()
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(who, color = Color.White, style = LibraryType.Tiny, maxLines = 1)
            Box(Modifier.padding(horizontal = 5.dp).size(6.dp).background(if (mine) com.chencohen.ptt.ui.style.Pink else LibraryColors.Accent, CircleShape))
            Text(elapsed(since), color = Color.White, style = LibraryType.Tiny)
        }
    }
}

/** My voice push, at the chosen row's end: "You · 0:03" in a coral ring (red when urgent). */
@Composable
private fun MyPill(urgent: Boolean, since: Long) {
    val ring = if (urgent) LibraryColors.Error else com.chencohen.ptt.ui.style.Pink
    Text(
        stringResource(R.string.talk_you) + " · " + elapsed(since),
        color = LibraryColors.OnBackground, style = LibraryType.Tiny,
        modifier = Modifier.background(ring.copy(alpha = 0.12f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun elapsed(since: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return clipLength((now - since).coerceAtLeast(0))
}

@Composable
private fun RowFace(item: TalkListItem, size: Dp) {
    if (item.group) GroupAvatar(item.name, item.groupPicture, size)
    else HearFace(item.chat, size) { Avatar(item.chat, size) }
}

/** No Quick talk chat yet: one line, and Add (the same list as "+"). No Push, no writing bubble. */
@Composable
private fun Empty(modifier: Modifier, add: () -> Unit) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.quick_talk_empty_simple), color = LibraryColors.Muted, style = LibraryType.Body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            stringResource(R.string.quick_talk_add),
            color = LibraryColors.OnBackground, style = LibraryType.RowTitle,
            modifier = Modifier.padding(top = 20.dp)
                .background(LibraryColors.Raised, CircleShape)
                .clickable(role = Role.Button, onClick = add)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/** "+": every contact and group with a switch: on, it's a row on the Talk tab. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(choices: List<TalkChoice>, setOn: (String, Boolean) -> Unit, close: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, containerColor = LibraryColors.Background) {
        Text(
            stringResource(R.string.quick_talk_add_title), color = LibraryColors.OnBackground, style = LibraryType.PageTitle,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(choices, key = { it.chat }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Switch) { setOn(c.chat, !c.on) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (c.group) GroupAvatar(c.name, c.groupPicture, 40.dp) else Avatar(c.chat, 40.dp)
                    Text(c.name, color = LibraryColors.OnBackground, style = LibraryType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Switch(c.on, { on -> setOn(c.chat, on) })
                }
            }
        }
    }
}
