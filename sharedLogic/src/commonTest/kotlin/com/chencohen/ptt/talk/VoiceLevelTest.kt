package com.chencohen.ptt.talk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceLevelTest {
    @Test
    fun levelsOnAFiftyToTenDbScale() {
        assertEquals(0f, VoiceLevel.fromRms(0.0))
        assertEquals(0f, VoiceLevel.fromRms(32768.0 * 0.001)) // -60 dBFS
        assertEquals(0.5f, VoiceLevel.fromRms(32768.0 * 0.0316), 0.01f) // -30 dBFS
        assertEquals(1f, VoiceLevel.fromRms(32768.0 * 0.5)) // -6 dBFS
    }

    @Test
    fun risesFastFallsSlowly() {
        val up = VoiceLevel.smooth(0f, 1f, 60f)
        val down = VoiceLevel.smooth(1f, 0f, 60f)
        assertEquals(0.632f, up, 0.01f)
        assertTrue(1f - down < up, "falls slower than it rises: $down")
        // At 60 fps it gets there and stays in range.
        var level = 0f
        repeat(30) { level = VoiceLevel.smooth(level, 1f, 16.7f) }
        assertTrue(level > 0.99f && level <= 1f)
    }

    @Test
    fun rmsOfAFrame() {
        assertEquals(0.0, VoiceLevel.rms(ShortArray(960)))
        assertEquals(1000.0, VoiceLevel.rms(ShortArray(960) { if (it % 2 == 0) 1000 else -1000 }), 0.001)
        assertEquals(1000.0, VoiceLevel.rms(shortArrayOf(1000, -1000, 5000), count = 2), 0.001)
    }
}
