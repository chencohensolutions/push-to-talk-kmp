package com.chencohen.ptt.media

import com.chencohen.ptt.messaging.PartContent

/**
 * Which previews a shared picture gets (2026-11-28, hen): one per receiving screen width, so each phone shows a
 * picture exactly as wide as its screen; most phones are 1080 or 1440 pixels wide, so there are seldom more than two.
 * A picture narrower than a screen gets one preview at its own size. Widths are physical pixels, the screen's short
 * side.
 */
object PreviewSpec {
    /** A phone that didn't say its screen (an older app). */
    const val DEFAULT_WIDTH = 1080

    /** At most this many previews per picture (the widest that many screens). */
    const val MAX_PREVIEWS = 3

    /** The preview widths for a picture [originalWidth] wide shown on [screens] (this phone's included). */
    fun widths(screens: Collection<Int>, originalWidth: Int): List<Int> {
        val wanted = screens.map { if (it in 240..8192) it else DEFAULT_WIDTH }.ifEmpty { listOf(DEFAULT_WIDTH) }
        val capped = wanted.map { minOf(it, originalWidth.coerceAtLeast(1)) }.distinct().sorted()
        return if (capped.size <= MAX_PREVIEWS) capped else capped.takeLast(MAX_PREVIEWS)
    }

    /** The preview a phone [screen] wide shows: the narrowest at least as wide, else the widest. */
    fun pick(previews: List<PartContent>, screen: Int): PartContent? {
        if (previews.isEmpty()) return null
        return previews.filter { it.width >= screen }.minByOrNull { it.width } ?: previews.maxBy { it.width }
    }

    /** [width] × [height] scaled to [toWidth] wide, its own aspect (at least 1 pixel high). */
    fun heightAt(width: Int, height: Int, toWidth: Int): Int =
        if (width <= 0) height else maxOf(1, ((height.toLong() * toWidth + width / 2) / width).toInt())
}
