package com.chencohen.ptt.ui.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The viewer's zoom, copied from Journal's (journal-client-kmp, ui/library/ViewerZoom.kt) without its crops, resume
 * and minimap (2026-11-28, hen: "take inspiration from journal app"): [scale] times the picture's fitted size (1 is
 * fitted), moved by [offset] pixels from the screen's centre. A double tap steps fitted → filling the screen → the
 * original's own pixels (1:1) → fitted, around the tapped point; a pinch zooms around the fingers and, let go within
 * [SNAP] of fitted, filling or 1:1, snaps there ("the zoom should snap near zoom to extents and zoom to fit"). Talk
 * adds a fling: a drag let go while moving glides on and slows down ("pan should work with ease").
 */
@Stable
internal class PhotoZoom(private val scope: CoroutineScope, private var restoring: ZoomPart? = null) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private var viewport = Size.Zero
    private var fitted = Size.Zero

    /** The longer side of the original's own pixels (not the decoded image's). */
    private var pixelSide = 0

    private var animation: Job? = null

    val isZoomed: Boolean get() = scale > 1.01f

    /** The smallest picture of the whole photo its page has ready, for the minimap (set by the page). */
    var picture by mutableStateOf<ImageBitmap?>(null)

    /** The page's check of whether what is on screen is at the original's quality ([ReportQuality]). */
    var quality by mutableStateOf<State<Boolean>?>(null)

    /** Read straight from the page's check: the minimap is grey until it is true. */
    val atOriginalQuality: Boolean get() = quality?.value ?: false

    /** How many screen pixels the photo's longer side takes now. */
    val shownLongerSide: Float get() = max(fitted.width, fitted.height) * scale

    /** How many screen pixels the photo's width takes now. */
    val shownWidth: Float get() = fitted.width * scale

    /**
     * The part of the photo on screen now, widened by [margin] of its size on each side, as fractions (0..1) of the
     * photo's width and height; null before the first layout.
     */
    fun visibleRect(margin: Float): Rect? {
        if (fitted.isEmpty() || viewport.isEmpty()) return null
        val centre = Offset(viewport.width / 2, viewport.height / 2)
        val photoLeft = centre.x - fitted.width / 2
        val photoTop = centre.y - fitted.height / 2
        fun across(x: Float) = (centre.x + (x - centre.x - offset.x) / scale - photoLeft) / fitted.width
        fun down(y: Float) = (centre.y + (y - centre.y - offset.y) / scale - photoTop) / fitted.height
        val left = across(0f)
        val right = across(viewport.width)
        val top = down(0f)
        val bottom = down(viewport.height)
        val dx = (right - left) * margin
        val dy = (bottom - top) * margin
        return Rect(
            (left - dx).coerceIn(0f, 1f), (top - dy).coerceIn(0f, 1f), (right + dx).coerceIn(0f, 1f), (bottom + dy).coerceIn(0f, 1f),
        ).takeIf { it.width > 0f && it.height > 0f }
    }

    fun layout(viewport: Size, photoRatio: Float, pixelSide: Int) {
        this.viewport = viewport
        this.pixelSide = pixelSide
        fitted = if (viewport.width / viewport.height > photoRatio) Size(viewport.height * photoRatio, viewport.height)
        else Size(viewport.width, viewport.width / photoRatio)
        if (resumeWaiting) {
            resumeWaiting = false
            resume()
        }
    }

    /** Whether it has a zoom from last time still to [resume] to. */
    val hasSaved: Boolean get() = restoring != null

    private var resumeWaiting = false

    /** Glides from fitted to where it was left last time; waits for the first layout when there hasn't been one. */
    fun resume() {
        val part = restoring ?: return
        if (fitted.isEmpty()) {
            resumeWaiting = true
            return
        }
        restoring = null
        glideTo(part)
    }

    /** What to keep when the picture is left: the part on screen (null when fitted, or not laid out yet). */
    val zoomedPart: ZoomPart? get() {
        restoring?.let { return it }
        if (!isZoomed) return null
        val part = visibleRect(0f) ?: return null
        return ZoomPart(part.left, part.top, part.width, part.height)
    }

    /**
     * Animates until [part] fills the screen as far as it can, centred on it: the scale changes evenly in steps of
     * doubling, not of size, and the middle slides with it, so it reads as one move.
     */
    private fun glideTo(part: ZoomPart) {
        if (fitted.isEmpty() || part.w <= 0f || part.h <= 0f) return
        val fromScale = scale
        val fromMiddle = Offset(0.5f - offset.x / scale / fitted.width, 0.5f - offset.y / scale / fitted.height)
        val toScale = kotlin.math.min(viewport.width / (part.w * fitted.width), viewport.height / (part.h * fitted.height)).coerceIn(1f, maxScale)
        val toMiddle = Offset(part.x + part.w / 2, part.y + part.h / 2)
        animation?.cancel()
        animation = scope.launch {
            val progress = Animatable(0f)
            progress.animateTo(1f, tween(RESUME_MILLIS, easing = androidx.compose.animation.core.FastOutSlowInEasing)) {
                val now = fromScale * (toScale / fromScale).pow(value)
                val middle = fromMiddle + (toMiddle - fromMiddle) * value
                scale = now
                offset = clamp(Offset((0.5f - middle.x) * fitted.width * now, (0.5f - middle.y) * fitted.height * now), now)
            }
        }
    }

    /** Fitted, filling the screen, and the original's own size: what a double tap steps through and a pinch snaps to. */
    private val levels: List<Float> get() {
        if (fitted.isEmpty()) return listOf(1f)
        val fill = max(viewport.width / fitted.width, viewport.height / fitted.height)
        val original = if (pixelSide > 0) pixelSide / max(fitted.width, fitted.height) else 1f
        // A small picture's own size can be below fitted or near filling: those steps are left out.
        return listOf(1f, fill, original).filter { it >= 1f && it <= maxScale }.sorted()
            .fold(emptyList()) { kept, level -> if (kept.isNotEmpty() && level < kept.last() * 1.05f) kept else kept + level }
    }

    /** Twice past the original's own pixels, and never less than filling the screen. */
    private val maxScale: Float get() {
        if (fitted.isEmpty()) return 1f
        val original = if (pixelSide > 0) pixelSide / max(fitted.width, fitted.height) else 1f
        val fill = max(viewport.width / fitted.width, viewport.height / fitted.height)
        return max(max(2 * original, fill), 1f)
    }

    /** Pinching and dragging: [zoom] times bigger around [focus] (from the screen's centre), moved by [pan]. */
    fun transform(focus: Offset, zoom: Float, pan: Offset) {
        animation?.cancel()
        // Below fitted while pinching is allowed (it springs back in [settle]); past the maximum is not.
        val next = (scale * zoom).coerceIn(0.7f, maxScale)
        offset = clamp(focus - (focus - offset) * (next / scale) + pan, next)
        scale = next
    }

    /**
     * Fingers lifted: back to fitted when pinched smaller; after a pinch, to fitted, filling or the original's size
     * when within [SNAP] of one, around [focus]; after a drag, on at [velocity] (pixels a second), slowing down.
     */
    fun settle(pinched: Boolean, focus: Offset, velocity: Offset) {
        if (scale < 1f) return zoomTo(1f)
        if (pinched) {
            val near = levels.minByOrNull { abs(ln(scale / it)) } ?: return
            if (abs(ln(scale / near)) < ln(SNAP) && abs(scale - near) > 0.001f) zoomTo(near, focus)
            return
        }
        if (isZoomed && velocity.getDistance() > FLING_MIN) fling(velocity)
    }

    /** Double tap: the next of fitted → filling the screen → the original's size around [focus], then back to fitted. */
    fun step(focus: Offset) {
        val next = levels.firstOrNull { it > scale * 1.02f }
        if (next == null) zoomTo(1f) else zoomTo(next, focus)
    }

    /** Back to fitted at once (another picture swiped to). */
    fun reset() {
        animation?.cancel()
        scale = 1f
        offset = Offset.Zero
    }

    private fun zoomTo(target: Float, focus: Offset = Offset.Zero) {
        val targetOffset = clamp(focus - (focus - offset) * (target / scale), target)
        val from = scale
        val fromOffset = offset
        animation?.cancel()
        animation = scope.launch {
            val progress = Animatable(0f)
            progress.animateTo(1f, tween(ZOOM_MILLIS)) {
                scale = from + (target - from) * value
                offset = fromOffset + (targetOffset - fromOffset) * value
            }
        }
    }

    private fun fling(velocity: Offset) {
        animation?.cancel()
        animation = scope.launch {
            Animatable(offset, Offset.VectorConverter).animateDecay(velocity, exponentialDecay(frictionMultiplier = 1.6f)) {
                val before = offset
                offset = clamp(value, scale)
                // Held at the edges, nothing moves any more: stop.
                if (offset == before) cancelAnimation()
            }
        }
    }

    /** Within the picture's edges: a side smaller than the screen stays centred. */
    private fun clamp(offset: Offset, scale: Float): Offset {
        val maxX = max(0f, (fitted.width * scale - viewport.width) / 2)
        val maxY = max(0f, (fitted.height * scale - viewport.height) / 2)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
}

