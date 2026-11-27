package com.chencohen.ptt.ui.live

// Where the floating window's pieces rest (2026-11-27, hen: "drag the floating bar ... along the edges", "auto hide in
// a smart way", "make the videos stay at the top in original position, not attached to the bar unless they are at
// the same place; make the video also to be dragged"). Plain numbers in screen pixels from the screen's top left,
// whatever the language: nothing here is mirrored. docs/screens/notifications.md, "Floating window".

/** The screen edge the bar rests on. On the left and right edges it stands upright. */
enum class DockEdge { BOTTOM, TOP, LEFT, RIGHT;
    val upright: Boolean get() = this == LEFT || this == RIGHT
}

/** The bar's place: its [edge], and for the side edges how far down the screen its centre is (0 top, 1 bottom). */
data class Dock(val edge: DockEdge, val along: Float = 0.5f) {
    fun save(): String = "${edge.name}:$along"

    companion object {
        val DEFAULT = Dock(DockEdge.BOTTOM)

        fun parse(s: String?): Dock {
            val parts = s?.split(':') ?: return DEFAULT
            val edge = runCatching { DockEdge.valueOf(parts[0]) }.getOrNull() ?: return DEFAULT
            val along = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(FloatDock.ALONG_MIN, FloatDock.ALONG_MAX) ?: 0.5f
            return Dock(edge, along)
        }
    }
}

/** A rectangle on the screen. */
data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val w: Float get() = right - left
    val h: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2
    val centreY: Float get() = (top + bottom) / 2
}

/** Which way the videos lean across the screen. */
enum class Lean { LEFT, CENTRE, RIGHT }

/** Up and down: at the top (the original place), at the bottom, or where they were dropped ([VideoPlace.top]). */
enum class Height { TOP, BOTTOM, FREE }

/** Where the videos rest, on their own: [lean] across, [height] up and down ([top]: their top edge, for FREE). */
data class VideoPlace(val lean: Lean = Lean.CENTRE, val height: Height = Height.TOP, val top: Float = 0f) {
    companion object {
        val DEFAULT = VideoPlace()
    }
}

/** The screen as the floating window sees it: its size, and the room the status and navigation bars take. */
data class FloatScreen(val w: Float, val h: Float, val topInset: Float, val bottomInset: Float, val margin: Float)

object FloatDock {
    const val ALONG_MIN = 0.12f
    const val ALONG_MAX = 0.88f
    /** The share of the screen's width at each side where a dropped bar goes to that edge. */
    const val SIDE_SHARE = 0.22f
    /** Nobody has talked for this long: the bar folds into its edge. */
    const val QUIET_MS = 8_000L

    /**
     * Where a bar let go with its centre at [x], [y] rests: the left or right edge if it is near one (and there at
     * that height), else the top or bottom, whichever half it is in.
     */
    fun nearest(x: Float, y: Float, screen: FloatScreen): Dock {
        val along = (y / screen.h).coerceIn(ALONG_MIN, ALONG_MAX)
        return when {
            x < screen.w * SIDE_SHARE -> Dock(DockEdge.LEFT, along)
            x > screen.w * (1 - SIDE_SHARE) -> Dock(DockEdge.RIGHT, along)
            y < screen.h / 2 -> Dock(DockEdge.TOP)
            else -> Dock(DockEdge.BOTTOM)
        }
    }

    /**
     * The bar folds into its edge now: nobody has talked for [quietMs] (or the keyboard is up, or an app went full
     * screen), and nothing that must stay in view is going on: my push held or hands-free, live video, a call ringing.
     */
    fun folds(quietMs: Long, keyboard: Boolean, fullScreen: Boolean, holding: Boolean, liveVideo: Boolean, ringing: Boolean): Boolean {
        if (holding || liveVideo || ringing) return false
        return keyboard || fullScreen || quietMs >= QUIET_MS
    }

