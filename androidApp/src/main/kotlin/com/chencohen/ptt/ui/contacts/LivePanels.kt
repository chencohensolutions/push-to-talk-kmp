package com.chencohen.ptt.ui.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.live.LiveGroupLayout
import com.chencohen.ptt.ui.live.LiveItem
import com.chencohen.ptt.ui.live.LiveLayout
import com.chencohen.ptt.ui.live.LiveSlot
import com.chencohen.ptt.ui.live.LiveMotion
import com.chencohen.ptt.ui.live.LiveRect
import androidx.compose.ui.layout.onGloballyPositioned
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.talk.clipLength
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Push-to-video out of the chat's flow (2026-11-24, hen): theirs drops down under the header, mine rises above the
// buttons; the chat stays between them, still usable. docs/screens/conversation.md, "Live panels".

/** m:ss since [since], ticking. */
@Composable
private fun elapsedSince(since: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    return clipLength((now - since).coerceAtLeast(0L))
}

/** A red dot and words over the video, on a dark pill. */
@Composable
private fun LiveTag(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(LibraryColors.Error, CircleShape))
        Text(text, color = Color.White, style = LibraryType.Small.copy(textDirection = layoutTextDirection()), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** The live videos' height: this share of the screen's (2026-11-24, hen: the same size as the floating window). */
internal const val LIVE_VIDEO_SHARE = 0.236f

/**
 * Boxes of the given shapes (width for 1 high), [LIVE_VIDEO_SHARE] of the screen high, side by side and centred, 6 dp
 * apart; all shrunk together only if they'd be wider than the space. [content] gets each box's index.
 */
@Composable
internal fun LiveBoxes(
    shapes: List<Float>,
    modifier: Modifier = Modifier,
    /** Beside the boxes, top-aligned (a voice pill beside a video; 2026-11-24, hen): before them and after. */
    before: @Composable () -> Unit = {},
    after: @Composable () -> Unit = {},
    content: @Composable (Int, Modifier) -> Unit,
) {
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        val gap = 6.dp
        var h = screen * LIVE_VIDEO_SHARE
        val wide = h * shapes.sum() + gap * (shapes.size - 1).coerceAtLeast(0)
        if (wide > maxWidth) h *= (maxWidth - gap * (shapes.size - 1).coerceAtLeast(0)) / (h * shapes.sum())
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
            before()
            shapes.forEachIndexed { i, shape ->
                val box = RoundedCornerShape(18.dp)
                content(i, Modifier.size(h * shape, h).shadow(6.dp, box).clip(box).background(Color.Black))
            }
            after()
        }
    }
}

/** A side of a chat that is live now, for a live row drawn outside the chat (ChatLive.liveRow). */
enum class LiveKind { None, Voice, Video }

/**
 * What is live in a chat now, for a live row drawn outside it (the floating chat: TalkBubble's row at the top of the
 * screen): each side's kind and since when, whose face and name theirs is, and whether both are live.
 */
data class LiveNow(
    val theirs: LiveKind = LiveKind.None,
    val mine: LiveKind = LiveKind.None,
    val theirUser: String = "",
    val theirName: String = "",
    val theirSince: Long = 0L,
    val mySince: Long = 0L,
    val bothLive: Boolean = false,
    /** My push is urgent (the red bell; 2026-11-25, hen): "You · Urgent" in red. */
    val myUrgent: Boolean = false,
)

/** Test builds only (TestBubble): the other side shown live in the open chat without a real push. */
internal object LivePretend {
    /** Whose shape LiveTalkVideo.shapes holds for the pretended video. */
    const val IDENTITY = "pretend.0"
    val theirs = kotlinx.coroutines.flow.MutableStateFlow(LiveKind.None)

    /** A group member pretended live: [user] (their "user.device" is "[user].1"), on video or voice, since when. */
    data class Member(val user: String, val video: Boolean, val since: Long)

    /** Group members pretended live at once, in the open group chat and the floating window's row (several talkers). */
    val members = kotlinx.coroutines.flow.MutableStateFlow<List<Member>>(emptyList())

    /** My own side pretended live in the open group chat (no camera, no sound, nothing sent). */
    val mine = kotlinx.coroutines.flow.MutableStateFlow(LiveKind.None)
}

/** Someone live in a group's overlay: [key] their "user.device" (or LiveLayout.MINE), on [video] or voice, [since]. */
internal data class GroupTalker(val key: String, val user: String, val name: String, val video: Boolean, val since: Long, val mine: Boolean = false)

/** The log tag of the chat's live row. */
private const val LIVE_ROW = "LiveRow"

/** One side of the live row: [key] whose it is (LiveLayout.THEIRS, MINE), a video box [shape] wide for 1 high or a voice pill. */
internal data class LiveSide(val key: String, val video: Boolean, val shape: Float)

/** The Talk tab's stage (2026-11-26, hen): a video box as tall as the stage gives, in place of a share of the screen. */
internal val LocalLiveBoxHeight = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.unit.Dp?> { null }

