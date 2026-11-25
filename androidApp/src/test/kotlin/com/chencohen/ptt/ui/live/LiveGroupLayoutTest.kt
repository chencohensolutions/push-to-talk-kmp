package com.chencohen.ptt.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A group's live overlay (2026-11-25, hen: several people talking at once): the videos' row, the pills under it, who
 * is in view of more than four, and that every change (one joining, one pushed out, 1 to 4 boxes) is one move.
 */
class LiveGroupLayoutTest {
    private val width = 1056f
    private val gap = 18f
    private val full = 566f
    private val shape = 0.75f
    private val off = 150f
    private val mine = LiveLayout.MINE

    private fun video(key: String) = LiveSlot(key, true)
    private fun voice(key: String, w: Float = 330f) = LiveSlot(key, false, w, 168f)

    private fun place(vararg slots: LiveSlot, rtl: Boolean = true, chip: Pair<Float, Float>? = null) =
        LiveGroupLayout.place(slots.toList(), width, gap, full, shape, rtl, chip = chip)

    /** A motion resting at [rects]. */
    private fun resting(rects: Map<String, LiveRect>) = LiveMotion().also {
        it.target(rects, off)
        it.frame(0)
        it.frame(1_000)
        assertFalse(it.running)
    }

    private fun frames(motion: LiveMotion, from: Long = 2_000): List<Map<String, LiveRect>> {
        val out = ArrayList<Map<String, LiveRect>>()
        var t = from
        while (motion.running) {
            motion.frame(t)
            out += motion.rects
            t += 16
        }
        return out
    }

    @Test
    fun boxesAreEqualAndKeepTheirHeightUntilTheyCantFit() {
        // One and two 3:4 boxes fit at their full height; three are narrower (more cropped) at the same height; four
        // would be slimmer than 9:16, so the row gets lower too.
        assertEquals(full * shape to full, LiveGroupLayout.boxSize(1, full, shape, width, gap))
        assertEquals(full * shape to full, LiveGroupLayout.boxSize(2, full, shape, width, gap))
        val (w3, h3) = LiveGroupLayout.boxSize(3, full, shape, width, gap)
        assertEquals((width - 2 * gap) / 3, w3, 0.01f)
        assertEquals(full, h3, 0f)
        val (w4, h4) = LiveGroupLayout.boxSize(4, full, shape, width, gap)
        assertEquals((width - 3 * gap) / 4, w4, 0.01f)
        assertEquals(w4 / LiveGroupLayout.SLIMMEST, h4, 0.01f)
        assertTrue(h4 < full)
    }

    @Test
    fun threeVideosSitInOneRowCentredTheFirstAtTheLanguagesStartSide() {
        for (rtl in listOf(true, false)) {
            val rects = place(video("a.1"), video("b.1"), video(mine), rtl = rtl)
            val all = rects.values
            assertTrue(all.all { it.y == 0f && it.h == full && it.video == 1f })
            assertEquals(1, all.map { it.w }.distinct().size)
            assertEquals(width / 2, (all.minOf { it.x } + all.maxOf { it.x + it.w }) / 2, 0.01f)
            // Exactly the width: the gaps between them, the edges at the edges.
            assertEquals(0f, all.minOf { it.x }, 0.01f)
            assertEquals(width, all.maxOf { it.x + it.w }, 0.01f)
            val order = rects.entries.sortedBy { it.value.x }.map { it.key }
            assertEquals(if (rtl) listOf(mine, "b.1", "a.1") else listOf("a.1", "b.1", mine), order)
        }
    }

    @Test
    fun fourWithVideoAndVoiceStackThePillsUnderTheBoxes() {
        val rects = place(video("a.1"), voice("b.1"), video("c.1"), voice(mine), rtl = false)
        val a = rects.getValue("a.1")
        val c = rects.getValue("c.1")
        assertEquals(full, a.h, 0f)
        assertEquals(a.w, c.w, 0f)
        assertTrue(a.x < c.x)
        // The pills: one row under the boxes, the gap between, centred, tops level.
        val b = rects.getValue("b.1")
        val m = rects.getValue(mine)
        assertEquals(full + gap, b.y, 0f)
        assertEquals(b.y, m.y, 0f)
        assertEquals(330f, b.w, 0f)
        assertEquals(width / 2, (b.x + m.x + m.w) / 2, 0.01f)
        // No video: the pills are the top row.
        val voices = place(voice("a.1"), voice("b.1"), voice(mine))
        assertTrue(voices.values.all { it.y == 0f })
    }

    @Test
    fun pillsThatDontFitShowTheirFacesOnly() {
        val slots = listOf(voice("a.1", 400f), voice("b.1", 400f), voice("c.1", 400f), voice(mine, 300f))
        assertTrue(LiveGroupLayout.facesOnly(slots, width, gap))
        val rects = LiveGroupLayout.place(slots, width, gap, full, shape, rtl = true)
        assertTrue(rects.values.all { it.w == 168f })
        assertEquals(width / 2, (rects.values.minOf { it.x } + rects.values.maxOf { it.x + it.w }) / 2, 0.01f)
        assertFalse(LiveGroupLayout.facesOnly(slots.take(2), width, gap))
    }

    @Test
    fun theChipSitsCentredUnderEverything() {
        val rects = place(video("a.1"), voice("b.1"), chip = 300f to 60f)
        val chip = rects.getValue(LiveGroupLayout.MORE)
        assertEquals(full + gap + 168f + gap, chip.y, 0f)
        assertEquals(width / 2, chip.centreX, 0.01f)
    }

