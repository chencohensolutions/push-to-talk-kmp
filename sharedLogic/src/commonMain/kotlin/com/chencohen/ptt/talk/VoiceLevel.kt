package com.chencohen.ptt.talk

import kotlin.math.exp
import kotlin.math.log10

/**
 * How loud the voice is now, 0 to 1, for the talk button's glow and growth (docs/screens/talk.md; 2026-11-22, hen):
 * a 20 ms frame's RMS on a -50 to -10 dBFS scale, smoothed so it rises fast and falls slowly.
 */
object VoiceLevel {
    const val FLOOR_DBFS = -50.0
    const val TOP_DBFS = -10.0
    const val ATTACK_MS = 60f
    const val RELEASE_MS = 200f

    /** A frame's level from its RMS (16-bit full scale 32768). */
    fun fromRms(rms: Double): Float {
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms / 32768.0)
        return ((db - FLOOR_DBFS) / (TOP_DBFS - FLOOR_DBFS)).coerceIn(0.0, 1.0).toFloat()
    }

    /** The shown level after [dtMs], moving towards [target]: [ATTACK_MS] up, [RELEASE_MS] down. */
    fun smooth(current: Float, target: Float, dtMs: Float): Float {
        val tau = if (target > current) ATTACK_MS else RELEASE_MS
        val k = 1f - exp(-dtMs / tau)
        return current + (target - current) * k
    }

    /** A frame's RMS without allocating (the audio threads call it every 20 ms). */
    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / count)
    }
}
