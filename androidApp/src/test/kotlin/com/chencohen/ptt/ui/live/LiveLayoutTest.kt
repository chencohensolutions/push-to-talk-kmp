package com.chencohen.ptt.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live overlay's places and moves (2026-11-25, hen: "every combined animation in one move"; his phone is in
 * Hebrew): where pills and boxes rest, and that every change is one move for all of them, in either direction.
 */
class LiveLayoutTest {
    private val width = 1080f
    private val gap = 16f
    private val off = 150f
    private val theirPill = LiveItem(LiveLayout.THEIRS, 368f, 166f, video = false)
    private val myPill = LiveItem(LiveLayout.MINE, 390f, 166f, video = false)
    private val theirBox = LiveItem(LiveLayout.THEIRS, 339f, 452f, video = true)
    private val myBox = LiveItem(LiveLayout.MINE, 339f, 452f, video = true)

    private fun place(vararg items: LiveItem, rtl: Boolean = true) = LiveLayout.place(items.toList(), width, gap, rtl)

    /** A motion resting at [items]. */
    private fun resting(vararg items: LiveItem, rtl: Boolean = true) = LiveMotion().also {
        it.target(place(*items, rtl = rtl), off)
        it.frame(0)
        it.frame(1_000)
        assertFalse(it.running)
    }

    /** Every frame of the move [motion] is in, 16 ms apart, from its start to its end. */
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
    fun oneItemIsCentred() {
        for (rtl in listOf(true, false)) {
            val r = place(theirPill, rtl = rtl).getValue(LiveLayout.THEIRS)
            assertEquals(540f, r.centreX, 0.01f)
            assertEquals(0f, r.y, 0f)
            assertEquals(368f, r.w, 0f)
        }
    }

    @Test
    fun twoItemsAreCentredTogetherTheirsAtTheLanguagesStartSide() {
        val hebrew = place(theirPill, myPill, rtl = true)
        val english = place(theirPill, myPill, rtl = false)
        for (rects in listOf(hebrew, english)) {
            val left = rects.values.minOf { it.x }
            val right = rects.values.maxOf { it.x + it.w }
            // The pair is centred, side by side, the gap between them.
            assertEquals(540f, (left + right) / 2, 0.01f)
            assertEquals(368f + 390f + gap, right - left, 0.01f)
            assertTrue(rects.values.all { it.y == 0f })
        }
        // Hebrew: theirs at the right, mine at the left. English: the other way.
        assertTrue(hebrew.getValue(LiveLayout.THEIRS).x > hebrew.getValue(LiveLayout.MINE).x)
        assertTrue(english.getValue(LiveLayout.THEIRS).x < english.getValue(LiveLayout.MINE).x)
        // Only the order is mirrored: the same two places, counted from the left edge.
        assertEquals(hebrew.getValue(LiveLayout.MINE).x, 540f - (368f + 390f + gap) / 2, 0.01f)
        assertEquals(english.getValue(LiveLayout.THEIRS).x, 540f - (368f + 390f + gap) / 2, 0.01f)
    }

