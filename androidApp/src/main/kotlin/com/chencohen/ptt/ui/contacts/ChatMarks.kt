package com.chencohen.ptt.ui.contacts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.ReadMarks
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

// --- One line that is nothing, dashed or whole (2026-11-26, hen) ------------------------------------------------------
//
// "With smart transition, not a crossfade": a border or a ring is one number, how much of each dash's place is drawn.
// 0: nothing. LINE_DASHED: a dashed line. 1: the dashes have met, a whole line. Going between them the dashes grow from
// their middles (out of nothing, or until their ends meet) or shrink back, in one move of LINE_MS; nothing fades.
// My message's outline and the rings around faces both draw with it.

/** How much of each dash's place a dashed line draws. */
internal const val LINE_DASHED = 0.58f

/** One move of a line (nothing, dashed, whole), a bubble's fill and a face's glide between two gaps. */
internal const val LINE_MS = 250

internal fun <T> lineMove() = tween<T>(LINE_MS, easing = FastOutSlowInEasing)

/** A closed [path] of [length] px, in dashes about [dash] px apart (a whole number of them, so the pattern has no seam). */
internal fun DrawScope.drawMorphLine(path: Path, length: Float, color: Color, width: Float, drawn: Float, dash: Float) {
    if (drawn <= 0.005f || length <= 0f) return
    val period = length / (length / dash).roundToInt().coerceAtLeast(1)
    val gap = period * (1f - drawn)
    // Under half a pixel of gap: the dashes have met.
    val effect = if (gap < 0.5f) null else PathEffect.dashPathEffect(floatArrayOf(period - gap, gap), period - gap / 2f)
    drawPath(path, color, style = Stroke(width, pathEffect = effect, cap = StrokeCap.Butt))
}

/**
 * A ring around a face: [ring] 0 none, 1 dashed, 2 whole (the chat header: in its talk room, on this chat's screen).
 * It comes out of nothing as dashes that grow, and closes into a whole ring as they meet; back the same way.
 */
@Composable
internal fun Modifier.morphRing(ring: Int, color: Color, width: Dp = 2.dp, dash: Dp = 7.dp): Modifier {
    val drawn by animateFloatAsState(if (ring >= 2) 1f else if (ring == 1) LINE_DASHED else 0f, lineMove(), label = "ring")
    return drawWithCache {
        val w = width.toPx()
        val r = size.minDimension / 2f - w / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val path = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
        val length = (2.0 * Math.PI * r).toFloat()
        onDrawBehind { drawMorphLine(path, length, color, w, drawn, dash.toPx()) }
    }
}

// --- My bubble: dashed until the server has it, whole on the server, filled once everyone's phone has it --------------

/** How a bubble of mine looks now: [line] how much of its outline's dashes is drawn, [fill] 0 an outline, 1 whole green. */
internal class BubbleLook(reach: ReadMarks.Reach) {
    val line = Animatable(lineOf(reach))
    val fill = Animatable(fillOf(reach))

    companion object {
        fun lineOf(reach: ReadMarks.Reach) = if (reach == ReadMarks.Reach.SENDING || reach == ReadMarks.Reach.FAILED) LINE_DASHED else 1f
        fun fillOf(reach: ReadMarks.Reach) = if (reach == ReadMarks.Reach.RECEIVED) 1f else 0f
    }
}

/**
 * [reach] as the bubble shows it. As it is when it comes on screen; a change after that is one move each: the dashes
 * close into a whole line, then the green grows in from the outline (the two never at once, so each step is seen
 * even when the receipts come together).
 */
@Composable
internal fun rememberBubbleLook(reach: ReadMarks.Reach): BubbleLook {
    val look = remember { BubbleLook(reach) }
    LaunchedEffect(reach) {
        val line = BubbleLook.lineOf(reach)
        val fill = BubbleLook.fillOf(reach)
        if (fill < look.fill.value) look.fill.animateTo(fill, lineMove())
        if (line != look.line.value) look.line.animateTo(line, lineMove())
        if (fill != look.fill.value) look.fill.animateTo(fill, tween(LINE_MS, easing = FastOutSlowInEasing))
    }
    return look
}

