package com.chencohen.ptt.talk

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A media stream from 0 to 15, with what was set on it. */
internal class FakeVolume(var now: Int = 0, var dnd: Boolean = false) : TalkVolume {
    val sets = mutableListOf<Int>()
    override fun level() = now
    override fun max() = 15
    override fun set(level: Int) {
        sets += level
        now = level
    }
    override fun changeable() = !dnd
}

class TalkLoudnessTest {
    private class Saved { var share: Double? = null }

    private fun loudness(v: FakeVolume, on: Boolean = true, share: Double = TalkLoudness.DEFAULT_SHARE, saved: Saved = Saved()) =
        TalkLoudness({ v }, { on }, { share }, { saved.share = it })

    @Test
    fun theTalkVolumeIsSetWhileHeardThenTheOldOneComesBack() {
        val v = FakeVolume(now = 3)
        val l = loudness(v)
        l.begin()
        assertEquals(12, v.now, "80% of 15")
        l.begin()
        l.end()
        assertEquals(3, v.now)
        assertEquals(listOf(12, 3), v.sets)
        l.end()
        assertEquals(listOf(12, 3), v.sets, "ended once")
    }

    @Test
    fun aHigherVolumeIsSetExactlyToTheTalkVolumeToo() {
        val v = FakeVolume(now = 15)
        val l = loudness(v, share = 0.4)
        l.begin()
        assertEquals(6, v.now)
        l.end()
        assertEquals(15, v.now)
    }

    @Test
    fun aMutedPhoneIsHeardAndMutedAgain() {
        val v = FakeVolume(now = 0)
        val l = loudness(v, share = 0.0)
        l.begin()
        assertEquals(1, v.now, "never set to 0")
        l.end()
        assertEquals(0, v.now)
    }

    @Test
    fun theVolumeKeysMeanwhileSaveANewTalkVolume() {
        val v = FakeVolume(now = 5)
        val saved = Saved()
        val l = loudness(v, saved = saved)
        l.begin()
        v.now = 9 // the keys, while hen's push plays
        l.end()
        assertEquals(0.6, saved.share)
        assertEquals(5, v.now, "the volume from before comes back")
    }

    @Test
    fun untouchedNothingIsSaved() {
        val v = FakeVolume(now = 5)
        val saved = Saved()
        val l = loudness(v, saved = saved)
        l.begin()
        l.end()
        assertEquals(null, saved.share)
    }

    @Test
    fun offOrDoNotDisturbChangesNothing() {
        val off = FakeVolume(now = 4)
        loudness(off, on = false).apply { begin(); end() }
        val dnd = FakeVolume(now = 4, dnd = true)
        loudness(dnd).apply { begin(); end() }
        assertEquals(emptyList(), off.sets)
        assertEquals(emptyList(), dnd.sets)
    }

    @Test
    fun overlappingPushesKeepItUpUntilTwoSecondsAfterTheLast() = runTest {
        val v = FakeVolume(now = 2)
        val l = loudness(v)
        val dimmer = TalkDuck(backgroundScope, { null }, { l.begin() }, { l.end() })
        dimmer.heard("hen-burst-1")
        dimmer.heard("testbot-burst-2")
        assertEquals(12, v.now)
        dimmer.quiet("hen-burst-1")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(12, v.now, "testbot still talking")
        dimmer.quiet("testbot-burst-2")
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(12, v.now, "within the 2 s")
        advanceTimeBy(600)
        runCurrent()
        assertEquals(2, v.now)
        assertEquals(listOf(12, 2), v.sets)
    }

    @Test
    fun levelsRoundAndStayInRange() {
        assertEquals(12, TalkLoudness.levelFor(0.8, 15))
        assertEquals(15, TalkLoudness.levelFor(1.5, 15))
        assertEquals(1, TalkLoudness.levelFor(-1.0, 15))
        assertTrue(TalkLoudness.levelFor(0.8, 25) == 20)
    }
}
