package com.chencohen.ptt.ui.contacts

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
) {
    val coral = LibraryColors.Accent
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        if (!editing && text.isEmpty()) {
            val hint = stringResource(R.string.chat_write_hint)
            val dash = LibraryColors.Muted.copy(alpha = 0.6f)
            Text(
                hint,
                color = LibraryColors.Muted,
                style = LibraryType.Body,
                modifier = Modifier
                    .drawBehind {
                        drawRoundRect(
                            dash,
                            cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                        )
                    }
                    .clickable(role = Role.Button, onClickLabel = hint, onClick = startEditing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            Column(
                Modifier.widthIn(min = 120.dp, max = 300.dp)
                    .animateContentSize(tween(200))
                    .border(1.5.dp, coral, LibraryShapes.Card)
                    .background(coral.copy(alpha = 0.08f), LibraryShapes.Card)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(stringResource(R.string.chat_draft), color = coral, style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicTextField(
                        value = text,
                        onValueChange = { change(it.take(4_000)) },
                        textStyle = LibraryType.Body.copy(color = LibraryColors.OnBackground),
                        cursorBrush = SolidColor(coral),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                        modifier = Modifier.weight(1f, fill = false).widthIn(min = 60.dp).padding(bottom = 6.dp).focusRequester(focus),
                    )
                    Spacer(Modifier.width(8.dp))
                    val label = stringResource(R.string.chat_send)
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
    val coral = LibraryColors.Accent
    val fill = androidx.compose.ui.graphics.lerp(coral.copy(alpha = 0.08f), LibraryColors.Inverse, s)
    val ink = androidx.compose.ui.graphics.lerp(LibraryColors.OnBackground, LibraryColors.OnInverse, s)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .animateContentSize(tween(MORPH_MS))
                .border(1.5.dp, coral.copy(alpha = 1f - s), LibraryShapes.Card)
                .background(fill, LibraryShapes.Card)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (s < 1f) Text(stringResource(R.string.chat_draft), color = coral.copy(alpha = 1f - s), style = LibraryType.Small, modifier = Modifier.padding(bottom = 2.dp))
            Text(message.body, color = ink, style = LibraryType.Body)
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at)),
                color = LibraryColors.OnInverseMuted.copy(alpha = s), style = LibraryType.Tiny,
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
                bars.forEach { b -> Box(Modifier.width(3.dp).height((4 + 24 * b.coerceIn(0f, 1f)).dp).background(LibraryColors.Accent, CircleShape)) }
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
) {
    val microphone = rememberMicrophone(actions.micDenied)
    val small by animateDpAsState(if (compact) 40.dp else 52.dp, label = "small buttons")
    val big by animateDpAsState(if (compact) 48.dp else 84.dp, label = "talk button")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(LibraryIcons.Video, stringResource(R.string.call_video), small) { actions.call(true) }
        val talkLabel = stringResource(R.string.talk_button)
        Box(
            Modifier.size(big)
                .background(if (talking) LibraryColors.Accent else LibraryColors.Accent.copy(alpha = 0.85f), CircleShape)
                .semantics { contentDescription = talkLabel }
                .holdToTalk(onPress = { microphone(actions.startVoice) }, onRelease = { actions.finishVoice(true) }),
            contentAlignment = Alignment.Center,
        ) { Icon(LibraryIcons.Talk, null, tint = Color.White, modifier = Modifier.size(big * 0.42f)) }
        RoundButton(LibraryIcons.Call, stringResource(R.string.call_voice), small) { actions.call(false) }
        if (recording) {
            RoundButton(LibraryIcons.ArrowUp, stringResource(R.string.voice_send), small, filled = true) { actions.finishVoice(true) }
        } else {
            RoundButton(LibraryIcons.Mic, stringResource(R.string.voice_message), small) { microphone(actions.startVoiceMessage) }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Dp, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size)
            .background(if (filled) LibraryColors.Accent else LibraryColors.Raised, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (filled) Color.White else LibraryColors.OnBackground, modifier = Modifier.size(size * 0.45f)) }
}