    @Test
    fun mineJoinsWhileTheirsGlidesAsideInOneMove() {
        for (rtl in listOf(true, false)) {
            val motion = resting(theirPill, rtl = rtl)
            val before = motion.rects.getValue(LiveLayout.THEIRS)
            val goal = place(theirPill, myPill, rtl = rtl)
            assertTrue(motion.target(goal, off))
            val all = frames(motion)
            assertTrue(all.size > 10)
            // Mine: in its own place sideways from the first frame, at its own size, coming down from above the
            // screen's top edge; never at the centre, never sliding sideways.
            val mine = all.map { it.getValue(LiveLayout.MINE) }
            assertTrue(mine.all { it.x == goal.getValue(LiveLayout.MINE).x && it.w == 390f && it.h == 166f })
            assertEquals(-(166f + off), mine.first().y, 0.01f)
            assertEquals(0f, mine.last().y, 0f)
            assertTrue(mine.zipWithNext().all { (a, b) -> b.y >= a.y })
            // Theirs: straight from the centre to its side (the right in Hebrew), over the same frames.
            val theirs = all.map { it.getValue(LiveLayout.THEIRS) }
            assertEquals(before.x, theirs.first().x, 0.01f)
            assertEquals(goal.getValue(LiveLayout.THEIRS).x, theirs.last().x, 0f)
            val towards = if (rtl) 1 else -1
            assertTrue(theirs.zipWithNext().all { (a, b) -> (b.x - a.x) * towards >= 0f })
            assertTrue(theirs.all { it.y == 0f })
            // Together: halfway through, both are on their way (not one after the other).
            val mid = all[all.size / 2]
            assertTrue(mid.getValue(LiveLayout.MINE).y in -(166f + off) + 1..-1f)
            assertTrue(kotlin.math.abs(mid.getValue(LiveLayout.THEIRS).x - before.x) > 1f)
            assertTrue(kotlin.math.abs(mid.getValue(LiveLayout.THEIRS).x - goal.getValue(LiveLayout.THEIRS).x) > 1f)
        }
    }

    @Test
    fun oneLeavesUpWhileTheOtherGlidesToTheCentreInOneMove() {
        for (leaver in listOf(LiveLayout.MINE, LiveLayout.THEIRS)) {
            val motion = resting(theirPill, myPill)
            val before = motion.rects
            val stays = if (leaver == LiveLayout.MINE) theirPill else myPill
            assertTrue(motion.target(place(stays), off))
            val all = frames(motion)
            // The leaver: straight up from where it was, out through the top edge.
            val leaving = all.dropLast(1).map { it.getValue(leaver) }
            assertTrue(leaving.all { it.x == before.getValue(leaver).x })
            assertTrue(leaving.zipWithNext().all { (a, b) -> b.y <= a.y })
            assertTrue(leaving.last().y < -(166f + off) * 0.9f)
            // The other: to the centre over the same frames, and alone at the end.
            val staying = all.map { it.getValue(stays.key) }
            assertEquals(before.getValue(stays.key).x, staying.first().x, 0.01f)
            assertEquals(540f, staying.last().centreX, 0.01f)
            val mid = all[all.size / 2]
            assertTrue(mid.getValue(leaver).y < -1f)
            assertTrue(kotlin.math.abs(mid.getValue(stays.key).centreX - 540f) > 1f)
            assertNull(all.last()[leaver])
            assertEquals(1, motion.rects.size)
        }
    }

    @Test
    fun voiceIntoVideoGrowsAboutItsCentreWithItsTopFixed() {
        for (rtl in listOf(true, false)) {
            val motion = resting(myPill, rtl = rtl)
            assertTrue(motion.target(place(myBox, rtl = rtl), off))
            val all = frames(motion).map { it.getValue(LiveLayout.MINE) }
            // One element: its centre and its top never move, it only grows, and its inside turns from pill to box.
            assertTrue(all.all { kotlin.math.abs(it.centreX - 540f) < 0.01f })
            assertTrue(all.all { it.y == 0f })
            assertTrue(all.zipWithNext().all { (a, b) -> b.h >= a.h && b.video >= a.video })
            assertEquals(390f, all.first().w, 0.01f)
            assertEquals(339f, all.last().w, 0f)
            assertEquals(452f, all.last().h, 0f)
            assertEquals(1f, all.last().video, 0f)
            // And back.
            assertTrue(motion.target(place(myPill, rtl = rtl), off))
            val back = frames(motion, 5_000).map { it.getValue(LiveLayout.MINE) }
            assertTrue(back.all { kotlin.math.abs(it.centreX - 540f) < 0.01f && it.y == 0f })
            assertTrue(back.zipWithNext().all { (a, b) -> b.h <= a.h && b.video <= a.video })
            assertEquals(166f, back.last().h, 0f)
            assertEquals(0f, back.last().video, 0f)
        }
    }