/**
 * Live in the chat, floating over it just below the header (2026-11-24, hen: "the video and pills should be floating,
 * not inside black box"; 2026-11-25: "just below the header"): for each side, theirs at the language's start side and
 * mine at the other, a voice pill or a video box 23.6% of the screen high in the picture's own shape (no crop, no
 * bars), with only their own rounded shape and a soft shadow: nothing behind them, the chat scrolls on beneath.
 * Where each rests and how they move is LiveLayout and LiveMotion's (one move for everything; docs/screens/style.md,
 * "Motion"): a side comes down from under the header's edge and goes back up under it, the other glides aside or
 * back to the centre meanwhile, and "the pill becomes the video" as one frame growing about its centre.
 */
@Composable
internal fun LivePanel(
    theirs: Boolean, who: String?, theirSince: Long,
    mine: Boolean, mySince: Long, @Suppress("UNUSED_PARAMETER") handsFree: Boolean,
    /** The talker's "user.device": their video through the talk room, when it comes (LiveTalkVideo). */
    theirIdentity: String? = null,
    /** How far behind the clip path's picture plays (the test builds' readout). */
    behindMs: Long? = null,
    /** Voice (a side not on video): its pill, in that side's place. */
    voices: List<VoiceSide> = emptyList(),
    /** The other side, for its face while its box waits for a first picture. */
    theirUser: String = "",
    theirName: String = "",
    /** My push now is urgent (the red bell; 2026-11-25, hen): "You · Urgent", in red. */
    myUrgent: Boolean = false,
) {
    val arriving by com.chencohen.ptt.talk.LiveTalkVideo.arriving.collectAsState()
    // Through the room while its frames come; else the clips' pictures (never both).
    val room = theirIdentity?.let { arriving[it] }
    val shown by com.chencohen.ptt.talk.AndroidTalkVideoOut.shown.collectAsState()
    var lastShown by remember { androidx.compose.runtime.mutableStateOf(shown) }
    if (shown != null) lastShown = shown
    val s = shown ?: lastShown
    val shapes by com.chencohen.ptt.talk.LiveTalkVideo.shapes.collectAsState()
    // Their camera's shape as they said it with their key (2026-11-25): known before the first picture.
    val said = theirIdentity?.let { shapes[it] }?.let { it / 1000f }
    val theirShape = if (room != null) room.aspect else if (said != null) said else if (s != null && s.width > 0 && s.height > 0) {
        val a = s.width.toFloat() / s.height
        if (s.rotation % 180 != 0) 1f / a else a
    } else 3f / 4f
    val myShape = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
    val theirVoice = voices.firstOrNull { !it.mine }
    val myVoice = voices.firstOrNull { it.mine }
    val me = youLabel(myUrgent)
    val myRing = if (myUrgent) LibraryColors.Error else com.chencohen.ptt.ui.style.Pink
    // Each side's face and words as they last were, kept while it goes up, so it doesn't empty first.
    val last = remember { arrayOfNulls<VoiceSide>(2) }
    if (theirs || theirVoice != null) last[0] = theirVoice ?: VoiceSide(theirUser, who ?: theirName, false, theirSince)
    if (mine || myVoice != null) last[1] = myVoice ?: VoiceSide("", me, true, mySince)
    // The pill grows once its picture's shape is known (2026-11-25, hen): until then it stays the pill, so the box
    // never grows to a guessed size and then changes again.
    val ready = room != null || s != null || said != null
    // Mine without the room (the camera's own preview) is ready a moment after it starts.
    val local by com.chencohen.ptt.talk.LiveTalkVideo.local.collectAsState()
    var settled by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(mine) {
        settled = false
        if (mine) {
            delay(700)
            settled = true
        }
    }
    // A side that goes keeps its last picture while it slides up (2026-11-25, hen: "the camera turns black and then
    // slides up"): its track, gone by then, stays in its box until the box is out, and so does whether it had a picture.
    val heldRoom = remember { arrayOfNulls<com.chencohen.ptt.talk.LiveTalkVideo.Live>(1) }
    if (room != null) heldRoom[0] = room else if (theirs) heldRoom[0] = null
    val showRoom = room ?: heldRoom[0]
    val heldLocal = remember { arrayOfNulls<io.livekit.android.room.track.VideoTrack>(1) }
    if (local != null) heldLocal[0] = local else if (mine) heldLocal[0] = null
    val showLocal = local ?: heldLocal[0]
    // A box shows its picture only once pictures really come (theirs: through the room or the clip; mine: the room's
    // track carries my camera's frames, LiveTalkVideo.localPicture), the face until then: never a black box
    // (2026-11-25: black while waiting for the floor, and once for theirs before its first picture).
    val localPicture by com.chencohen.ptt.talk.LiveTalkVideo.localPicture.collectAsState()
    // Their video stops: their picture fades to black while the box goes up, in the same move (2026-11-25, hen).
    val theirFade by androidx.compose.animation.core.animateFloatAsState(if (theirs) 0f else 1f, tween(300), label = "theirFade")
    val pictured = remember { booleanArrayOf(false, false) }
    if (theirs) pictured[0] = showRoom != null || shown != null
    if (mine) pictured[1] = if (local != null) localPicture else settled
    val sides = buildList {
        if (theirs || theirVoice != null) add(LiveSide(LiveLayout.THEIRS, theirs && ready, theirShape))
        if (mine || myVoice != null) add(LiveSide(LiveLayout.MINE, mine, myShape))
    }
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val teal = LibraryColors.Accent
    LiveStage(
        sides,
        boxHeight = LocalLiveBoxHeight.current ?: (screen * LIVE_VIDEO_SHARE),
        ring = { if (it.key == LiveLayout.MINE) myRing else teal },
        handoff = true,
        pill = { side ->
            val voice = last[if (side.key == LiveLayout.MINE) 1 else 0]
            if (voice != null) VoicePillRow(voice, if (voice.mine) myRing else LibraryColors.Accent)
        },
    ) { side, w, h, p ->
        val isMine = side.key == LiveLayout.MINE
        val voice = last[if (isMine) 1 else 0] ?: return@LiveStage
        val tag = when {
            // Who only, no time, at the box's top centre (2026-11-25, hen).
            !isMine -> who ?: voice.name
            else -> me
        }
        LiveBoxInside(voice, tag, w, h, p, pictured = side.video && pictured[if (isMine) 1 else 0]) { box ->
            if (isMine) {
                // The room's track while it reads the camera (and after, while the box goes), else the camera's own preview.
                if (showLocal != null) com.chencohen.ptt.ui.talk.LocalRoomVideo(showLocal, box)
                else com.chencohen.ptt.ui.talk.PushVideoPreview(box, cover = true)
            } else {
                Box(box) {
                    if (showRoom != null) com.chencohen.ptt.ui.talk.RoomVideoView(showRoom, Modifier.fillMaxSize())
                    else com.chencohen.ptt.ui.talk.TalkVideoView(Modifier.fillMaxSize(), cover = true, inset = false)
                    if (theirFade > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = theirFade)))
                    com.chencohen.ptt.ui.talk.DelayReadout(room, theirSince, behindMs, Modifier.align(Alignment.BottomCenter).padding(6.dp))
                }
            }
        }
    }
}

