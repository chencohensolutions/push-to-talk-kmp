package com.chencohen.ptt.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The floating bar's edges, its folding, and the videos' own place (2026-11-27, hen). */
class FloatDockTest {
    private val screen = FloatScreen(w = 1080f, h = 2340f, topInset = 100f, bottomInset = 130f, margin = 24f)

    @Test
    fun aBarLetGoNearASideGoesToThatEdgeAtThatHeight() {
        assertEquals(Dock(DockEdge.LEFT, 0.5f), FloatDock.nearest(100f, 1170f, screen))
        assertEquals(DockEdge.RIGHT, FloatDock.nearest(1000f, 600f, screen).edge)
        assertEquals(600f / 2340f, FloatDock.nearest(1000f, 600f, screen).along, 0.001f)
        // Not into a corner: the along is kept off the very ends.
        assertEquals(FloatDock.ALONG_MAX, FloatDock.nearest(1000f, 2330f, screen).along, 0.001f)
    }

    @Test
    fun aBarLetGoInTheMiddleGoesToTheTopOrBottom() {
        assertEquals(Dock(DockEdge.TOP), FloatDock.nearest(540f, 500f, screen))
        assertEquals(Dock(DockEdge.BOTTOM), FloatDock.nearest(540f, 1800f, screen))
    }

    @Test
    fun aDockIsSavedAndReadBack() {
        val dock = Dock(DockEdge.RIGHT, 0.3f)
        assertEquals(dock, Dock.parse(dock.save()))
        assertEquals(Dock.DEFAULT, Dock.parse(null))
        assertEquals(Dock.DEFAULT, Dock.parse("SIDEWAYS:0.4"))
    }

    @Test
    fun theBarFoldsOnlyWhenQuietAndNothingMustStayInView() {
        assertFalse(FloatDock.folds(3_000, keyboard = false, fullScreen = false, holding = false, liveVideo = false, ringing = false))
        assertTrue(FloatDock.folds(8_000, keyboard = false, fullScreen = false, holding = false, liveVideo = false, ringing = false))
        assertTrue(FloatDock.folds(0, keyboard = true, fullScreen = false, holding = false, liveVideo = false, ringing = false))
        assertTrue(FloatDock.folds(0, keyboard = false, fullScreen = true, holding = false, liveVideo = false, ringing = false))
        // Never while Push is held, video is live or a call rings.
        assertFalse(FloatDock.folds(60_000, keyboard = true, fullScreen = false, holding = true, liveVideo = false, ringing = false))
        assertFalse(FloatDock.folds(60_000, keyboard = true, fullScreen = false, holding = false, liveVideo = true, ringing = false))
        assertFalse(FloatDock.folds(60_000, keyboard = false, fullScreen = false, holding = false, liveVideo = false, ringing = true))
    }

    @Test
    fun videosStayAtTheTopWhateverTheBarDoes() {
        val place = VideoPlace.DEFAULT
        val bar = ScreenRect(980f, 1000f, 1080f, 1300f)
        val (x, y) = FloatDock.videosAt(place, 700f, 500f, screen, Dock(DockEdge.RIGHT, 0.5f), bar, 12f)
        assertEquals((1080f - 700f) / 2, x, 0.01f)
        assertEquals(100f, y, 0.01f)
    }

    @Test
    fun videosJoinTheBarOnlyAtItsPlace() {
        // Bar at the top, videos at the top: under the bar.
        val topBar = ScreenRect(390f, 100f, 690f, 250f)
        assertEquals(262f, FloatDock.videosAt(VideoPlace.DEFAULT, 700f, 500f, screen, Dock(DockEdge.TOP), topBar, 12f).second, 0.01f)
        // Bar on the right, videos dropped at the right beside it: they sit at its inner side, level with it.
        val sideBar = ScreenRect(980f, 1000f, 1080f, 1300f)
        val dropped = VideoPlace(Lean.RIGHT, Height.FREE, 1000f)
        val (x, y) = FloatDock.videosAt(dropped, 400f, 500f, screen, Dock(DockEdge.RIGHT, 0.49f), sideBar, 12f)
        assertEquals(980f - 12f - 400f, x, 0.01f)
        assertEquals(1150f - 250f, y, 0.01f)
        // The same videos on the left: not joined, they stay where they were dropped.
        val left = VideoPlace(Lean.LEFT, Height.FREE, 1000f)
        assertEquals(24f to 1000f, FloatDock.videosAt(left, 400f, 500f, screen, Dock(DockEdge.RIGHT, 0.49f), sideBar, 12f))
    }