    @Test
    fun oneToFourVideosEachJoinInOneMoveTheOthersNarrowingTogether() {
        for (rtl in listOf(true, false)) {
            val keys = listOf("a.1", "b.1", "c.1", "d.1")
            var motion = resting(place(video(keys[0]), rtl = rtl))
            for (n in 2..4) {
                val goal = place(*keys.take(n).map { video(it) }.toTypedArray(), rtl = rtl)
                val before = motion.rects
                assertTrue(motion.target(goal, off))
                val all = frames(motion)
                assertTrue("frames of $n: ${all.size}", all.size > 10)
                val newcomer = keys[n - 1]
                // The newcomer: in its own place sideways and at its final size from the first frame, coming down.
                assertTrue(all.all { it.getValue(newcomer).x == goal.getValue(newcomer).x && it.getValue(newcomer).w == goal.getValue(newcomer).w })
                assertTrue(all.map { it.getValue(newcomer).y }.zipWithNext().all { (p, q) -> q >= p })
                // The others: narrowing (and lowering at 4) and gliding over the same frames, all equal at every frame.
                for (frame in all) assertEquals(1, keys.take(n - 1).map { frame.getValue(it).w }.distinct().size)
                for (k in keys.take(n - 1)) {
                    val ws = all.map { it.getValue(k).w }
                    assertTrue(ws.zipWithNext().all { (p, q) -> q <= p + 0.01f })
                    assertEquals(goal.getValue(k), all.last().getValue(k))
                }
                // Together: halfway, the newcomer is still above and the first has moved part of the way.
                val mid = all[all.size / 2]
                assertTrue(mid.getValue(newcomer).y < -1f)
                val k0 = keys[0]
                assertTrue(kotlin.math.abs(mid.getValue(k0).x - before.getValue(k0).x) > 1f || n == 4 && kotlin.math.abs(mid.getValue(k0).w - before.getValue(k0).w) > 1f)
                motion = resting(goal)
            }
        }
    }

    @Test
    fun aFifthPushesOutTheQuietestAndTheRestCloseUpInOneMove() {
        val cast = LiveCast()
        val a = LivePerson("a.1", 1_000)
        val b = LivePerson("b.1", 2_000)
        val c = LivePerson("c.1", 3_000)
        val me = LivePerson(mine, 4_000)
        val e = LivePerson("e.1", 5_000)
        assertEquals(listOf("a.1", "b.1", "c.1", mine), cast.update(listOf(a, b, c, me)))
        assertTrue(cast.hidden.isEmpty())
        // The fifth: a.1 (quiet longest) goes, e.1 comes in at the end of the others, mine stays last.
        assertEquals(listOf("b.1", "c.1", "e.1", mine), cast.update(listOf(a, b, c, me, e)))
        assertEquals(listOf("a.1"), cast.hidden.map { it.key })
        // The move: a.1 goes up where it is, b.1 and c.1 slide one place towards the start, e.1 comes down into the
        // place c.1 had, mine stays put; all in the same frames.
        for (rtl in listOf(true, false)) {
            val before = place(video("a.1"), video("b.1"), video("c.1"), voice(mine), rtl = rtl)
            val after = place(video("b.1"), video("c.1"), video("e.1"), voice(mine), rtl = rtl)
            val motion = resting(before)
            assertTrue(motion.target(after, off))
            val all = frames(motion)
            val out = all.dropLast(1).map { it.getValue("a.1") }
            assertTrue(out.all { it.x == before.getValue("a.1").x })
            assertTrue(out.zipWithNext().all { (p, q) -> q.y <= p.y })
            assertNull(all.last()["a.1"])
            assertEquals(before.getValue("c.1").x, after.getValue("e.1").x, 0.01f)
            assertEquals(before.getValue("a.1").x, after.getValue("b.1").x, 0.01f)
            assertTrue(all.all { it.getValue(mine) == before.getValue(mine) })
            val mid = all[all.size / 2]
            assertTrue(mid.getValue("e.1").y < -1f && mid.getValue("a.1").y < -1f)
            assertTrue(kotlin.math.abs(mid.getValue("b.1").x - before.getValue("b.1").x) > 1f)
        }
        // The chip's choice: a.1 back into view in the place of the quietest (b.1), nothing else moves.
        assertEquals(listOf("a.1", "c.1", "e.1", mine), cast.choose("a.1", 6_000))
        assertEquals(listOf("b.1"), cast.hidden.map { it.key })
        // Someone leaving makes room: no one is brought back by itself (the chip still offers b.1).
        assertEquals(listOf("a.1", "e.1", mine), cast.update(listOf(a, b, me, e)))
        assertEquals(listOf("b.1"), cast.hidden.map { it.key })
        // b.1 pushes again: a fresh push, in view at once.
        assertEquals(listOf("a.1", "e.1", "b.1", mine), cast.update(listOf(a, LivePerson("b.1", 7_000), me, e)))
    }

    @Test
    fun withoutMineFourOthersFitAndMineTakesOneOfTheFour() {
        val cast = LiveCast()
        val four = (1..4).map { LivePerson("u$it.1", it * 1_000L) }
        assertEquals(four.map { it.key }, cast.update(four))
        // I push: the one quiet longest makes room for mine.
        assertEquals(listOf("u2.1", "u3.1", "u4.1", mine), cast.update(four + LivePerson(mine, 9_000)))
        assertEquals(listOf("u1.1"), cast.hidden.map { it.key })
    }
}