/**
 * A group's live overlay (2026-11-25, hen: several people talking at once in a group): everyone live in it, mine one of
 * them, at most four in view (LiveCast: the ones who spoke last; a fifth pushes out the one quiet longest), the videos
 * in one row of equal boxes cropped to fill, the voices as pills under them, and "+N talking · Name" for the rest: a
 * tap brings that one into view in the quietest one's place. Each box shows its picture through the room, else the
 * face (the clip path's one decoder can't show several). All of it moves as one (LiveStage, LiveMotion).
 */
@Composable
internal fun GroupLivePanel(talkers: List<GroupTalker>, myUrgent: Boolean = false) {
    val arriving by com.chencohen.ptt.talk.LiveTalkVideo.arriving.collectAsState()
    val local by com.chencohen.ptt.talk.LiveTalkVideo.local.collectAsState()
    val localPicture by com.chencohen.ptt.talk.LiveTalkVideo.localPicture.collectAsState()
    val me = youLabel(myUrgent)
    val myRing = if (myUrgent) LibraryColors.Error else com.chencohen.ptt.ui.style.Pink
    // Each one as they last were, kept while they go up, so a box doesn't empty first.
    val last = remember { HashMap<String, GroupTalker>() }
    talkers.forEach { last[it.key] = it }
    val cast = remember { com.chencohen.ptt.ui.live.LiveCast() }
    var chosen by remember { mutableLongStateOf(0L) }
    val people = talkers.map { com.chencohen.ptt.ui.live.LivePerson(it.key, it.since) }
    val inView = remember(people, chosen) { cast.update(people); cast.shown }
    val hidden = cast.hidden
    // Mine without the room (the camera's own preview) is ready a moment after it starts.
    val mineLive = talkers.any { it.mine && it.video }
    var settled by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(mineLive) {
        settled = false
        if (mineLive) {
            delay(700)
            settled = true
        }
    }
    val byKey = talkers.associateBy { it.key }
    val sides = inView.mapNotNull { k -> byKey[k]?.let { LiveSide(k, it.video, 3f / 4f) } }
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val myShape = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
    val newest = hidden.firstOrNull()?.let { byKey[it.key] }
    val teal = LibraryColors.Accent
    LiveStage(
        sides,
        boxHeight = LocalLiveBoxHeight.current ?: (screen * LIVE_VIDEO_SHARE),
        ring = { if (it.key == LiveLayout.MINE) myRing else teal },
        handoff = true,
        group = true,
        groupShape = myShape,
        chip = newest?.let { n ->
            {
                MoreTalking(hidden.size, if (n.mine) me else n.name) {
                    cast.choose(n.key, System.currentTimeMillis())
                    chosen = System.currentTimeMillis()
                }
            }
        },
        pill = { side ->
            val t = last[side.key]
            if (t != null) VoicePillRow(VoiceSide(if (t.mine) "" else t.user, if (t.mine) me else t.name, t.mine, t.since), if (t.mine) myRing else LibraryColors.Accent)
        },
    ) { side, w, h, p ->
        val t = last[side.key] ?: return@LiveStage
        val voice = VoiceSide(if (t.mine) "" else t.user, if (t.mine) me else t.name, t.mine, t.since)
        val tag = when {
            !t.mine -> t.name
            else -> me
        }
        val room = if (t.mine) null else arriving[t.key]
        val pictured = side.video && if (t.mine) (if (local != null) localPicture else settled) else room != null
        LiveBoxInside(voice, tag, w, h, p, pictured = pictured) { box ->
            if (t.mine) {
                if (local != null) com.chencohen.ptt.ui.talk.LocalRoomVideo(local!!, box)
                else com.chencohen.ptt.ui.talk.PushVideoPreview(box, cover = true)
            } else if (room != null) {
                com.chencohen.ptt.ui.talk.RoomVideoView(room, box)
            }
        }
    }
}

