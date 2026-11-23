package com.chencohen.ptt.ui.contacts

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.shadow
import com.chencohen.ptt.messaging.ReadMarks.Tick
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryShapes
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.talk.clipLength
import com.chencohen.ptt.ui.talk.holdToTalk
import com.chencohen.ptt.ui.talk.rememberMicrophone
import com.chencohen.ptt.ui.talk.rememberVoiceLevel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/*
 * Writing inside the chat (2026-11-23, hen, from the approved mockup "chat-typing-in-chat"): no line at the bottom; the
 * last thing in the chat is an empty dashed bubble on this phone's side, "Write a message…". A tap makes it the draft:
 * an outlined bubble (coral outline, faint fill, "Writing…") edited in place, with a round Send inside it. Send fills
 * it in place into the sent message, and a new empty bubble comes under it, the keyboard staying up. Under the chat a
 * row of round buttons, icons only: Video call, Push-to-talk (big, hold to talk), Voice call, Voice message; with the
 * keyboard up, one compact row of small ones above it.
 */

/** The empty bubble, or the draft being written, at the end of the chat. */
@Composable
internal fun ComposeBubble(
    text: String,
    editing: Boolean,
    focus: FocusRequester,
    startEditing: () -> Unit,
    change: (String) -> Unit,
    send: () -> Unit,
    modifier: Modifier = Modifier,
    /** On the bubble being written (its place on the screen, for Send's flight). */
    bubbleModifier: Modifier = Modifier,
) {
    val coral = com.chencohen.ptt.ui.style.Pink
    Box(modifier.fillMaxWidth(), contentAlignment = MySide) {
        if (!editing && text.isEmpty()) {
            val hint = stringResource(R.string.chat_write_hint)
            val dash = LibraryColors.Muted.copy(alpha = 0.6f)
            Text(
                hint,
                color = LibraryColors.Muted,
                style = LibraryType.Body,
                // Nearly the whole row (2026-11-24, hen: wider), anchored on my side.
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRoundRect(
                            dash,
                            cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                        )
                    }
                    .shadow(6.dp, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(LibraryColors.Background.copy(alpha = 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClickLabel = hint, onClick = startEditing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            // As wide as the row (2026-11-24, hen: wider, the same width as the empty one, so nothing shrinks when the
            // first letter goes in); the text wraps inside it, and past 6 lines it scrolls inside with the caret in view.
            val style = LibraryType.Body.copy(color = LibraryColors.OnBackground)
            val label = stringResource(R.string.chat_send)
            Column(
                bubbleModifier.fillMaxWidth()
                    .shadow(8.dp, LibraryShapes.Card)
                    .background(LibraryColors.Background, LibraryShapes.Card)
                    .border(1.5.dp, coral, LibraryShapes.Card)
                    .background(coral.copy(alpha = 0.08f), LibraryShapes.Card)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(stringResource(R.string.chat_my_draft), color = coral, style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicTextField(
                        value = text,
                        onValueChange = { change(it.take(4_000)) },
                        textStyle = style,
                        cursorBrush = SolidColor(coral),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                        modifier = Modifier.weight(1f).padding(bottom = 6.dp).focusRequester(focus),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(36.dp)
                            .background(if (text.isNotBlank()) coral else coral.copy(alpha = 0.3f), CircleShape)
                            .clickable(enabled = text.isNotBlank(), role = Role.Button, onClickLabel = label, onClick = send),
                        contentAlignment = Alignment.Center,
                    ) { Icon(LibraryIcons.ArrowUp, label, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

/** A message just sent from the draft bubble: it fills in place, outline to this phone's bubble colour (250 ms). */
@Composable
internal fun SentMorphBubble(message: TextMessage) {
    val settled = remember(message.id) { Animatable(0f) }
    LaunchedEffect(message.id) { settled.animateTo(1f, tween(MORPH_MS)) }
    val s = settled.value
    val coral = com.chencohen.ptt.ui.style.Pink
    val fill = androidx.compose.ui.graphics.lerp(coral.copy(alpha = 0.08f), LibraryColors.Mine, s)
    val ink = androidx.compose.ui.graphics.lerp(LibraryColors.OnBackground, LibraryColors.OnMine, s)
    Box(Modifier.fillMaxWidth(), contentAlignment = MySide) {
        Column(
            Modifier.widthIn(max = bubbleMaxWidth())
                .animateContentSize(tween(MORPH_MS))
                .border(1.5.dp, coral.copy(alpha = 1f - s), LibraryShapes.Card)
                .background(fill, LibraryShapes.Card)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (s < 1f) Text(stringResource(R.string.chat_my_draft), color = coral.copy(alpha = 1f - s), style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp))
            Text(message.body, color = ink, style = LibraryType.Body)
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at)),
                color = LibraryColors.OnMineMuted.copy(alpha = 0.6f * s), style = LibraryType.Tiny,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
            )
        }
    }
}

/** A voice message being recorded: red dot, time, the voice's level, and ✕ to throw it away. */
@Composable
internal fun RecordingStrip(discard: () -> Unit) {
    var since by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = System.currentTimeMillis() - since
            delay(200)
        }
    }
    val level by rememberVoiceLevel(true)
    val bars = remember { FloatArray(24) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            System.arraycopy(bars, 1, bars, 0, bars.size - 1)
            bars[bars.size - 1] = level
            tick++
            delay(80)
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier.background(LibraryColors.Raised, LibraryShapes.Card).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(10.dp).background(LibraryColors.Error, CircleShape))
            Text(clipLength(elapsed), color = LibraryColors.OnBackground, style = LibraryType.Caption)
            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                @Suppress("UNUSED_EXPRESSION") tick
                bars.forEach { b -> Box(Modifier.width(3.dp).height((4 + 24 * b.coerceIn(0f, 1f)).dp).background(com.chencohen.ptt.ui.style.Pink, CircleShape)) }
            }
            val label = stringResource(R.string.voice_discard)
            Box(
                Modifier.size(36.dp).clickable(role = Role.Button, onClickLabel = label, onClick = discard),
                contentAlignment = Alignment.Center,
            ) { Icon(LibraryIcons.Close, label, tint = LibraryColors.Muted) }
        }
    }
}

/**
 * The buttons under the chat, icons only (hen: "no text labels"): Video call, Push-to-talk (hold), Voice call, Voice
 * message (a tap starts recording; while it records it is Send). [compact] with the keyboard up: small, in one row.
 */
@Composable
internal fun ChatButtons(
    compact: Boolean,
    talking: Boolean,
    recording: Boolean,
    actions: ConversationActions,
    /** This chat's push is video (the camera goes too). */
    video: Boolean = false,
) {
    val microphone = rememberMicrophone(actions.micDenied)
    val small by animateDpAsState(if (compact) 40.dp else 52.dp, label = "small buttons")
    val big by animateDpAsState(if (compact) 48.dp else 84.dp, label = "talk button")
    // Laid out on the screen itself, the same in Hebrew and English (2026-11-24, hen: the thumb finds it in one
    // place): from the left, voice message, voice call, video call, then the big button near the right edge and the
    // voice | video switch, upright, at the edge.
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = if (compact) 6.dp else 12.dp, bottom = if (compact) 6.dp else 12.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    RoundButton(LibraryIcons.ArrowUp, stringResource(R.string.voice_send), small, filled = true) { actions.finishVoice(true) }
                } else {
                    RoundButton(LibraryIcons.Mic, stringResource(R.string.voice_message), small) { microphone(actions.startVoiceMessage) }
                }
                RoundButton(LibraryIcons.Call, stringResource(R.string.call_voice), small) { actions.call(false) }
                RoundButton(LibraryIcons.Video, stringResource(R.string.call_video), small) { actions.call(true) }
            }
            val talkLabel = stringResource(if (video) R.string.talk_mode_video else R.string.talk_button)
            Box(Modifier.padding(start = 8.dp).size(big)) {
                Box(
                    Modifier.fillMaxSize()
                        .shadow(10.dp, CircleShape)
                        .background(if (talking) com.chencohen.ptt.ui.style.Pink else com.chencohen.ptt.ui.style.Pink.copy(alpha = 0.9f), CircleShape)
                        .semantics { contentDescription = talkLabel }
                        .holdToTalk(onPress = { microphone(actions.startVoice) }, onRelease = { actions.finishVoice(true) }),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.Crossfade(video, animationSpec = androidx.compose.animation.core.tween(200), label = "talk icon") { v ->
                        Icon(if (v) LibraryIcons.Video else LibraryIcons.Talk, null, tint = Color.White, modifier = Modifier.size(big * 0.42f))
                    }
                }
                // My camera, small, on the button's top-left corner (clear of the switch): framed before pressing.
                androidx.compose.animation.AnimatedVisibility(
                    video && !compact,
                    enter = androidx.compose.animation.fadeIn(tween<Float>(200)) + androidx.compose.animation.scaleIn(tween<Float>(200), initialScale = 0.6f),
                    exit = androidx.compose.animation.fadeOut(tween<Float>(150)) + androidx.compose.animation.scaleOut(tween<Float>(150), targetScale = 0.6f),
                    modifier = Modifier.align(Alignment.TopStart).offset(x = (-10).dp, y = (-6).dp),
                ) {
                    Box(Modifier.size(34.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(Color.Black).border(2.dp, Color.White, CircleShape)) {
                        com.chencohen.ptt.ui.talk.PushVideoPreview(Modifier.fillMaxSize())
                    }
                }
            }
            actions.setPushVideo?.let { set -> TalkModeSwitch(video, set, actions.micDenied, Modifier.padding(start = 10.dp)) }
        }
    }
}

