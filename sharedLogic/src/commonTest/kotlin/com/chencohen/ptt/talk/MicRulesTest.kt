package com.chencohen.ptt.talk

import com.chencohen.ptt.talk.MicRules.Recording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicRulesTest {
    private val clip = 11
    private val room = 22
    private val otherApp = 33

    @Test
    fun theRoomRecordingIsTheOneThatAppearedWhenItOpened() {
        val now = listOf(Recording(clip, false), Recording(room, false), Recording(otherApp, false))
        assertEquals(setOf(room), MicRules.roomSessions(before = setOf(otherApp), now = now, own = setOf(clip)))
    }

    @Test
    fun theAppsOwnSilencedClipRecorderDoesNotTakeTheRoomMicrophone() {
        // The clip recorder warm while the room's opens: the system silences one of the app's two; not someone else.
        val now = listOf(Recording(clip, silenced = true), Recording(room, silenced = false))
        assertFalse(MicRules.takenFromIdle(now, room = setOf(room), phoneCall = false))
    }

    @Test
    fun anotherAppSilencedByOursDoesNotTakeIt() {
        val now = listOf(Recording(otherApp, silenced = true), Recording(room, silenced = false))
        assertFalse(MicRules.takenFromIdle(now, room = setOf(room), phoneCall = false))
    }

    @Test
    fun theRoomRecordingSilencedIsTaken() {
        val now = listOf(Recording(otherApp, silenced = false), Recording(room, silenced = true))
        assertTrue(MicRules.takenFromIdle(now, room = setOf(room), phoneCall = false))
    }

    @Test
    fun noKnownRoomRecordingIsNeverAGuessToLetGo() {
        assertFalse(MicRules.takenFromIdle(listOf(Recording(otherApp, true)), room = emptySet(), phoneCall = false))
    }

    @Test
    fun theAppsOwnTalkSessionIsNotAPhoneCall() {
        assertFalse(MicRules.phoneCall(callMode = true, ownTalkSession = true))
        assertTrue(MicRules.phoneCall(callMode = true, ownTalkSession = false))
        val phoneCall = MicRules.phoneCall(callMode = true, ownTalkSession = true)
        assertFalse(MicRules.takenFromIdle(listOf(Recording(room, false)), setOf(room), phoneCall))
        assertFalse(MicRules.busy(emptyList(), emptySet(), emptySet(), phoneCall))
    }

    @Test
    fun busyCountsOnlyRecordingsThatAreNotOurs() {
        assertFalse(MicRules.busy(listOf(Recording(clip, false), Recording(room, false)), setOf(clip), setOf(room), false))
        assertTrue(MicRules.busy(listOf(Recording(otherApp, false)), setOf(clip), setOf(room), false))
        assertTrue(MicRules.busy(emptyList(), emptySet(), emptySet(), phoneCall = true))
    }
}
