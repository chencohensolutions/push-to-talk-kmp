package com.chencohen.ptt.talk

import kotlin.test.Test
import kotlin.test.assertEquals

class CanHearTest {
    @Test
    fun aCallComesFirstThenDoNotDisturbThenSilence() {
        assertEquals(Hear.IN_CALL, Hear.of(inCall = true, dnd = true, ringerSilent = true, mediaMuted = true))
        assertEquals(Hear.DND, Hear.of(inCall = false, dnd = true, ringerSilent = true, mediaMuted = false))
        assertEquals(Hear.SILENT, Hear.of(inCall = false, dnd = false, ringerSilent = true, mediaMuted = false))
        assertEquals(Hear.SILENT, Hear.of(inCall = false, dnd = false, ringerSilent = false, mediaMuted = true))
        assertEquals(Hear.OK, Hear.of(inCall = false, dnd = false, ringerSilent = false, mediaMuted = false))
    }

    @Test
    fun theProtoRoundTrips() {
        for (h in listOf(Hear.OK, Hear.SILENT, Hear.DND, Hear.IN_CALL)) assertEquals(h, Hear.fromProto(h.proto))
        assertEquals(Hear.UNKNOWN, Hear.fromProto(0))
        assertEquals(Hear.UNKNOWN, Hear.fromProto(99))
    }

    @Test
    fun aContactsPresenceSaysWhetherTheyHear() {
        assertEquals(Hear.OFFLINE, Hear.of(online = false, said = Hear.OK))
        assertEquals(Hear.DND, Hear.of(online = true, said = Hear.DND))
        assertEquals(Hear.UNKNOWN, Hear.of(online = null, said = null))
    }

    @Test
    fun whatPlaysAndWhatIsSaved() {
        assertEquals(HearDecision.Play, CanHearRules.decide(Hear.OK, urgent = false, allowed = false, canOpenDnd = false))
        assertEquals(HearDecision.Play, CanHearRules.decide(Hear.UNKNOWN, urgent = true, allowed = false, canOpenDnd = false))
        assertEquals(HearDecision.Save(HeardStatus.SAVED_DND), CanHearRules.decide(Hear.DND, urgent = false, allowed = true, canOpenDnd = true))
        assertEquals(HearDecision.Save(HeardStatus.SAVED_SILENT), CanHearRules.decide(Hear.SILENT, urgent = false, allowed = true, canOpenDnd = true))
        assertEquals(HearDecision.Save(HeardStatus.SAVED_IN_CALL), CanHearRules.decide(Hear.IN_CALL, urgent = true, allowed = true, canOpenDnd = true))
        assertEquals(HearDecision.ThroughDnd, CanHearRules.decide(Hear.DND, urgent = true, allowed = true, canOpenDnd = true))
        // Let through, but without Do Not Disturb access: it arrives silently.
        assertEquals(HearDecision.Save(HeardStatus.SAVED_URGENT_NOT_ALLOWED), CanHearRules.decide(Hear.DND, urgent = true, allowed = true, canOpenDnd = false))
        assertEquals(HearDecision.Save(HeardStatus.SAVED_URGENT_NOT_ALLOWED), CanHearRules.decide(Hear.DND, urgent = true, allowed = false, canOpenDnd = true))
        // Silence needs no access: the media stream plays.
        assertEquals(HearDecision.ThroughDnd, CanHearRules.decide(Hear.SILENT, urgent = true, allowed = true, canOpenDnd = false))
    }

    @Test
    fun aGroupCountsWhoCanHearNow() {
        assertEquals(2 to 4, CanHearRules.canHearCount(listOf(Hear.OK, Hear.DND, Hear.UNKNOWN, Hear.OFFLINE)))
    }

    @Test
    fun aContactsHearStateFlappingWhileTheyReconnectDoesntFlashTheBadge() {
        // 2026-11-27 (hen): Sharon's phone reconnected every 1.5 s for half a minute; her DND badge flickered.
        val steady = SteadyHear()
        assertEquals(Hear.DND to false, steady.offer("sharon", Hear.DND, 0))
        var t = 0L
        repeat(15) {
            t += 750
            // Gone, then back before her phone says DND again: neither shows.
            assertEquals(Hear.DND, steady.offer("sharon", Hear.OFFLINE, t).first)
            assertEquals(Hear.DND, steady.settle("sharon", t + 500))
            t += 750
            assertEquals(Hear.DND, steady.offer("sharon", Hear.UNKNOWN, t).first)
            assertEquals(Hear.DND, steady.offer("sharon", Hear.DND, t + 100).first)
            assertEquals(Hear.DND, steady.settle("sharon", t + 2_200))
        }
        // A real change shows once it has held 2 s.
        t += 1_000
        steady.offer("sharon", Hear.OK, t)
        assertEquals(Hear.DND, steady.settle("sharon", t + 1_000))
        assertEquals(Hear.OK, steady.settle("sharon", t + 2_000))
        // Another contact isn't held by hers.
        assertEquals(Hear.OK to false, steady.offer("testbot", Hear.OK, t))
    }
}
