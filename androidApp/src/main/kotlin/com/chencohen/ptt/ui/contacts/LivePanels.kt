package com.chencohen.ptt.ui.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.talk.clipLength
import kotlinx.coroutines.delay

// Push-to-video out of the chat's flow (2026-11-24, hen): theirs drops down under the header, mine rises above the
// buttons; the chat stays between them, still usable. docs/screens/conversation.md, "Live panels".

private const val PANEL_MS = 250

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
        Text(text, color = Color.White, style = LibraryType.Small)
    }
}

/** The live videos' height: this share of the screen's (2026-11-24, hen: the same size as the floating window). */
internal const val LIVE_VIDEO_SHARE = 0.236f

/**
 * Boxes of the given shapes (width for 1 high), [LIVE_VIDEO_SHARE] of the screen high, side by side and centred, 6 dp
 * apart; all shrunk together only if they'd be wider than the space. [content] gets each box's index.
 */
@Composable
internal fun LiveBoxes(shapes: List<Float>, modifier: Modifier = Modifier, content: @Composable (Int, Modifier) -> Unit) {
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        val gap = 6.dp
        var h = screen * LIVE_VIDEO_SHARE
        val wide = h * shapes.sum() + gap * (shapes.size - 1).coerceAtLeast(0)
        if (wide > maxWidth) h *= (maxWidth - gap * (shapes.size - 1).coerceAtLeast(0)) / (h * shapes.sum())
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            shapes.forEachIndexed { i, shape ->
                val box = RoundedCornerShape(18.dp)
                content(i, Modifier.size(h * shape, h).shadow(6.dp, box).clip(box).background(Color.Black))
            }
        }
    }
}

/**
 * Push-to-video under the header (2026-11-24, hen: "when two sides push to video show videos at top side by side; when
 * I push show the video on the top, never on bottom"; then "the video should be the same size" as the floating
 * window): theirs (or, in a group, whoever holds the floor: [who] named) and mine, each a box 23.6% of the screen high
 * and as wide as its picture's own shape (no crop, no bars), rounded, centred, theirs at the start side and mine at the
 * end. Slides down in 250 ms when the first starts, back up when the last ends; it always pushes the chat down.
 * [waitingFor]: mine queued behind someone else's floor (a group).
 */
