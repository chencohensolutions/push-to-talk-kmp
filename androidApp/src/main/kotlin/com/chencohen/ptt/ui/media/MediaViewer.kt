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
import kotlinx.coroutines.flow.first
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
    val allowPhotos = rememberAllPhotos()
    BackHandler { if (info) info = false else close() }
    var box by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // Each picture's zoom (Journal's, ViewerZoom.kt); one swiped away goes back to fitted.
    val zoomScope = androidx.compose.runtime.rememberCoroutineScope()
    val zooms = remember { mutableMapOf<String, PhotoZoom>() }
    fun zoomOf(id: String) = zooms.getOrPut(id) { PhotoZoom(zoomScope, keptZooms["${message.id}/$id"]) }
    LaunchedEffect(pager.currentPage) { val now = items.getOrNull(pager.currentPage)?.id; zooms.forEach { (id, z) -> if (id != now) z.reset() } }
    // Leaving a picture (a swipe, or closing): where it was zoomed is kept; fitted forgets it.
    val currentId = items.getOrNull(pager.currentPage)?.id
    DisposableEffect(currentId) {
        onDispose {
            val id = currentId ?: return@onDispose
            val part = zooms[id]?.zoomedPart
            if (part != null) keptZooms["${message.id}/$id"] = part else keptZooms.remove("${message.id}/$id")
        }
    }
    // A picture zoomed last time opens as usual, then glides there once the opening move and any swipe have settled.
    LaunchedEffect(currentId) {
        val id = currentId ?: return@LaunchedEffect
        val zoom = zoomOf(id)
        if (!zoom.hasSaved) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { shown.value >= 1f && !pager.isScrollInProgress }.first { it }
        zoom.resume()
    }
    // A tap hides the bars and the system's, so the picture has the whole screen; another brings them back (as Journal).
    var chrome by remember { mutableStateOf(true) }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(chrome) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    val bar by androidx.compose.animation.core.animateFloatAsState(if (chrome) 1f else 0f, androidx.compose.animation.core.tween(200), label = "viewer bars")
    val zoomedNow = items.getOrNull(pager.currentPage)?.let { zooms[it.id]?.isZoomed } == true
    val p = shown.value
    Box(Modifier.fillMaxSize().onGloballyPositioned { box = it.boundsInWindow() }.background(Color.Black.copy(alpha = p))) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }, userScrollEnabled = p >= 1f && !zoomedNow) { page ->
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
                ItemPage(message, item, originals["${message.id}/${item.id}"], preview, zoomOf(item.id), tap = { chrome = !chrome })
            }
        }
        val item = items.getOrNull(pager.currentPage)
        if (bar > 0f) Row(
            Modifier.fillMaxWidth().graphicsLayer { alpha = p * bar }.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
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
        if (item != null && bar > 0f) {
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp).graphicsLayer { alpha = p * bar }) {
                OriginalPill(item, originals["${message.id}/${item.id}"], again = { open(message.id, item.id) }, cancel = { cancel(message.id, item.id) }, allow = { allowPhotos { open(message.id, item.id) } })
            }
        }
        // Where the screen is on the whole picture while zoomed (Journal's minimap): shown with the bars, or while moving.
        val zoomHere = item?.let { zooms[it.id] }
        val small = zoomHere?.picture
        val part = if (zoomHere?.isZoomed == true) zoomHere.visibleRect(0f) else null
        androidx.compose.animation.AnimatedVisibility(
            small != null && part != null && !info,
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 12.dp),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            if (small != null && part != null) ZoomMinimap(small, part, sharp = zoomHere?.atOriginalQuality == true)
        }
        if (info && item != null) MediaInfoSheet(message, item, originals["${message.id}/${item.id}"], keeping, close = { info = false })
    }
}

