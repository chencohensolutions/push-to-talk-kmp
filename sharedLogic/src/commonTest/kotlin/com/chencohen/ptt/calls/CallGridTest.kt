package com.chencohen.ptt.calls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallGridTest {
    private val portrait = 1080f to 2340f
    private val landscape = 2340f to 1080f

    private fun shape(count: Int, screen: Pair<Float, Float>) = CallGrid.shape(count, screen.first, screen.second)

    @Test
    fun theGridGivesTheBiggestEqualTilesForTheScreen() {
        assertEquals(CallGrid.Shape(1, 1), shape(1, portrait))
        assertEquals(CallGrid.Shape(1, 2), shape(2, portrait))
        assertEquals(CallGrid.Shape(2, 1), shape(2, landscape))
        assertEquals(CallGrid.Shape(2, 2), shape(4, portrait))
        assertEquals(CallGrid.Shape(2, 3), shape(6, portrait))
        assertEquals(CallGrid.Shape(3, 2), shape(6, landscape))
        assertEquals(CallGrid.Shape(1, 3), shape(3, portrait), "3 on a tall phone: three wide tiles beat two rows of two")
        assertEquals(CallGrid.Shape(3, 1), shape(3, landscape))
        // A squarer phone (a foldable open) still gets equal tiles.
        assertEquals(CallGrid.Shape(2, 2), CallGrid.shape(4, 1800f, 2100f))
    }

    @Test
    fun aCentredFaceNeedsNoMove() {
        // A 3:4 picture covering a 540×780 tile, the face in its middle at the head-and-shoulders size already.
        val (cw, ch) = CallGrid.content(540f, 780f, 0.75f, cover = true)
        val p = CallGrid.place(540f, 780f, cw, ch, faceX = 0.5f, faceY = 0.45f, faceHeight = 0.45f * 780f / ch, maxZoom = 2f)
        assertEquals(1f, p.scale, 0.01f)
        assertEquals(0f, p.x + (540f - cw) / 2 * 0f, 1f)
    }

    @Test
    fun anOffCentreFaceIsBroughtToTheMiddleAtTheSameScaleInEveryTile() {
        val tiles = listOf(540f to 780f, 360f to 1170f, 720f to 540f)
        for ((tw, th) in tiles) {
            val (cw, ch) = CallGrid.content(tw, th, 0.75f, cover = true)
            val faceH = 0.15f
            val p = CallGrid.place(tw, th, cw, ch, faceX = 0.3f, faceY = 0.3f, faceHeight = faceH, maxZoom = 2f)
            val ox = (tw - cw) / 2
            val oy = (th - ch) / 2
            // Where the face lands, and how tall it is, in the tile.
            val fx = ox + 0.3f * cw * p.scale + p.x
            val fy = oy + 0.3f * ch * p.scale + p.y
            val shownFace = faceH * ch * p.scale / th
            if (p.scale < 2f) assertEquals(0.45f, shownFace, 0.01f, "the same head-and-shoulders scale in a ${tw}x$th tile")
            // The tile stays covered: no empty edge.
            assertTrue(ox + p.x <= 0.5f && ox + cw * p.scale + p.x >= tw - 0.5f, "covered across in ${tw}x$th")
            assertTrue(oy + p.y <= 0.5f && oy + ch * p.scale + p.y >= th - 0.5f, "covered down in ${tw}x$th")
            // As near the middle as the picture allows.
            assertTrue(fx <= tw / 2 + 1f, "face brought toward the middle across in ${tw}x$th")
            assertTrue(fy <= th * 0.45f + 1f, "face brought toward the middle down in ${tw}x$th")
        }
    }

    @Test
    fun zoomIsCappedAndNeverBelowOne() {
        val (cw, ch) = CallGrid.content(540f, 780f, 0.75f, cover = true)
        assertEquals(2f, CallGrid.place(540f, 780f, cw, ch, 0.5f, 0.5f, faceHeight = 0.02f, maxZoom = 2f).scale)
        assertEquals(1f, CallGrid.place(540f, 780f, cw, ch, 0.5f, 0.5f, faceHeight = 0.9f, maxZoom = 2f).scale)
    }

    @Test
    fun aFittedPictureNeverShowsMoreThanItHad() {
        // Normal: the picture fits the tile exactly; following only zooms in and stays inside it.
        val p = CallGrid.place(540f, 720f, 540f, 720f, faceX = 0.9f, faceY = 0.1f, faceHeight = 0.2f, maxZoom = 1.8f)
        assertTrue(p.x <= 0f && 540f * p.scale + p.x >= 540f)
        assertTrue(p.y <= 0f && 720f * p.scale + p.y >= 720f)
    }
}