/** "+2 talking · Dana" under the group's live overlay: those live but not in view; a tap brings Dana into view. */
@Composable
private fun MoreTalking(count: Int, name: String, choose: () -> Unit) {
    Text(
        stringResource(R.string.talk_more_talking, count, android.text.BidiFormatter.getInstance().unicodeWrap(name)),
        color = LibraryColors.OnInverse, style = LibraryType.Small.copy(textDirection = layoutTextDirection()), maxLines = 1,
        modifier = Modifier.padding(4.dp).shadow(6.dp, CircleShape).background(LibraryColors.Inverse, CircleShape)
            .clickable(role = Role.Button, onClick = choose).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * The live row's stage: [sides] in reading order (theirs first), each ONE frame that is a voice pill ([pill], at its
 * own size) or a video box ([box], [boxHeight] high unless they must all shrink to fit) and grows from one to the
 * other. Every frame is placed by LiveLayout and moved by LiveMotion, in pixels from the left edge whatever the
 * language (2026-11-25, hen: in Hebrew items came in at the wrong side and moved in steps): no Row, no arrangement,
 * nothing mirrored. A side comes in from above the stage's top edge (cut there: the chat's header is above it) and
 * leaves the same way.
 */
@Composable
internal fun LiveStage(
    sides: List<LiveSide>,
    boxHeight: Dp,
    modifier: Modifier = Modifier,
    ring: (LiveSide) -> Color,
    pill: @Composable (LiveSide) -> Unit,
    /**
     * A group (2026-11-25, hen: several people talking at once): the boxes in one row, equal widths, each [groupShape]
     * wide for 1 high at most and cropped to fill; the pills under them, face only when the names don't fit; [chip]
     * (the ones live but not in view) centred under all. Else (one to one) the row as it always was.
     */
    group: Boolean = false,
    groupShape: Float = 3f / 4f,
    chip: (@Composable () -> Unit)? = null,
    /**
     * The chat's own row: where its items are on the screen is kept for the floating window that takes them over as
     * the chat goes, and items the floating window hands back come from where they were there ([LiveHandoff]).
     */
    handoff: Boolean = false,
    /** A box's inside, drawn in the frame: its picture's full size [w] × [h], and how far it is a box by now, 0 to 1. */
    box: @Composable androidx.compose.foundation.layout.BoxScope.(side: LiveSide, w: Dp, h: Dp, p: Float) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val motion = remember { LiveMotion() }
    // Each pill's own size, measured; each side as it last was (kept while it goes); each box's size.
    val pills = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.unit.IntSize>() }
    val known = remember { HashMap<String, LiveSide>() }
    val boxes = remember { HashMap<String, Pair<Float, Float>>() }
    sides.forEach { known[it.key] = it }
    val lastChip = remember { arrayOfNulls<@Composable () -> Unit>(1) }
    if (chip != null) lastChip[0] = chip
    // Read below: the stage is laid out again on each frame of a move.
    val frame = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val running = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    // The stage's top left on the screen.
    val origin = remember { FloatArray(2) }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().then(
            if (handoff) Modifier.onGloballyPositioned { c ->
                val p = c.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
                origin[0] = p.x
                origin[1] = p.y
            } else Modifier,
        ),
    ) {
        val width = constraints.maxWidth.toFloat()
        val side = with(density) { 12.dp.toPx() }
        val top = with(density) { 8.dp.toPx() }
        val gap = with(density) { 6.dp.toPx() }
        frame.longValue
        // A pill (or the chip) not measured yet (it is, a frame from now): the row waits for it, so nothing moves twice.
        val chipSize = if (chip != null) pills[LiveGroupLayout.MORE] else null
        if (group && sides.all { it.video || pills[it.key] != null } && (chip == null || chipSize != null)) {
            val full = with(density) { boxHeight.toPx() }
            val (bw, bh) = LiveGroupLayout.boxSize(sides.count { it.video }, full, groupShape, width - 2 * side, gap)
            val slots = sides.map { s ->
                if (s.video) LiveSlot(s.key, true).also { boxes[s.key] = bw to bh }
                else pills.getValue(s.key).let { LiveSlot(s.key, false, it.width.toFloat(), it.height.toFloat()) }
            }
            motion.target(
                LiveGroupLayout.place(slots, width - 2 * side, gap, full, groupShape, rtl, left = side, chip = chipSize?.let { it.width.toFloat() to it.height.toFloat() }),
                offTop = top + with(density) { 12.dp.toPx() }, small = with(density) { 16.dp.toPx() }, from = handedBack(handoff, origin, top),
            )
        } else if (!group && sides.all { it.video || pills[it.key] != null }) {
            val h = LiveLayout.boxHeight(
                with(density) { boxHeight.toPx() }, sides.filter { it.video }.map { it.shape },
                sides.filter { !it.video }.map { pills.getValue(it.key).width.toFloat() }, width - 2 * side, gap,
            )
            val items = sides.map { s ->
                if (s.video) LiveItem(s.key, h * s.shape, h, true).also { boxes[s.key] = it.w to it.h }
                else pills.getValue(s.key).let { LiveItem(s.key, it.width.toFloat(), it.height.toFloat(), false) }
            }
            // From above the stage's top edge, the shadow too.
            motion.target(LiveLayout.place(items, width, gap, rtl), offTop = top + with(density) { 12.dp.toPx() }, small = with(density) { 16.dp.toPx() }, from = handedBack(handoff, origin, top))
        }
        androidx.compose.runtime.SideEffect {
            if (handoff) com.chencohen.ptt.ui.live.LiveHandoff.chatRow(origin[0], origin[1] + top, motion.goals.filterKeys { !motion.leaving(it) })
            if (motion.running && running[0]?.isActive != true) {
                running[0] = scope.launch {
                    // The system's animation speed (Remove animations: at once), as the app's other animations.
                    val scale = coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f
                    while (motion.running) androidx.compose.runtime.withFrameMillis {
                        motion.frame(it, scale)
                        frame.longValue = it
                        // `adb shell setprop log.tag.LiveRow VERBOSE`: each frame of a move, to check it is one move.
                        if (android.util.Log.isLoggable(LIVE_ROW, android.util.Log.VERBOSE)) {
                            android.util.Log.v(LIVE_ROW, "frame $it: " + motion.rects.entries.joinToString { (k, r) -> "$k cx=%.1f top=%.1f w=%.1f h=%.1f".format(r.centreX, top + r.y, r.w, r.h) })
                        }
                    }
                }
            }
        }
        val rects = motion.rects
        val shown = (sides.map { it.key } + rects.keys).distinct().filter { it != LiveGroupLayout.MORE } + listOfNotNull(LiveGroupLayout.MORE.takeIf { chip != null || it in rects })
        androidx.compose.ui.layout.Layout(
            content = {
                shown.forEach { k ->
                    if (k == LiveGroupLayout.MORE) {
                        // The chip, at its own size; as it last was while it goes up.
                        androidx.compose.runtime.key(k) {
                            Box(Modifier.wrapContentSize(unbounded = true).onSizeChanged { pills[k] = it }) { (chip ?: lastChip[0])?.invoke() }
                        }
                        return@forEach
                    }
                    val s = known.getValue(k)
                    androidx.compose.runtime.key(k) {
                        val p = rects[k]?.video ?: if (s.video) 1f else 0f
                        val shape = RoundedCornerShape(androidx.compose.ui.unit.lerp(32.dp, 18.dp, p))
                        val size = boxes[k]
                        Box(
                            Modifier.testTag("live-$k").shadow(6.dp, shape).clip(shape)
                                .background(androidx.compose.ui.graphics.lerp(LibraryColors.Raised, Color.Black, p))
                                .border(1.dp, ring(s).copy(alpha = 1f - 0.6f * p), shape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if ((s.video || p > 0f) && size != null) box(s, with(density) { size.first.toDp() }, with(density) { size.second.toDp() }, p)
                            if (!s.video || p < 1f) {
                                // At its own size whatever the frame's, centred: the frame growing covers or uncovers it.
                                // In a group, a frame narrower than the pill (face only: the names don't fit) keeps its face,
                                // at the language's start side, in view: the pill slides to that edge, the frame covering
                                // the words (less so as it grows into a box).
                                val frameW = rects[k]?.w ?: 0f
                                val pillW = pills[k]?.width?.toFloat() ?: 0f
                                val slide = if (group && frameW in 0.5f..<pillW) (pillW - frameW) / 2 * (1f - p) * (if (rtl) -1 else 1) else 0f
                                Box(
                                    Modifier.wrapContentSize(unbounded = true).onSizeChanged { pills[k] = it }
                                        .graphicsLayer { alpha = 1f - p; translationX = slide },
                                ) { pill(s) }
                            }
                        }
                    }
                }
            },
            // Cut at the stage's top edge: a side comes out from under the header, not over it.
            modifier = Modifier.drawWithContent {
                clipRect(left = -size.width, top = 0f, right = 2 * size.width, bottom = size.height + 4 * top) { this@drawWithContent.drawContent() }
            },
        ) { measurables, constraints ->
            val placed = measurables.mapIndexed { i, m ->
                val r = rects[shown[i]]
                if (r == null) {
                    // Not in the row yet: only measured (its pill's size is wanted).
                    m.measure(androidx.compose.ui.unit.Constraints.fixed(0, 0))
                    null
                } else {
                    // Both edges from the centre: growing, the frame's centre stays where it is to the pixel.
                    val left = kotlin.math.round(r.centreX - r.w / 2).toInt()
                    val right = kotlin.math.round(r.centreX + r.w / 2).toInt()
                    Triple(m.measure(androidx.compose.ui.unit.Constraints.fixed((right - left).coerceAtLeast(0), kotlin.math.round(r.h).toInt().coerceAtLeast(0))), left, kotlin.math.round(top + r.y).toInt())
                }
            }
            val bottom = rects.values.maxOfOrNull { top + it.bottom + top } ?: 0f
            layout(constraints.maxWidth, kotlin.math.ceil(bottom).toInt().coerceAtLeast(0)) {
                // Left to right always (place, never placeRelative): the language picks the order, not the geometry.
                placed.forEach { it?.let { (p, x, y) -> p.place(x, y) } }
            }
        }
    }
}

