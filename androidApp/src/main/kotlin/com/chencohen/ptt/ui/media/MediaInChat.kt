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
private suspend fun loadPicture(messageId: String, itemId: String, preview: suspend (String, String) -> ByteArray?): Bitmap? {
    val key = "$messageId/$itemId"
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
            val newest = all.indexOfLast { it.id == newestKey }.takeIf { it >= 0 } ?: all.lastIndex
            val oldest = all.indexOfFirst { it.id == oldestKey }.takeIf { it >= 0 } ?: newest
            val from = (oldest - if (older) AHEAD else BEHIND).coerceAtLeast(0)
            val to = (newest + if (older) BEHIND else AHEAD).coerceAtMost(all.lastIndex)
            if (from > to) return@collectLatest
            // Nearest first, in the scroll's direction.
            val order = if (older) (oldest downTo from) + (newest..to) else (newest..to) + (oldest downTo from)
            for (i in order) {
                val message = all.getOrNull(i) ?: continue
                message.media?.items?.forEach { item ->
                    if (item.kind == MediaKind.IMAGE || item.kind == MediaKind.VIDEO) loadPicture(message.id, item.id, preview)
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
            // Between the pictures only: the half bubbles touch the first and the last (2026-11-28, hen).
            if (index > 0) {
                val note = changeOver(item, media.items[index - 1])
                // Where or when the next was taken, in a strip of the bubble between them, so the message reads as one
                // long bubble with the pictures in it (2026-11-28, hen); else a thin gap.
                if (note != null) {
                    Box(Modifier.padding(horizontal = 12.dp).width(com.chencohen.ptt.ui.contacts.bubbleMaxWidth()).middle()) { PictureNote(note, color = muted) }
                } else Spacer(Modifier.height(2.dp))
            }
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
 * Where and when the pictures were taken, for the top half bubble (2026-11-28, hen: "add date taken and location data
 * in the top of the bubble"): "Tel Aviv · Yesterday 18:40". The place is named on this phone, in its own language, by
 * Android's Geocoder from the first item's coordinates (they came end to end); either part alone when only it is known.
 */
@Composable
internal fun takenAndWhere(items: List<AttachmentContent>): String? {
    val first = items.firstOrNull() ?: return null
    return listOfNotNull(placeOf(first), first.takenAt.takeIf { it > 0 }?.let { dayAndClock(first) }).joinToString(" · ").ifEmpty { null }
}

/**
 * Over a picture after the first, when it differs from the one before (2026-11-28, hen: "if image taken after 5
 * minutes than the previous image show date taken above the image, if location is different than previous images,
 * show location"): its place when that differs, its time when more than five minutes apart (the day too when that
 * changed). Null: nothing new.
 */
@Composable
internal fun changeOver(item: AttachmentContent, before: AttachmentContent): String? {
    val place = placeOf(item)
    val newPlace = place != null && place != placeOf(before)
    val apart = item.takenAt > 0 && before.takenAt > 0 && kotlin.math.abs(item.takenAt - before.takenAt) > CHANGE_MS
    if (!newPlace && !apart) return null
    val time = if (!apart) null else if (localDay(item) == localDay(before)) clockOf(item) else dayAndClock(item)
    return listOfNotNull(place.takeIf { newPlace }, time).joinToString(" · ")
}

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
internal fun Picture(message: TextMessage, item: AttachmentContent, preview: suspend (String, String) -> ByteArray?, select: (() -> Unit)?, onClick: () -> Unit) {
    val key = "${message.id}/${item.id}"
    val bitmap by produceState(drawn.get(key), key) {
        if (value != null) return@produceState
        value = loadPicture(message.id, item.id, preview)
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
        // Going up to the server (mine) or coming down from it (theirs): a small cloud (the server) in the end corner
        // with an arrow up or down, filling with colour from the bottom as it goes (2026-11-28, hen: "some icon";
        // mock transfer-icon.png, B).
        LocalTransfers.current[item.id]?.let { done ->
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
        // A video's length (when it was taken is on the top half bubble, the time it was sent on the bottom one).
        item.durationMs.takeIf { it > 0 }?.let { Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { Chip(com.chencohen.ptt.ui.talk.clipLength(it)) } }
    }
}

/** A line over a picture saying where or when it was taken, when that changed from the one before. */
@Composable
internal fun PictureNote(text: String, color: Color = LibraryColors.Muted) {
    Text(
        text, color = color, style = LibraryType.Tiny, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
    )
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
