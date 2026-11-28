package com.chencohen.ptt.ui.media

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import com.chencohen.ptt.ui.contacts.liveCaret
import com.awxkee.jxlcoder.JxlCoder
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.AttachmentContent
import com.chencohen.ptt.messaging.MediaKind
import com.chencohen.ptt.messaging.MessageStatus
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.contacts.ink
import com.chencohen.ptt.ui.contacts.myHalfBubble
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Out to the screen's edges through the chat's side padding ([side] each side; 2026-11-27, hen: pictures as wide as
 * the screen).
 */
internal fun Modifier.bleed(side: androidx.compose.ui.unit.Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = (side * 2).roundToPx()
        val width = constraints.maxWidth + extra
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
    },
)

/** The pictures drawn lately, by "<message>/<item>", so scrolling back doesn't decode them again. */
private val drawn = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

/**
 * Shared pictures, videos and files in the chat (2026-11-28, hen; docs/screens/conversation.md, "Shared pictures"):
 * each picture or video as wide as the screen, its own shape, between two half bubbles on the sender's side, in their
 * bubble's colour. The top one says who sent them in a group (theirs), else when they were taken; the bottom one holds
 * the caption and the time, and on mine moves as my text bubbles do (dashed while it goes, the dashes growing as its
 * previews upload, whole on the server, filled once every phone has it). A file is a row in the same frame. The preview comes from what the phone
 * keeps, or quietly from the server.
 */
