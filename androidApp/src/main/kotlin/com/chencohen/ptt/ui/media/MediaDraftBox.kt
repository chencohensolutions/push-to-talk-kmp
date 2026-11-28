package com.chencohen.ptt.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import com.chencohen.ptt.ui.contacts.bubbleMaxWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.MediaKind
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType

/** Pictures picked for a chat, not sent yet: shown in the writing bubble, the caption written under them. */
class MediaDraft(
    val messages: List<TextMessage>,
    /** Sends them with [caption], less the items taken out ([removed], by item id). */
    val send: (caption: String, removed: Set<String>) -> Unit,
    /** Sends them quietly before their caption (Send with the box empty), less [removed]. */
    val sendFirst: (removed: Set<String>) -> Unit,
    /** Drops them all. */
    val drop: () -> Unit,
)

/**
 * The writing bubble with pictures in it (2026-11-28, hen: "the image should be 100% width and the textbox should be
 * its max width of a bubble"): the pictures edge to edge across the screen, each in its own shape with ✕ to take it
 * out, between two half bubbles outlined in the writing colour on my side, a bubble's widest. The bottom one is the
 * text box: the caption, Send, and the trash that drops them all. So the draft already looks like the message it
 * becomes (MediaMessage), and Send moves nothing sideways. More pictures than fit (about half the screen) scroll.
 * ✕ on the last one drops the draft.
 */
