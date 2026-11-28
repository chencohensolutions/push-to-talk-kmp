package com.chencohen.ptt.ui.media

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
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
import com.awxkee.jxlcoder.JxlCoder
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.AttachmentContent
import com.chencohen.ptt.messaging.MediaKind
import com.chencohen.ptt.messaging.MessageStatus
import com.chencohen.ptt.messaging.TextMessage
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
 * Shared pictures, videos and files in the chat (2026-11-28, hen; docs/screens/chat.md, "Shared media"): each picture
 * or video as wide as the screen, its own shape, with the time it was sent and a hint of when it was taken; a file as
 * a bubble with its name and size. The preview comes from what the phone keeps, or quietly from the server.
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
) {
    val media = message.media ?: return
    val mine = message.outgoing
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        media.items.forEachIndexed { i, item ->
            val last = i == media.items.lastIndex && media.caption.isBlank()
            when (item.kind) {
                MediaKind.IMAGE, MediaKind.VIDEO -> Picture(message, item, preview, if (last) time else null, failed = mine && message.status == MessageStatus.FAILED, select = select) {
                    when {
                        selecting && select != null -> select()
                        mine && message.status == MessageStatus.FAILED -> retry(message.id)
                        else -> open(message.id, item.id)
                    }
                }
                else -> FileRow(item, mine, if (last) time else null)
            }
        }
        if (media.caption.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                Column(Modifier.background(if (mine) LibraryColors.Mine else LibraryColors.Theirs, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(media.caption, color = if (mine) LibraryColors.OnMine else LibraryColors.OnTheirs, style = LibraryType.Message)
                    Text(time, color = if (mine) LibraryColors.OnMineMuted else LibraryColors.OnTheirsMuted, style = LibraryType.Tiny, modifier = Modifier.align(Alignment.End))
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Picture(message: TextMessage, item: AttachmentContent, preview: suspend (String, String) -> ByteArray?, time: String?, failed: Boolean, select: (() -> Unit)?, onClick: () -> Unit) {
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
        // When and (later, the place) where it was taken, and the time it was sent (2026-11-27, hen).
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val taken = item.takenAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
            val length = item.durationMs.takeIf { it > 0 }?.let { com.chencohen.ptt.ui.talk.clipLength(it) }
            listOfNotNull(length, taken).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Chip(it) }
            Spacer(Modifier.weight(1f))
            time?.let { Chip(it, warn = failed) }
        }
    }
}

@Composable
private fun Chip(text: String, warn: Boolean = false) {
    Text(
        text, color = if (warn) LibraryColors.Warning else Color.White, style = LibraryType.Tiny,
        modifier = Modifier.background(Color(0x80000000), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun FileRow(item: AttachmentContent, mine: Boolean, time: String?) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Row(
            Modifier.background(if (mine) LibraryColors.Mine else LibraryColors.Theirs, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LibraryIcons.Paste, null, tint = if (mine) LibraryColors.OnMine else LibraryColors.OnTheirs, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(item.name.ifBlank { stringResource(R.string.media_file) }, color = if (mine) LibraryColors.OnMine else LibraryColors.OnTheirs, style = LibraryType.Message, maxLines = 2)
                val size = android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, item.size)
                Text(listOfNotNull(size, time).joinToString(" · "), color = if (mine) LibraryColors.OnMineMuted else LibraryColors.OnTheirsMuted, style = LibraryType.Tiny)
            }
        }
    }
}