/** The text on a bubble of mine: the page's while it is an outline, the bubble's own once the green is under it. */
@Composable
internal fun BubbleLook.ink(): Color {
    // The text turns with the green as it fades in.
    val t = fill.value.coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.lerp(LibraryColors.OnBackground, LibraryColors.OnMine, t * t * (3f - 2f * t))
}

/** My bubble's outline and fill, in a rounded box of [radius]. */
@Composable
internal fun Modifier.myBubble(look: BubbleLook, radius: Dp = 16.dp): Modifier {
    val lineColor = LibraryColors.MineLine
    val fillColor = LibraryColors.Mine
    return drawWithCache {
        val w = 1.5.dp.toPx()
        val r = radius.toPx()
        val line = Path().apply { addRoundRect(RoundRect(w / 2f, w / 2f, size.width - w / 2f, size.height - w / 2f, CornerRadius((r - w / 2f).coerceAtLeast(0f)))) }
        val length = PathMeasure().apply { setPath(line, true) }.length
        onDrawBehind {
            val f = look.fill.value
            if (f >= 1f) {
                drawRoundRect(fillColor, cornerRadius = CornerRadius(r))
                return@onDrawBehind
            }
            if (f > 0f) {
                // The green fades in over the whole bubble at once (2026-11-26, hen: "fade into green, not filled").
                drawRoundRect(fillColor.copy(alpha = fillColor.alpha * f), cornerRadius = CornerRadius(r))
            }
            drawMorphLine(line, length, androidx.compose.ui.graphics.lerp(lineColor, fillColor, f), w, look.line.value, 9.dp.toPx())
        }
    }
}

/**
 * Half of my bubble round shared pictures (2026-11-28, hen): above them ([top]) or below them, rounded on its far
 * side and open where the pictures meet it. Its line and fill move as [myBubble]'s; while its previews upload, the
 * dashes grow towards a whole line with [progress] (0 to 1).
 */
@Composable
internal fun Modifier.myHalfBubble(look: BubbleLook, top: Boolean, progress: Float? = null, radius: Dp = 16.dp): Modifier {
    val lineColor = LibraryColors.MineLine
    val fillColor = LibraryColors.Mine
    return drawWithCache {
        val w = 1.5.dp.toPx()
        val r = radius.toPx()
        val h = size.height
        val x0 = w / 2f
        val x1 = size.width - w / 2f
        val round = RoundRect(
            x0, if (top) w / 2f else -r, x1, if (top) h + r else h - w / 2f,
            topLeftCornerRadius = if (top) CornerRadius(r) else CornerRadius.Zero, topRightCornerRadius = if (top) CornerRadius(r) else CornerRadius.Zero,
            bottomRightCornerRadius = if (top) CornerRadius.Zero else CornerRadius(r), bottomLeftCornerRadius = if (top) CornerRadius.Zero else CornerRadius(r),
        )
        // The outline without the side the pictures touch: down one side, round the far edge, back up the other.
        val line = Path().apply {
            if (top) {
                moveTo(x0, h)
                lineTo(x0, r)
                quadraticBezierTo(x0, w / 2f, r, w / 2f)
                lineTo(x1 - r, w / 2f)
                quadraticBezierTo(x1, w / 2f, x1, r)
                lineTo(x1, h)
            } else {
                moveTo(x0, 0f)
                lineTo(x0, h - r)
                quadraticBezierTo(x0, h - w / 2f, r, h - w / 2f)
                lineTo(x1 - r, h - w / 2f)
                quadraticBezierTo(x1, h - w / 2f, x1, h - r)
                lineTo(x1, 0f)
            }
        }
        val length = PathMeasure().apply { setPath(line, false) }.length
        val area = Path().apply { addRoundRect(round) }
        onDrawBehind {
            val f = look.fill.value
            clipRect(0f, 0f, size.width, size.height) {
                if (f > 0f) drawPath(area, fillColor.copy(alpha = fillColor.alpha * f))
            }
            if (f >= 1f) return@onDrawBehind
            val drawn = progress?.let { LINE_DASHED + (1f - LINE_DASHED) * it } ?: look.line.value
            drawMorphLine(line, length, androidx.compose.ui.graphics.lerp(lineColor, fillColor, f), w, drawn, 9.dp.toPx())
        }
    }
}