/**
 * Where an item the floating window just handed back starts in the chat's stage (its top left on the screen [origin],
 * its items' resting top [top]): where it was in the floating window (2026-11-27, hen: back in the chat "the video
 * should move to the in-chat position").
 */
private fun handedBack(handoff: Boolean, origin: FloatArray, top: Float): ((String, LiveRect) -> LiveRect?)? {
    if (!handoff) return null
    val back = com.chencohen.ptt.ui.live.LiveHandoff.recentFloating(System.currentTimeMillis()).takeIf { it.isNotEmpty() } ?: return null
    return { key, t -> back[key]?.let { s -> LiveRect(s.left - origin[0], s.top - origin[1] - top, s.w, s.h, t.video) } }
}

/**
 * A video box's inside (2026-11-25, hen: "the voice pill should transform to a video box"): the picture at the box's
 * full size [w] × [h], centred, so the growing frame uncovers it; it fades in once [pictured] (its first picture), the
 * face shown large until then, so the box is never black; the tag fades in at the bottom as the frame becomes a box
 * ([p], 0 to 1).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.LiveBoxInside(side: VoiceSide, tag: String, w: Dp, h: Dp, p: Float, pictured: Boolean, picture: @Composable (Modifier) -> Unit) {
    val shown by androidx.compose.animation.core.animateFloatAsState(if (pictured) 1f else 0f, tween(250), label = "first picture")
    Box(Modifier.requiredSize(w, h).graphicsLayer { alpha = shown }) { picture(Modifier.fillMaxSize()) }
    // Until the first picture: the face, large.
    if (shown < 1f) {
        Box(Modifier.graphicsLayer { alpha = p * (1f - shown) }) { BigFace(side) }
    }
    LiveTag(tag, Modifier.align(Alignment.TopCenter).padding(8.dp).graphicsLayer { alpha = p })
}

/** The face, large, while the box waits for its first picture. */
@Composable
private fun BigFace(side: VoiceSide) {
    val face = com.chencohen.ptt.ui.face.faceOf(if (side.mine) "" else side.user)
    if (face != null) {
        androidx.compose.foundation.Image(face, side.name, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.size(96.dp))
    } else {
        Box(Modifier.size(72.dp).background(LibraryColors.Mine, CircleShape), contentAlignment = Alignment.Center) {
            Text(side.name.take(1).uppercase(), color = LibraryColors.OnMine, style = LibraryType.RowTitle)
        }
    }
}

