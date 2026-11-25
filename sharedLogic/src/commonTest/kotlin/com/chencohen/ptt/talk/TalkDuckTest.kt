package com.chencohen.ptt.talk

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Other apps' sound, with what was asked of it. */
private class FakeDuck(var grant: Boolean = true) : AudioDuck {
    val calls = mutableListOf<String>()
    override fun take(): Boolean {
        calls += "take"
        return grant
    }
    override fun release() {
        calls += "release"
    }
}

class TalkDuckTest {
    @Test
    fun aHeardPushDimsTheOthersUntilTwoSecondsAfterItEnds() = runTest {
        val duck = FakeDuck()
        val dimmer = TalkDuck(backgroundScope, { duck }, {}, {})
        dimmer.heard("hen-burst-1")
        assertEquals(listOf("take"), duck.calls)
        dimmer.quiet("hen-burst-1")
        advanceTimeBy(TalkDuck.RELEASE_AFTER_MS - 1)
        runCurrent()
        assertEquals(listOf("take"), duck.calls, "still down just before 2 s")
        assertTrue(dimmer.holding)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("take", "release"), duck.calls)
        assertFalse(dimmer.holding)
    }

    @Test
    fun aNewPushWithinTheTwoSecondsKeepsItDown() = runTest {
        val duck = FakeDuck()
        val dimmer = TalkDuck(backgroundScope, { duck }, {}, {})
        dimmer.heard("hen-burst-1")
        dimmer.quiet("hen-burst-1")
        advanceTimeBy(1_500)
        dimmer.heard("testbot-burst-2")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("take"), duck.calls, "one take, no bounce")
        dimmer.quiet("testbot-burst-2")
        advanceTimeBy(TalkDuck.RELEASE_AFTER_MS + 1)
        runCurrent()
        assertEquals(listOf("take", "release"), duck.calls)
    }

    @Test
    fun twoTalkersAtOnceKeepItDownUntilBothEnd() = runTest {
        val duck = FakeDuck()
        val dimmer = TalkDuck(backgroundScope, { duck }, {}, {})
        dimmer.heard("hen-burst-1")
        dimmer.heard("testbot-burst-2")
        dimmer.quiet("hen-burst-1")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("take"), duck.calls)
        dimmer.quiet("testbot-burst-2")
        advanceTimeBy(TalkDuck.RELEASE_AFTER_MS + 1)
        runCurrent()
        assertEquals(listOf("take", "release"), duck.calls)
    }

    @Test
    fun anEndForAnUnknownOrRepeatedBurstChangesNothing() = runTest {
        val duck = FakeDuck()
        val dimmer = TalkDuck(backgroundScope, { duck }, {}, {})
        dimmer.quiet("hen-burst-1")
        dimmer.heard("hen-burst-1")
        dimmer.quiet("hen-burst-1")
        dimmer.quiet("hen-burst-1")
        advanceTimeBy(TalkDuck.RELEASE_AFTER_MS + 1)
        runCurrent()
        assertEquals(listOf("take", "release"), duck.calls)
    }

    @Test
    fun aRefusedDuckStillLetsTalkPlayAndIsntAskedAgainEachBurst() = runTest {
        val duck = FakeDuck(grant = false)
        val dimmer = TalkDuck(backgroundScope, { duck }, {}, {})
        dimmer.heard("hen-burst-1")
        dimmer.heard("testbot-burst-2")
        assertEquals(listOf("take"), duck.calls)
    }

    @Test
    fun turnedOffNothingIsAsked() = runTest {
        val duck = FakeDuck()
        var lowerOthers = false
        val dimmer = TalkDuck(backgroundScope, { duck.takeIf { lowerOthers } }, {}, {})
        dimmer.heard("hen-burst-1")
        dimmer.quiet("hen-burst-1")
        advanceTimeBy(TalkDuck.RELEASE_AFTER_MS + 1)
        runCurrent()
        assertEquals(emptyList(), duck.calls)
        lowerOthers = true
        dimmer.heard("testbot-burst-2")
        assertEquals(listOf("take"), duck.calls)
    }

    @Test
    fun turnedOffWhileDownItStillComesBack() = runTest {
        val duck = FakeDuck()
        var lowerOthers = true
        val dimmer = TalkDuck(backgroundScope, { duck.takeIf { lowerOthers } }, {}, {})
        dimmer.heard("hen-burst-1")
        lowerOthers = false
        dimmer.quiet("hen-burst-1")
        advanceTimeBy(TalkDuck.RELEASE_AFTER_MS + 1)
        runCurrent()
        assertEquals(listOf("take", "release"), duck.calls)
    }

    @Test
    fun leavingTheRoomGivesItBackAtOnce() = runTest {
        val duck = FakeDuck()
        val dimmer = TalkDuck(backgroundScope, { duck }, {}, {})
        dimmer.heard("hen-burst-1")
        dimmer.clear()
        assertEquals(listOf("take", "release"), duck.calls)
        advanceTimeBy(TalkDuck.RELEASE_AFTER_MS + 1)
        runCurrent()
        assertEquals(listOf("take", "release"), duck.calls)
    }
}
