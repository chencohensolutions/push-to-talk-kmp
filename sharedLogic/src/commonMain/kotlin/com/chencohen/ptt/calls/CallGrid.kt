package com.chencohen.ptt.calls

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Group video calls (2026-11-23, hen: "with 4 or 6 people, all faces are centred and the boxes are evenly sized"): the
 * screen split into equal tiles, each cropped to fill, and with Follow face each tile's crop on that person's face at
 * the same head-and-shoulders scale, so the grid looks even on any phone's shape.
 */
object CallGrid {
    /** Columns and rows. */
    data class Shape(val columns: Int, val rows: Int)

    /**
     * The columns and rows for [count] tiles on a [width]×[height] screen that give the biggest equal tiles for a
     * picture of [pictureAspect] (width for 1 high; a phone's camera upright is 3:4): each layout is scored by the
     * biggest picture of that shape a tile can show whole, and fewer empty cells win a tie.
     */
    fun shape(count: Int, width: Float, height: Float, pictureAspect: Float = 3f / 4f): Shape {
        if (count <= 1 || width <= 0f || height <= 0f) return Shape(1, 1)
        var best = Shape(1, count)
        var bestScore = -1f
        var bestEmpty = Int.MAX_VALUE
        for (columns in 1..count) {
            val rows = (count + columns - 1) / columns
            val w = width / columns
            val h = height / rows
            val score = min(w / pictureAspect, h)
            val empty = columns * rows - count
            if (score > bestScore + 0.5f || (abs(score - bestScore) <= 0.5f && empty < bestEmpty)) {
                best = Shape(columns, rows)
                bestScore = score
                bestEmpty = empty
            }
        }
        return best
    }

    /** A video's size on the screen. */
    data class Size(val width: Float, val height: Float)

    /**
     * Normal in a 1:1 call (2026-11-23, hen: "there is more room for the video"): theirs whole at its own shape
     * ([theirAspect], width for 1 high), as tall as the screen on its side ([landscape]) or as wide upright; this
     * phone's ([ownAspect]) as big as fits in the rest. When that would leave this phone's under a quarter of the
     * screen, both shrink together so both show whole.
     */
    fun normal(width: Float, height: Float, theirAspect: Float, ownAspect: Float, landscape: Boolean): Pair<Size, Size> {
        if (landscape) {
            val theirs = Size(height * theirAspect, height)
            val rest = width - theirs.width
            if (rest >= width / 4) return theirs to fit(rest, height, ownAspect)
            val f = width / (height * theirAspect + height * ownAspect)
            return Size(height * theirAspect * f, height * f) to Size(height * ownAspect * f, height * f)
        }
        val theirs = Size(width, width / theirAspect)
        val rest = height - theirs.height
        if (rest >= height / 4) return theirs to fit(width, rest, ownAspect)
        val f = height / (width / theirAspect + width / ownAspect)
        return Size(width * f, width / theirAspect * f) to Size(width * f, width / ownAspect * f)
    }

    private fun fit(width: Float, height: Float, aspect: Float) =
        if (width / height > aspect) Size(height * aspect, height) else Size(width, width / aspect)

    /** A view's zoom (from its top left corner) and offset, in pixels. */
    data class Placement(val scale: Float, val x: Float, val y: Float)

    /**
     * Where a picture goes in a [boxWidth]×[boxHeight] box so the face at ([faceX], [faceY]) (shares of the picture,
     * its centre) [faceHeight] tall (a share of the picture's height) sits at ([targetX], [targetY]) (shares of the box)
     * at [faceShare] of the box's height, zoomed at most [maxZoom]. The picture, [contentWidth]×[contentHeight], starts
     * centred in the box (bigger when cropped to fill); whatever the box showed before stays covered.
     */
    fun place(
        boxWidth: Float,
        boxHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        faceX: Float,
        faceY: Float,
        faceHeight: Float,
        maxZoom: Float,
        targetX: Float = 0.5f,
        targetY: Float = 0.45f,
        faceShare: Float = 0.45f,
        /** The zoom the user pinched (Follow face keeps the face centred at it); null: Follow face picks it. */
        fixedZoom: Float? = null,
    ): Placement {
        val ox = (boxWidth - contentWidth) / 2
        val oy = (boxHeight - contentHeight) / 2
        val zoom = fixedZoom?.coerceAtLeast(1f)
            ?: if (faceHeight > 0f) (faceShare * boxHeight / (faceHeight * contentHeight)).coerceIn(1f, max(1f, maxZoom)) else 1f
        val x = span(boxWidth * targetX - ox - faceX * contentWidth * zoom, min(boxWidth, ox + contentWidth) - ox - contentWidth * zoom, max(0f, ox) - ox)
        val y = span(boxHeight * targetY - oy - faceY * contentHeight * zoom, min(boxHeight, oy + contentHeight) - oy - contentHeight * zoom, max(0f, oy) - oy)
        return Placement(zoom, x, y)
    }

    /** A part of a box, as shares of its width and height. */
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Where a face [faceWidth]×[faceHeight] (shares of the box), centred at [faceX] across, goes down the box (its
     * centre, a share of the height): at [want] (the user's face height, 2026-11-23, hen), unless that puts it under
     * [window] (this phone's own video); then the nearest place just above or below the window that is still in the box.
     */
    fun faceClearOf(want: Float, faceWidth: Float, faceHeight: Float, window: Area?, faceX: Float = 0.5f, margin: Float = 0.02f): Float {
        val low = faceHeight / 2
        val high = 1f - faceHeight / 2
        val at = if (low <= high) want.coerceIn(low, high) else 0.5f
        if (window == null) return at
        if (faceX + faceWidth / 2 <= window.left || faceX - faceWidth / 2 >= window.right) return at
        if (at + faceHeight / 2 <= window.top || at - faceHeight / 2 >= window.bottom) return at
        val below = window.bottom + margin + faceHeight / 2
        val above = window.top - margin - faceHeight / 2
        return listOf(below, above).filter { it in low..high }.minByOrNull { abs(it - at) } ?: at
    }

    /** Of [slots] (top left corners), the one nearest [x], [y]. */
    fun nearest(slots: List<Pair<Float, Float>>, x: Float, y: Float): Int =
        slots.indices.minByOrNull { (slots[it].first - x).let { d -> d * d } + (slots[it].second - y).let { d -> d * d } } ?: 0

    /** The size a picture of [aspect] takes to cover ([cover]) or fit in a [width]×[height] box. */
    fun content(width: Float, height: Float, aspect: Float, cover: Boolean): Pair<Float, Float> =
        if ((width / height > aspect) == cover) width to width / aspect else height * aspect to height

    private fun span(want: Float, low: Float, high: Float) = if (low <= high) want.coerceIn(low, high) else (low + high) / 2
}
