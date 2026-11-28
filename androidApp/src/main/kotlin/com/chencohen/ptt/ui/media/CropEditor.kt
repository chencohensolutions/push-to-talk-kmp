package com.chencohen.ptt.ui.media

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.awxkee.jxlcoder.JxlCoder
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.AttachmentContent
import com.chencohen.ptt.messaging.CropContent
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** A picture of my message the crop editor works on: [item] of message [messageId], its original at [source]. */
class CropTarget(val messageId: String, val item: AttachmentContent, val source: String?)

/**
 * Crop and focus (2026-11-28, hen: "pick the point of interest of every image i send ... when composing an image, by
 * default its the full width of the image and the top and bottom border can be trimmed"; "interact with no text, only
 * icons"; mocks: the Picture crop and focus design). The picture with a frame across it, the part outside dimmed: drag the
 * frame's top or bottom line (from anywhere near it) to trim, pinch to zoom in so the sides are trimmed too, drag with
 * one finger to move the picture under the frame, tap to set the point of interest (2026-11-28, hen: "i want to be
 * able to move the lines"; "zoom the image so also part of the sides will get cropped"). Narrower than 1080 pixels of
 * the original, the frame turns red and says how many are left (hen: "i want to know when i zooming pass of 1080p").
 * The reset icon brings the whole picture back ("remove the ratio buttons, add reset button"). With several pictures, a filmstrip picks one (a dot on those set), and the copy icon gives them all this one's
 * crop. The check gives [done] each changed picture's crop (null: the whole picture).
 */
@Composable
internal fun CropEditor(
    targets: List<CropTarget>,
    start: Int,
    preview: suspend (String, String) -> ByteArray?,
    done: (Map<Pair<String, String>, CropContent?>) -> Unit,
    close: () -> Unit,
) {
    if (targets.isEmpty()) return close()
    val crops = remember(targets) { mutableStateListOf<CropContent>().apply { targets.forEach { add(it.item.crop ?: CropContent()) } } }
    var index by remember(targets) { mutableIntStateOf(start.coerceIn(0, targets.lastIndex)) }
    BackHandler(onBack = close)
    val target = targets[index]
    val crop = crops[index]
    fun set(c: CropContent) {
        crops[index] = c
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF111111)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(LibraryIcons.Close, stringResource(R.string.crop_close), onClick = close)
            Spacer(Modifier.weight(1f))
            if (targets.size > 1) Text("${index + 1} / ${targets.size}", color = Color(0xFFB9B4AE), style = LibraryType.Caption)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }
        // A reset brings the frame back to where the whole picture shows.
        var resets by remember(targets) { mutableIntStateOf(0) }
        CropArea(target, crop, resets, preview, Modifier.weight(1f).fillMaxWidth(), ::set)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
            IconCircle(LibraryIcons.Expand, stringResource(R.string.crop_whole), outlined = true, onClick = {
                set(CropContent())
                resets++
            })
        }
        Spacer(Modifier.height(12.dp))
        if (targets.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            targets.forEachIndexed { i, t ->
                Thumb(t, preview, selected = i == index, set = crops[i].clean() != null, label = stringResource(R.string.crop_picture, i + 1)) { index = i }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (targets.size > 1) IconCircle(LibraryIcons.Copy, stringResource(R.string.crop_same_for_all), outlined = true, onClick = {
                // The same trim, as parts of each picture's height, and the same point.
                for (i in crops.indices) crops[i] = crop
            })
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(LibraryColors.Writing).clickable {
                    val changed = targets.indices.mapNotNull { i ->
                        val now = crops[i].clean()
                        if (now == targets[i].item.crop) null else (targets[i].messageId to targets[i].item.id) to now
                    }.toMap()
                    done(changed)
                },
                contentAlignment = Alignment.Center,
            ) { Icon(LibraryIcons.Check, stringResource(R.string.crop_done), tint = Color.White, modifier = Modifier.size(24.dp)) }
        }
    }
}

/** A preview this wide is what a 1080p screen shows; kept narrower than this, the picture is enlarged there. */
private const val SHARP_WIDTH = 1080

/** Screen widths the size line names: the widest of these the kept part fills without being enlarged. */
private val SCREENS = listOf(1080, 1440, 2160, 2880, 4320)

private enum class Drag { TOP, BOTTOM, MOVE }