/**
 * Voice | video for the big button: two segments, upright, a microphone over a camera, the chosen one filled; the
 * fill slides between them. Video asks for the camera the first time (from the app; the
 * quick-reply window opens the chat for that, [denied]).
 */
@Composable
internal fun TalkModeSwitch(video: Boolean, set: (Boolean) -> Unit, denied: () -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val canAsk = androidx.activity.compose.LocalActivityResultRegistryOwner.current != null
    val ask = if (canAsk) androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> if (granted) set(true) } else null
    fun choose(on: Boolean) {
        when {
            on == video -> {}
            !on -> set(false)
            context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED -> set(true)
            ask != null -> ask.launch(android.Manifest.permission.CAMERA)
            else -> denied()
        }
    }
    // Upright, as the reply bubble's: the microphone on top, the camera below; the fill slides between them.
    val segment = 38.dp
    val at by animateDpAsState(if (video) segment else 0.dp, tween(200), label = "mode")
    Box(modifier.shadow(4.dp, CircleShape).background(LibraryColors.Raised, CircleShape).padding(3.dp)) {
        Box(Modifier.offset(y = at).size(34.dp, segment).background(LibraryColors.Inverse, CircleShape))
        androidx.compose.foundation.layout.Column {
            listOf(false to LibraryIcons.Mic, true to LibraryIcons.Video).forEach { (on, icon) ->
                val chosen = on == video
                val ink by androidx.compose.animation.animateColorAsState(if (chosen) LibraryColors.OnInverse else LibraryColors.OnBackground, tween(200), label = "ink")
                val words = stringResource(if (on) R.string.talk_mode_video else R.string.talk_mode_voice)
                Box(
                    Modifier.size(34.dp, segment).clip(CircleShape)
                        .semantics { selected = chosen }
                        .clickable(role = Role.Tab, onClickLabel = words) { choose(on) },
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, words, tint = ink, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Dp, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size)
            .shadow(6.dp, CircleShape)
            .background(if (filled) com.chencohen.ptt.ui.style.Pink else LibraryColors.Raised, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (filled) Color.White else LibraryColors.OnBackground, modifier = Modifier.size(size * 0.45f)) }
}

/**
 * How far a message of mine got (2026-11-23, hen picked option A, "filling circle"): a ~12 dp circle beside the time,
 * in its colour. Sending: a dashed ring, slowly turning; Sent (the server has it): an empty ring; Delivered (on their
 * phone): a solid dot; Read: nothing (it fades as their circle glides to it). Each change eases in ~200 ms: the ring
 * closes, then fills. The one place to swap the set. (2026-11-24: envelopes instead, below.)
 */
@Composable
internal fun StatusMark(tick: com.chencohen.ptt.messaging.ReadMarks.Tick, modifier: Modifier = Modifier) {
    val ink = LibraryColors.OnMineMuted
    val warn = LibraryColors.Warning
    val label = when (tick) {
        Tick.SENDING -> stringResource(R.string.chat_status_pending)
        Tick.SENT -> stringResource(R.string.chat_status_sent)
        Tick.DELIVERED -> stringResource(R.string.chat_status_delivered)
        Tick.FAILED -> stringResource(R.string.chat_status_failed)
        Tick.NONE -> stringResource(R.string.chat_status_read)
    }
    // Envelopes (2026-11-24, hen's pick "E2 · Flying, sent, sealed"): Sending flies (shifted ahead, two speed lines
    // trailing, drifting); Sent rests; Delivered gets a round seal on its flap (popping in); Read: no mark. Drawn on a
    // 16-unit square; in Hebrew mirrored so the lines trail behind.
    val ease = tween<Float>(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val flying by androidx.compose.animation.core.animateFloatAsState(if (tick == Tick.SENDING) 1f else 0f, ease, label = "flying")
    val sealed by androidx.compose.animation.core.animateFloatAsState(
        if (tick == Tick.DELIVERED) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "seal",
    )
    val shown by androidx.compose.animation.core.animateFloatAsState(if (tick == Tick.NONE) 0f else 1f, tween(250), label = "mark fades")
    val drifting = androidx.compose.animation.core.rememberInfiniteTransition(label = "sending")
    val drift by drifting.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "drift")
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    if (shown <= 0f) return
    androidx.compose.foundation.Canvas(modifier.size(16.dp).semantics { contentDescription = label }) {
        val u = size.minDimension / 16f
        val color = (if (tick == Tick.FAILED) warn else ink).copy(alpha = 0.75f * shown)
        fun l(a: Float, b: Float) = a + (b - a) * flying
        val stroke = Stroke(width = 1.35f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        withTransform({ if (rtl) scale(-1f, 1f, pivot = center) }) {
            // The envelope: resting (x2 y3.8 12x8.6) or flying (x4 y4.6 10.5x7.6).
            val x = l(2f, 4f)
            val y = l(3.8f, 4.6f)
            val w = l(12f, 10.5f)
            val h = l(8.6f, 7.6f)
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(x * u, y * u), androidx.compose.ui.geometry.Size(w * u, h * u), androidx.compose.ui.geometry.CornerRadius(l(1.6f, 1.4f) * u), style = stroke)
            val flap = androidx.compose.ui.graphics.Path().apply {
                moveTo(l(2.6f, 4.5f) * u, l(4.6f, 5.3f) * u)
                lineTo(l(8f, 9.25f) * u, l(8.8f, 9f) * u)
                lineTo(l(13.4f, 14f) * u, l(4.6f, 5.3f) * u)
            }
            drawPath(flap, color, style = stroke)
            // The speed lines, trailing and drifting back while it flies.
            if (flying > 0f) {
                val back = drift * 0.8f
                val lines = color.copy(alpha = color.alpha * flying * (1f - 0.5f * drift))
                drawLine(lines, androidx.compose.ui.geometry.Offset((0.8f - back) * u, 7f * u), androidx.compose.ui.geometry.Offset((2.8f - back) * u, 7f * u), 1.35f * u, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(lines, androidx.compose.ui.geometry.Offset((1.4f - back) * u, 9.6f * u), androidx.compose.ui.geometry.Offset((3f - back) * u, 9.6f * u), 1.35f * u, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            // The seal on the flap, popping in.
            if (sealed > 0.01f) drawCircle(color, 2.3f * u * sealed.coerceAtLeast(0f), androidx.compose.ui.geometry.Offset(8f * u, 9.4f * u))
        }
    }
}

/**
 * Their small letter circle, right after the last message of mine they read (2026-11-23, hen, from the mockups); a
 * tap shows when they read it.
 */
@Composable
internal fun ReaderCircle(user: String, readAt: Long?, end: Boolean) {
    var showTime by remember { androidx.compose.runtime.mutableStateOf(false) }
    val label = com.chencohen.ptt.genderedString(R.string.chat_read_up_to_here, user, user)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.animation.AnimatedVisibility(showTime && readAt != null) {
                Text(
                    stringResource(R.string.chat_read_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(readAt ?: 0L))),
                    color = LibraryColors.Muted, style = LibraryType.Tiny, modifier = Modifier.padding(end = 6.dp),
                )
            }
            Box(
                Modifier.size(20.dp).background(LibraryColors.Mine, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = label) { showTime = !showTime }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Text(user.take(1).uppercase(), color = LibraryColors.OnMine, style = LibraryType.Tiny) }
        }
    }
    @Suppress("UNUSED_EXPRESSION") end
}

/**
 * A video burst in the chat (2026-11-23, hen: "every video a person sends will be in the chat"): its first picture,
 * its length, and a play mark; a tap plays it full screen, a long press saves it as a file. Live: a "live" mark until
 * it is whole.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun VideoClipContent(message: TextMessage, live: Boolean, onInverse: Boolean, play: (String) -> Unit, save: ((String) -> Unit)?, compact: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clip = message.clip ?: return
    var thumb by remember(message.id) { androidx.compose.runtime.mutableStateOf(com.chencohen.ptt.ui.talk.ClipThumbs.cached(message.id)) }
    LaunchedEffect(message.id, clip.kept, live) { if (thumb == null && !live) thumb = com.chencohen.ptt.ui.talk.ClipThumbs.load(context, message.id) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val label = stringResource(R.string.clip_play)
    val ratio = thumb?.let { it.width.toFloat() / it.height } ?: 0.75f
    Box(
        Modifier.width(if (compact) 132.dp else if (ratio < 1f) 168.dp else 240.dp).aspectRatio(ratio)
            .background(Color.Black, shape)
            .clip(shape)
            .combinedClickable(role = Role.Button, onClickLabel = label, onLongClick = save?.let { s -> { s(message.id) } }) { play(message.id) },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedVisibility(thumb != null, enter = androidx.compose.animation.fadeIn(tween(200)), modifier = Modifier.fillMaxSize()) {
            thumb?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Box(Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(LibraryIcons.Play, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        val length = if (live) stringResource(R.string.clip_live) else clip.durationMs.takeIf { it > 0 }?.let { clipLength(it) }
        if (length != null) {
            Text(
                length, color = Color.White, style = LibraryType.Tiny,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    @Suppress("UNUSED_EXPRESSION") onInverse
}

/**
 * A push-to-video burst live in the chat (2026-11-23, hen): mine, the camera as it sends, at the end of the chat in an
 * outlined bubble like the draft (with [framed]), a red dot and the time; theirs, their video as it plays. When it
 * ends the bubble stays and becomes the video (first picture, length).
 */
@Composable
internal fun LiveVideoBubble(mine: Boolean, startedAt: Long, framed: Boolean = true, waitingFor: String? = null) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            delay(200)
        }
    }
    val coral = com.chencohen.ptt.ui.style.Pink
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val video = @Composable {
        Box(Modifier.width(168.dp).aspectRatio(3f / 4f).background(Color.Black, shape).clip(shape), contentAlignment = Alignment.Center) {
            if (mine) com.chencohen.ptt.ui.talk.PushVideoPreview(Modifier.fillMaxSize()) else com.chencohen.ptt.ui.talk.TalkVideoView(Modifier.fillMaxSize(), cover = true)
            // Recording while their burst still goes: sent right after it.
            androidx.compose.animation.AnimatedVisibility(waitingFor != null, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                Text(
                    stringResource(R.string.talk_waiting_for, waitingFor.orEmpty()),
                    color = Color.White, style = LibraryType.Tiny,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(LibraryColors.Error, CircleShape))
                Text(clipLength(elapsed), color = Color.White, style = LibraryType.Tiny, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
    if (!framed) {
        video()
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.border(1.5.dp, coral, LibraryShapes.Card).background(coral.copy(alpha = 0.08f), LibraryShapes.Card).padding(6.dp)) { video() }
    }
}

/**
 * In a group, the members who read up to here (2026-11-23, hen): their letter circles, at most 3 then "+N"; a tap
 * lists who read and when.
 */
@Composable
internal fun GroupReaders(readers: List<Pair<String, Long>>) {
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    val label = stringResource(R.string.chat_read_by, readers.joinToString(", ") { it.first })
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
            Row(
                Modifier.clickable(role = Role.Button, onClickLabel = label) { open = !open }.semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                readers.take(3).forEach { (who, _) ->
                    Box(Modifier.size(20.dp).background(LibraryColors.Mine, CircleShape), contentAlignment = Alignment.Center) {
                        Text(who.take(1).uppercase(), color = LibraryColors.OnMine, style = LibraryType.Tiny)
                    }
                }
                if (readers.size > 3) Text("+${readers.size - 3}", color = LibraryColors.Muted, style = LibraryType.Tiny, modifier = Modifier.padding(start = 2.dp))
            }
            androidx.compose.animation.AnimatedVisibility(open) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 4.dp)) {
                    readers.forEach { (who, at) ->
                        Text("$who · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)), color = LibraryColors.Muted, style = LibraryType.Tiny)
                    }
                }
            }
        }
    }
}

/**
 * Two video bursts at the same time, one each side (2026-11-23, hen): side by side in one row, theirs on their side and
 * mine on mine, the one that started later lower by how much later it started (up to 120 dp); both a little smaller.
 */
@Composable
internal fun VideoPair(earlier: TextMessage, later: TextMessage, bubble: @Composable (TextMessage) -> Unit) {
    val length = earlier.clip?.durationMs?.takeIf { it > 0 } ?: 5_000L
    val drop = (120f * ((later.at - earlier.at).toFloat() / length).coerceIn(0f, 1f)).dp
    val theirs = if (earlier.outgoing) later else earlier
    val mine = if (earlier.outgoing) earlier else later
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).padding(top = if (theirs === later) drop else 0.dp), contentAlignment = Alignment.TopStart) { bubble(theirs) }
        Box(Modifier.weight(1f).padding(top = if (mine === later) drop else 0.dp), contentAlignment = Alignment.TopEnd) { bubble(mine) }
    }
}

