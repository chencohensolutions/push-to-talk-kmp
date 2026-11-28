package com.chencohen.ptt.ui.media

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithCache
import com.chencohen.ptt.ui.contacts.liveCaret
import com.chencohen.ptt.ui.contacts.myMiddleBubble
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
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

/**
 * The pictures drawn lately, by "<message>/<item>", so scrolling back doesn't decode them again: an eighth of what the
 * app may hold (about 30 screen-wide previews on an S25), at least 96 MB.
 */
private val drawn = object : LruCache<String, Bitmap>(maxOf(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

/** Where each picture of the chat lies on screen now, by "<message>/<item>" (the viewer's transition). */
internal val pictureBounds = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.geometry.Rect>()

/** The picture drawn in the chat, if it is kept (the viewer shows it at once). */
internal fun drawnPicture(messageId: String, item: AttachmentContent): Bitmap? = drawn.get(drawnKey(messageId, item))

/** A drawn picture's key: its message, item and preview (a new composition makes new previews, 2026-11-28). */
private fun drawnKey(messageId: String, item: AttachmentContent) = "$messageId/${item.id}/${item.previews.firstOrNull()?.blob.orEmpty()}"

/**
 * Pictures are read, decoded and handed to the GPU here, two at a time, never on the main thread (2026-11-28, hen:
 * "the scroll is jaggy, load images just before the scroll reveals them, on a background thread").
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val decoding = Dispatchers.Default.limitedParallelism(2)
private val loadScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + decoding)
private val loading = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Bitmap?>>()

/**
 * The picture of [itemId] in [messageId], decoded once however many ask (the picture on screen and the prefetcher). A
 * hardware bitmap is made from it on this background thread, so its upload to the GPU doesn't stall a frame the first
 * time it is drawn.
 */
private suspend fun loadPicture(messageId: String, item: AttachmentContent, preview: suspend (String, String) -> ByteArray?): Bitmap? {
    val itemId = item.id
    val key = drawnKey(messageId, item)
    drawn.get(key)?.let { return it }
    // Started only once it is in the map, so its own removal can't run before it is put there.
    val job = loading.computeIfAbsent(key) {
        loadScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val bytes = preview(messageId, itemId) ?: return@async null
                val decoded = runCatching { if (JxlCoder.isJXL(bytes)) JxlCoder.decode(bytes) else android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    ?: return@async null
                val ready = decoded.copy(Bitmap.Config.HARDWARE, false)?.also { decoded.recycle() } ?: decoded.also { it.prepareToDraw() }
                drawn.put(key, ready)
                ready
            } finally {
                loading.remove(key)
            }
        }
    }
    job.start()
    return job.await()
}

/** How many messages ahead in the scroll's direction, and behind it, have their pictures loaded before they show. */
private const val AHEAD = 8
private const val BEHIND = 3

/**
 * Loads the pictures of the messages about to scroll into view, in the direction the chat is scrolling, so each has its
 * bitmap before its row comes on screen. [list] is the chat's (upside down: item 0 is the newest), [messages] oldest
 * first, the items keyed by message id.
 */
@Composable
internal fun PrefetchPictures(list: androidx.compose.foundation.lazy.LazyListState, messages: List<TextMessage>, preview: suspend (String, String) -> ByteArray?) {
    val latest = androidx.compose.runtime.rememberUpdatedState(messages)
    androidx.compose.runtime.LaunchedEffect(list) {
        var lastFirst = -1
        var older = true
        androidx.compose.runtime.snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo
            Triple(list.firstVisibleItemIndex, visible.firstOrNull()?.key, visible.lastOrNull()?.key)
        }.collectLatest { (first, newestKey, oldestKey) ->
            if (lastFirst >= 0 && first != lastFirst) older = first > lastFirst
            lastFirst = first
            val all = latest.value
            val goingOlder = older
            // Which pictures, worked out off the main thread too (2026-11-28, hen: "move all logic to the background").
            withContext(decoding) {
                val newest = all.indexOfLast { it.id == newestKey }.takeIf { it >= 0 } ?: all.lastIndex
                val oldest = all.indexOfFirst { it.id == oldestKey }.takeIf { it >= 0 } ?: newest
                val from = (oldest - if (goingOlder) AHEAD else BEHIND).coerceAtLeast(0)
                val to = (newest + if (goingOlder) BEHIND else AHEAD).coerceAtMost(all.lastIndex)
                if (from > to) return@withContext
                // Nearest first, in the scroll's direction.
                val order = if (goingOlder) (oldest downTo from) + (newest..to) else (newest..to) + (oldest downTo from)
                for (i in order) {
                    val message = all.getOrNull(i) ?: continue
                    message.media?.items?.forEach { item ->
                        if (item.kind == MediaKind.IMAGE || item.kind == MediaKind.VIDEO) loadPicture(message.id, item, preview)
                    }
                }
            }
        }
    }
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
    /** Mine, sent: a double tap on its bottom half edits the caption, the caret at the character tapped (2026-11-28, hen). */
    startEdit: ((caret: Int) -> Unit)? = null,
    /** Mine, its caption being edited now: in place, in the bottom half, ✓ where the time was. */
    edit: com.chencohen.ptt.ui.contacts.Editing? = null,
    editChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit = {},
    editDone: (save: Boolean) -> Unit = {},
    editGone: () -> Unit = {},
    /** Mine, selected by a long press: Aa on each picture and video, to write the words above it (2026-11-28, hen). */
    texting: Boolean = false,
    setText: (itemId: String, text: String) -> Unit = { _, _ -> },
) {
    val media = message.media ?: return
    // The item whose words above it are being written now (after Aa), and what is written so far.
    var writing by remember(message.id) { mutableStateOf<String?>(null) }
    val mine = message.outgoing
    val failed = mine && message.status == MessageStatus.FAILED
    val time = remember(message.at) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at)) }
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
    @Composable
    fun Modifier.middle(): Modifier = when {
        look != null -> this.then(Modifier.myMiddleBubble(look, progress))
        media.quiet -> LibraryColors.Muted.copy(alpha = 0.6f).let { c -> this.drawBehind {
            val w = 1.dp.toPx()
            drawLine(c, androidx.compose.ui.geometry.Offset(w / 2, 0f), androidx.compose.ui.geometry.Offset(w / 2, size.height), w)
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width - w / 2, 0f), androidx.compose.ui.geometry.Offset(size.width - w / 2, size.height), w)
        } }
        else -> this.background(LibraryColors.Theirs)
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = side) {
        // The top half bubble: who, in a group; else when they were taken.
        val taken = takenAndWhere(media.items)
        Column(Modifier.padding(horizontal = 12.dp).width(com.chencohen.ptt.ui.contacts.bubbleMaxWidth()).half(top = true).padding(horizontal = 14.dp, vertical = if (author != null || taken != null) 6.dp else 5.dp)) {
            if (author != null) Text(author, color = if (com.chencohen.ptt.ui.contacts.LocalMemberHues.current.isEmpty()) LibraryColors.Accent else LibraryColors.OnTheirs, style = LibraryType.Small)
            if (taken != null) Text(taken, color = muted, style = LibraryType.Tiny, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        media.items.forEachIndexed { index, item ->
            // Said once on top for them all; above a picture only where its place or time changed from the one before,
            // in a strip of the bubble (2026-11-28, hen: "show less redundancy about time and location"; "no chip, make
            // the details above the image").
            val change = if (index > 0) changeFrom(media.items[index - 1], item) else null
            // Words written above this picture, in a strip of the bubble (2026-11-28, hen: "add text above any image").
            if (writing == item.id) {
                Box(Modifier.padding(horizontal = 12.dp).width(com.chencohen.ptt.ui.contacts.bubbleMaxWidth()).middle()) {
                    TextAbove(item.text, ink) { save, text ->
                        writing = null
                        if (save) setText(item.id, text)
                    }
                }
            } else if (item.text.isNotBlank()) {
                Box(Modifier.padding(horizontal = 12.dp).width(com.chencohen.ptt.ui.contacts.bubbleMaxWidth()).middle()) {
                    Text(item.text, color = ink, style = LibraryType.Message, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            // Its own place and time, right above it, under the words above it (2026-11-28, hen: "the date belongs to
            // the lower image, it should be below the text").
            if (change != null) Box(Modifier.padding(horizontal = 12.dp).width(com.chencohen.ptt.ui.contacts.bubbleMaxWidth()).middle()) {
                PictureNote(change, color = muted)
            } else if (index > 0 && writing != item.id && item.text.isBlank()) Spacer(Modifier.height(2.dp))
            when (item.kind) {
                MediaKind.IMAGE, MediaKind.VIDEO -> Picture(
                    message, item, preview, select = select,
                    // Aa writes above it; the selection ends so the keyboard and taps belong to the words.
                    words = if (texting && mine && !media.quiet) ({ writing = item.id; select?.invoke(); Unit }) else null,
                ) {
                    when {
                        selecting && select != null -> select()
                        failed -> retry(message.id)
                        else -> open(message.id, item.id)
                    }
                }
                else -> FileRow(item) { if (!selecting) open(message.id, item.id) }
            }
        }
        // Mine sent before the caption: the caption box under the chat is its bottom half while I write it.
        if (mine && media.quiet) return@Column
        if (edit != null) {
            CaptionEdit(edit, editChange, editDone, editGone)
            return@Column
        }
        // The bottom half bubble: the caption and the time.
        var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
        val captionPlace = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(2) }
        Column(
            Modifier.padding(horizontal = 12.dp).width(com.chencohen.ptt.ui.contacts.bubbleMaxWidth()).half(top = false)
                .then(if (failed) Modifier.clickable { retry(message.id) } else Modifier)
                .onGloballyPositioned { captionPlace[0] = it }
                .then(
                    // A double tap anywhere on the bottom half (as on a text bubble, 2026-11-24): on the caption the
                    // caret at that character, elsewhere at its end.
                    if (startEdit != null && !selecting) Modifier.pointerInput(message.id) {
                        detectTapGestures(
                            onLongPress = select?.let { s -> { _: androidx.compose.ui.geometry.Offset -> s() } },
                            onDoubleTap = { at ->
                                val half = captionPlace[0]
                                val text = captionPlace[1]
                                val l = layout
                                val caret = if (half != null && text != null && l != null && half.isAttached && text.isAttached) {
                                    val p = text.localPositionOf(half, at)
                                    if (p.x >= 0f && p.y >= 0f && p.x <= text.size.width && p.y <= text.size.height) l.getOffsetForPosition(p) else media.caption.length
                                } else media.caption.length
                                startEdit(caret)
                            },
                        )
                    } else Modifier,
                )
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 7.dp),
        ) {
            val live = liveEdit?.text
            if (live != null || media.caption.isNotBlank()) Text(
                live ?: media.caption, color = ink, style = LibraryType.Message, onTextLayout = { layout = it },
                modifier = Modifier.onGloballyPositioned { captionPlace[1] = it }
                    .then(if (live != null && liveEdit.selEnd >= 0) Modifier.liveCaret(layout, liveEdit.selStart, liveEdit.selEnd) else Modifier),
            )
            if (liveEdit != null || (!mine && media.quiet)) Text(stringResource(if (liveEdit != null && !media.quiet) R.string.chat_editing else R.string.chat_draft), color = muted, style = LibraryType.Tiny, modifier = Modifier.align(Alignment.End))
            else Text(
                if (failed) stringResource(R.string.chat_status_failed) + " · " + time else time,
                color = if (failed) LibraryColors.Warning else muted, style = LibraryType.Tiny, modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/**
 * My sent caption, edited in place (2026-11-28, hen: "double tap on message text to edit"): the bottom half turns to
 * the writing look (coral outline, open towards the pictures), the caption a field with the caret where it was tapped,
 * ✓ where the time was. ✓ saves (an empty caption takes it off); Back or a tap outside end it unchanged.
 */
@Composable
private fun CaptionEdit(edit: com.chencohen.ptt.ui.contacts.Editing, change: (androidx.compose.ui.text.input.TextFieldValue) -> Unit, done: (save: Boolean) -> Unit, gone: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var saving by remember(edit.id) { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(edit.id) {
        // They see it is being edited from the first moment.
        change(edit.value)
        runCatching { focus.requestFocus() }
    }
    androidx.compose.runtime.LaunchedEffect(edit.leaving) { if (edit.leaving && saving == null) saving = false }
    androidx.compose.runtime.LaunchedEffect(saving) {
        val save = saving ?: return@LaunchedEffect
        done(save)
        gone()
    }
    val coral = LibraryColors.Writing
    Column(
        Modifier.padding(horizontal = 12.dp).width(com.chencohen.ptt.ui.contacts.bubbleMaxWidth())
            .background(coral.copy(alpha = 0.08f), halfShape(false))
            .writingHalf(coral, top = false)
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 7.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = edit.value,
            onValueChange = { v ->
                if (v.text != edit.value.text) edit.changed = true
                change(v.copy(text = v.text.take(4_000)))
            },
            textStyle = LibraryType.Message.copy(color = LibraryColors.OnBackground),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(coral),
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        val save = stringResource(R.string.chat_edit_save)
        Box(
            Modifier.align(Alignment.End).padding(top = 6.dp).size(18.dp).clip(CircleShape).background(coral, CircleShape)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = save) { if (saving == null) saving = true },
            contentAlignment = Alignment.Center,
        ) { Icon(LibraryIcons.Check, save, tint = Color.White, modifier = Modifier.size(12.dp)) }
    }
}

private fun halfShape(top: Boolean) = if (top) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)

/**
 * The top half bubble's line (2026-11-28, hen: "make the first row with time span and locations of all included
 * images"): one picture's place and when it was taken ("Tel Aviv · Yesterday 18:40"); several pictures' places, each
 * once in the order taken (three, then "+2"), and the span from the first taken to the last ("Yesterday 18:40 – 21:05",
 * the day again when it changed). Places are named on this phone, in its own language, by Android's Geocoder from the
 * items' coordinates (they came end to end).
 */
@Composable
internal fun takenAndWhere(items: List<AttachmentContent>): String? {
    val first = items.firstOrNull() ?: return null
    if (items.size == 1) return takenLine(first)
    val places = items.map { placeOf(it) }.filterNotNull().distinct()
    val where = when {
        places.isEmpty() -> null
        places.size <= 3 -> places.joinToString(", ")
        else -> places.take(3).joinToString(", ") + " +" + (places.size - 3)
    }
    val timed = items.filter { it.takenAt > 0 }
    val earliest = timed.minByOrNull { it.takenAt }
    val latest = timed.maxByOrNull { it.takenAt }
    val span = when {
        earliest == null || latest == null -> null
        latest.takenAt - earliest.takenAt < CHANGE_MS -> dayAndClock(earliest)
        localDay(earliest) == localDay(latest) -> dayAndClock(earliest) + " – " + clockOf(latest)
        else -> dayAndClock(earliest) + " – " + dayAndClock(latest)
    }
    return listOfNotNull(where, span).joinToString(" · ").ifEmpty { null }
}

/**
 * What changed at [item] from [before], the picture above it: its place when that is another one, its time when 5
 * minutes or more passed (with the day when that changed too); null when neither did.
 */
@Composable
internal fun changeFrom(before: AttachmentContent, item: AttachmentContent): String? {
    val place = placeOf(item)?.takeIf { it != placeOf(before) }
    val time = when {
        item.takenAt <= 0 || before.takenAt <= 0 -> null
        kotlin.math.abs(item.takenAt - before.takenAt) < CHANGE_MS -> null
        localDay(item) == localDay(before) -> clockOf(item)
        else -> dayAndClock(item)
    }
    return listOfNotNull(place, time).joinToString(" · ").ifEmpty { null }
}

/** One picture's place and when it was taken ("Tel Aviv · Yesterday 18:40"). */
@Composable
internal fun takenLine(item: AttachmentContent): String? =
    listOfNotNull(placeOf(item), item.takenAt.takeIf { it > 0 }?.let { dayAndClock(item) }).joinToString(" · ").ifEmpty { null }

private const val CHANGE_MS = 5 * 60_000L

/** The place [item] was taken, named on this phone (cached); null when it has none or it can't be named now. */
@Composable
private fun placeOf(item: AttachmentContent): String? {
    if (!item.hasPlace) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    val place by produceState(placeNames[placeKey(item)], item.latE7, item.lonE7) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { placeName(context, item.latE7 / 1e7, item.lonE7 / 1e7) }?.also { placeNames[placeKey(item)] = it }
    }
    return place
}

/** Its time at the place it was taken ("18:40"). */
private fun clockOf(item: AttachmentContent): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(item.takenAt + item.takenOffsetMin * 60_000L))

private fun localDay(item: AttachmentContent): Long = (item.takenAt + item.takenOffsetMin * 60_000L) / 86_400_000L

/** "Yesterday 18:40", "Today 09:12", or the date and time further back. */
private fun dayAndClock(item: AttachmentContent): String {
    val local = item.takenAt + item.takenOffsetMin * 60_000L
    val day = android.text.format.DateUtils.getRelativeTimeSpanString(
        local - java.util.TimeZone.getDefault().getOffset(local), System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS,
    ).toString()
    return "$day ${clockOf(item)}"
}

private fun placeKey(item: AttachmentContent) = "${item.latE7 / 1000},${item.lonE7 / 1000}"

/** Place names looked up lately, by coordinates rounded to about 100 m. */
private val placeNames = java.util.concurrent.ConcurrentHashMap<String, String>()

/** The town (or the nearest named area) at [lat], [lon]; null without a network or a geocoder. */
private suspend fun placeName(context: android.content.Context, lat: Double, lon: Double): String? {
    if (!android.location.Geocoder.isPresent()) return null
    val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
    val address = kotlinx.coroutines.withTimeoutOrNull(8_000) {
        kotlinx.coroutines.suspendCancellableCoroutine<android.location.Address?> { done ->
            geocoder.getFromLocation(lat, lon, 1, object : android.location.Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<android.location.Address>) { if (done.isActive) done.resumeWith(Result.success(addresses.firstOrNull())) }
                override fun onError(errorMessage: String?) { if (done.isActive) done.resumeWith(Result.success(null)) }
            })
        }
    } ?: return null
    return address.locality ?: address.subLocality ?: address.subAdminArea ?: address.adminArea ?: address.countryName
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Picture(message: TextMessage, item: AttachmentContent, preview: suspend (String, String) -> ByteArray?, select: (() -> Unit)?, words: (() -> Unit)? = null, onClick: () -> Unit) {
    val key = "${message.id}/${item.id}"
    val drawnAs = drawnKey(message.id, item)
    // A new key (a new composition's preview) keeps the state, not its first value: the old picture shows until the
    // new one is decoded, then is replaced (2026-11-28, hen: "the cropped image in chat is not as the original crop").
    val bitmap by produceState(drawn.get(drawnAs), drawnAs) {
        drawn.get(drawnAs)?.let { value = it; return@produceState }
        loadPicture(message.id, item, preview)?.let { value = it }
    }
    // Its composition (2026-11-28, hen): the part of the original the sender kept, cut around their point of interest
    // where this box is another shape.
    val ratio = if (item.width > 0 && item.height > 0) (item.shownWidth.toFloat() / item.shownHeight).coerceIn(0.4f, 3f) else 1f
    val focus = item.crop?.focusInCrop
    val align = if (focus == null) Alignment.Center else androidx.compose.ui.BiasAbsoluteAlignment(focus.first * 2 - 1, focus.second * 2 - 1)
    val label = stringResource(if (item.kind == MediaKind.VIDEO) R.string.media_video else R.string.media_picture)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(LibraryColors.Raised)
            // Where it lies on screen: the viewer grows out of here and shrinks back into it.
            .onGloballyPositioned { if (it.isAttached) pictureBounds[key] = it.boundsInWindow() }.combinedClickable(onLongClick = select, onClick = onClick).semantics { contentDescription = label },
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), alignment = align, contentScale = ContentScale.Crop) }
        // A video with its clip plays in place, muted, once (2026-11-28, hen: autoplay muted on screen, no loop, an
        // unmute button); its first frame shows until the clip's does, and again with ▶ once it ended.
        var playing by remember(key) { mutableStateOf(false) }
        if (item.kind == MediaKind.VIDEO && item.clip != null) ClipInChat(message.id, item, onPlaying = { playing = it })
        if (item.kind == MediaKind.VIDEO && !playing) {
            // While its clip is made here (mine, in a draft or waiting to go), a ring round ▶ fills as it goes.
            val encodings = LocalEncoding.current
            val making by androidx.compose.runtime.remember(encodings, item.id) { androidx.compose.runtime.derivedStateOf { encodings.value[item.id] } }
            Box(Modifier.align(Alignment.Center).size(56.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Play, null, tint = Color.White, modifier = Modifier.size(28.dp))
                making?.let { done ->
                    val shown by androidx.compose.animation.core.animateFloatAsState(done, label = "clip")
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { shown }, modifier = Modifier.size(56.dp), color = LibraryColors.Writing, strokeWidth = 3.dp,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                }
            }
        }
        // Going up to the server (mine) or coming down from it (theirs): a small cloud (the server) in the end corner
        // with an arrow up or down, filling with colour from the bottom as it goes (2026-11-28, hen: "some icon";
        // mock transfer-icon.png, B).
        val transfers = LocalTransfers.current
        val transfer by androidx.compose.runtime.remember(transfers, item.id) { androidx.compose.runtime.derivedStateOf { transfers.value[item.id] } }
        transfer?.let { done ->
            val shown by androidx.compose.animation.core.animateFloatAsState(done, label = "transfer")
            val tint = if (message.outgoing) LibraryColors.Writing else LibraryColors.Accent
            Box(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).size(width = 44.dp, height = 34.dp).drawWithCache {
                    // The cloud in a 44 x 34 box, scaled to the drawn size.
                    val sx = size.width / 44f
                    val sy = size.height / 34f
                    val cloud = androidx.compose.ui.graphics.Path().apply {
                        moveTo(12f * sx, 30f * sy)
                        lineTo(34f * sx, 30f * sy)
                        cubicTo(39f * sx, 30f * sy, 42f * sx, 26f * sy, 42f * sx, 22f * sy)
                        cubicTo(42f * sx, 17.5f * sy, 38.5f * sx, 14f * sy, 34f * sx, 14f * sy)
                        cubicTo(32.5f * sx, 8f * sy, 27.5f * sx, 4f * sy, 22f * sx, 4f * sy)
                        cubicTo(17.5f * sx, 4f * sy, 14f * sx, 7f * sy, 13f * sx, 12f * sy)
                        cubicTo(7.5f * sx, 12.5f * sy, 3f * sx, 16f * sy, 3f * sx, 21f * sy)
                        cubicTo(3f * sx, 26f * sy, 7f * sx, 30f * sy, 12f * sx, 30f * sy)
                        close()
                    }
                    val line = 1.6.dp.toPx()
                    val arrow = 2.dp.toPx()
                    onDrawBehind {
                        drawPath(cloud, Color(0x8C000000))
                        val top = 30f * sy - (26f * sy) * shown
                        clipPath(cloud) { drawRect(tint.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, size.height - top)) }
                        drawPath(cloud, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(line))
                        val x = 22f * sx
                        val up = message.outgoing
                        val tip = if (up) 14f * sy else 26f * sy
                        val tail = if (up) 26f * sy else 14f * sy
                        val head = if (up) 4f * sy else -4f * sy
                        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(arrow, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                        drawLine(Color.White, androidx.compose.ui.geometry.Offset(x, tail), androidx.compose.ui.geometry.Offset(x, tip), arrow, androidx.compose.ui.graphics.StrokeCap.Round)
                        drawPath(androidx.compose.ui.graphics.Path().apply {
                            moveTo(x - 4f * sx, tip + head)
                            lineTo(x, tip)
                            lineTo(x + 4f * sx, tip + head)
                        }, Color.White, style = stroke)
                    }
                },
            )
        }
        // Aa: write the words above it (my message, selected).
        words?.let { write ->
            val label = stringResource(R.string.media_text_above)
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp).size(36.dp).clip(CircleShape).background(Color(0x99000000))
                    .clickable(onClickLabel = label, onClick = write).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Text("Aa", color = Color.White, style = LibraryType.Tiny) }
        }
        // A video's length (when it was taken is on the top half bubble, the time it was sent on the bottom one).
        item.durationMs.takeIf { it > 0 }?.let { Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { Chip(com.chencohen.ptt.ui.talk.clipLength(it)) } }
    }
}