@Composable
internal fun LivePanel(
    theirs: Boolean, who: String?, theirSince: Long,
    mine: Boolean, mySince: Long, waitingFor: String?, handsFree: Boolean,
) {
    val shown by com.chencohen.ptt.talk.AndroidTalkVideoOut.shown.collectAsState()
    var lastShown by remember { androidx.compose.runtime.mutableStateOf(shown) }
    if (shown != null) lastShown = shown
    val s = shown ?: lastShown
    val theirShape = if (s != null && s.width > 0 && s.height > 0) {
        val a = s.width.toFloat() / s.height
        if (s.rotation % 180 != 0) 1f / a else a
    } else 3f / 4f
    val myShape = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
    // Kept while the panel slides away, so it doesn't empty first.
    var lastSides by remember { androidx.compose.runtime.mutableStateOf(theirs to mine) }
    if (theirs || mine) lastSides = theirs to mine
    val (t, m) = lastSides
    AnimatedVisibility(
        theirs || mine,
        enter = expandVertically(tween(PANEL_MS), expandFrom = Alignment.Top) + slideInVertically(tween(PANEL_MS)) { -it },
        exit = shrinkVertically(tween(PANEL_MS), shrinkTowards = Alignment.Top) + slideOutVertically(tween(PANEL_MS)) { -it },
    ) {
        val sides = listOfNotNull(if (t) "theirs" else null, if (m) "mine" else null)
        LiveBoxes(sides.map { if (it == "theirs") theirShape else myShape }) { i, box ->
            if (sides[i] == "theirs") {
                Box(box) {
                    com.chencohen.ptt.ui.talk.TalkVideoView(Modifier.fillMaxSize(), cover = true, inset = false)
                    val words = listOfNotNull(who, stringResource(R.string.talk_panel_live), elapsedSince(theirSince)).joinToString(" · ")
                    LiveTag(words, Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
            } else {
                Box(box) {
                    com.chencohen.ptt.ui.talk.PushVideoPreview(Modifier.fillMaxSize(), cover = true)
                    val me = stringResource(R.string.talk_panel_you)
                    val words = if (waitingFor != null) stringResource(R.string.talk_waiting_for, waitingFor)
                    else listOfNotNull(me, stringResource(if (handsFree) R.string.talk_panel_hands_free else R.string.talk_panel_live), elapsedSince(mySince)).joinToString(" · ")
                    LiveTag(words, Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
            }
        }
    }
}

/** Someone pushing voice in the chat, for the [VoiceBar]: their face ("" is mine), name, and when they started. */
internal data class VoiceSide(val user: String, val name: String, val mine: Boolean, val since: Long)

/**
 * Voice push-to-talk under the header (2026-11-24, hen: "when someone push to talk, voice, it's not really showing";
 * mock A, a slim live bar): their face with the teal ring, "Sharon is talking" (or "You're talking", or both), the
 * sound as a moving wave, and a teal dot with the time. Slides down in 250 ms and pushes the chat down; under the video
 * panel when the other side is on video.
 */
@Composable
internal fun VoiceBar(sides: List<VoiceSide>) {
    // Kept while it slides away, so the words don't vanish first.
    var last by remember { androidx.compose.runtime.mutableStateOf(sides) }
    if (sides.isNotEmpty()) last = sides
    AnimatedVisibility(
        sides.isNotEmpty(),
        enter = expandVertically(tween(PANEL_MS), expandFrom = Alignment.Top) + slideInVertically(tween(PANEL_MS)) { -it },
        exit = shrinkVertically(tween(PANEL_MS), shrinkTowards = Alignment.Top) + slideOutVertically(tween(PANEL_MS)) { -it },
    ) {
        val shown = last
        val shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
        Row(
            Modifier.fillMaxWidth().height(56.dp).shadow(4.dp, shape).clip(shape).background(LibraryColors.Raised).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { shown.take(2).forEach { TalkingFace(it) } }
            val words = when {
                shown.size > 1 -> stringResource(R.string.talk_bar_both, shown.first { !it.mine }.name)
                shown.first().mine -> stringResource(R.string.talk_bar_you)
                else -> com.chencohen.ptt.genderedString(R.string.talk_someone, shown.first().user, shown.first().name)
            }
            Text(words, color = LibraryColors.OnBackground, style = LibraryType.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1)
            VoiceWave(theirs = shown.any { !it.mine }, mine = shown.any { it.mine }, modifier = Modifier.weight(1f).height(28.dp))
            Box(Modifier.size(8.dp).background(LibraryColors.Accent, CircleShape))
            Text(elapsedSince(shown.minOf { it.since }), color = LibraryColors.Muted, style = LibraryType.Small)
        }
    }
}

/** A face in the voice bar, 36 dp, a teal ring breathing around it. */
@Composable
private fun TalkingFace(side: VoiceSide) {
    val face = com.chencohen.ptt.ui.face.faceOf(if (side.mine) "" else side.user)
    val breath by androidx.compose.animation.core.rememberInfiniteTransition(label = "talking").animateFloat(
        0.4f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "ring",
    )
    val teal = LibraryColors.Accent
    Box(
        Modifier.size(40.dp).background(LibraryColors.Raised, CircleShape).drawBehind {
            drawCircle(teal.copy(alpha = breath), radius = size.minDimension / 2f - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) {
        if (face != null) {
            androidx.compose.foundation.Image(face, side.name, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.size(32.dp))
        } else {
            Box(Modifier.size(32.dp).background(LibraryColors.Mine, CircleShape), contentAlignment = Alignment.Center) {
                Text(side.name.take(1).uppercase(), color = LibraryColors.OnMine, style = LibraryType.Small)
            }
        }
    }
}

/**
 * The sound as a wave moving from the end: a bar every 70 ms from the voice level (theirs as it plays, mine from the
 * microphone), smoothed as on the Talk button.
 */
@Composable
private fun VoiceWave(theirs: Boolean, mine: Boolean, modifier: Modifier) {
    val bars = remember { androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(BARS) { add(0f) } } }
    LaunchedEffect(theirs, mine) {
        var level = 0f
        var last = 0L
        var due = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 16f else (now - last) / 1_000_000f
                last = now
                val target = maxOf(if (theirs) com.chencohen.ptt.talk.TalkAudio.playLevel else 0f, if (mine) com.chencohen.ptt.talk.TalkAudio.micLevel else 0f)
                level = com.chencohen.ptt.talk.VoiceLevel.smooth(level, target, dt)
                if (now >= due) {
                    due = now + 70_000_000L
                    bars.removeAt(0)
                    bars.add(level)
                }
            }
        }
    }
    val teal = LibraryColors.Accent
    androidx.compose.foundation.Canvas(modifier) {
        val step = size.width / BARS
        val w = step * 0.55f
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        bars.forEachIndexed { i, v ->
            val h = (size.height * (0.12f + 0.88f * v)).coerceAtLeast(2.dp.toPx())
            val x = if (rtl) size.width - (i + 1) * step else i * step
            drawRoundRect(teal.copy(alpha = 0.45f + 0.55f * v), topLeft = androidx.compose.ui.geometry.Offset(x + (step - w) / 2, (size.height - h) / 2), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2))
        }
    }
}

private const val BARS = 28

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
    Text(
        listOfNotNull(words, length, at).joinToString(" · "),
        color = LibraryColors.Muted, style = LibraryType.Tiny, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
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
internal fun StopLive(size: Dp, stop: () -> Unit) {
    val label = stringResource(R.string.talk_stop)
    Box(
        Modifier.size(size).shadow(10.dp, CircleShape).background(com.chencohen.ptt.ui.style.Pink, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = stop),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(size * 0.26f).background(Color.White, RoundedCornerShape(4.dp)))
            Text(label, color = Color.White, style = LibraryType.Tiny, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
