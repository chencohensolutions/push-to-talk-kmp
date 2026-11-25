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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.chencohen.ptt.ui.talk.holdOrLock
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
    /** Its field took the focus. */
    focused: () -> Unit = {},
    /** The text and where the caret is in it, at every change, the caret moving too (live typing shows it). */
    caret: (String, androidx.compose.ui.text.TextRange) -> Unit = { _, _ -> },
) {
    // The field's own value, composition included, kept as the keyboard gives it (2026-11-24, hen: suggestions and
    // autocorrect didn't take when it was rebuilt from the text each time): set from outside only when the text
    // itself changed there (emptied after Send).
    var field by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))) }
    if (field.text != text) field = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
    val coral = com.chencohen.ptt.ui.style.Pink
    // One element, resting ("Write a message…", dashed) or writing (coral outline), morphing both ways in 250 ms on the
    // keyboard's easing (2026-11-24, hen); with text in it, it stays writing. Same size in both (no jump), anchored on
    // my side, no label.
    val open by androidx.compose.animation.core.animateFloatAsState(
        if (editing || text.isNotEmpty()) 1f else 0f,
        tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "writing",
    )
    val style = LibraryType.Message.copy(color = LibraryColors.OnBackground)
    val label = stringResource(R.string.chat_send)
    val hint = stringResource(R.string.chat_write_hint)
    // From 38.2% of the row (1 − 0.618, the golden ratio) growing with the text, at once, up to 85%, then wrapping;
    // past 6 lines it scrolls inside with the caret in view.
    // Never narrower than its resting words (2026-11-24: "Write a message…" was cut to "Write a" in English).
    val width = maxOf(draftWidth(text, style, chrome = 14.dp + 8.dp + 8.dp + 36.dp), draftWidth(hint, style, chrome = 14.dp + 8.dp + 8.dp + 36.dp))
    val dash = LibraryColors.Muted.copy(alpha = 0.6f)
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Box(modifier.fillMaxWidth(), contentAlignment = MySide) {
        Row(
            bubbleModifier.width(width)
                .shadow(6.dp + 2.dp * open, LibraryShapes.Card)
                .background(LibraryColors.Background.copy(alpha = 0.92f + 0.08f * open), LibraryShapes.Card)
                .drawBehind {
                    if (open < 1f) {
                        drawRoundRect(
                            dash.copy(alpha = dash.alpha * (1f - open)),
                            cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                        )
                    }
                }
                .border(1.5.dp, coral.copy(alpha = open), LibraryShapes.Card)
                .background(coral.copy(alpha = 0.08f * open), LibraryShapes.Card)
                // Resting, a tap anywhere on it starts writing, not only on its words (2026-11-24, hen).
                .then(
                    if (!editing) Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null, role = Role.Button, onClickLabel = hint,
                    ) { startEditing() } else Modifier,
                )
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = field,
                onValueChange = { v ->
                    val before = field
                    val next = if (v.text.length > 4_000) v.copy(text = v.text.take(4_000)) else v
                    field = next
                    if (next.text != before.text) change(next.text)
                    if (next.text != before.text || next.selection != before.selection) caret(next.text, next.selection)
                },
                textStyle = style,
                // The caret fades in with the morph.
                cursorBrush = SolidColor(coral.copy(alpha = open)),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        // The resting words fade and slide a little as the caret comes.
                        if (text.isEmpty() && open < 1f) {
                            Text(
                                hint, color = LibraryColors.Muted, style = LibraryType.Body, maxLines = 1,
                                modifier = Modifier.graphicsLayer {
                                    alpha = 1f - open
                                    translationX = 12.dp.toPx() * open * (if (rtl) -1f else 1f)
                                },
                            )
                        }
                        field()
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(focus)
                    .semantics { contentDescription = hint }
                    .onFocusChanged {
                        if (it.isFocused) {
                            focused()
                            if (!editing) startEditing()
                        }
                    },
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(36.dp).graphicsLayer {
                    alpha = open
                    scaleX = 0.7f + 0.3f * open
                    scaleY = 0.7f + 0.3f * open
                }
                    .background(if (text.isNotBlank()) coral else coral.copy(alpha = 0.3f), CircleShape)
                    .clickable(enabled = text.isNotBlank() && open > 0.5f, role = Role.Button, onClickLabel = label, onClick = send),
                contentAlignment = Alignment.Center,
            ) { Icon(LibraryIcons.ArrowUp, label, tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** The writing bubble's least and most width, of the row (2026-11-24, hen). */
internal const val DRAFT_MIN = 0.382f
internal const val DRAFT_MAX = 0.85f

/** The row the chat's bubbles sit in: the screen less its 12 dp sides. */
@Composable
internal fun chatRowWidth(): Dp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 24.dp

/** A writing box's width for [text]: its longest line and [chrome], from 38.2% to 85% of the row. */
@Composable
internal fun draftWidth(text: String, style: androidx.compose.ui.text.TextStyle, chrome: Dp): Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val row = chatRowWidth()
    val textWidth = remember(text, style) { with(density) { measurer.measure(text.ifEmpty { " " }, style).size.width.toDp() } }
    return (textWidth + chrome + 4.dp).coerceIn(row * DRAFT_MIN, row * DRAFT_MAX)
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
            Text(message.body, color = ink, style = LibraryType.Message)
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
 * The buttons under the chat, icons only (hen: "no text labels"): Voice message (a tap starts recording; while it
 * records it is Send) and Push-to-talk (hold). No call buttons here (2026-11-25, hen: "in chat screen, remove the
 * video and voice call buttons"). [compact] with the keyboard up: small, in one row.
 */
@Composable
internal fun ChatButtons(
    compact: Boolean,
    talking: Boolean,
    recording: Boolean,
    actions: ConversationActions,
    /** This chat's push is video (the camera goes too). */
    video: Boolean = false,
    /** Locked hands-free (2026-11-24, hen): the button is Stop. */
    handsFree: Boolean = false,
    /** Slid up to the lock while held: hands-free video (2026-11-24, hen). */
    lock: () -> Unit = {},
    /** The finger started sliding up (the camera warms) or came back down / let go. */
    sliding: (Boolean) -> Unit = {},
    /** The finger went down on Push ([true]) or lifted. */
    pressed: (Boolean) -> Unit = {},
    stopLive: () -> Unit = {},
    /** Pressed and let go (locked: slid up to the lock), in place of starting and ending a burst directly. */
    press: (() -> Unit)? = null,
    release: ((locked: Boolean) -> Unit)? = null,
    /** My camera, small, on the button's corner. */
    thumb: Boolean = true,
    /**
     * My press waits for this one's floor (2026-11-25, hen: waiting looks like waiting, not live): the big button
     * dimmed inside a dashed ring, and "Waiting for Sharon…" beside it.
     */
    waitingFor: String? = null,
) {
    val microphone = rememberMicrophone(actions.micDenied)
    val small by animateDpAsState(if (compact) 40.dp else 52.dp, label = "small buttons")
    val big by animateDpAsState(if (compact) 48.dp else 84.dp, label = "talk button")
    // The language's own direction, for words in the row (the row itself is laid out left to right).
    val reading = androidx.compose.ui.platform.LocalLayoutDirection.current
    // Laid out on the screen itself, the same in Hebrew and English: the big button in the middle of the screen and
    // voice message just left of it (2026-11-25, hen: "make the push button in center and the voice message button on
    // its left").
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (compact) 6.dp else 12.dp, bottom = if (compact) 6.dp else 12.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).padding(end = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (waitingFor != null) {
                    Text(
                        // The line in the language's direction, the name wrapped: a Latin name doesn't turn a Hebrew line around.
                        stringResource(R.string.talk_waiting_for, android.text.BidiFormatter.getInstance().unicodeWrap(waitingFor)), color = LibraryColors.Muted,
                        style = LibraryType.Caption.copy(textDirection = if (reading == androidx.compose.ui.unit.LayoutDirection.Rtl) androidx.compose.ui.text.style.TextDirection.Rtl else androidx.compose.ui.text.style.TextDirection.Ltr),
                        // Beside the buttons, which are at the right whatever the language.
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Right,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                }
                if (recording) {
                    RoundButton(LibraryIcons.ArrowUp, stringResource(R.string.voice_send), small, filled = true) { actions.finishVoice(true) }
                } else {
                    RoundButton(LibraryIcons.Mic, stringResource(R.string.voice_message), small) { microphone(actions.startVoiceMessage) }
                }
            }
            val talkLabel = stringResource(R.string.talk_button)
            // Hold: voice push-to-talk. Slide up while holding, to the camera-and-lock target above the button: it
            // turns into hands-free video until Stop (2026-11-24, hen: "holding the button transmitting audio, sliding
            // the button up open hands free video"). The camera warms as the finger starts to slide.
            var lifting by remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
            var lockedNow by remember { androidx.compose.runtime.mutableStateOf(false) }
            var slidingNow by remember { androidx.compose.runtime.mutableStateOf(false) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val lockAt = with(density) { 72.dp.toPx() }
            val warmFrom = with(density) { 16.dp.toPx() }
            val context = androidx.compose.ui.platform.LocalContext.current
            val canAsk = androidx.activity.compose.LocalActivityResultRegistryOwner.current != null
            val askCamera = if (canAsk) androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {} else null
            fun cameraAllowed() = context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val onLock by androidx.compose.runtime.rememberUpdatedState(lock)
            val onSliding by androidx.compose.runtime.rememberUpdatedState(sliding)
            val onPressed by androidx.compose.runtime.rememberUpdatedState(pressed)
            val onRelease by androidx.compose.runtime.rememberUpdatedState(release)
            val onPress by androidx.compose.runtime.rememberUpdatedState(press ?: actions.startVoice)
            val ring = com.chencohen.ptt.ui.style.Pink
            Box(
                Modifier.size(big)
                    .drawBehind {
                        // Waiting: a dashed ring around the button, the button itself dimmed.
                        if (waitingFor != null) {
                            drawCircle(
                                ring, radius = size.minDimension / 2 + 5.dp.toPx(),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx()))),
                            )
                        }
                    }
                    .graphicsLayer { alpha = if (waitingFor != null) 0.45f else 1f },
            ) {
                if (handsFree) {
                    StopLive(big, stopLive)
                } else Box(
                    Modifier.fillMaxSize()
                        .shadow(10.dp, CircleShape)
                        .background(if (talking) com.chencohen.ptt.ui.style.Pink else com.chencohen.ptt.ui.style.Pink.copy(alpha = 0.9f), CircleShape)
                        .semantics { contentDescription = talkLabel }
                        .holdOrLock(
                            lockAt = lockAt,
                            onPress = { lifting = 0f; lockedNow = false; slidingNow = false; onPressed(true); microphone(onPress) },
                            onLift = { up ->
                                lifting = up
                                val slide = up > warmFrom && !lockedNow
                                if (slide != slidingNow) {
                                    slidingNow = slide
                                    onSliding(slide)
                                }
                                if (up >= lockAt && !lockedNow) {
                                    if (cameraAllowed()) {
                                        lockedNow = true
                                        onLock()
                                    } else {
                                        askCamera?.launch(android.Manifest.permission.CAMERA) ?: actions.micDenied()
                                    }
                                }
                            },
                            onRelease = { locked ->
                                lifting = -1f
                                onPressed(false)
                                if (slidingNow) {
                                    slidingNow = false
                                    onSliding(false)
                                }
                                // Already switched to hands-free video at the lock.
                                if (lockedNow) return@holdOrLock
                                val r = onRelease
                                if (r != null) r(false) else actions.finishVoice(true)
                                @Suppress("UNUSED_EXPRESSION") locked
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(LibraryIcons.Talk, null, tint = Color.White, modifier = Modifier.size(big * 0.42f))
                }
                // The target, over the button while it is held: a camera over a lock; it fills as the thumb nears it.
                if (lifting >= 0f && !handsFree) {
                    val near = (lifting / lockAt).coerceIn(0f, 1f)
                    androidx.compose.foundation.layout.Column(
                        Modifier.align(Alignment.TopCenter).offset(y = -(72.dp + 16.dp * near)).size(44.dp, 72.dp)
                            .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                            .background(androidx.compose.ui.graphics.lerp(LibraryColors.Background, com.chencohen.ptt.ui.style.Pink, near), androidx.compose.foundation.shape.RoundedCornerShape(22.dp)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val ink = if (near > 0.5f) Color.White else LibraryColors.OnBackground
                        Icon(LibraryIcons.Video, null, tint = ink, modifier = Modifier.size(20.dp))
                        Icon(LibraryIcons.Lock, stringResource(R.string.talk_lock_hint), tint = ink, modifier = Modifier.padding(top = 6.dp).size(18.dp))
                    }
                }
            }
            // The same width as the left side's, so the big button sits in the middle of the screen.
            Spacer(Modifier.weight(1f).padding(start = 8.dp))
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
internal fun ReaderCircle(user: String, readAt: Long?, end: Boolean, here: Boolean = false) {
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
            val tap = Modifier.clickable(role = Role.Button, onClickLabel = label) { showTime = !showTime }.semantics { contentDescription = label }
            HereFace(user, here, tap)
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
internal fun VideoClipContent(
    message: TextMessage,
    live: Boolean,
    onInverse: Boolean,
    play: (String) -> Unit,
    save: ((String) -> Unit)?,
    compact: Boolean = false,
    /** Plays it in its bubble with sound, from the start (a tap, or the speaker). */
    playHere: ((String) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clip = message.clip ?: return
    var thumb by remember(message.id) { androidx.compose.runtime.mutableStateOf(com.chencohen.ptt.ui.talk.ClipThumbs.cached(message.id)) }
    // Its first picture; the clip may not be here yet (still coming, or not uploaded yet): tried again until it is
    // (2026-11-24, hen: "when sending video the recipient see blank").
    LaunchedEffect(message.id, clip.kept, live) {
        var tries = 0
        while (thumb == null && !live && tries++ < 60) {
            thumb = com.chencohen.ptt.ui.talk.ClipThumbs.load(context, message.id)
            if (thumb == null) kotlinx.coroutines.delay(1_500)
        }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val label = stringResource(R.string.clip_play)
    val ratio = thumb?.let { it.width.toFloat() / it.height } ?: 0.75f
    // The most visible video in the chat plays here, muted and looping, while the app is in front (2026-11-24, hen).
    val autoplay = LocalVideoAutoplay.current
    val resumed = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val playingMuted = autoplay != null && !live && resumed && autoplay.current == message.id
    if (autoplay != null) androidx.compose.runtime.DisposableEffect(message.id) { onDispose { autoplay.gone(message.id) } }
    Box(
        Modifier.width(if (compact) 132.dp else if (ratio < 1f) 168.dp else 240.dp).aspectRatio(ratio)
            .background(Color.Black, shape)
            .clip(shape)
            .then(if (autoplay != null) Modifier.onGloballyPositioned { c -> autoplay.seen(message.id, c.boundsInWindow()) } else Modifier)
            // A double tap on the bubble plays it here with sound, from the start (2026-11-24, hen: a single tap does
            // nothing, so scrolling can't start it); a long press selects it; the full screen: a tap while it plays.
            // Without a chat around it (playHere null), a tap plays it full screen as before.
            .then(if (playHere == null) Modifier.combinedClickable(role = Role.Button, onClickLabel = label, onLongClick = save?.let { s -> { s(message.id) } }) { play(message.id) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedVisibility(thumb != null, enter = androidx.compose.animation.fadeIn(tween(200)), modifier = Modifier.fillMaxSize()) {
            thumb?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        // Over its first picture, so it starts with no flash.
        if (playingMuted) com.chencohen.ptt.ui.talk.InlineVideo(message.id, Modifier.fillMaxSize())
        if (playingMuted && playHere != null) {
            val unmute = stringResource(R.string.clip_unmute)
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .clickable(role = Role.Button, onClickLabel = unmute) { playHere(message.id) },
                contentAlignment = Alignment.Center,
            ) { Icon(LibraryIcons.SpeakerOff, unmute, tint = Color.White, modifier = Modifier.size(16.dp)) }
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
 * Which video bubble plays muted (2026-11-24, hen): the one most on screen, at least 60% of it, of those the chat
 * shows; [viewport] is the chat's list on the screen.
 */
internal class VideoAutoplay {
    var viewport by androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private val shares = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    val current: String? by androidx.compose.runtime.derivedStateOf { shares.filterValues { it >= 0.6f }.maxByOrNull { it.value }?.key }

    fun seen(id: String, bounds: androidx.compose.ui.geometry.Rect) {
        val view = viewport ?: return
        val h = bounds.height
        val share = if (h <= 0f) 0f else ((minOf(bounds.bottom, view.bottom) - maxOf(bounds.top, view.top)).coerceAtLeast(0f) / h)
        val was = shares[id]
        if (was == null || kotlin.math.abs(was - share) > 0.04f || (share >= 0.6f) != (was >= 0.6f)) shares[id] = share
    }

    fun gone(id: String) {
        shares.remove(id)
    }
}

internal val LocalVideoAutoplay = androidx.compose.runtime.staticCompositionLocalOf<VideoAutoplay?> { null }

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
internal fun GroupReaders(readers: List<Pair<String, Long>>, here: Set<String> = emptySet()) {
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    val label = stringResource(R.string.chat_read_by, readers.joinToString(", ") { it.first })
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
            Row(
                Modifier.clickable(role = Role.Button, onClickLabel = label) { open = !open }.semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Those in the chat now first, each with their ring (2026-11-24, hen: "so it could work on groups").
                readers.sortedByDescending { it.first in here }.take(3).forEach { (who, _) -> HereFace(who, who in here, Modifier) }
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


/**
 * [user]'s face (or letter) at the end of what they read (2026-11-24, hen, "B"): while they have this chat open
 * ([here]), full size and bright with a slow teal ring breathing around it; after, smaller and grey.
 */
@Composable
internal fun HereFace(user: String, here: Boolean, modifier: Modifier) {
    val face = com.chencohen.ptt.ui.face.faceOf(user)
    val grow by androidx.compose.animation.core.animateFloatAsState(if (here) 1f else 0f, tween(400), label = "here")
    val breath by androidx.compose.animation.core.rememberInfiniteTransition(label = "breath").animateFloat(
        0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "ring",
    )
    val size = (20f + 8f * grow).dp
    val teal = LibraryColors.Accent
    val grey = androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0.25f + 0.75f * grow) }
    Box(
        modifier.size(size + 8.dp).drawBehind {
            if (grow > 0f) drawCircle(teal.copy(alpha = breath * grow), radius = this.size.minDimension / 2f - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) {
        if (face != null) {
            androidx.compose.foundation.Image(
                face, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(grey),
                modifier = Modifier.size(size).graphicsLayer { alpha = 0.6f + 0.4f * grow },
            )
        } else {
            Box(
                Modifier.size(size - 4.dp).background(if (here) LibraryColors.Mine else LibraryColors.Raised, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(user.take(1).uppercase(), color = if (here) LibraryColors.OnMine else LibraryColors.Muted, style = LibraryType.Tiny) }
        }
    }
}
