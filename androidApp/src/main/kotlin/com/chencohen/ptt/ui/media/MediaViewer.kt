package com.chencohen.ptt.ui.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.awxkee.jxlcoder.JxlCoder
import com.chencohen.ptt.R
import com.chencohen.ptt.media.OriginalState
import com.chencohen.ptt.messaging.AttachmentContent
import com.chencohen.ptt.messaging.MediaKind
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * A shared item full screen (2026-11-28, hen; docs/screens/viewer.md): the preview at once, and the untouched original
 * fetched quietly from the phone that shared it, with a small pill saying how far it got and ✕ to stop. A picture
 * switches to the original when it is here; a video plays the original; a file opens in an app that reads it.
 * Several items in one message: swipe between them.
 */
@Composable
internal fun MediaViewer(
    message: TextMessage,
    startItem: String,
    sender: String,
    originals: Map<String, OriginalState>,
    preview: suspend (messageId: String, itemId: String) -> ByteArray?,
    open: (messageId: String, itemId: String) -> Unit,
    cancel: (messageId: String, itemId: String) -> Unit,
    /** Where the item is kept, here and on the others' phones (Info). */
    keeping: suspend (messageId: String, itemId: String) -> com.chencohen.ptt.media.MediaKeeping?,
    back: () -> Unit,
) {
    val media = message.media ?: return back()
    val items = media.items.filter { it.kind == MediaKind.IMAGE || it.kind == MediaKind.VIDEO || it.id == startItem }
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.id == startItem }.coerceAtLeast(0)) { items.size }
    // Out of the picture's place in the chat and back into it (2026-11-28, hen: "seamless transition between chat and
    // the image"): the picture moves and grows from where it lies in the chat to the screen as the black comes up,
    // in one move; closing, the same back to where it lies then.
    val shown = remember { androidx.compose.animation.core.Animatable(if (pictureBounds["${message.id}/$startItem"] != null) 0f else 1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val ease = androidx.compose.animation.core.FastOutSlowInEasing
    LaunchedEffect(Unit) { shown.animateTo(1f, androidx.compose.animation.core.tween(300, easing = ease)) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            val item = items.getOrNull(pager.currentPage)
            if (item != null && pictureBounds["${message.id}/${item.id}"] != null) shown.animateTo(0f, androidx.compose.animation.core.tween(260, easing = ease))
            back()
        }
    }
    var info by remember { mutableStateOf(false) }
    BackHandler { if (info) info = false else close() }
    var box by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val p = shown.value
    Box(Modifier.fillMaxSize().onGloballyPositioned { box = it.boundsInWindow() }.background(Color.Black.copy(alpha = p))) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }, userScrollEnabled = p >= 1f) { page ->
            val item = items[page]
            // Each item on screen is asked for: the original comes while the preview shows.
            LaunchedEffect(item.id) { open(message.id, item.id) }
            val current = page == pager.currentPage
            Box(Modifier.fillMaxSize().graphicsLayer {
                val at = if (current) p else 1f
                val from = pictureBounds["${message.id}/${item.id}"]
                val window = box
                if (at >= 1f || from == null || window == null) {
                    alpha = if (from == null && current) p else 1f
                    return@graphicsLayer
                }
                // The picture's place on screen when it fits, and where it lies in the chat.
                val ratio = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1f
                val fitW = minOf(size.width, size.height * ratio)
                val fitH = fitW / ratio
                val centre = androidx.compose.ui.geometry.Offset(window.left + size.width / 2, window.top + size.height / 2)
                fun lerp(a: Float, b: Float) = a + (b - a) * at
                scaleX = lerp(from.width / fitW, 1f)
                scaleY = lerp(from.height / fitH, 1f)
                translationX = lerp(from.center.x - centre.x, 0f)
                translationY = lerp(from.center.y - centre.y, 0f)
            }) {
                ItemPage(message, item, originals["${message.id}/${item.id}"], preview)
            }
        }
        val item = items.getOrNull(pager.currentPage)
        Row(
            Modifier.fillMaxWidth().graphicsLayer { alpha = p }.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = ::close), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Back, stringResource(R.string.media_back), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(sender, color = Color.White, style = LibraryType.Body)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(message.at)), color = Color(0xCCFFFFFF), style = LibraryType.Tiny)
            }
            if (items.size > 1) Text("${pager.currentPage + 1} / ${items.size}", color = Color.White, style = LibraryType.Tiny, modifier = Modifier.padding(end = 4.dp))
            // Info (2026-11-28, hen): the original's EXIF, what this phone keeps of it, and who holds what.
            if (item != null) Box(Modifier.size(48.dp).clip(CircleShape).clickable { info = true }, contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Info, stringResource(R.string.media_info), tint = Color.White)
            }
        }
        if (item != null) {
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp).graphicsLayer { alpha = p }) {
                OriginalPill(item, originals["${message.id}/${item.id}"], again = { open(message.id, item.id) }, cancel = { cancel(message.id, item.id) })
            }
        }
        if (info && item != null) MediaInfoSheet(message, item, originals["${message.id}/${item.id}"], keeping, close = { info = false })
    }
}