private const val ZOOM_MILLIS = 250

/** The glide from the opened picture to where it was zoomed last time. */
private const val RESUME_MILLIS = 550

/** A part of a picture on screen, as fractions (0..1) of its width and height. */
internal data class ZoomPart(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Where each picture was left zoomed (by "message/item"), so opening it again glides back there, as Journal does. Journal
 * keeps it in the photo's note; Talk has none, so it is kept while the app runs.
 */
internal val keptZooms = java.util.concurrent.ConcurrentHashMap<String, ZoomPart>()

/** A pinch let go within this factor of fitted, filling the screen or the original's size goes to it. */
private const val SNAP = 1.15f

/** Slower than this (pixels a second) a drag stops where it is let go. */
private const val FLING_MIN = 300f

/**
 * The picture laid out as [zoom] shows it: fitted in the screen at its shape ([ratio]), times the zoom, moved by its
 * offset from the centre. Read while laying out, so a pinch relays it without recomposing.
 */
internal fun Modifier.zoomedLayout(zoom: PhotoZoom, ratio: Float): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val fittedWidth = if (width.toFloat() / height > ratio) height * ratio else width.toFloat()
    val fittedHeight = fittedWidth / ratio
    val w = (fittedWidth * zoom.scale).roundToInt().coerceAtLeast(1)
    val h = (fittedHeight * zoom.scale).roundToInt().coerceAtLeast(1)
    val photo = measurable.measure(Constraints.fixed(w, h))
    layout(width, height) {
        photo.place(((width - w) / 2f + zoom.offset.x).roundToInt(), ((height - h) / 2f + zoom.offset.y).roundToInt())
    }
}