/** Each video of mine whose clip is being made now (0 to 1), by item id: MediaSharing.encoding. */
internal val LocalEncoding = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Map<String, Float>>> { com.chencohen.ptt.ui.contacts.NoProgress }

/** A video's clip, opened (MediaSharing.clip); null: none, or can't be had now. */
internal val LocalClips = androidx.compose.runtime.staticCompositionLocalOf<suspend (messageId: String, itemId: String) -> ByteArray?> { { _, _ -> null } }

/** Whether the clips in the chat play with their sound (one tap on the speaker turns them all on, until the app closes). */
private val clipSound = mutableStateOf(false)

/**
 * [item]'s clip playing in place, muted unless the speaker was tapped, from its first frame to its end, once. Over the
 * picture only once its first frame is drawn; [onPlaying] says while it is on.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ClipInChat(messageId: String, item: AttachmentContent, onPlaying: (Boolean) -> Unit) {
    val clips = LocalClips.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val bytes by produceState<ByteArray?>(null, messageId, item.id) { value = clips(messageId, item.id) }
    val clip = bytes ?: return
    var shown by remember(clip) { mutableStateOf(false) }
    var ended by remember(clip) { mutableStateOf(false) }
    val player = remember(clip) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory { androidx.media3.datasource.ByteArrayDataSource(clip) }
                .createMediaSource(androidx.media3.common.MediaItem.fromUri("talk://clip/$messageId/${item.id}"))
            setMediaSource(source)
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            volume = if (clipSound.value) 1f else 0f
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { shown = true }
            override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_ENDED) ended = true }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val sound by clipSound
    androidx.compose.runtime.LaunchedEffect(sound) { player.volume = if (sound) 1f else 0f }
    val on = shown && !ended
    androidx.compose.runtime.LaunchedEffect(on) { onPlaying(on) }
    if (ended) return
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { android.view.TextureView(it).also { view -> player.setVideoTextureView(view) } },
        modifier = Modifier.fillMaxSize().alpha(if (shown) 1f else 0f),
    )
    if (on) {
        val label = stringResource(if (sound) R.string.media_clip_mute else R.string.media_clip_unmute)
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Color(0x99000000))
                .clickable(onClickLabel = label) { clipSound.value = !sound }.semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (sound) LibraryIcons.Speaker else LibraryIcons.SpeakerOff, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The words above a picture or video of my sent message, written in place (2026-11-28, hen): the field with the caret
 * at their end, ✓ saves (empty takes them off), ✕ leaves them as they were.
 */
