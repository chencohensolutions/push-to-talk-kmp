package com.chencohen.ptt.talk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin Silero against the ONNX model's own probabilities (vadTraces) on speech in car-like noise. */
class SileroVadTest {
    private fun resource(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("vad/$name")!!.readBytes()

    private fun weights(): FloatArray {
        val b = ByteBuffer.wrap(resource("silero_vad_16k.bin")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    /** A 16 kHz mono 16-bit WAV's samples (-1 to 1). */
    private fun wav(name: String): FloatArray {
        val bytes = resource(name)
        var at = 12
        while (at < bytes.size) {
            val id = String(bytes, at, 4)
            val size = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val s = ByteBuffer.wrap(bytes, at + 8, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(s.remaining()) { s.get(it) / 32768f }
            }
            at += 8 + size
        }
        error("no data in $name")
    }

    private fun probabilities(vad: SileroVad, samples: FloatArray): FloatArray {
        val frame = FloatArray(512)
        return FloatArray(samples.size / 512) { i ->
            samples.copyInto(frame, 0, i * 512, i * 512 + 512)
            vad.probability(frame)
        }
    }

    private fun decide(p: FloatArray): Pair<VoiceEnd.Result, Int> {
        val end = VoiceEnd(32.0)
        p.forEachIndexed { i, v -> end.feed(v).let { if (it != VoiceEnd.Result.GOING) return it to i } }
        return VoiceEnd.Result.GOING to p.size
    }

    @Test
    fun matchesTheOnnxModelFrameByFrame() {
        val w = weights()
        assertEquals(SileroVad.WEIGHTS, w.size)
        val vad = SileroVad(w)
        for (name in listOf("sentence_0db", "pause_0db", "short_5db")) {
            vad.reset()
            val ours = probabilities(vad, wav("$name.wav"))
            val onnx = vadTraces.first { it.name == name }.probabilities
            assertEquals(onnx.size, ours.size, name)
            val worst = ours.indices.maxOf { abs(ours[it] - onnx[it]) }
            // The traces are rounded to 3 decimals and made before the WAV's 16-bit rounding.
            assertTrue(worst < 0.03f, "$name: worst difference $worst")
            assertEquals(decide(onnx), decide(ours), name)
        }
    }

    @Test
    fun fastEnoughForRealTime() {
        val vad = SileroVad(weights())
        val samples = wav("sentence_0db.wav")
        probabilities(vad, samples)
        val start = System.nanoTime()
        val frames = probabilities(vad, samples).size
        val perFrameMs = (System.nanoTime() - start) / 1e6 / frames
        println("Silero in Kotlin: %.3f ms a 32 ms frame on this machine".format(perFrameMs))
        assertTrue(perFrameMs < 16.0, "$perFrameMs ms per frame")
    }
}