/** Someone pushing voice in the chat, for the [VoiceBar]: their face ("" is mine), name, and when they started. */
internal data class VoiceSide(val user: String, val name: String, val mine: Boolean, val since: Long)

@Composable
private fun VoicePill(side: VoiceSide) {
    val ring = if (side.mine) com.chencohen.ptt.ui.style.Pink else LibraryColors.Accent
    val shape = RoundedCornerShape(32.dp)
    Box(Modifier.shadow(6.dp, shape).clip(shape).background(LibraryColors.Raised).border(1.dp, ring, shape)) { VoicePillRow(side, ring) }
}

/** A voice pill's inside: the face in its glowing ring, then who and the time. */
@Composable
private fun VoicePillRow(side: VoiceSide, ring: Color) {
    Row(
        Modifier.padding(start = 2.dp, top = 2.dp, bottom = 2.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TalkingFace(side, ring)
        val time = elapsedSince(side.since)
        if (side.mine) {
            Text("${side.name} · $time", color = LibraryColors.OnBackground, style = LibraryType.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textDirection = layoutTextDirection()), maxLines = 1)
        } else {
            Column {
                // The name wrapped, the line in the screen's direction: a Latin name doesn't turn a Hebrew line around.
                Text(com.chencohen.ptt.genderedString(R.string.talk_someone, side.user, android.text.BidiFormatter.getInstance().unicodeWrap(side.name)), color = LibraryColors.OnBackground, style = LibraryType.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textDirection = layoutTextDirection()), maxLines = 1)
                Text(time, color = LibraryColors.Muted, style = LibraryType.Tiny.copy(textDirection = layoutTextDirection()))
            }
        }
    }
}