    @Test
    fun droppedVideosSnapToTheTopOrBottomWhenNear() {
        assertEquals(VideoPlace(Lean.CENTRE, Height.TOP, 0f), FloatDock.dropVideos(ScreenRect(190f, 150f, 890f, 650f), screen))
        assertEquals(Height.BOTTOM, FloatDock.dropVideos(ScreenRect(190f, 1750f, 890f, 2250f), screen).height)
        val free = FloatDock.dropVideos(ScreenRect(20f, 900f, 420f, 1400f), screen)
        assertEquals(VideoPlace(Lean.LEFT, Height.FREE, 900f), free)
    }

    @Test
    fun videosComeAndGoByTheEdgeTheyRestAt() {
        // Alone: the nearest edge to where they rest.
        assertEquals(DockEdge.TOP, FloatDock.videosEdge(VideoPlace.DEFAULT, 700f, 500f, screen, null, null, 12f))
        assertEquals(DockEdge.BOTTOM, FloatDock.videosEdge(VideoPlace(Lean.CENTRE, Height.BOTTOM), 700f, 500f, screen, null, null, 12f))
        assertEquals(DockEdge.LEFT, FloatDock.videosEdge(VideoPlace(Lean.LEFT, Height.FREE, 900f), 400f, 500f, screen, null, null, 12f))
        assertEquals(DockEdge.RIGHT, FloatDock.videosEdge(VideoPlace(Lean.RIGHT, Height.FREE, 900f), 400f, 500f, screen, null, null, 12f))
        // Joined to the bar: the bar's edge, even where another edge is nearer.
        val sideBar = ScreenRect(940f, 900f, 1080f, 1500f)
        assertEquals(DockEdge.RIGHT, FloatDock.videosEdge(VideoPlace(Lean.RIGHT, Height.FREE, 1000f), 400f, 500f, screen, Dock(DockEdge.RIGHT, 0.5f), sideBar, 12f))
        val bottomBar = ScreenRect(200f, 2000f, 880f, 2210f)
        assertEquals(DockEdge.BOTTOM, FloatDock.videosEdge(VideoPlace(Lean.CENTRE, Height.BOTTOM), 700f, 500f, screen, Dock(DockEdge.BOTTOM), bottomBar, 12f))
    }

    @Test
    fun anItemComesInAndGoesOutThroughItsEdge() {
        val rest = LiveRect(100f, 0f, 300f, 400f, 1f)
        val away = LiveAway(DockEdge.LEFT, left = -50f, top = -200f, right = 1100f, bottom = 2000f)
        val motion = LiveMotion()
        motion.target(mapOf("a" to rest), away)
        // In: starts just past the left edge at its own height and size.
        assertEquals(LiveRect(-350f, 0f, 300f, 400f, 1f), motion.rects.getValue("a"))
        motion.frame(0); motion.frame(10_000)
        assertEquals(rest, motion.rects.getValue("a"))
        // Out the same way, to the right one if the edge is the right.
        motion.target(emptyMap(), away.copy(edge = DockEdge.RIGHT))
        motion.frame(20_000); motion.frame(20_150)
        val half = motion.rects.getValue("a")
        assertTrue(half.x > rest.x && half.y == rest.y)
        motion.frame(30_000)
        assertTrue(motion.rects.isEmpty())
    }
}
