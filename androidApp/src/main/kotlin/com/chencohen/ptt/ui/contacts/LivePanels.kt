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
        Text(text, color = Color.White, style = LibraryType.Small, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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

/**
 * Live in the chat, floating over it under the header (2026-11-24, hen: "the video and pills should be floating, not
 * inside black box"; "same ux in chat screen"): for each side, theirs at the start and mine at the end, a voice pill or
 * a video box 23.6% of the screen high in the picture's own shape (no crop, no bars), with only their own rounded shape
 * and a soft shadow: nothing behind them, the chat scrolls on beneath. "The pill becomes the video": a side turning to
 * video grows its box out of the pill's size and place (250 ms), and back. The whole slides down from under the
 * header when the first side starts and back up when the last ends. [waitingFor]: mine queued behind someone else's
 * floor (a group).
 */
@Composable
internal fun LivePanel(
    theirs: Boolean, who: String?, theirSince: Long,
    mine: Boolean, mySince: Long, waitingFor: String?, @Suppress("UNUSED_PARAMETER") handsFree: Boolean,
    /** The talker's "user.device": their video through the talk room, when it comes (LiveTalkVideo). */
    theirIdentity: String? = null,
    /** How far behind the clip path's picture plays (the test builds' readout). */
    behindMs: Long? = null,
    /** Voice (a side not on video): its pill, in that side's place. */
    voices: List<VoiceSide> = emptyList(),
) {
    val arriving by com.chencohen.ptt.talk.LiveTalkVideo.arriving.collectAsState()
    // Through the room while its frames come; else the clips' pictures (never both).
    val room = theirIdentity?.let { arriving[it] }
    val shown by com.chencohen.ptt.talk.AndroidTalkVideoOut.shown.collectAsState()
    var lastShown by remember { androidx.compose.runtime.mutableStateOf(shown) }
    if (shown != null) lastShown = shown
    val s = shown ?: lastShown
    val theirShape = if (room != null) room.aspect else if (s != null && s.width > 0 && s.height > 0) {
        val a = s.width.toFloat() / s.height
        if (s.rotation % 180 != 0) 1f / a else a
    } else 3f / 4f
    val myShape = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
    val theirVoice = voices.firstOrNull { !it.mine }
    val myVoice = voices.firstOrNull { it.mine }
    val any = theirs || mine || theirVoice != null || myVoice != null
    // Kept while it slides away, so it doesn't empty first.
    var last by remember { androidx.compose.runtime.mutableStateOf(Triple(theirs to mine, theirVoice, myVoice)) }
    if (any) last = Triple(theirs to mine, theirVoice, myVoice)
    val (videos, tv, mv) = last
    AnimatedVisibility(
        any,
        enter = fadeIn(tween(PANEL_MS)) + slideInVertically(tween(PANEL_MS)) { -it },
        exit = fadeOut(tween(PANEL_MS)) + slideOutVertically(tween(PANEL_MS)) { -it },
    ) {
        val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
            val gap = 6.dp
            // The boxes' height: 23.6% of the screen, both shrunk together only if they'd be wider than the space.
            val shapes = listOfNotNull(if (videos.first) theirShape else null, if (videos.second) myShape else null)
            var h = screen * LIVE_VIDEO_SHARE
            if (shapes.isNotEmpty()) {
                val wide = h * shapes.sum() + gap * (shapes.size - 1)
                if (wide > maxWidth) h *= (maxWidth - gap * (shapes.size - 1)) / (h * shapes.sum())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                if (videos.first || tv != null) {
                    LiveSlot(videos.first, h * theirShape, h, pill = { tv?.let { VoicePill(it) } }) { box ->
                        Box(box) {
                            if (room != null) com.chencohen.ptt.ui.talk.RoomVideoView(room, Modifier.fillMaxSize())
                            else com.chencohen.ptt.ui.talk.TalkVideoView(Modifier.fillMaxSize(), cover = true, inset = false)
                            // One line in a narrow box: the red dot says live.
                            LiveTag(listOfNotNull(who, elapsedSince(theirSince)).joinToString(" · "), Modifier.align(Alignment.BottomStart).padding(8.dp))
                            com.chencohen.ptt.ui.talk.DelayReadout(room, theirSince, behindMs, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                    }
                }
                if (videos.second || mv != null) {
                    LiveSlot(videos.second, h * myShape, h, pill = { mv?.let { VoicePill(it) } }) { box ->
                        Box(box) {
                            com.chencohen.ptt.ui.talk.PushVideoPreview(Modifier.fillMaxSize(), cover = true)
                            val words = if (waitingFor != null) stringResource(R.string.talk_waiting_for, waitingFor)
                            else listOf(stringResource(R.string.talk_panel_you), elapsedSince(mySince)).joinToString(" · ")
                            LiveTag(words, Modifier.align(Alignment.BottomStart).padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One side's place: its voice [pill], or its video box ([w] × [h]) grown out of the pill's size and place (250 ms),
 * and back to the pill when the video stops while the voice goes on. Only the shape and a soft shadow, nothing behind.
 */
@Composable
private fun LiveSlot(video: Boolean, w: Dp, h: Dp, pill: @Composable () -> Unit, box: @Composable (Modifier) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var pillSize by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val p by androidx.compose.animation.core.animateFloatAsState(if (video) 1f else 0f, tween(PANEL_MS), label = "pill to video")
    if (p == 0f) {
        Box(Modifier.onSizeChanged { pillSize = it }) { pill() }
        return
    }
    val pw = with(density) { pillSize.width.toDp() }
    val ph = with(density) { pillSize.height.toDp() }
    val shape = RoundedCornerShape(androidx.compose.ui.unit.lerp(32.dp, 18.dp, p))
    Box(Modifier.size(androidx.compose.ui.unit.lerp(pw, w, p), androidx.compose.ui.unit.lerp(ph, h, p)).shadow(6.dp, shape).clip(shape).background(Color.Black)) {
        box(Modifier.fillMaxSize())
        // The pill's face fades into the first picture.
        if (p < 1f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - p }) { pill() }
    }
}

/** Someone pushing voice in the chat, for the [VoiceBar]: their face ("" is mine), name, and when they started. */
internal data class VoiceSide(val user: String, val name: String, val mine: Boolean, val since: Long)

@Composable
private fun VoicePill(side: VoiceSide) {
    val ring = if (side.mine) com.chencohen.ptt.ui.style.Pink else LibraryColors.Accent
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier.shadow(6.dp, shape).clip(shape).background(LibraryColors.Raised).border(1.dp, ring, shape).padding(start = 2.dp, top = 2.dp, bottom = 2.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TalkingFace(side, ring)
        val time = elapsedSince(side.since)
        if (side.mine) {
            Text("${side.name} · $time", color = LibraryColors.OnBackground, style = LibraryType.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1)
        } else {
            Column {
                Text(com.chencohen.ptt.genderedString(R.string.talk_someone, side.user, side.name), color = LibraryColors.OnBackground, style = LibraryType.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1)
                Text(time, color = LibraryColors.Muted, style = LibraryType.Tiny)
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
            RoundToggle(if (call.microphone) com.chencohen.ptt.ui.style.LibraryIcons.Mic else com.chencohen.ptt.ui.style.LibraryIcons.MicOff, stringResource(if (call.microphone) R.string.call_mute else R.string.call_unmute)) { media(!call.microphone, call.camera) }
            RoundToggle(if (call.camera) com.chencohen.ptt.ui.style.LibraryIcons.Video else com.chencohen.ptt.ui.style.LibraryIcons.VideoOff, stringResource(if (call.camera) R.string.call_camera_off else R.string.call_camera_on)) { media(call.microphone, !call.camera) }
        }
    }
}

@Composable
private fun RoundToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).background(LibraryColors.Raised, CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { androidx.compose.material3.Icon(icon, label, tint = LibraryColors.OnBackground, modifier = Modifier.size(22.dp)) }
}