// --- Faces in the gaps between messages --------------------------------------------------------------------------------

/** A face in a gap, and the room a gap with faces takes under its bubble (the face floats in the middle of the gap). */
internal val MARK_FACE = 22.dp
private val MARK_SPACE = 4.dp
private val MARK_TOP = 5.dp
internal val MARK_ROOM = 26.dp

/** How many faces stand side by side in a row of [width]. */
internal fun marksAcross(width: Dp): Int = ((width + MARK_SPACE) / (MARK_FACE + MARK_SPACE)).toInt().coerceAtLeast(2)

/**
 * How far, in pixels, place [from] is from place [to] in a gap: places are counted from my side inward, so toward the
 * left when my side is the right one and toward the right when it is the left one ([rtl]).
 */
internal fun slotShift(from: Float, to: Float, step: Float, rtl: Boolean): Float = (from - to) * step * if (rtl) 1f else -1f

/**
 * Where each face was last drawn, so one that turns up in another gap glides there from where it was instead of going
 * and coming (2026-11-26, hen: "one move"), and which gaps are on screen now.
 */
internal class MarkHomes {
    class Home(val host: String) {
        var slot = 0f
        var shift = Offset.Zero
        var leftAt = 0L
    }

    /** The top of each gap on screen (the bottom of its bubble), by its message. */
    val anchors = HashMap<String, LayoutCoordinates>()
    val last = HashMap<Pair<String, Boolean>, Home>()

    /** The gaps that are on screen (composed), by message. */
    val present = mutableStateMapOf<String, Boolean>()

    /**
     * [places] as they show now: a face whose gap isn't on screen yet (a message just sent, still coming in; or the end
     * of the chat scrolled away) stays in the gap it is in until that one is, and then glides.
     */
    fun shown(places: List<ReadMarks.Place>): Map<String, List<ReadMarks.Mark>> = ReadMarks.gaps(
        places.map { p ->
            val was = last[p.who to p.read]?.takeIf { it.leftAt == 0L }?.host
            if (p.host !in present && was != null && was != p.host && was in present) p.copy(host = was) else p
        },
    )
}

/**
 * The gap under a message of mine ([host]): no room at all without faces, [MARK_ROOM] with; the room opens and closes
 * in the same move as the faces glide, so one gap gives its room as the other takes it. The faces stand side by side
 * from my side inward, never over one another; with more than [most], the rest as "+N". A tap lists who and when.
 */
