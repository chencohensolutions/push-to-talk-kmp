package com.chencohen.ptt.ui.calls

import android.graphics.RectF
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.chencohen.ptt.calls.CallGrid
import kotlin.math.abs

/** The zoom pinched on a video ([zoom], null: no pinching there) and the gesture that changes it. */
internal class Pinch(val zoom: Float?, val modifier: Modifier)

/**
 * Pinch to zoom (2026-11-23, hen) on a video laid out whole: from 1.0 (the whole picture) up to [most], starting at
 * [start]; kept under [key] for next time. Null [key] (or not [active]): no pinching.
 */
@Composable
internal fun rememberPinch(
    key: String?,
    active: Boolean,
    start: Float,
    most: Float,
    /** One finger dragged up or down (2026-11-23, hen: the face's height), in pixels; not during a pinch. */
    onDrag: ((Float) -> Unit)? = null,
): Pinch {
    val slop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    val dragNow = androidx.compose.runtime.rememberUpdatedState(onDrag)
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("call_view", android.content.Context.MODE_PRIVATE) }
    var pinched by remember(key) { mutableFloatStateOf(key?.let { prefs.getFloat(it, 0f) } ?: 0f) }
    if (key == null || !active) return Pinch(null, Modifier)
    val zoom = (if (pinched > 0f) pinched else start).coerceIn(1f, most)
    val modifier = Modifier.pointerInput(key, most) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var pinchedHere = false
            var pulled = 0f
            var dragging = false
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (fingers == 1 && !pinchedHere && dragNow.value != null) {
                    val c = event.changes.first { it.pressed }
                    val dy = c.position.y - c.previousPosition.y
                    val dx = c.position.x - c.previousPosition.x
                    pulled += dy
                    if (!dragging && kotlin.math.abs(pulled) > slop && kotlin.math.abs(dy) >= kotlin.math.abs(dx)) dragging = true
                    if (dragging) {
                        dragNow.value?.invoke(dy)
                        c.consume()
                    }
                }
                if (fingers >= 2) {
                    pinchedHere = true
                    val change = event.calculateZoom()
                    if (change != 1f) {
                        val now = ((if (pinched > 0f) pinched else start) * change).coerceIn(1f, most)
                        pinched = now
                        prefs.edit().putFloat(key, now).apply()
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    return Pinch(zoom, modifier)
}

/**
 * Follow face's framing of a picture ([content], centred in a [boxSize] box): pans and zooms to keep [box] (the face,
 * shares of the upright picture) near [faceAt] down the box, at the zoom pinched ([zoom]) or its own (at most
 * [maxZoom]). Straight to the face the first time, then a half-second glide to each new place; small moves (5% of the
 * box, 0.08 of zoom) are left alone; without a face, the plain picture (at the zoom pinched). Shared by calls and
 * push-to-video.
 */
@Composable
internal fun faceFraming(
    key: Any,
    on: Boolean,
    fill: Boolean,
    boxSize: IntSize,
    content: Pair<Float, Float>?,
    maxZoom: Float,
    faceAt: Float,
    box: RectF?,
    zoom: Float?,
    /** This phone's own window over the box (shares of it): the face is kept out from under it. */
    avoid: CallGrid.Area? = null,
    /** Without a face, [faceAt] pans the picture up and down too (as if a face were in its upper third). */
    pan: Boolean = false,
): Modifier {
    var target by remember(key) { mutableStateOf(Triple(1f, 0f, 0f)) }
    val bw = boxSize.width.toFloat()
    val bh = boxSize.height.toFloat()
    if (content == null || bw <= 0f || bh <= 0f) {
        if (target != Triple(1f, 0f, 0f)) target = Triple(1f, 0f, 0f)
    } else if (box == null || !on) {
        val (cw, ch) = content
        val plainAt = CallGrid.frame(bw, bh, cw, ch, null, faceAt, zoom, maxZoom, avoid, pan)
        val t = plainAt?.let { Triple(it.scale, it.x, it.y) } ?: Triple(1f, 0f, 0f)
        if (target != t) target = t
    } else {
        val (cw, ch) = content
        val face = CallGrid.Face(box.centerX(), box.centerY(), box.width(), box.height())
        val p = CallGrid.frame(bw, bh, cw, ch, face, faceAt, zoom, if (fill) minOf(maxZoom, 1.5f) else maxZoom, avoid, pan)!!
        val (s0, x0, y0) = target
        if (abs(p.scale - s0) > 0.08f || abs(p.x - x0) > bw * 0.05f || abs(p.y - y0) > bh * 0.05f) {
            target = Triple(p.scale, p.x, p.y)
        }
    }
    var placed by remember(key, on) { mutableStateOf(false) }
    val atFace = box != null && on
    val ease: AnimationSpec<Float> = if (!placed && atFace) snap() else tween(500, easing = FastOutSlowInEasing)
    LaunchedEffect(atFace) { if (atFace) placed = true }
    val s by animateFloatAsState(target.first, ease, label = "follow zoom")
    val x by animateFloatAsState(target.second, ease, label = "follow x")
    val y by animateFloatAsState(target.third, ease, label = "follow y")
    return Modifier.graphicsLayer {
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = s
        scaleY = s
        translationX = x
        translationY = y
    }
}

/** A face position (centre and height, shares of the picture) as a box about as wide as a head. */
internal fun faceBox(x: Float, y: Float, h: Float) = RectF(x - h * 0.375f, y - h / 2, x + h * 0.375f, y + h / 2)