/** A face in a voice pill: a ring around it that glows with the voice (brightness, glow, thickness; 60 ms up, 200 down). */
@Composable
private fun TalkingFace(side: VoiceSide, color: Color) {
    val face = com.chencohen.ptt.ui.face.faceOf(if (side.mine) "" else side.user)
    val level by voiceLevel(side.mine)
    Box(
        Modifier.size(52.dp).drawBehind {
            val r = 18.dp.toPx()
            val thick = (2f + 2.5f * level).dp.toPx()
            val glow = 7.dp.toPx() * level
            var g = glow
            while (g > 0.5.dp.toPx()) {
                drawCircle(color.copy(alpha = 0.35f * level * (1 - g / (glow + 0.01f))), radius = r + thick / 2 + g, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                g -= 1.5.dp.toPx()
            }
            drawCircle(color.copy(alpha = 0.55f + 0.45f * level), radius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = thick))
        },
        contentAlignment = Alignment.Center,
    ) {
        if (face != null) {
            androidx.compose.foundation.Image(face, side.name, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.size(32.dp))
        } else {
            Box(Modifier.size(30.dp).background(LibraryColors.Mine, CircleShape), contentAlignment = Alignment.Center) {
                Text(side.name.take(1).uppercase(), color = LibraryColors.OnMine, style = LibraryType.Small)
            }
        }
    }
}

/** Theirs as it plays, or mine from the microphone, smoothed as on the Talk button. */
@Composable
private fun voiceLevel(mine: Boolean): androidx.compose.runtime.State<Float> {
    val level = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(mine) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 16f else (now - last) / 1_000_000f
                last = now
                val target = if (mine) com.chencohen.ptt.talk.TalkAudio.micLevel else com.chencohen.ptt.talk.TalkAudio.playLevel
                level.floatValue = com.chencohen.ptt.talk.VoiceLevel.smooth(level.floatValue, target, dt)
            }
        }
    }
    return level
}


