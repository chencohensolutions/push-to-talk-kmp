package com.chencohen.ptt.talk

import kotlin.test.Test
import kotlin.test.assertEquals

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

class TalkUnmuteTest {
    @Test
    fun aMutedPhoneIsTurnedUpWhileTalkPlaysThenBackOff() {
        val v = FakeVolume()
        val unmute = TalkUnmute({ v }, { true })
        unmute.begin()
        assertEquals(9, v.now, "60% of 15")
        unmute.begin()
        unmute.end()
        assertEquals(0, v.now)
        assertEquals(listOf(9, 0), v.sets)
        unmute.end()
        assertEquals(listOf(9, 0), v.sets, "ended once")
    }

    @Test
    fun aVolumeThatIsOnIsLeftAlone() {
        val v = FakeVolume(now = 4)
        val unmute = TalkUnmute({ v }, { true })
        unmute.begin()
        unmute.end()
        assertEquals(4, v.now)
        assertEquals(emptyList(), v.sets)
    }

    @Test
    fun offOrDoNotDisturbChangesNothing() {
        val off = FakeVolume()
        TalkUnmute({ off }, { false }).apply { begin(); end() }
        val dnd = FakeVolume(dnd = true)
        TalkUnmute({ dnd }, { true }).apply { begin(); end() }
        assertEquals(emptyList(), off.sets)
        assertEquals(emptyList(), dnd.sets)
    }

    @Test
    fun aVolumeTheUserChangesMeanwhileStays() {
        val v = FakeVolume()
        val unmute = TalkUnmute({ v }, { true })
        unmute.begin()
        v.now = 12
        unmute.end()
        assertEquals(12, v.now)
        assertEquals(listOf(9), v.sets)
    }
}