    /**
     * Where videos let go at [at] (their bounding box now) rest: the top or bottom when near either (the top is the
     * original place, just below the status bar), else where they were dropped; leaning to the side they are nearer
     * to, or centred.
     */
    fun dropVideos(at: ScreenRect, screen: FloatScreen): VideoPlace {
        val lean = when {
            at.centreX < screen.w / 3 -> Lean.LEFT
            at.centreX > screen.w * 2 / 3 -> Lean.RIGHT
            else -> Lean.CENTRE
        }
        val near = screen.h * 0.12f
        val height = when {
            at.top < screen.topInset + near -> Height.TOP
            at.bottom > screen.h - screen.bottomInset - near -> Height.BOTTOM
            else -> Height.FREE
        }
        val top = at.top.coerceIn(screen.topInset, (screen.h - screen.bottomInset - at.h).coerceAtLeast(screen.topInset))
        return VideoPlace(lean, height, if (height == Height.FREE) top else 0f)
    }

    /**
     * The videos are at the bar's place, so they go with it: beside it on a side edge (when they lean to that side and
     * overlap it up and down), under it at the top, above it at the bottom.
     */
    fun joined(place: VideoPlace, alone: ScreenRect, dock: Dock, bar: ScreenRect): Boolean = when (dock.edge) {
        DockEdge.TOP -> place.height == Height.TOP
        DockEdge.BOTTOM -> place.height == Height.BOTTOM
        DockEdge.LEFT -> place.lean == Lean.LEFT && alone.top < bar.bottom && alone.bottom > bar.top
        DockEdge.RIGHT -> place.lean == Lean.RIGHT && alone.top < bar.bottom && alone.bottom > bar.top
    }

    /**
     * The screen edge the videos at [place] come in through and go out by (2026-11-27, hen: "if the video is docked
     * any edge it should exit and enter from that edge"): the bar's edge while they are joined to it ([joined]), else
     * the edge nearest to where they rest, the top when it is as near as any.
     */
    fun videosEdge(place: VideoPlace, w: Float, h: Float, screen: FloatScreen, dock: Dock?, bar: ScreenRect?, gap: Float): DockEdge {
        val (x, y) = videosAt(place, w, h, screen, dock, bar, gap)
        val alone = alone(place, w, h, screen)
        if (dock != null && bar != null && joined(place, alone, dock, bar)) return dock.edge
        val away = listOf(
            DockEdge.TOP to y - screen.topInset,
            DockEdge.BOTTOM to screen.h - screen.bottomInset - (y + h),
            DockEdge.LEFT to x,
            DockEdge.RIGHT to screen.w - (x + w),
        )
        return away.minBy { it.second }.first
    }

    private fun alone(place: VideoPlace, w: Float, h: Float, screen: FloatScreen): ScreenRect {
        val m = screen.margin
        val x = when (place.lean) {
            Lean.LEFT -> m
            Lean.CENTRE -> (screen.w - w) / 2
            Lean.RIGHT -> screen.w - m - w
        }
        val y = when (place.height) {
            Height.TOP -> screen.topInset
            Height.BOTTOM -> screen.h - screen.bottomInset - m - h
            Height.FREE -> place.top
        }
        return ScreenRect(x, y, x + w, y + h)
    }

    /**
     * Where videos [w] by [h] rest (their top left on the screen) at [place], with the bar at [dock] taking [bar] (its
     * resting frame with the room its hints need; null: no bar). Joined to the bar ([joined]), they sit beside it
     * [gap] away; else where [place] says, whatever the bar does.
     */
    fun videosAt(place: VideoPlace, w: Float, h: Float, screen: FloatScreen, dock: Dock?, bar: ScreenRect?, gap: Float): Pair<Float, Float> {
        val alone = alone(place, w, h, screen)
        val x = alone.left
        val y = alone.top
        if (dock == null || bar == null || !joined(place, alone, dock, bar)) return x to y
        val lowest = (screen.h - screen.bottomInset - h).coerceAtLeast(screen.topInset)
        return when (dock.edge) {
            DockEdge.TOP -> x to bar.bottom + gap
            DockEdge.BOTTOM -> x to bar.top - gap - h
            DockEdge.LEFT -> bar.right + gap to (bar.centreY - h / 2).coerceIn(screen.topInset, lowest)
            DockEdge.RIGHT -> bar.left - gap - w to (bar.centreY - h / 2).coerceIn(screen.topInset, lowest)
        }
    }
}