@Composable
private fun TextAbove(was: String, ink: Color, done: (save: Boolean, text: String) -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(was, androidx.compose.ui.text.TextRange(was.length))) }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (value.text.isEmpty()) Text(stringResource(R.string.media_text_above_hint), color = ink.copy(alpha = 0.6f), style = LibraryType.Message)
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = { value = it.copy(text = it.text.take(2_000)) },
                textStyle = LibraryType.Message.copy(color = ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(ink), maxLines = 6,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        val cancel = stringResource(R.string.media_draft_remove)
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = cancel) { done(false, was) }, contentAlignment = Alignment.Center) {
            Icon(LibraryIcons.Close, cancel, tint = ink, modifier = Modifier.size(16.dp))
        }
        val save = stringResource(R.string.chat_edit_save)
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(LibraryColors.Writing, CircleShape).clickable(onClickLabel = save) { done(true, value.text) },
            contentAlignment = Alignment.Center,
        ) { Icon(LibraryIcons.Check, save, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

/** A strip's line above a picture saying where or when it was taken, when that changed from the picture before. */
@Composable
internal fun PictureNote(text: String, color: Color = LibraryColors.Muted) {
    Text(
        text, color = color, style = LibraryType.Tiny, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** Each item's preview going up or coming down now (0 to 1), by item id: MediaSharing.transfers. */
internal val LocalTransfers = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Map<String, Float>>> { com.chencohen.ptt.ui.contacts.NoProgress }

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
