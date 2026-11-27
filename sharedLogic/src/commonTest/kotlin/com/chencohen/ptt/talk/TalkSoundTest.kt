package com.chencohen.ptt.talk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TalkSoundTest {
    @Test
    fun onScreenTalkIsAlwaysMedia() {
        for (rights in listOf(false, true)) for (stay in listOf(false, true)) {
            assertEquals(TalkSound.Kind.Media, TalkSound.kind(onScreen = true, readyWithRights = rights, stayConnected = stay))
        }
    }

    @Test
    fun offScreenTalkIsMediaUnderAServiceThatMayPlay() {
        assertEquals(TalkSound.Kind.Media, TalkSound.kind(onScreen = false, readyWithRights = true, stayConnected = false))
        assertEquals(TalkSound.Kind.Media, TalkSound.kind(onScreen = false, readyWithRights = false, stayConnected = true))
    }

    @Test
    fun onlyOffScreenWithNoRightToPlayIsCallAudio() {
        assertEquals(TalkSound.Kind.Call, TalkSound.kind(onScreen = false, readyWithRights = false, stayConnected = false))
    }

    @Test
    fun aQuietPressGetsTheSpeakerphoneTuning() {
        assertTrue(TalkSound.micTuning(modeNormal = true, headset = false, hearing = false, talkSession = false))
    }

    @Test
    fun noCommunicationModeWhileIncomingTalkIsHeard() {
        assertFalse(TalkSound.micTuning(modeNormal = true, headset = false, hearing = true, talkSession = false))
    }

    @Test
    fun noTuningWithAHeadsetInASessionOrInACall() {
        assertFalse(TalkSound.micTuning(modeNormal = true, headset = true, hearing = false, talkSession = false))
        assertFalse(TalkSound.micTuning(modeNormal = true, headset = false, hearing = false, talkSession = true))
        assertFalse(TalkSound.micTuning(modeNormal = false, headset = false, hearing = false, talkSession = false))
    }

    @Test
    fun aCallsVoiceIsAlwaysBoostedAndMediaOnlyWithTheSetting() {
        assertTrue(TalkSound.boost(setting = false, kind = TalkSound.Kind.Call))
        assertTrue(TalkSound.boost(setting = true, kind = TalkSound.Kind.Call))
        assertTrue(TalkSound.boost(setting = true, kind = TalkSound.Kind.Media))
        assertFalse(TalkSound.boost(setting = false, kind = TalkSound.Kind.Media))
    }

    @Test
    fun theCallVolumeIsTheTalkVolumesShare() {
        // The S25's streams: media 0 to 15, call 1 to 15. Talk volume 80%: 12 of 15.
        assertEquals(12, TalkSound.callLevel(mediaLevel = 3, mediaMax = 15, callNow = 4, callMin = 1, callMax = 15, talkVolume = 0.8))
        // A call stream with fewer steps (0 to 7): the same share of it.
        assertEquals(6, TalkSound.callLevel(mediaLevel = 3, mediaMax = 15, callNow = 2, callMin = 1, callMax = 7, talkVolume = 0.8))
    }

    @Test
    fun withTalkVolumeOffTheCallVolumeFollowsTheMediaVolume() {
        assertEquals(10, TalkSound.callLevel(mediaLevel = 10, mediaMax = 15, callNow = 4, callMin = 1, callMax = 15, talkVolume = null))
        assertEquals(15, TalkSound.callLevel(mediaLevel = 15, mediaMax = 15, callNow = 4, callMin = 1, callMax = 15, talkVolume = null))
    }

    @Test
    fun theCallVolumeIsNeverLoweredAndNeverZero() {
        // Louder already: left alone.
        assertEquals(14, TalkSound.callLevel(mediaLevel = 5, mediaMax = 15, callNow = 14, callMin = 1, callMax = 15, talkVolume = 0.5))
        // Media at 0 with Talk volume off: the call stream's least, not 0 (Android refuses it).
        assertEquals(1, TalkSound.callLevel(mediaLevel = 0, mediaMax = 15, callNow = 1, callMin = 1, callMax = 15, talkVolume = null))
        assertEquals(1, TalkSound.callLevel(mediaLevel = 0, mediaMax = 15, callNow = 0, callMin = 0, callMax = 15, talkVolume = null))
    }

    @Test
    fun aBrokenStreamRangeStillGivesALevelInside() {
        assertEquals(1, TalkSound.callLevel(mediaLevel = 0, mediaMax = 0, callNow = 0, callMin = 0, callMax = 0, talkVolume = null))
        assertEquals(15, TalkSound.callLevel(mediaLevel = 5, mediaMax = 15, callNow = 3, callMin = 1, callMax = 15, talkVolume = 7.0))
    }
}