/**
 * Pinching (two fingers) and, while zoomed, dragging with one: seen before the pager, which then doesn't swipe. One
 * finger moves the picture only once it has gone further than a tap may, so a double tap still reaches its handler.
 */
internal fun Modifier.zoomGestures(zoom: () -> PhotoZoom?): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var started = false
        var pinched = false
        var dragged = 0f
        val centre = Offset(size.width / 2f, size.height / 2f)
        var focus = Offset.Zero
        val tracker = VelocityTracker()
        var lastFingers = 0
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val current = zoom() ?: continue
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2 || current.isZoomed) {
                val change = event.calculateZoom()
                val pan = event.calculatePan()
                if (!started && fingers < 2) dragged += pan.getDistance()
                if ((change != 1f || pan != Offset.Zero) && (started || fingers >= 2 || dragged > viewConfiguration.touchSlop)) {
                    started = true
                    if (fingers >= 2 && change != 1f) pinched = true
                    focus = event.calculateCentroid() - centre
                    current.transform(focus, change, pan)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
            // The fling follows one finger; a finger added or lifted starts the count again.
            val finger = event.changes.firstOrNull { it.pressed }
            if (fingers != lastFingers) tracker.resetTracking()
            lastFingers = fingers
            if (finger != null && fingers == 1) tracker.addPosition(finger.uptimeMillis, finger.position)
        } while (event.changes.any { it.pressed })
        val velocity = runCatching { tracker.calculateVelocity() }.getOrNull()?.let { Offset(it.x, it.y) } ?: Offset.Zero
        zoom()?.settle(pinched, focus, if (started) velocity else Offset.Zero)
    }
}

/**
 * Where the screen is on the whole photo while zoomed: [image] small, with the part on screen ([part], 0..1) outlined
 * and the rest dimmed; grey until the photo on screen is at the original's quality ([sharp]), its colour then fading
 * in. Its longer side is [side].
 */
@Composable
internal fun ZoomMinimap(image: ImageBitmap, part: Rect, sharp: Boolean, modifier: Modifier = Modifier, side: Dp = 96.dp) {
    val colour by animateFloatAsState(if (sharp) 1f else 0f, tween(MINIMAP_COLOUR_MILLIS), label = "minimap colour")
    val ratio = image.width.toFloat() / image.height
    val size = if (ratio >= 1f) DpSize(side, side / ratio) else DpSize(side * ratio, side)
    Canvas(
        modifier
            .size(size)
            .shadow(6.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black),
    ) {
        val whole = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
        if (colour < 1f) drawImage(image, dstSize = whole, filterQuality = FilterQuality.Medium, colorFilter = GREY)
        if (colour > 0f) drawImage(image, dstSize = whole, filterQuality = FilterQuality.Medium, alpha = colour)
        val left = part.left * this.size.width
        val top = part.top * this.size.height
        val right = part.right * this.size.width
        val bottom = part.bottom * this.size.height
        val dim = Color.Black.copy(alpha = 0.5f)
        // The rest dimmed, in four bands around the part.
        drawRect(dim, Offset.Zero, Size(this.size.width, top))
        drawRect(dim, Offset(0f, bottom), Size(this.size.width, this.size.height - bottom))
        drawRect(dim, Offset(0f, top), Size(left, bottom - top))
        drawRect(dim, Offset(right, top), Size(this.size.width - right, bottom - top))
        drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(1.5.dp.toPx()))
    }
}

private const val MINIMAP_COLOUR_MILLIS = 300

private val GREY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