    @Test
    fun voiceIntoVideoBesideTheOtherKeepsThePairCentred() {
        val motion = resting(theirPill, myPill)
        assertTrue(motion.target(place(theirPill, myBox), off))
        for (rects in frames(motion)) {
            val left = rects.values.minOf { it.x }
            val right = rects.values.maxOf { it.x + it.w }
            // Mine grows and theirs makes room in the same move: the pair stays centred, tops level, the gap kept.
            assertEquals(540f, (left + right) / 2, 0.01f)
            assertTrue(rects.values.all { it.y == 0f })
            assertEquals(gap, rects.getValue(LiveLayout.THEIRS).x - (rects.getValue(LiveLayout.MINE).x + rects.getValue(LiveLayout.MINE).w), 0.01f)
        }
    }

    @Test
    fun voiceIntoVideoBesideTheOtherStillGrowsAboutItsOwnCentre() {
        // Two are centred as a pair, so the one that grows keeps its own centre exactly (the pair's edge moves out
        // by half of what it gains) and only the other glides: mine at the left in Hebrew, at the right in English.
        for (rtl in listOf(true, false)) {
            val motion = resting(theirPill, myPill, rtl = rtl)
            val centre = motion.rects.getValue(LiveLayout.MINE).centreX
            val theirs = motion.rects.getValue(LiveLayout.THEIRS).centreX
            assertTrue(motion.target(place(theirPill, myBox, rtl = rtl), off))
            val all = frames(motion)
            assertTrue(all.all { kotlin.math.abs(it.getValue(LiveLayout.MINE).centreX - centre) < 0.01f && it.getValue(LiveLayout.MINE).y == 0f })
            assertTrue(all.zipWithNext().all { (a, b) -> b.getValue(LiveLayout.MINE).h >= a.getValue(LiveLayout.MINE).h })
            // Theirs keeps its size and moves towards mine by half of what mine lost in width, over the same frames.
            assertEquals(theirs + (if (rtl) -1 else 1) * (390f - 339f) / 2, all.last().getValue(LiveLayout.THEIRS).centreX, 0.01f)
            assertTrue(all.all { it.getValue(LiveLayout.THEIRS).w == 368f })
        }
    }

    @Test
    fun aBoxComesInAtItsFinalSize() {
        val motion = LiveMotion()
        motion.target(place(theirBox), off)
        val all = frames(motion).map { it.getValue(LiveLayout.THEIRS) }
        assertTrue(all.all { it.w == 339f && it.h == 452f && it.video == 1f && kotlin.math.abs(it.centreX - 540f) < 0.01f })
        assertEquals(-(452f + off), all.first().y, 0.01f)
        assertEquals(0f, all.last().y, 0f)
    }

    @Test
    fun aChangeMidMoveStartsFromWhereEverythingIs() {
        val motion = resting(theirPill)
        motion.target(place(theirPill, myPill), off)
        motion.frame(2_000)
        motion.frame(2_150)
        val mid = motion.rects
        // Mine goes again before it has arrived: it turns back up from where it is, theirs back to the centre.
        assertTrue(motion.target(place(theirPill), off))
        val all = frames(motion, 2_166)
        assertEquals(mid.getValue(LiveLayout.MINE).y, all.first().getValue(LiveLayout.MINE).y, 0.01f)
        assertEquals(mid.getValue(LiveLayout.THEIRS).x, all.first().getValue(LiveLayout.THEIRS).x, 0.01f)
        assertEquals(540f, all.last().getValue(LiveLayout.THEIRS).centreX, 0.01f)
        assertNull(all.last()[LiveLayout.MINE])
        // And one that comes back while it leaves comes down from where it is, not from the top again.
        motion.target(place(theirPill, myPill), off)
        motion.frame(4_000)
        motion.frame(4_150)
        motion.target(place(theirPill), off)
        motion.frame(4_166)
        motion.frame(4_300)
        val leaving = motion.rects.getValue(LiveLayout.MINE)
        assertTrue(motion.leaving(LiveLayout.MINE))
        motion.target(place(theirPill, myPill), off)
        assertFalse(motion.leaving(LiveLayout.MINE))
        assertEquals(leaving.y, frames(motion, 4_316).first().getValue(LiveLayout.MINE).y, 0.01f)
    }

