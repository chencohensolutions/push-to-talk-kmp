package com.chencohen.ptt.talk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceBoostTest {
    @Test
    fun quietVoiceIsDoubled() {
        assertEquals(2000, VoiceBoost.boost(1000).toInt(), "plain gain below the knee")
        assertEquals(-2000, VoiceBoost.boost(-1000).toInt())
    }

    @Test
    fun loudPeaksBendAndNeverClip() {
        val samples = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 20000, -20000, 12000)
        VoiceBoost.apply(samples)
        samples.forEach { assertTrue(it in -32767..32767) }
        assertTrue(samples[2] > 23000 && samples[2] < 32767, "20000 doubled bends under full scale: ${samples[2]}")
        assertTrue(samples[0] >= samples[2], "louder stays louder")
    }

    @Test
    fun itRisesWithTheInput() {
        var last = -1
        for (x in 0..32767 step 512) {
            val y = VoiceBoost.boost(x.toShort()).toInt()
            assertTrue(y >= last, "monotonic at $x")
            last = y
        }
    }

    @Test
    fun onlyTheCountGiven() {
        val samples = shortArrayOf(1000, 1000)
        VoiceBoost.apply(samples, count = 1)
        assertEquals(listOf<Short>(2000, 1000), samples.toList())
    }
}
