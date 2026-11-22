package com.chencohen.ptt.talk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceEndTest {
    private val frameMs = 512 * 1000.0 / 16_000

    private fun run(probabilities: FloatArray, config: VoiceEndConfig = VoiceEndConfig()): Pair<VoiceEnd.Result, Double> {
        val vad = VoiceEnd(frameMs, config)
        probabilities.forEachIndexed { i, p ->
            val r = vad.feed(p)
            if (r != VoiceEnd.Result.GOING) return r to (i + 1) * frameMs / 1000
        }
        return VoiceEnd.Result.GOING to probabilities.size * frameMs / 1000
    }

    @Test
    fun speechInCarNoiseEndsAboutASecondAndAHalfAfterIt() {
        for (trace in vadTraces.filter { it.name != "noise_only" }) {
            val (result, at) = run(trace.probabilities)
            assertEquals(VoiceEnd.Result.ENDED, result, trace.name)
            val after = at - trace.speechTo
            // Ends after the last word plus the hangover, not inside the sentence (the 0.8 s pause included).
            assertTrue(after in 1.0..2.2, "${trace.name}: ended ${"%.2f".format(after)} s after the speech")
        }
    }

    @Test
    fun carNoiseAloneIsNothingHeard() {
        val noise = vadTraces.first { it.name == "noise_only" }
        val (result, at) = run(noise.probabilities)
        assertEquals(VoiceEnd.Result.NOTHING_HEARD, result)
        assertEquals(4.0, at, 0.05)
    }

    @Test
    fun aClickIsNotSpeech() {
        val p = FloatArray(200) { 0.02f }.also { it[20] = 0.95f; it[21] = 0.95f }
        assertEquals(VoiceEnd.Result.NOTHING_HEARD, run(p).first)
    }

    @Test
    fun aNoisyFloorRaisesTheThreshold() {
        // Noise that looks a bit like speech (0.4) before anyone talks: 0.45 isn't speech, 0.9 is.
        val p = FloatArray(10) { 0.4f } + FloatArray(40) { 0.45f } + FloatArray(20) { 0.9f } + FloatArray(80) { 0.45f }
        val (result, at) = run(p)
        assertEquals(VoiceEnd.Result.ENDED, result)
        assertEquals((70 * frameMs + 1_500) / 1000, at, 0.05)
    }

    @Test
    fun theCapAndTheConfig() {
        assertEquals(VoiceEnd.Result.TOO_LONG, run(FloatArray(2_000) { 0.9f }).first)
        val (result, at) = run(FloatArray(10) { 0f } + FloatArray(20) { 0.9f } + FloatArray(40) { 0f }, VoiceEndConfig(endSilenceMs = 800))
        assertEquals(VoiceEnd.Result.ENDED, result)
        assertEquals((30 * frameMs + 800) / 1000, at, 0.05)
    }

    @Test
    fun downsampling() {
        val out = FloatArray(2)
        assertEquals(2, Downsample.to16k(shortArrayOf(3, 3, 3, -6, -6, -6, 9), 7, out))
        assertEquals(3f / 32768f, out[0], 1e-7f)
        assertEquals(-6f / 32768f, out[1], 1e-7f)
    }
}
