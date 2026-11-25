package com.chencohen.ptt.ui.contacts

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.ui.live.LiveLayout
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The chat's live row as it is really laid out, in Hebrew and in English (2026-11-25, hen: on his Hebrew phone mine
 * came in at its final place and then both moved, and the growing box shifted sideways): each item's frame on the
 * screen, frame by frame, counted from the left edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveStageTest {
    /** A frame on the screen: its left edge, top, width and height in pixels. */
    private data class Seen(val x: Float, val y: Float, val w: Int, val h: Int) {
        val centreX get() = x + w / 2f
    }

    private val sides = mutableStateOf(listOf<LiveSide>())
    private val seen = HashMap<String, Seen>()
    private var root: androidx.compose.ui.platform.ViewRootForTest? = null
    private var window: android.view.View? = null
    private var origin = androidx.compose.ui.geometry.Offset.Zero

    /** The row in a 400 × 700 space, one pixel a dp, pills 120 × 60, boxes 200 high; then [steps] run on it. */
    private fun stage(direction: LayoutDirection, steps: () -> Unit) {
        // One display frame for each 16 ms the clock is moved on, not all of them at once.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        sides.value = emptyList()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // The frame around a pill or a box is its parent's parent (the stage's own Box).
        fun note(key: String) = Modifier.onGloballyPositioned { inside: LayoutCoordinates ->
            val frame = inside.parentLayoutCoordinates?.parentLayoutCoordinates ?: inside.parentLayoutCoordinates ?: return@onGloballyPositioned
            // Counted from the stage's own left edge and top.
            seen[key] = frame.positionInRoot().let { Seen(it.x - origin.x, it.y - origin.y, frame.size.width, frame.size.height) }
        }
        activity.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(400.dp, 700.dp).onGloballyPositioned { origin = it.positionInRoot() }) {
                    LiveStage(
                        sides.value, boxHeight = 200.dp, ring = { Color.Red },
                        pill = { side -> Box(note(side.key).size(120.dp, 60.dp)) },
                    ) { side, w, h, _ -> Box(Modifier.requiredSize(w, h)) { Box(note(side.key).size(1.dp)) } }
                }
            }
        }
        window = activity.window.decorView
        root = null
        steps()
    }

    /** One frame, 16 ms on; what each item looks like after it (null: not on the stage). */
    private fun frame(): Map<String, Seen> {
        seen.clear()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS)
        // No display here to ask for the layout: asked by hand, as Compose's own tests do.
        fun find(v: android.view.View): androidx.compose.ui.platform.ViewRootForTest? =
            v as? androidx.compose.ui.platform.ViewRootForTest ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        if (root == null) root = window?.let { find(it) }
        root?.measureAndLayoutForTest()
        return HashMap(seen)
    }

    /** Frames until the row has been still for a while (a move is 300 ms): each frame's items, while they moved. */
    private fun move(): List<Map<String, Seen>> {
        val out = ArrayList<Map<String, Seen>>()
        var last: Map<String, Seen>? = null
        var still = 0
        val now = HashMap<String, Seen>()
        while (still < 30 && out.size < 200) {
            val changed = frame()
            // Only what was placed again shows in a frame: the rest is where it was.
            now.putAll(changed)
            now.keys.retainAll(sides.value.map { it.key }.toSet() + changed.keys)
            if (now == last) still++ else {
                still = 0
                out += HashMap(now)
            }
            last = HashMap(now)
        }
        return out
    }

    private val theirs = LiveLayout.THEIRS
    private val mine = LiveLayout.MINE

    @Test
    fun inHebrewMineComesInAtItsOwnPlaceWhileTheirsGlidesToTheRight() = stage(LayoutDirection.Rtl) {
        sides.value = listOf(LiveSide(theirs, false, 0.75f))
        val alone = move().last().getValue(theirs)
        // Alone: centred, 8 px under the stage's top.
        assertEquals(200f, alone.centreX, 0.5f)
        assertEquals(8f, alone.y, 0.5f)
        sides.value = listOf(LiveSide(theirs, false, 0.75f), LiveSide(mine, false, 0.75f))
        val frames = move().filter { mine in it }
        assertTrue("frames of the move: ${frames.size}", frames.size > 8)
        // Mine: at the left (Hebrew), at that x from its first frame to its last, coming down from above the top.
        val mineX = frames.map { it.getValue(mine).centreX }
        assertTrue("mine's centre $mineX", mineX.all { kotlin.math.abs(it - mineX.last()) <= 0.5f })
        assertEquals(200f - 3f - 60f, mineX.last(), 0.5f)
        assertTrue(frames.first().getValue(mine).y < -40f)
        assertEquals(8f, frames.last().getValue(mine).y, 0.5f)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.getValue(mine).y >= a.getValue(mine).y })
        // Theirs: from the centre to the right, in the same frames, never the other way.
        val theirX = frames.map { it.getValue(theirs).centreX }
        assertTrue("theirs' centre $theirX", theirX.zipWithNext().all { (a, b) -> b >= a - 0.5f })
        assertEquals(200f + 3f + 60f, theirX.last(), 0.5f)
        val mid = frames[frames.size / 2]
        assertTrue(mid.getValue(theirs).centreX > 201f && mid.getValue(theirs).centreX < 262f && mid.getValue(mine).y < 7f)
    }

    @Test
    fun inEnglishTheSameMoveIsMirroredOnlyInItsOrder() = stage(LayoutDirection.Ltr) {
        sides.value = listOf(LiveSide(theirs, false, 0.75f))
        assertEquals(200f, move().last().getValue(theirs).centreX, 0.5f)
        sides.value = listOf(LiveSide(theirs, false, 0.75f), LiveSide(mine, false, 0.75f))
        val frames = move().filter { mine in it }
        assertTrue(frames.map { it.getValue(mine).centreX }.all { kotlin.math.abs(it - (200f + 3f + 60f)) <= 0.5f })
        assertEquals(200f - 3f - 60f, frames.last().getValue(theirs).centreX, 0.5f)
        assertTrue(frames.map { it.getValue(theirs).centreX }.zipWithNext().all { (a, b) -> b <= a + 0.5f })
    }

    @Test
    fun voiceIntoVideoGrowsAboutItsCentreInEitherDirection() {
        for (direction in listOf(LayoutDirection.Rtl, LayoutDirection.Ltr)) stage(direction) {
            sides.value = listOf(LiveSide(mine, false, 0.75f))
            val pill = move().last().getValue(mine)
            assertEquals(120, pill.w)
            sides.value = listOf(LiveSide(mine, true, 0.75f))
            val frames = move()
            assertTrue("frames of the growth: ${frames.size}", frames.size > 8)
            // One frame growing: its centre and its top where the pill's were, in every frame.
            assertTrue(frames.all { kotlin.math.abs(it.getValue(mine).centreX - 200f) <= 0.5f && kotlin.math.abs(it.getValue(mine).y - 8f) <= 0.5f })
            assertTrue(frames.zipWithNext().all { (a, b) -> b.getValue(mine).h >= a.getValue(mine).h })
            assertEquals(150, frames.last().getValue(mine).w)
            assertEquals(200, frames.last().getValue(mine).h)
            sides.value = emptyList()
            // It leaves upward where it is, and is gone.
            val leaving = move()
            assertTrue(leaving.dropLast(1).all { kotlin.math.abs(it.getValue(mine).centreX - 200f) <= 0.5f })
            assertNull(leaving.last()[mine])
        }
    }
}