    @Test
    fun aPillsTimeGettingWiderDoesNotStartAMove() {
        val motion = resting(theirPill, myPill)
        val wider = myPill.copy(w = 398f)
        assertFalse(motion.target(place(theirPill, wider), off, small = 40f))
        assertFalse(motion.running)
        assertEquals(place(theirPill, wider), motion.rects)
        // The same places asked again: nothing.
        assertFalse(motion.target(place(theirPill, wider), off, small = 40f))
    }

    @Test
    fun withAnimationsOffEverythingIsInPlaceAtOnce() {
        val motion = resting(theirPill)
        motion.target(place(theirPill, myBox), off)
        motion.frame(2_000, scale = 0f)
        assertFalse(motion.running)
        assertEquals(place(theirPill, myBox), motion.rects)
    }

    @Test
    fun twoVideoBoxesShrinkTogetherToFitTheChatsWidth() {
        // A 360 dp wide chat at 3 px a dp, 12 dp kept clear at each side: two 3:4 boxes 184 dp high would be 282 dp
        // wide with their gap, and fit; in a narrow window they shrink together, never past the edges.
        val shapes = listOf(0.75f, 0.75f)
        val full = 552f
        assertEquals(full, LiveLayout.boxHeight(full, shapes, emptyList(), 1080f - 72f, 18f), 0f)
        val narrow = 720f - 72f
        val h = LiveLayout.boxHeight(full, shapes, emptyList(), narrow, 18f)
        assertTrue(h < full)
        assertEquals(narrow, h * 0.75f * 2 + 18f, 0.01f)
        val rects = LiveLayout.place(listOf(LiveItem(LiveLayout.THEIRS, h * 0.75f, h, true), LiveItem(LiveLayout.MINE, h * 0.75f, h, true)), 720f, 18f, rtl = true)
        assertEquals(36f, rects.values.minOf { it.x }, 0.01f)
        assertEquals(720f - 36f, rects.values.maxOf { it.x + it.w }, 0.01f)
        assertTrue(rects.values.all { it.h == h })
        // A voice pill beside a video box takes its own width first; the box has the rest.
        val beside = LiveLayout.boxHeight(full, listOf(0.75f), listOf(390f), 720f - 72f, 18f)
        assertEquals((720f - 72f - 390f - 18f) / 0.75f, beside, 0.01f)
        // One box alone keeps its full height.
        assertEquals(full, LiveLayout.boxHeight(full, listOf(0.75f), emptyList(), narrow, 18f), 0f)
    }

    @Test
    fun theRowIsCentredOnTheScreenWhereverItsWindowIs() {
        // The floating window's own left edge 40 px left of the screen's: places are counted from the window's edge,
        // and still centred on the screen.
        val rects = LiveLayout.place(listOf(theirPill), width, gap, rtl = true, left = 40f)
        assertEquals(540f + 40f, rects.getValue(LiveLayout.THEIRS).centreX, 0.01f)
    }

    @Test
    fun theEaseIsSlowAtBothEndsAndEndsExactly() {
        assertEquals(0f, LiveMotion.ease(0f), 0f)
        assertEquals(1f, LiveMotion.ease(1f), 0f)
        assertTrue(LiveMotion.ease(0.1f) < 0.1f)
        assertTrue(LiveMotion.ease(0.9f) > 0.9f)
        var last = 0f
        for (i in 1..100) {
            val e = LiveMotion.ease(i / 100f)
            assertTrue(e >= last)
            last = e
        }
    }
}