@Composable
internal fun MediaDraftBox(
    draft: MediaDraft,
    preview: suspend (messageId: String, itemId: String) -> ByteArray?,
    modifier: Modifier = Modifier,
    /** Send: a picture of the box as drawn and where it is, so it flies into the chat as its message (2026-11-28, hen). */
    fly: (androidx.compose.ui.graphics.ImageBitmap, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
) {
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Sent with its caption, the whole box flies into the chat. Sent first (no caption yet) nothing flies: the pictures
    // stay where they are, the message taking their place above the caption box that stays.
    fun sendNow(flies: Boolean, action: () -> Unit) {
        val at = bounds
        if (!flies || at == null) return action()
        scope.launch {
            runCatching { layer.toImageBitmap() }.getOrNull()?.let { fly(it, at) }
            action()
        }
    }
    var caption by rememberSaveable(draft.messages.firstOrNull()?.id) { mutableStateOf("") }
    var removed by remember(draft.messages.firstOrNull()?.id) { mutableStateOf(emptySet<String>()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(draft.messages.firstOrNull()?.id) { runCatching { focus.requestFocus() } }
    val coral = LibraryColors.Writing
    val items = draft.messages.flatMap { m -> m.media?.items.orEmpty().map { m to it } }.filter { it.second.id !in removed }
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { draft.drop() }
        return
    }
    // The pictures scroll inside the box at most 45% of the screen high, and less with the keyboard up, so the caption
    // box under them (and the header, and a little of the chat) always stays in view (2026-11-28, hen: "i cant see the
    // text bubble when many images included").
    val screen = LocalConfiguration.current.screenHeightDp
    val keyboard = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current).let { px -> px / androidx.compose.ui.platform.LocalDensity.current.density }
    val maxPictures = minOf(screen * 0.45f, screen - keyboard - ROOM_AROUND).coerceAtLeast(120f).dp
    val width = bubbleMaxWidth()
    // Edge to edge: out through the side margins the writing area keeps (as the pictures in the chat).
    Column(
        modifier.fillMaxWidth().bleed(12.dp)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // The top half: where and when they were taken, as on the message it becomes.
        val taken = takenAndWhere(items.map { it.second })
        Box(Modifier.padding(horizontal = 12.dp).width(width).heightIn(min = 10.dp).writingHalf(coral, top = true).padding(horizontal = 14.dp, vertical = if (taken != null) 6.dp else 0.dp)) {
            if (taken != null) Text(taken, color = LibraryColors.Muted, style = LibraryType.Tiny, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxPictures).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEachIndexed { index, (message, item) ->
                if (index > 0) changeOver(item, items[index - 1].second)?.let {
                    // In a strip of the writing outline, as on the message it becomes.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.padding(horizontal = 12.dp).width(width).writingSides(coral)) { PictureNote(it) }
                    }
                }
                Box {
                    when (item.kind) {
                        MediaKind.IMAGE, MediaKind.VIDEO -> Picture(message, item, preview, select = null) {}
                        else -> Text(item.name, color = LibraryColors.OnBackground, style = LibraryType.Message, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).clip(CircleShape).background(Color(0x99000000))
                            .clickable { removed = removed + item.id },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(LibraryIcons.Close, stringResource(R.string.media_draft_remove), tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 12.dp).width(width).writingHalf(coral, top = false).padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = draft.drop), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Trash, stringResource(R.string.media_draft_drop), tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (caption.isEmpty()) Text(stringResource(R.string.media_caption_hint), color = LibraryColors.Muted, style = LibraryType.Message)
                BasicTextField(
                    caption, { caption = it }, Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = LibraryType.Message.copy(color = LibraryColors.OnBackground),
                    cursorBrush = SolidColor(LibraryColors.OnBackground),
                    maxLines = 6,
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(coral).clickable { sendNow(caption.isNotBlank()) { if (caption.isBlank()) draft.sendFirst(removed) else draft.send(caption, removed) } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(LibraryIcons.ArrowUp, stringResource(R.string.media_draft_send), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Pictures sent before their caption (2026-11-28, hen: "first send the pictures, then write the text", one button). */
class MediaCaption(
    val messageId: String,
    /** The caption as it stands (Live typing shows it under the pictures on their phones). */
    val live: (text: String, selStart: Int, selEnd: Int) -> Unit,
    /** Sends the caption (empty: none); this is what notifies them. */
    val commit: (caption: String) -> Unit,
    /** Takes the pictures back: deleted for everyone, from the server too, and no notification. */
    val abort: () -> Unit,
)

/**
 * The caption box after the pictures went (one Send, hen 2026-11-28): the bottom half bubble of my pictures, outlined in
 * the writing colour, a bubble wide on my side, right under them at the end of the chat. Send sends the caption and
 * notifies them; empty, it notifies them of the pictures alone. Left empty (the chat closed, or [IDLE_NOTIFY] without a
 * letter), they are notified of the pictures then.
 */
@Composable
internal fun MediaCaptionBox(caption: MediaCaption, modifier: Modifier = Modifier) {
    var text by rememberSaveable(caption.messageId, stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var done by remember(caption.messageId) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val coral = LibraryColors.Writing
    fun commit(value: String) {
        if (done) return
        done = true
        caption.commit(value)
    }
    LaunchedEffect(caption.messageId) { runCatching { focus.requestFocus() } }
    LaunchedEffect(caption.messageId, text.text.isEmpty()) {
        if (text.text.isEmpty()) {
            kotlinx.coroutines.delay(IDLE_NOTIFY)
            commit("")
        }
    }
    androidx.compose.runtime.DisposableEffect(caption.messageId) { onDispose { commit("") } }
    Column(modifier.fillMaxWidth().bleed(12.dp).padding(bottom = 6.dp), horizontalAlignment = Alignment.End) {
        Row(
            Modifier.padding(horizontal = 12.dp).width(bubbleMaxWidth()).writingHalf(coral, top = false).padding(start = 4.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable {
                    done = true
                    caption.abort()
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(LibraryIcons.Trash, stringResource(R.string.media_draft_drop), tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.text.isEmpty()) Text(stringResource(R.string.media_caption_hint), color = LibraryColors.Muted, style = LibraryType.Message)
                BasicTextField(
                    text,
                    { value ->
                        text = value
                        caption.live(value.text, value.selection.start, value.selection.end)
                    },
                    Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = LibraryType.Message.copy(color = LibraryColors.OnBackground),
                    cursorBrush = SolidColor(LibraryColors.OnBackground),
                    maxLines = 6,
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(coral).clickable { commit(text.text) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(LibraryIcons.ArrowUp, stringResource(R.string.media_draft_send), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The draft's height besides its pictures, with the header and a strip of the chat (dp). */
private const val ROOM_AROUND = 260f

/** How long an empty caption box waits before telling them of the pictures alone. */
private const val IDLE_NOTIFY = 120_000L

/** The writing outline's two sides only: a strip of the draft's bubble between two pictures. */
private fun Modifier.writingSides(color: Color): Modifier = drawBehind {
    val w = 1.5.dp.toPx()
    drawLine(color, androidx.compose.ui.geometry.Offset(w / 2, 0f), androidx.compose.ui.geometry.Offset(w / 2, size.height), w)
    drawLine(color, androidx.compose.ui.geometry.Offset(size.width - w / 2, 0f), androidx.compose.ui.geometry.Offset(size.width - w / 2, size.height), w)
}

/** A half bubble's outline in [color], open on the side the pictures touch (the writing look of myHalfBubble). */
private fun Modifier.writingHalf(color: Color, top: Boolean, radius: Dp = 16.dp): Modifier = drawWithCache {
    val w = 1.5.dp.toPx()
    val r = radius.toPx()
    val h = size.height
    val x0 = w / 2f
    val x1 = size.width - w / 2f
    val y = if (top) w / 2f else h - w / 2f
    val corner = if (top) r else h - r
    val line = Path().apply {
        moveTo(x0, if (top) h else 0f)
        lineTo(x0, corner)
        quadraticBezierTo(x0, y, r, y)
        lineTo(x1 - r, y)
        quadraticBezierTo(x1, y, x1, corner)
        lineTo(x1, if (top) h else 0f)
    }
    onDrawBehind { drawPath(line, color, style = Stroke(w)) }
}
