package com.chencohen.ptt.talk

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Settings › Talk › Boost voice (2026-11-25, hen; off by default): incoming talk's own samples made louder in the app,
 * [GAIN] times, without touching the system volume. A soft limiter keeps it from clipping: below [KNEE] of full scale
 * the gain is plain, above it the peaks bend smoothly toward full scale and never pass it.
 */
object VoiceBoost {
    const val GAIN = 2.0
    const val KNEE = 0.7

    /** [samples]' first [count], boosted in place. */
    fun apply(samples: ShortArray, count: Int = samples.size, gain: Double = GAIN) {
        for (i in 0 until count) samples[i] = boost(samples[i], gain)
    }

    fun boost(sample: Short, gain: Double = GAIN): Short {
        val x = sample / 32768.0 * gain
        val a = abs(x)
        val y = if (a <= KNEE) a else KNEE + (1 - KNEE) * tanh((a - KNEE) / (1 - KNEE))
        val out = kotlin.math.round((if (x < 0) -y else y) * 32768.0)
        return out.toInt().coerceIn(-32767, 32767).toShort()
    }
}
