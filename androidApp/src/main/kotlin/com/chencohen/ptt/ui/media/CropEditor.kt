package com.chencohen.ptt.ui.media

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
 * icons"; mocks: the Picture crop and focus design). The whole original, the part outside the crop dimmed: drag the
 * top or bottom edge to trim it (it snaps to 4:5, 1:1 and 16:9 with a tick), drag inside to move the kept part, tap to
 * set the point of interest. The shape icons set a ratio around the point; the corners icon brings the whole picture
 * back. With several pictures, a filmstrip picks one (a dot on those set), and the copy icon gives them all this one's
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
            IconCircle(LibraryIcons.Expand, stringResource(R.string.crop_whole), onClick = { set(CropContent()) })
        }
        CropArea(target, crop, preview, Modifier.weight(1f).fillMaxWidth(), ::set)
        val shape = shapeOf(target.item, crop)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            RatioChip(stringResource(R.string.crop_free), selected = shape == null, onClick = {}) {
                Icon(LibraryIcons.Crop, null, Modifier.size(20.dp))
            }
            for ((ratio, label, w, h) in listOf(Ratio(0.8f, R.string.crop_4_5, 16, 20), Ratio(1f, R.string.crop_square, 18, 18), Ratio(16f / 9, R.string.crop_16_9, 22, 13))) {
                RatioChip(stringResource(label), selected = shape == ratio, onClick = { set(withRatio(target.item, crop, ratio)) }) {
                    Box(Modifier.size(w.dp, h.dp).border(2.dp, androidx.compose.material3.LocalContentColor.current, RoundedCornerShape(2.dp)))
                }
            }
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

private data class Ratio(val ratio: Float, val label: Int, val w: Int, val h: Int)

/** The ratio chip the crop is at now (within 1%), null: free. */
private fun shapeOf(item: AttachmentContent, crop: CropContent): Float? {
    if (crop.top == 0 && crop.bottom == 0) return null
    val ratio = item.width.toFloat() / (item.height.toFloat() * crop.kept / CropContent.WHOLE).coerceAtLeast(1f)
    return SNAPS.firstOrNull { abs(ratio - it) / it < 0.01f }
}

private val SNAPS = listOf(0.8f, 1f, 16f / 9)

/** [crop] at [ratio] (width to height), the kept part centred on the point of interest (or where it is now). */
private fun withRatio(item: AttachmentContent, crop: CropContent, ratio: Float): CropContent {
    if (item.width <= 0 || item.height <= 0) return crop
    val kept = (item.width / ratio / item.height * CropContent.WHOLE).roundToInt()
    if (kept >= CropContent.WHOLE) return crop.copy(top = 0, bottom = 0)
    val centre = if (crop.hasFocus) crop.focusY else crop.top + crop.kept / 2
    val top = (centre - kept / 2).coerceIn(0, CropContent.WHOLE - kept)
    return crop.copy(top = top, bottom = CropContent.WHOLE - kept - top)
}

private enum class Drag { TOP, BOTTOM, MOVE }