@Composable
private fun ItemPage(message: TextMessage, item: AttachmentContent, state: OriginalState?, preview: suspend (String, String) -> ByteArray?, zoom: PhotoZoom, tap: () -> Unit) {
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
                value = withContext(Dispatchers.IO) { decodeOriginal(context, path, OPEN_SIDE) }
            }
            // Zoomed in for the first time: the original at its own pixels (as many as can be drawn), as Journal does.
            var wantSharp by remember(item.id) { mutableStateOf(false) }
            LaunchedEffect(zoom.isZoomed) { if (zoom.isZoomed) wantSharp = true }
            val sharp by produceState<Bitmap?>(null, ready?.path, wantSharp) {
                val path = ready?.path ?: return@produceState
                if (wantSharp) value = withContext(Dispatchers.IO) { decodeOriginal(context, path, zoomSide(item)) }
            }
            val image = sharp ?: full ?: small
            val whole = small ?: full
            val minimapPicture = remember(whole) { whole?.asImageBitmap() }
            androidx.compose.runtime.SideEffect { zoom.picture = minimapPicture }
            val shownSide = image?.let { maxOf(it.width, it.height) } ?: 0
            val detail = rememberDetailLayer(item, zoom, shownSide) { ready?.path?.let { openOriginalRegions(context, it) } }
            ReportQuality(zoom, item, sharp = sharp != null, shownSide = shownSide, detail = detail)
            val ratio = when {
                item.width > 0 && item.height > 0 -> item.width.toFloat() / item.height
                image != null -> image.width.toFloat() / image.height
                else -> 1f
            }
            val pixelSide = maxOf(item.width, item.height).takeIf { it > 0 } ?: image?.let { maxOf(it.width, it.height) } ?: 0
            var screen by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
            androidx.compose.runtime.SideEffect { if (!screen.isEmpty()) zoom.layout(screen, ratio, pixelSide) }
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged { screen = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
                    .zoomGestures { zoom }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { tap() }, onDoubleTap = { at -> zoom.step(at - Offset(size.width / 2f, size.height / 2f)) })
                    },
            ) {
                image?.let { Image(remember(it) { it.asImageBitmap() }, null, Modifier.zoomedLayout(zoom, ratio).detailOver(detail), contentScale = ContentScale.FillBounds) }
            }
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
private fun OriginalPill(item: AttachmentContent, state: OriginalState?, again: () -> Unit, cancel: () -> Unit, allow: () -> Unit) {
    val text = when (state) {
        null, is OriginalState.Ready -> return
        OriginalState.Asking -> stringResource(R.string.media_original_asking)
        is OriginalState.Coming -> stringResource(R.string.media_original_coming, mb(state.done), mb(state.total))
        OriginalState.Gone -> stringResource(R.string.media_original_gone)
        OriginalState.Changed -> stringResource(R.string.media_original_changed)
        OriginalState.Failed -> stringResource(R.string.media_original_failed)
        OriginalState.Unreadable -> stringResource(R.string.media_original_unreadable)
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
            } else if (state == OriginalState.Unreadable) {
                Text(
                    stringResource(R.string.media_original_allow), color = Color(0xFFFF8A80), style = LibraryType.Tiny,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = allow).padding(horizontal = 10.dp, vertical = 8.dp),
                )
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
/** The original, its longer side at most [side], in graphics memory (drawn without a copy). */
private fun decodeOriginal(context: Context, path: String, side: Int): Bitmap? = runCatching {
    val source = if (path.startsWith("content:")) ImageDecoder.createSource(context.contentResolver, Uri.parse(path)) else ImageDecoder.createSource(File(path))
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        val long = maxOf(info.size.width, info.size.height)
        if (long > side) {
            val scale = side.toFloat() / long
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }
}.getOrNull()

/** The longer side an original opens at (sharp on any screen); zooming in decodes it again at [zoomSide]. */
private const val OPEN_SIDE = 4096

/** Most pixels of an original decoded for zooming (Journal's): under Android's limit for drawing a bitmap. */
private const val ZOOM_MAX_PIXELS = 24_000_000L

/** Longest side of an original decoded for zooming (larger textures fail on some GPUs). */
private const val ZOOM_MAX_SIDE = 8192

/** The longer side [item]'s original is decoded at for zooming: its own, unless that is too many pixels to draw. */
private fun zoomSide(item: AttachmentContent): Int {
    val pixels = item.width.toLong() * item.height
    val longer = maxOf(item.width, item.height)
    if (pixels <= 0) return ZOOM_MAX_SIDE
    val side = if (pixels <= ZOOM_MAX_PIXELS) longer else (longer * kotlin.math.sqrt(ZOOM_MAX_PIXELS.toDouble() / pixels)).toInt()
    return minOf(side, ZOOM_MAX_SIDE)
}

/** A file opened in an app that reads it: the one on this phone where it is, or the copy that came (FileProvider). */
private fun openFile(context: Context, ready: OriginalState.Ready, item: AttachmentContent) {
    val uri = if (ready.local) Uri.parse(ready.path) else FileProvider.getUriForFile(context, "${context.packageName}.calls", File(ready.path))
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, item.name)) }
}

/** The photo permissions that let Talk read again the pictures and videos it shared (2026-11-28, hen: all photos). */
internal val ALL_PHOTOS = arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.ACCESS_MEDIA_LOCATION)

/** Whether Talk may read all the phone's pictures and videos (not only the ones selected). */
internal fun hasAllPhotos(context: Context): Boolean = ALL_PHOTOS.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

/**
 * Asks for access to all photos, then calls back; when Android no longer asks (denied before), opens Talk's app settings,
 * where Photos and videos can be set to Allow all.
 */
@Composable
internal fun rememberAllPhotos(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        val then = after
        after = null
        if (hasAllPhotos(context)) then?.invoke()
        else runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    return { then ->
        if (hasAllPhotos(context)) then() else {
            after = then
            launcher.launch(ALL_PHOTOS)
        }
    }
}