/** "You're both live · Make it a call" (2026-11-24, hen), just under the top panel, in its own space. */
@Composable
internal fun BothLiveChip(shown: Boolean, makeCall: () -> Unit) {
    AnimatedVisibility(shown, enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.shadow(6.dp, CircleShape).background(LibraryColors.Inverse, CircleShape)
                    .clickable(role = Role.Button, onClick = makeCall)
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.talk_both_live), color = LibraryColors.OnInverse, style = LibraryType.Small)
                Text(
                    stringResource(R.string.talk_make_call), color = Color.White, style = LibraryType.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 10.dp).background(com.chencohen.ptt.ui.style.Pink, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** "⤢ Full screen" (2026-11-27, hen), just under the top panel, in its own space: the live talk full screen. */
@Composable
internal fun FullScreenChip(shown: Boolean, open: () -> Unit) {
    AnimatedVisibility(shown, enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.shadow(6.dp, CircleShape).background(LibraryColors.Inverse, CircleShape)
                    .clickable(role = Role.Button, onClick = open)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(com.chencohen.ptt.ui.style.LibraryIcons.Expand, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.talk_full_screen), color = LibraryColors.OnInverse,
                    style = LibraryType.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * A push-to-talk burst the chat doesn't keep (2026-11-24, hen: its "Keep voice / videos in chat" off): one muted,
 * centred line once it ends, "Sharon sent live video · 0:07"; nothing to play. A run of them is a [TalkSession].
 */
@Composable
internal fun TalkTrace(message: TextMessage, author: String, at: String? = null) {
    val clip = message.clip ?: return
    val length = clip.durationMs.takeIf { it > 0 }?.let { clipLength(it) }
    val words = when {
        message.outgoing && clip.video -> stringResource(R.string.talk_trace_my_video)
        message.outgoing -> stringResource(R.string.talk_trace_my_voice)
        clip.video -> com.chencohen.ptt.genderedString(R.string.talk_trace_video, author, author)
        else -> com.chencohen.ptt.genderedString(R.string.talk_trace_voice, author, author)
    }
    // One column: the line, and for mine what became of it at each listener (2026-11-25, hen), centred under it.
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            listOfNotNull(words, length, at).joinToString(" · "),
            color = LibraryColors.Muted, style = LibraryType.Tiny, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (message.outgoing && clip.heardBy.isNotEmpty()) {
            com.chencohen.ptt.ui.talk.HeardLabels(clip, com.chencohen.ptt.messaging.Group.idOf(message.user) != null, { it }, onInverse = false)
        }
    }
}

/**
 * Push-to-talk back and forth with nothing else between (2026-11-24, hen): one quiet line, "Push-to-talk · Sharon ×5,
 * you ×4 · 21:40–21:45"; a tap lists each one under it, a tap again folds them.
 */
@Composable
internal fun TalkSession(run: List<TextMessage>, nameOf: (TextMessage) -> String, open: Boolean, toggle: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val time = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val me = stringResource(R.string.talk_panel_you)
    // Each side by count, in the order they first spoke.
    val sides = LinkedHashMap<String, Int>()
    run.forEach { m -> val who = if (m.outgoing) me else nameOf(m); sides[who] = (sides[who] ?: 0) + 1 }
    val from = time.format(java.util.Date(run.first().at))
    val to = time.format(java.util.Date(run.last().at))
    val words = listOf(
        stringResource(R.string.talk_session),
        sides.entries.joinToString(", ") { (who, n) -> if (n > 1) "$who ×$n" else who },
        if (from == to) from else "$from–$to",
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth()) {
        Text(
            words, color = LibraryColors.Muted, style = LibraryType.Tiny, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = toggle).padding(vertical = 6.dp),
        )
        AnimatedVisibility(open, enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
            Column { run.forEach { m -> TalkTrace(m, nameOf(m), at = time.format(java.util.Date(m.at))) } }
        }
    }
}

/** Locked, hands-free: the big button's place shows Stop. */
@Composable
internal fun StopLive(size: Dp, stop: () -> Unit, urgent: Boolean = false) {
    val label = stringResource(R.string.talk_stop)
    // Locked on the urgent bell (2026-11-26, hen): a red Stop with the bell.
    val fill by androidx.compose.animation.animateColorAsState(if (urgent) LibraryColors.Error else com.chencohen.ptt.ui.style.Pink, tween(220), label = "stop fill")
    Box(
        Modifier.size(size).shadow(10.dp, CircleShape).background(fill, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = stop),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (urgent) com.chencohen.ptt.ui.style.IconsOnAccent { androidx.compose.material3.Icon(com.chencohen.ptt.ui.style.LibraryIcons.Bell, null, tint = Color.White, modifier = Modifier.size(size * 0.3f)) }
            else Box(Modifier.size(size * 0.26f).background(Color.White, RoundedCornerShape(4.dp)))
            Text(label, color = Color.White, style = LibraryType.Tiny, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * The call in the talk room (2026-11-25, hen: both live becomes a call "instantly"): theirs and mine as in the live
 * panel, a video box for each side whose camera is on and a voice pill otherwise, side by side.
 */
@Composable
internal fun RoomCallTop(call: com.chencohen.ptt.messaging.RoomCall, them: String, theirName: String, me: String) {
    val arriving by com.chencohen.ptt.talk.LiveTalkVideo.arriving.collectAsState()
    val theirs = arriving.values.firstOrNull { it.identity.substringBeforeLast('.') != me }
    val myShape = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
    val shapes = listOfNotNull(theirs?.aspect, if (call.camera) myShape else null)
    LiveBoxes(
        shapes,
        before = { if (theirs == null) VoicePill(VoiceSide(them, theirName, false, call.since)) },
        after = { if (!call.camera) VoicePill(VoiceSide("", stringResource(R.string.talk_panel_you), true, call.since)) },
    ) { i, box ->
        Box(box) {
            if (i == 0 && theirs != null) com.chencohen.ptt.ui.talk.RoomVideoView(theirs, Modifier.fillMaxSize())
            else com.chencohen.ptt.ui.talk.PushVideoPreview(Modifier.fillMaxSize(), cover = true)
        }
    }
}

/** The room call's bar: its time and End, then the microphone and the camera. */
@Composable
internal fun RoomCallBottom(call: com.chencohen.ptt.messaging.RoomCall, end: () -> Unit, media: (microphone: Boolean, camera: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().background(LibraryColors.Accent, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.call_in_chat, elapsedSince(call.since)), color = Color.White, style = LibraryType.RowTitle, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.call_end), color = Color.White, style = LibraryType.RowTitle,
                modifier = Modifier.background(LibraryColors.Error, CircleShape).clickable(role = Role.Button, onClick = end).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundToggle(if (call.microphone) com.chencohen.ptt.ui.style.LibraryIcons.Mic else com.chencohen.ptt.ui.style.LibraryIcons.MicOff, stringResource(if (call.microphone) R.string.call_mute else R.string.call_unmute), if (call.microphone) com.chencohen.ptt.ui.style.IconLive else null) { media(!call.microphone, call.camera) }
            RoundToggle(if (call.camera) com.chencohen.ptt.ui.style.LibraryIcons.Video else com.chencohen.ptt.ui.style.LibraryIcons.VideoOff, stringResource(if (call.camera) R.string.call_camera_off else R.string.call_camera_on), if (call.camera) com.chencohen.ptt.ui.style.IconLive else null) { media(call.microphone, !call.camera) }
        }
    }
}

@Composable
private fun RoundToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, key: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).background(LibraryColors.Raised, CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { com.chencohen.ptt.ui.style.FineIcon(icon, label, Modifier.size(22.dp), tint = LibraryColors.OnBackground, key = key) }
}

/** Text in the layout's own direction (not its first letter's): Hebrew lines stay right to left with a Latin name. */
@Composable
private fun layoutTextDirection(): androidx.compose.ui.text.style.TextDirection =
    if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) androidx.compose.ui.text.style.TextDirection.Rtl
    else androidx.compose.ui.text.style.TextDirection.Ltr


/** "You", or "You · Urgent" while my push is urgent (2026-11-25, hen). */
@Composable
internal fun youLabel(urgent: Boolean): String =
    if (urgent) stringResource(R.string.talk_panel_you) + " · " + stringResource(R.string.talk_urgent) else stringResource(R.string.talk_panel_you)