@Composable
internal fun MarkGap(host: String, marks: List<ReadMarks.Mark>, homes: MarkHomes, most: Int, name: (String) -> String, modifier: Modifier = Modifier) {
    val shown = marks.count { !it.under }
    val room = remember { Animatable(if (shown > 0) 1f else 0f) }
    LaunchedEffect(shown > 0) { room.animateTo(if (shown > 0) 1f else 0f, lineMove()) }
    DisposableEffect(host) {
        homes.present[host] = true
        onDispose {
            homes.present.remove(host)
            homes.anchors.remove(host)
        }
    }
    // Faces already there as the gap comes on screen are just there; one that comes after that comes in.
    val settled = remember { booleanArrayOf(false) }
    SideEffect { settled[0] = true }
    var open by remember { mutableStateOf(false) }
    val slots = remember(marks, most) { ReadMarks.slots(marks, most) }
    val more = ReadMarks.more(marks, most)
    Box(
        modifier.fillMaxWidth()
            .onPlaced { homes.anchors[host] = it }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                layout(placeable.width, (MARK_ROOM.toPx() * room.value).roundToInt()) { placeable.place(0, 0) }
            },
    ) {
        Box(Modifier.fillMaxWidth().padding(top = MARK_TOP), contentAlignment = Alignment.TopEnd) {
            // Dashed circles first: one under its ringed face is drawn below it.
            marks.sortedBy { it.read }.forEach { mark ->
                val slot = slots[mark] ?: 0
                // Past the last place: one of the "+N".
                val counted = more > 0 && !mark.under && marks.filter { !it.under }.indexOf(mark) >= most - 1
                key(mark.who, mark.read) { MarkFace(mark, host, slot, homes, drawn = !counted, comesIn = settled[0]) }
            }
            if (more > 0) {
                Box(Modifier.offset(x = -(MARK_FACE + MARK_SPACE) * (most - 1)).size(MARK_FACE), contentAlignment = Alignment.Center) {
                    Text("+$more", color = LibraryColors.Muted, style = LibraryType.Tiny, maxLines = 1, softWrap = false)
                }
            }
            if (shown > 0) {
                val label = marksLabel(marks, name)
                val across = minOf(shown, most)
                // The faces together are the button: a tap lists who read and whose phone got it, and when.
                Box(
                    Modifier.size(width = (MARK_FACE + MARK_SPACE) * across, height = MARK_FACE)
                        .clip(RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClickLabel = label) { open = true }
                        .semantics { contentDescription = label },
                )
                if (open) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = with(androidx.compose.ui.platform.LocalDensity.current) { IntOffset(0, (MARK_FACE + 4.dp).roundToPx()) },
                        onDismissRequest = { open = false },
                        properties = PopupProperties(focusable = true),
                    ) { MarksCard(marks.filter { !it.under }, name) }
                }
            }
        }
    }
}

@Composable
private fun marksLabel(marks: List<ReadMarks.Mark>, name: (String) -> String): String {
    val read = marks.filter { it.read }.joinToString(", ") { name(it.who) }
    val got = marks.filter { !it.read && !it.under }.joinToString(", ") { name(it.who) }
    return listOfNotNull(
        if (read.isNotEmpty()) stringResource(R.string.chat_read_by, read) else null,
        if (got.isNotEmpty()) stringResource(R.string.chat_received_by, got) else null,
    ).joinToString(" · ")
}

/** Who read up to here and whose phone got up to here, and when. */
@Composable
private fun MarksCard(marks: List<ReadMarks.Mark>, name: (String) -> String) {
    val shownAt = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shownAt.animateTo(1f, tween(160)) }
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(
        Modifier.graphicsLayer { alpha = shownAt.value }
            .padding(6.dp)
            .shadow(8.dp, RoundedCornerShape(14.dp))
            .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
            .widthIn(min = 150.dp, max = 260.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        marks.forEach { mark ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkPicture(mark.who, mark.read)
                Text(name(mark.who), color = LibraryColors.OnBackground, style = LibraryType.Small, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                val at = mark.at?.let { fmt.format(Date(it)) }
                Text(
                    when {
                        mark.read && at != null -> stringResource(R.string.chat_read_at, at)
                        mark.read -> stringResource(R.string.chat_status_read)
                        at != null -> stringResource(R.string.chat_received_at, at)
                        else -> stringResource(R.string.chat_received)
                    },
                    color = LibraryColors.Muted, style = LibraryType.Tiny, maxLines = 1,
                )
            }
        }
    }
}

/**
 * One face in a gap, at place [slot] from my side. New in this gap while it was last in another one on screen: it
 * starts where it was and glides here, over the bubbles between ([LINE_MS], with the gaps' own move). New altogether
 * ([comesIn]): it grows in. A change of place within the gap glides too.
 */
