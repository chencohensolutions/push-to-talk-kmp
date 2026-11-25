package com.chencohen.ptt.ui.live

// The live overlay's geometry and motion (2026-11-25, hen: "every combined animation in the app should be in one
// move"), the same for the chat's panel (LivePanels) and the floating window (TalkBubble): where each voice pill or
// video box rests, and how all of them get there together. Plain numbers, left to right from the overlay's left edge
// whatever the language: nothing here is mirrored, the language only picks the order. docs/screens/style.md, "Motion".

/** What one side shows now: [key] whose it is, its size, and whether it is a video box (else a voice pill). */
data class LiveItem(val key: String, val w: Float, val h: Float, val video: Boolean)

/**
 * An item's frame: [x] from the overlay's left edge and [y] below the items' resting top (negative: above it, on the
 * way in or out through the screen's top edge); [video] from 0, a voice pill, to 1, a video box.
 */
data class LiveRect(val x: Float, val y: Float, val w: Float, val h: Float, val video: Float) {
    val centreX: Float get() = x + w / 2
    val bottom: Float get() = y + h
}

object LiveLayout {
    const val THEIRS = "theirs"
    const val MINE = "mine"

    /**
     * The video boxes' height: [full], or less, all shrunk together, when the boxes (each [shapes] wide for 1 high)
     * and the [pills] beside them, [gap] apart, would be wider than [width].
     */
    fun boxHeight(full: Float, shapes: List<Float>, pills: List<Float>, width: Float, gap: Float): Float {
        if (shapes.isEmpty()) return full
        val room = width - pills.sum() - gap * (shapes.size + pills.size - 1)
        return if (full * shapes.sum() > room) (room / shapes.sum()).coerceAtLeast(0f) else full
    }

    /**
     * Where each of [items] rests in a space [width] wide that starts [left] of the overlay's left edge (0: the
     * overlay is the space): side by side, [gap] apart, centred as a group, tops level. [items] come in reading order
     * (theirs first, mine last): the first sits at the language's start side, the right when [rtl]. The frames
     * themselves are counted from the left edge either way.
     */
    fun place(items: List<LiveItem>, width: Float, gap: Float, rtl: Boolean, left: Float = 0f): Map<String, LiveRect> {
        val leftToRight = if (rtl) items.asReversed() else items
        val all = leftToRight.sumOf { it.w.toDouble() }.toFloat() + gap * (items.size - 1).coerceAtLeast(0)
        var x = left + (width - all) / 2
        val out = LinkedHashMap<String, LiveRect>()
        for (item in leftToRight) {
            out[item.key] = LiveRect(x, 0f, item.w, item.h, if (item.video) 1f else 0f)
            x += item.w + gap
        }
        return out
    }
}

/**
 * Moves the live items to where [LiveLayout.place] wants them, all in ONE move of [ms] with an ease: sizes, places,
 * a pill growing into a box, items coming and going. A new item drops in from above the screen's top edge straight
 * into its own place while the others glide to theirs; one leaving goes back up while the others close in. Each change
 * starts from wherever everything is at that moment, so nothing ever moves in steps.
 */
class LiveMotion(private val ms: Long = 300L) {
    private class Move(var from: LiveRect, var to: LiveRect, var now: LiveRect, var leaving: Boolean)

    private val moves = LinkedHashMap<String, Move>()
    /** When the move running now began; [WAITING] until its first frame. */
    private var began = IDLE

    /** Where everything is now, leaving items too (gone from here once they are out). */
    var rects: Map<String, LiveRect> = emptyMap()
        private set

    /** Where everything is going: each item's frame at the end of the move (a leaving one's, above the top edge). */
    val goals: Map<String, LiveRect> get() = moves.mapValues { it.value.to }

    /** A move is under way: [frame] wants calling each frame. */
    val running: Boolean get() = began != IDLE

    /** [key] is on its way out. */
    fun leaving(key: String): Boolean = moves[key]?.leaving == true

    /**
     * The items should rest at [targets] (LiveLayout.place). A change of who is there or of pill and box starts one
     * move for all (true); a frame only a little different ([small]: a pill's time getting a digit wider) is taken as
     * it is, without starting over. [offTop]: how far the items' resting top is below the screen's top edge.
     */
    fun target(targets: Map<String, LiveRect>, offTop: Float, small: Float = 0f): Boolean {
        var restart = moves.any { (key, m) -> key !in targets && !m.leaving }
        for ((key, t) in targets) {
            val m = moves[key]
            if (m == null || m.leaving || m.to.video != t.video || far(m.to, t, small)) restart = true
        }
        if (!restart) {
            var changed = false
            for ((key, t) in targets) {
                val m = moves.getValue(key)
                if (m.to == t) continue
                m.to = t
                if (!running) m.now = t
                changed = true
            }
            if (changed && !running) publish()
            return false
        }
        for ((key, m) in moves) {
            m.from = m.now
            if (key !in targets && !m.leaving) {
                // Out through the top edge from where it is, as it is.
                m.leaving = true
                m.to = m.now.copy(y = -(m.now.h + offTop))
            }
        }
        for ((key, t) in targets) {
            val m = moves[key]
            if (m == null) {
                // In from above the top edge, already in its own place sideways and at its final size.
                val above = t.copy(y = -(t.h + offTop))
                moves[key] = Move(above, t, above, false)
            } else {
                m.to = t
                m.leaving = false
            }
        }
        began = WAITING
        publish()
        return true
    }

    /** A frame at [now] (ms, any clock that only goes forward); [scale]: the system's animation speed (0: at once). */
    fun frame(now: Long, scale: Float = 1f) {
        if (began == IDLE) return
        if (began == WAITING) began = now
        val length = ms * scale
        val t = if (length <= 0f) 1f else ((now - began) / length).coerceIn(0f, 1f)
        val e = ease(t)
        for (m in moves.values) m.now = if (t >= 1f) m.to else lerp(m.from, m.to, e)
        if (t >= 1f) {
            moves.values.removeAll { it.leaving }
            moves.values.forEach { it.from = it.to }
            began = IDLE
        }
        publish()
    }

    /** Everything gone at once (the overlay itself went). */
    fun clear() {
        moves.clear()
        began = IDLE
        publish()
    }

    private fun publish() {
        rects = LinkedHashMap<String, LiveRect>().also { out -> moves.forEach { (key, m) -> out[key] = m.now } }
    }

    private fun far(a: LiveRect, b: LiveRect, small: Float) =
        kotlin.math.abs(a.x - b.x) > small || kotlin.math.abs(a.y - b.y) > small ||
            kotlin.math.abs(a.w - b.w) > small || kotlin.math.abs(a.h - b.h) > small

    private fun lerp(a: LiveRect, b: LiveRect, f: Float) = LiveRect(
        a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.w + (b.w - a.w) * f, a.h + (b.h - a.h) * f,
        a.video + (b.video - a.video) * f,
    )

    companion object {
        private const val IDLE = -1L
        private const val WAITING = -2L

        /** Slow at both ends, quick between (the standard curve: cubic-bezier 0.4, 0, 0.2, 1). */
        fun ease(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            fun bezier(s: Float, p1: Float, p2: Float) = 3 * (1 - s) * (1 - s) * s * p1 + 3 * (1 - s) * s * s * p2 + s * s * s
            var lo = 0f
            var hi = 1f
            repeat(20) {
                val mid = (lo + hi) / 2
                if (bezier(mid, 0.4f, 0.2f) < t) lo = mid else hi = mid
            }
            return bezier((lo + hi) / 2, 0f, 1f)
        }
    }
}