@Composable
private fun CropArea(target: CropTarget, crop: CropContent, resets: Int, preview: suspend (String, String) -> ByteArray?, modifier: Modifier, set: (CropContent) -> Unit) {
    val context = LocalContext.current
    val side = with(LocalDensity.current) { maxOf(LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.screenHeightDp).dp.roundToPx() }
    // The original as it is on this phone; until it is read (or when it can't be), the preview the chat shows.
    val bitmap by produceState<Bitmap?>(null, target.source, target.item.id) {
        value = withContext(Dispatchers.IO) {
            target.source?.let { decodeOriginal(context, it, side) } ?: preview(target.messageId, target.item.id)?.let { bytes ->
                runCatching { if (JxlCoder.isJXL(bytes)) JxlCoder.decode(bytes) else android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }
        }
    }
    val haptics = LocalHapticFeedback.current
    val current = androidx.compose.runtime.rememberUpdatedState(crop)
    BoxWithConstraints(modifier.clipToBounds()) {
        val item = target.item
        val ratio = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else bitmap?.let { it.width.toFloat() / it.height } ?: 1f
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        // The frame's width: the whole picture's when it fits, which zooming in spreads over the kept part.
        val frameW = minOf(boxW, boxH * ratio)
        val frameLeft = (boxW - frameW) / 2
        val whole = CropContent.WHOLE.toFloat()
        fun drawnW(c: CropContent) = frameW * whole / c.keptWidth.coerceAtLeast(1)
        fun drawnH(c: CropContent) = drawnW(c) / ratio
        fun frameH(c: CropContent) = drawnH(c) * c.kept / whole
        // Where the frame's top is on screen: it moves only when its lines are dragged, so the picture stays put then,
        // and moves under it when dragged, pinched or reset.
        var frameTop by remember(target.item.id, resets, boxH) {
            androidx.compose.runtime.mutableFloatStateOf(((boxH - drawnH(crop)) / 2 + drawnH(crop) * crop.top / whole).coerceIn(0f, (boxH - frameH(crop)).coerceAtLeast(0f)))
        }
        val density = LocalDensity.current
        val grab = with(density) { 40.dp.toPx() }
        val sharpWidth = (item.width.toLong() * crop.keptWidth / CropContent.WHOLE).toInt()
        val sharpHeight = (item.height.toLong() * crop.kept / CropContent.WHOLE).toInt()
        val blurry = item.width > SHARP_WIDTH && sharpWidth < SHARP_WIDTH
        androidx.compose.runtime.LaunchedEffect(blurry) { if (blurry) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(target.item.id, boxW, boxH) {
                    detectTapGestures { at ->
                        val c = current.value
                        val dw = drawnW(c)
                        val dh = drawnH(c)
                        val left = frameLeft - dw * c.left / whole
                        val top = frameTop - dh * c.top / whole
                        val x = ((at.x - left) / dw * whole).roundToInt().coerceIn(c.left, CropContent.WHOLE - c.right)
                        val y = ((at.y - top) / dh * whole).roundToInt().coerceIn(c.top, CropContent.WHOLE - c.bottom)
                        set(c.copy(focusX = x, focusY = y, hasFocus = true))
                    }
                }
                .pointerInput(target.item.id, boxW, boxH) {
                    androidx.compose.foundation.gestures.awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = current.value
                        val topY = frameTop
                        val bottomY = frameTop + frameH(start)
                        var mode = when {
                            abs(down.position.y - topY) <= grab && abs(down.position.y - topY) <= abs(down.position.y - bottomY) -> Drag.TOP
                            abs(down.position.y - bottomY) <= grab -> Drag.BOTTOM
                            else -> Drag.MOVE
                        }
                        var moved = false
                        val slop = viewConfiguration.touchSlop
                        var travelled = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val c = current.value
                            val most = (CropContent.WHOLE - CropContent.MIN_KEPT).toFloat()
                            if (pressed.size >= 2) {
                                // Pinch: the picture grows or shrinks around the fingers under a frame that keeps its size.
                                mode = Drag.MOVE
                                val zoom = event.calculateZoom()
                                val centroid = event.calculateCentroid()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    moved = true
                                    val dw = drawnW(c)
                                    val dh = drawnH(c)
                                    val left = frameLeft - dw * c.left / whole
                                    val top = frameTop - dh * c.top / whole
                                    val fh = frameH(c)
                                    // The kept part, as parts of the picture: its width and height shrink as it grows.
                                    var kw = (c.keptWidth / zoom).coerceIn(CropContent.MIN_KEPT.toFloat(), whole)
                                    var kh = (c.kept * kw / c.keptWidth.coerceAtLeast(1)).coerceAtMost(whole)
                                    if (kh < CropContent.MIN_KEPT) { kh = CropContent.MIN_KEPT.toFloat(); kw = c.keptWidth * kh / c.kept.coerceAtLeast(1) }
                                    val dw2 = frameW * whole / kw
                                    val dh2 = dw2 / ratio
                                    val u = (centroid.x - left) / dw
                                    val v = (centroid.y - top) / dh
                                    val left2 = centroid.x + pan.x - u * dw2
                                    val top2 = centroid.y + pan.y - v * dh2
                                    val l = ((frameLeft - left2) / dw2 * whole).coerceIn(0f, whole - kw)
                                    val t = ((frameTop - top2) / dh2 * whole).coerceIn(0f, whole - kh)
                                    // A frame taller than the picture now shows: it shrinks to it.
                                    val khShown = minOf(kh, fh / dh2 * whole)
                                    var next = c.copy(left = l.roundToInt(), right = (whole - kw - l).roundToInt().coerceAtLeast(0), top = t.roundToInt(), bottom = (whole - khShown - t).roundToInt().coerceAtLeast(0))
                                    if (next.hasFocus) next = next.copy(focusX = next.focusX.coerceIn(next.left, CropContent.WHOLE - next.right), focusY = next.focusY.coerceIn(next.top, CropContent.WHOLE - next.bottom))
                                    set(next)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = pressed.first()
                            val delta = change.position - change.previousPosition
                            travelled += delta.getDistance()
                            if (!moved && travelled < slop) continue
                            moved = true
                            change.consume()
                            val dh = drawnH(c)
                            val dw = drawnW(c)
                            val d = delta.y / dh * whole
                            when (mode) {
                                Drag.TOP -> {
                                    // The line follows the finger over a picture that stays put.
                                    val t = (c.top + d).coerceIn(0f, most - c.bottom).coerceAtLeast(c.top - frameTop / dh * whole)
                                    frameTop += (t - c.top) / whole * dh
                                    set(c.copy(top = t.roundToInt()).let { if (it.hasFocus) it.copy(focusY = it.focusY.coerceIn(it.top, CropContent.WHOLE - it.bottom)) else it })
                                }
                                Drag.BOTTOM -> {
                                    val room = (boxH - frameTop - frameH(c)) / dh * whole
                                    val b = (c.bottom - d).coerceIn(0f, most - c.top).coerceAtLeast(c.bottom - room)
                                    set(c.copy(bottom = b.roundToInt()).let { if (it.hasFocus) it.copy(focusY = it.focusY.coerceIn(it.top, CropContent.WHOLE - it.bottom)) else it })
                                }
                                Drag.MOVE -> {
                                    // The picture follows the finger under the frame.
                                    val dy = (-d).coerceIn(-c.top.toFloat(), c.bottom.toFloat())
                                    val dx = (-delta.x / dw * whole).coerceIn(-c.left.toFloat(), c.right.toFloat())
                                    set(c.copy(top = (c.top + dy).roundToInt(), bottom = (c.bottom - dy).roundToInt(), left = (c.left + dx).roundToInt(), right = (c.right - dx).roundToInt()))
                                }
                            }
                        }
                        if (!moved) return@awaitEachGesture
                    }
                },
        ) {
            val dw = drawnW(crop)
            val dh = drawnH(crop)
            val imageLeft = frameLeft - dw * crop.left / whole
            val imageTop = frameTop - dh * crop.top / whole
            val fh = frameH(crop)
            val edge = if (blurry) Color(0xFFFF5A4E) else Color.White
            bitmap?.let { b ->
                val image = remember(b) { b.asImageBitmap() }
                Box(Modifier.fillMaxSize().drawBehind {
                    drawImage(
                        image,
                        dstOffset = androidx.compose.ui.unit.IntOffset(imageLeft.roundToInt(), imageTop.roundToInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
                    )
                })
            }
            Box(Modifier.fillMaxSize().drawBehind {
                val dim = Color(0x99000000)
                val right = frameLeft + frameW
                val bottom = frameTop + fh
                drawRect(dim, Offset.Zero, Size(size.width, frameTop))
                drawRect(dim, Offset(0f, bottom), Size(size.width, size.height - bottom))
                drawRect(dim, Offset(0f, frameTop), Size(frameLeft, fh))
                drawRect(dim, Offset(right, frameTop), Size(size.width - right, fh))
                val line = 2.dp.toPx()
                drawRect(edge, Offset(frameLeft, frameTop), Size(frameW, line))
                drawRect(edge, Offset(frameLeft, bottom - line), Size(frameW, line))
                if (crop.left > 0 || crop.right > 0 || frameW < size.width) {
                    drawRect(edge, Offset(frameLeft, frameTop), Size(line, fh))
                    drawRect(edge, Offset(right - line, frameTop), Size(line, fh))
                }
                // Thirds, as a guide to the composition.
                val faint = Color(0x59FFFFFF)
                for (i in 1..2) {
                    drawRect(faint, Offset(frameLeft + frameW * i / 3, frameTop), Size(1f, fh))
                    drawRect(faint, Offset(frameLeft, frameTop + fh * i / 3), Size(frameW, 1f))
                }
            })
            Handle(stringResource(R.string.crop_top), with(density) { frameTop.toDp() }, boxW, edge)
            Handle(stringResource(R.string.crop_bottom), with(density) { (frameTop + fh).toDp() }, boxW, edge)
            // The kept part's size in the original's pixels, and the widest screen it fills without being enlarged
            // (2026-11-28, hen: "see the dimensions of the cropped section and what is the maximum resolution it is
            // without scaling"); red below 1080 ("i want to know when i zooming pass of 1080p").
            if (item.width > 0 && item.height > 0) Box(
                Modifier.align(Alignment.TopCenter).offset(y = with(density) { (frameTop + 14.dp.toPx()).toDp() })
                    .background(if (blurry) Color(0xFFFF5A4E) else Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                val fills = SCREENS.lastOrNull { it <= sharpWidth }
                Text(
                    if (fills == null) stringResource(R.string.crop_size_below, sharpWidth, sharpHeight, SHARP_WIDTH)
                    else stringResource(R.string.crop_size, sharpWidth, sharpHeight, fills),
                    color = Color.White, style = LibraryType.Caption,
                )
            }
            if (crop.hasFocus) {
                val x = with(density) { (imageLeft + dw * crop.focusX / whole).toDp() }
                val y = with(density) { (imageTop + dh * crop.focusY / whole).toDp() }
                val focusLabel = stringResource(R.string.crop_focus)
                Box(
                    Modifier.offset(x - 22.dp, y - 22.dp).size(44.dp).border(2.dp, Color.White, CircleShape).semantics { contentDescription = focusLabel },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(6.dp).background(Color.White, CircleShape)) }
            }
        }
    }
}

@Composable
private fun Handle(label: String, y: Dp, width: Float, color: Color) {
    val w = with(LocalDensity.current) { width.toDp() }
    Box(Modifier.offset(w / 2 - 32.dp, y - 12.dp).size(64.dp, 24.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(48.dp, 6.dp).background(color, RoundedCornerShape(3.dp)))
    }
}

@Composable
private fun IconCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, outlined: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (outlined) 52.dp else 48.dp).clip(CircleShape)
            .then(if (outlined) Modifier.border(1.dp, Color(0xFF555555), CircleShape) else Modifier).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color(0xFFF2F0ED), modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Thumb(target: CropTarget, preview: suspend (String, String) -> ByteArray?, selected: Boolean, set: Boolean, label: String, onClick: () -> Unit) {
    val bitmap by produceState(drawnPicture(target.messageId, target.item), target.messageId, target.item.id) {
        if (value != null) return@produceState
        val bytes = preview(target.messageId, target.item.id) ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { if (JxlCoder.isJXL(bytes)) JxlCoder.decode(bytes) else android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }
    Box(
        Modifier.size(52.dp).then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)).padding(4.dp) else Modifier)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A)).clickable(onClick = onClick).semantics { contentDescription = label },
    ) {
        bitmap?.let { Image(remember(it) { it.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (set) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(8.dp).background(LibraryColors.Accent, CircleShape))
    }
}