@Composable
private fun MarkFace(mark: ReadMarks.Mark, host: String, slot: Int, homes: MarkHomes, drawn: Boolean, comesIn: Boolean) {
    val id = mark.who to mark.read
    val home = remember { MarkHomes.Home(host) }
    // Where it was until now, if that was another gap (left within the last moment, or still there).
    val from = remember {
        homes.last[id]?.takeIf { it.host != host && (it.leftAt == 0L || android.os.SystemClock.uptimeMillis() - it.leftAt < 1_000) }
    }
    val glide = remember { Animatable(if (from != null) 0f else 1f) }
    val grown = remember { Animatable(if (from == null && comesIn) 0f else 1f) }
    val start = remember { arrayOfNulls<Offset>(1) }
    val place by animateFloatAsState(slot.toFloat(), lineMove(), label = "place")
    LaunchedEffect(Unit) {
        if (glide.value < 1f) glide.animateTo(1f, lineMove())
        if (grown.value < 1f) grown.animateTo(1f, lineMove())
    }
    SideEffect {
        home.slot = slot.toFloat()
        homes.last[id] = home
    }
    DisposableEffect(Unit) { onDispose { home.leftAt = android.os.SystemClock.uptimeMillis() } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val step = MARK_FACE + MARK_SPACE
    Box(
        Modifier
            // Toward the inside from my side: offset() counts from the layout's start, in either direction.
            .offset { IntOffset(-(step.toPx() * place).roundToInt(), 0) }
            .graphicsLayer {
                val p = glide.value
                if (from != null && start[0] == null) {
                    val here = homes.anchors[host]
                    val there = homes.anchors[from.host]
                    start[0] = if (here != null && there != null && here.isAttached && there.isAttached) {
                        here.localPositionOf(there, Offset.Zero) + Offset(slotShift(from.slot, slot.toFloat(), step.toPx(), rtl), 0f) + from.shift
                    } else Offset.Zero
                }
                val d = (start[0] ?: Offset.Zero) * (1f - p)
                home.shift = d
                translationX = d.x
                translationY = d.y
                val g = grown.value
                scaleX = 0.6f + 0.4f * g
                scaleY = 0.6f + 0.4f * g
                alpha = if (drawn) g else 0f
            },
    ) { MemberHue(mark.who) { MarkPicture(mark.who, mark.read) } }
}

/** The face itself: with a whole green ring (read up to here), or faint in a dashed circle (their phone has up to here). */
@Composable
private fun MarkPicture(who: String, read: Boolean) {
    val face = com.chencohen.ptt.ui.face.faceOf(who)
    // In their colour when the chat gives them one (2026-11-27, hen: "the small avatar between messages should respect
    // the colors"); else the green ring and the green letter as before.
    val theirs = LibraryColors.TheirLine != LibraryColors.Accent
    val line = if (theirs) LibraryColors.TheirLine.copy(alpha = if (read) 1f else 0.8f) else if (read) LibraryColors.MineLine else LibraryColors.Muted
    val page = LibraryColors.Background
    Box(
        Modifier.size(MARK_FACE).drawBehind {
            val w = 1.5.dp.toPx()
            val r = size.minDimension / 2f - w / 2f
            // A ringed face covers the dashed circle under it.
            if (read) drawCircle(page, radius = size.minDimension / 2f)
            val path = Path().apply { addOval(Rect(center.x - r, center.y - r, center.x + r, center.y + r)) }
            drawMorphLine(path, (2.0 * Math.PI * r).toFloat(), line, w, if (read) 1f else LINE_DASHED, 6.dp.toPx())
        },
        contentAlignment = Alignment.Center,
    ) {
        if (face != null) {
            Image(
                face, null, contentScale = ContentScale.Fit,
                colorFilter = if (read) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) }),
                modifier = Modifier.size(MARK_FACE - 5.dp).clip(CircleShape).graphicsLayer { alpha = if (read) 1f else 0.6f },
            )
        } else {
            Box(
                Modifier.size(MARK_FACE - 5.dp).background(if (read) (if (theirs) LibraryColors.Theirs else LibraryColors.Mine) else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    who.take(1).uppercase(), color = if (read) (if (theirs) LibraryColors.OnTheirs else LibraryColors.OnMine) else if (theirs) LibraryColors.TheirLine else LibraryColors.Muted,
                    style = LibraryType.Tiny.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)), maxLines = 1,
                )
            }
        }
    }
}