@Composable
internal fun MediaMessage(
    message: TextMessage,
    preview: suspend (messageId: String, itemId: String) -> ByteArray?,
    open: (messageId: String, itemId: String) -> Unit,
    retry: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A long press: the message is selected (then delete, as any message). */
    select: (() -> Unit)? = null,
    /** Selecting: a tap adds or removes it. */
    selecting: Boolean = false,
    /** Mine: how far it got (the half bubbles' line and fill). */
    reach: com.chencohen.ptt.messaging.ReadMarks.Reach? = null,
    /** Mine, uploading its previews: how far (0 to 1). */
    progress: Float? = null,
    /** Theirs in a group: the sender's name, on the top half bubble. */
    author: String? = null,
    /** Theirs, sent before its caption: the caption as they write it (Live typing), with their caret. */
    liveEdit: com.chencohen.ptt.messaging.LiveEdit? = null,
) {
    val media = message.media ?: return
    val mine = message.outgoing
    val failed = mine && message.status == MessageStatus.FAILED
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at))
    val look = if (mine) com.chencohen.ptt.ui.contacts.rememberBubbleLook(reach ?: com.chencohen.ptt.messaging.ReadMarks.Reach.RECEIVED) else null
    val ink = when {
        look != null -> look.ink()
        else -> LibraryColors.OnTheirs
    }
    val muted = ink.copy(alpha = 0.7f)
    val side = if (mine) Alignment.End else Alignment.Start
    @Composable
    fun Modifier.half(top: Boolean): Modifier = when {
        look != null -> this.myHalfBubble(look, top, progress)
        // Theirs sent before the caption: outlined like their live draft while they write it; solid once sent.
        media.quiet -> this.border(1.dp, LibraryColors.Muted.copy(alpha = 0.6f), halfShape(top))
        else -> this.background(LibraryColors.Theirs, halfShape(top))
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = side) {
        // The top half bubble: who, in a group; else when they were taken.
        val taken = takenLine(media.items)
        Column(Modifier.padding(horizontal = 12.dp).width(com.chencohen.ptt.ui.contacts.bubbleMaxWidth()).half(top = true).padding(horizontal = 14.dp, vertical = if (author != null || taken != null) 6.dp else 5.dp)) {
            if (author != null) Text(author, color = if (com.chencohen.ptt.ui.contacts.LocalMemberHues.current.isEmpty()) LibraryColors.Accent else LibraryColors.OnTheirs, style = LibraryType.Small)
            if (taken != null) Text(taken, color = muted, style = LibraryType.Tiny, maxLines = 1)
        }
        media.items.forEach { item ->
            when (item.kind) {
                MediaKind.IMAGE, MediaKind.VIDEO -> Picture(message, item, preview, select = select) {
                    when {
                        selecting && select != null -> select()
                        failed -> retry(message.id)
                        else -> open(message.id, item.id)
                    }
                }
                else -> FileRow(item) { if (!selecting) open(message.id, item.id) }
            }
            Spacer(Modifier.height(2.dp))
        }
        // Mine sent before the caption: the caption box under the chat is its bottom half while I write it.
        if (mine && media.quiet) return@Column
        // The bottom half bubble: the caption and the time.
        Column(
            Modifier.padding(horizontal = 12.dp).width(com.chencohen.ptt.ui.contacts.bubbleMaxWidth()).half(top = false)
                .then(if (failed) Modifier.clickable { retry(message.id) } else Modifier)
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 7.dp),
        ) {
            val live = liveEdit?.text
            var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            if (live != null || media.caption.isNotBlank()) Text(
                live ?: media.caption, color = ink, style = LibraryType.Message, onTextLayout = { layout = it },
                modifier = if (live != null && liveEdit.selEnd >= 0) Modifier.liveCaret(layout, liveEdit.selStart, liveEdit.selEnd) else Modifier,
            )
            if (liveEdit != null || (!mine && media.quiet)) Text(stringResource(R.string.chat_draft), color = muted, style = LibraryType.Tiny, modifier = Modifier.align(Alignment.End))
            else Text(
                if (failed) stringResource(R.string.chat_status_failed) + " · " + time else time,
                color = if (failed) LibraryColors.Warning else muted, style = LibraryType.Tiny, modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

private fun halfShape(top: Boolean) = if (top) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)

/** When the pictures were taken, at the place's own time: "Yesterday 18:40", or a span "18:40–18:52". Null: not known. */
@Composable
private fun takenLine(items: List<AttachmentContent>): String? {
    val times = items.filter { it.takenAt > 0 }.map { it.takenAt + it.takenOffsetMin * 60_000L }.sorted()
    if (times.isEmpty()) return null
    val utc = java.util.TimeZone.getTimeZone("UTC")
    val clock = DateFormat.getTimeInstance(DateFormat.SHORT).apply { timeZone = utc }
    val day = android.text.format.DateUtils.getRelativeTimeSpanString(
        times.first() - java.util.TimeZone.getDefault().getOffset(times.first()), System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS,
    ).toString()
    val first = clock.format(Date(times.first()))
    val last = clock.format(Date(times.last()))
    val sameDay = times.last() / 86_400_000L == times.first() / 86_400_000L
    return when {
        first == last -> "$day $first"
        sameDay -> "$day $first–$last"
        else -> "$day $first – " + DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = utc }.format(Date(times.last()))
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Picture(message: TextMessage, item: AttachmentContent, preview: suspend (String, String) -> ByteArray?, select: (() -> Unit)?, onClick: () -> Unit) {
    val key = "${message.id}/${item.id}"
    val bitmap by produceState(drawn.get(key), key) {
        if (value != null) return@produceState
        val bytes = preview(message.id, item.id) ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { if (JxlCoder.isJXL(bytes)) JxlCoder.decode(bytes) else android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }?.also { drawn.put(key, it) }
    }
    val ratio = if (item.width > 0 && item.height > 0) (item.width.toFloat() / item.height).coerceIn(0.4f, 3f) else 1f
    val label = stringResource(if (item.kind == MediaKind.VIDEO) R.string.media_video else R.string.media_picture)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(LibraryColors.Raised).combinedClickable(onLongClick = select, onClick = onClick).semantics { contentDescription = label },
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (item.kind == MediaKind.VIDEO) {
            Box(Modifier.align(Alignment.Center).size(56.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Play, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        // Going up to the server (mine) or coming down from it (theirs): a thin bar along the bottom, filling.
        LocalTransfers.current[item.id]?.let { done ->
            val shown by androidx.compose.animation.core.animateFloatAsState(done, label = "transfer")
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55000000))) {
                Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(if (message.outgoing) LibraryColors.Writing else LibraryColors.Accent))
            }
        }
        // A video's length (when it was taken is on the top half bubble, the time it was sent on the bottom one).
        item.durationMs.takeIf { it > 0 }?.let { Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { Chip(com.chencohen.ptt.ui.talk.clipLength(it)) } }
    }
}

/** Each item's preview going up or coming down now (0 to 1), by item id: MediaSharing.transfers. */
internal val LocalTransfers = androidx.compose.runtime.staticCompositionLocalOf<Map<String, Float>> { emptyMap() }

@Composable
private fun Chip(text: String, warn: Boolean = false) {
    Text(
        text, color = if (warn) LibraryColors.Warning else Color.White, style = LibraryType.Tiny,
        modifier = Modifier.background(Color(0x80000000), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun FileRow(item: AttachmentContent, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(LibraryColors.Raised).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Paste, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(item.name.ifBlank { stringResource(R.string.media_file) }, color = LibraryColors.OnBackground, style = LibraryType.Message, maxLines = 2)
            Text(android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, item.size), color = LibraryColors.Muted, style = LibraryType.Tiny)
        }
    }
}