@Composable
private fun CropArea(target: CropTarget, crop: CropContent, preview: suspend (String, String) -> ByteArray?, modifier: Modifier, set: (CropContent) -> Unit) {
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
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val item = target.item
        val ratio = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else bitmap?.let { it.width.toFloat() / it.height } ?: 1f
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val w = minOf(boxW, boxH * ratio)
        val h = w / ratio
        val density = LocalDensity.current
        val grab = with(density) { 28.dp.toPx() }
        Box(
            Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .pointerInput(target.item.id, h) {
                    detectTapGestures { at ->
                        val c = current.value
                        val x = (at.x / w * CropContent.WHOLE).roundToInt().coerceIn(0, CropContent.WHOLE)
                        val y = (at.y / h * CropContent.WHOLE).roundToInt().coerceIn(c.top, CropContent.WHOLE - c.bottom)
                        set(c.copy(focusX = x, focusY = y, hasFocus = true))
                    }
                }
                .pointerInput(target.item.id, h) {
                    var mode = Drag.MOVE
                    var snapped: Float? = null
                    var top = 0f
                    var bottom = 0f
                    detectDragGestures(
                        onDragStart = { at ->
                            val c = current.value
                            top = c.top.toFloat()
                            bottom = c.bottom.toFloat()
                            val topY = h * c.top / CropContent.WHOLE
                            val bottomY = h * (CropContent.WHOLE - c.bottom) / CropContent.WHOLE
                            mode = when {
                                abs(at.y - topY) <= grab && abs(at.y - topY) <= abs(at.y - bottomY) -> Drag.TOP
                                abs(at.y - bottomY) <= grab -> Drag.BOTTOM
                                else -> Drag.MOVE
                            }
                            snapped = shapeOf(item, c)
                        },
                    ) { change, delta ->
                        change.consume()
                        val d = delta.y / h * CropContent.WHOLE
                        val most = (CropContent.WHOLE - CropContent.MIN_KEPT).toFloat()
                        when (mode) {
                            Drag.TOP -> top = (top + d).coerceIn(0f, most - bottom)
                            Drag.BOTTOM -> bottom = (bottom - d).coerceIn(0f, most - top)
                            Drag.MOVE -> {
                                val move = d.coerceIn(-top, bottom)
                                top += move
                                bottom -= move
                            }
                        }
                        val c = current.value
                        var next = c.copy(top = top.roundToInt(), bottom = bottom.roundToInt())
                        if (mode != Drag.MOVE) {
                            // Snap stops at 4:5, 1:1 and 16:9, with a light tick as one is reached.
                            val keptPx = item.height.toFloat() * next.kept / CropContent.WHOLE
                            val now = item.width / keptPx.coerceAtLeast(1f)
                            val stop = SNAPS.firstOrNull { abs(now - it) / it < 0.03f }
                            if (stop != null) {
                                val kept = (item.width / stop / item.height * CropContent.WHOLE).roundToInt()
                                next = if (mode == Drag.TOP) next.copy(top = (CropContent.WHOLE - kept - next.bottom).coerceAtLeast(0)) else next.copy(bottom = (CropContent.WHOLE - kept - next.top).coerceAtLeast(0))
                            }
                            if (stop != null && stop != snapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            snapped = stop
                        }
                        // The point stays inside what is kept.
                        if (next.hasFocus) next = next.copy(focusY = next.focusY.coerceIn(next.top, CropContent.WHOLE - next.bottom))
                        set(next)
                    }
                },
        ) {
            bitmap?.let { Image(remember(it) { it.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            val topPx = h * crop.top / CropContent.WHOLE
            val bottomPx = h * (CropContent.WHOLE - crop.bottom) / CropContent.WHOLE
            Box(Modifier.fillMaxSize().drawBehind {
                val dim = Color(0x99000000)
                drawRect(dim, Offset.Zero, Size(size.width, topPx))
                drawRect(dim, Offset(0f, bottomPx), Size(size.width, size.height - bottomPx))
                val line = 2.dp.toPx()
                drawRect(Color.White, Offset(0f, topPx), Size(size.width, line))
                drawRect(Color.White, Offset(0f, bottomPx - line), Size(size.width, line))
                // Thirds, as a guide to the composition.
                val faint = Color(0x59FFFFFF)
                for (i in 1..2) {
                    drawRect(faint, Offset(size.width * i / 3, topPx), Size(1f, bottomPx - topPx))
                    drawRect(faint, Offset(0f, topPx + (bottomPx - topPx) * i / 3), Size(size.width, 1f))
                }
            })
            Handle(stringResource(R.string.crop_top), with(density) { topPx.toDp() }, w)
            Handle(stringResource(R.string.crop_bottom), with(density) { bottomPx.toDp() }, w)
            if (crop.hasFocus) {
                val x = with(density) { (w * crop.focusX / CropContent.WHOLE).toDp() }
                val y = with(density) { (h * crop.focusY / CropContent.WHOLE).toDp() }
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
private fun Handle(label: String, y: Dp, width: Float) {
    val w = with(LocalDensity.current) { width.toDp() }
    Box(Modifier.offset(w / 2 - 32.dp, y - 12.dp).size(64.dp, 24.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(48.dp, 6.dp).background(Color.White, RoundedCornerShape(3.dp)))
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
private fun RatioChip(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp, 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .border(1.dp, if (selected) Color.White else Color(0xFF555555), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (selected) Color(0xFF111111) else Color(0xFFF2F0ED)) { content() }
    }
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