@Composable
private fun ItemPage(message: TextMessage, item: AttachmentContent, state: OriginalState?, preview: suspend (String, String) -> ByteArray?) {
    val context = LocalContext.current
    val ready = state as? OriginalState.Ready
    // The picture the chat drew, at once (so it is there as it flies in), else the preview read.
    val small by produceState<Bitmap?>(drawnPicture(message.id, item.id), message.id, item.id) {
        if (value != null) return@produceState
        val bytes = preview(message.id, item.id) ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { if (JxlCoder.isJXL(bytes)) JxlCoder.decode(bytes) else android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }
    when {
        item.kind == MediaKind.VIDEO && ready != null -> Player(ready)
        item.kind == MediaKind.IMAGE -> {
            val full by produceState<Bitmap?>(null, ready?.path) {
                val path = ready?.path ?: return@produceState
                value = withContext(Dispatchers.IO) { decodeOriginal(context, path) }
            }
            Zoomable { (full ?: small)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }
        }
        item.kind == MediaKind.VIDEO -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            small?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        else -> {
            // A file: opened in an app that reads it once it is here.
            LaunchedEffect(ready?.path) { ready?.let { openFile(context, it, item) } }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(item.name, color = Color.White, style = LibraryType.Body, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

/** A picture that pinches and pans; a double tap zooms in or back. */
@Composable
private fun Zoomable(content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) { content() }
}

@Composable
private fun Player(ready: OriginalState.Ready) {
    val context = LocalContext.current
    val player = remember(ready.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uriOf(ready.path)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().clickable { player.playWhenReady = !player.playWhenReady }, contentAlignment = Alignment.Center) {
        AndroidView(factory = { android.view.SurfaceView(it).also { view -> player.setVideoSurfaceView(view) } }, modifier = Modifier.fillMaxSize())
    }
}

/** How far the original got, at the bottom: "Getting the original · 3 of 8 MB" with ✕, or why it didn't come. */
@Composable
private fun OriginalPill(item: AttachmentContent, state: OriginalState?, again: () -> Unit, cancel: () -> Unit) {
    val text = when (state) {
        null, is OriginalState.Ready -> return
        OriginalState.Asking -> stringResource(R.string.media_original_asking)
        is OriginalState.Coming -> stringResource(R.string.media_original_coming, mb(state.done), mb(state.total))
        OriginalState.Gone -> stringResource(R.string.media_original_gone)
        OriginalState.Changed -> stringResource(R.string.media_original_changed)
        OriginalState.Failed -> stringResource(R.string.media_original_failed)
    }
    val busy = state == OriginalState.Asking || state is OriginalState.Coming
    Column(
        Modifier.width(300.dp).background(Color(0xCC1B1F20), RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Color.White, style = LibraryType.Tiny, modifier = Modifier.weight(1f))
            if (busy) {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = cancel), contentAlignment = Alignment.Center) {
                    Icon(LibraryIcons.Close, stringResource(R.string.media_original_stop), tint = Color.White, modifier = Modifier.size(18.dp))
                }
            } else if (state == OriginalState.Failed) {
                Text(
                    stringResource(R.string.media_original_again), color = Color(0xFFFF8A80), style = LibraryType.Tiny,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = again).padding(horizontal = 10.dp, vertical = 8.dp),
                )
            } else {
                Spacer(Modifier.height(36.dp))
            }
        }
        if (state is OriginalState.Coming && state.total > 0) {
            Box(Modifier.padding(end = 10.dp).fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth((state.done.toFloat() / state.total).coerceIn(0f, 1f)).height(3.dp).background(Color(0xFFE5484D), RoundedCornerShape(2.dp)))
            }
        }
    }
}

private fun mb(bytes: Long): String = if (bytes < 1_000_000) "%.1f".format(bytes / 1_000_000.0) else (bytes / 1_000_000).toString()

private fun uriOf(path: String): Uri = if (path.startsWith("content:") || path.startsWith("file:")) Uri.parse(path) else Uri.fromFile(File(path))

/** The original picture, upright, no bigger than 4096 on its long side (a 200 MP photo would not fit in memory). */
private fun decodeOriginal(context: Context, path: String): Bitmap? = runCatching {
    val source = if (path.startsWith("content:")) ImageDecoder.createSource(context.contentResolver, Uri.parse(path)) else ImageDecoder.createSource(File(path))
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val long = maxOf(info.size.width, info.size.height)
        if (long > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / long
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }
}.getOrNull()

private const val MAX_SIDE = 4096

/** A file opened in an app that reads it: the one on this phone where it is, or the copy that came (FileProvider). */
private fun openFile(context: Context, ready: OriginalState.Ready, item: AttachmentContent) {
    val uri = if (ready.local) Uri.parse(ready.path) else FileProvider.getUriForFile(context, "${context.packageName}.calls", File(ready.path))
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, item.name)) }
}
