package com.chencohen.ptt.talk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * A test tune in place of the microphone, for test phones without one (2026-11-27, hen: "can you emulate sound from the
 * mic?"; the emulators run with no audio). Debug builds only, and only when turned on from the computer:
 * `adb shell setprop debug.ptt.fakemic 1` (off: `setprop debug.ptt.fakemic 0`). What the room's microphone captured is
 * replaced by short notes going up and down, like a voice's rhythm, so the other phone hears something.
 */
object FakeMic {
    private const val TAG = "FakeMic"
    private val notes = doubleArrayOf(392.0, 440.0, 494.0, 523.0, 587.0, 523.0, 494.0, 440.0)

    fun enabled(context: Context): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        val on = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.ptt.fakemic") as String
        }.getOrDefault("") == "1"
        if (on) Log.i(TAG, "the microphone is a test tune (debug.ptt.fakemic)")
        return on
    }

    /** Writes the tune over each captured buffer (16-bit PCM). */
    fun callback(): io.livekit.android.audio.AudioBufferCallback {
        var sample = 0L
        return object : io.livekit.android.audio.AudioBufferCallback {
          override fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channels: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): Long {
            // 2 = AudioFormat.ENCODING_PCM_16BIT
            if (audioFormat == 2 && sampleRate > 0 && channels > 0) {
                val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                b.position(0)
                val frames = bytesRead / (2 * channels)
                val noteLen = sampleRate / 4 // a quarter second each
                for (f in 0 until frames) {
                    val n = ((sample / noteLen) % notes.size).toInt()
                    val inNote = (sample % noteLen).toDouble() / noteLen
                    // A short gap between notes, and a soft attack and release.
                    val envelope = if (inNote > 0.85) 0.0 else sin(PI * (inNote / 0.85)).coerceAtLeast(0.0)
                    val v = (sin(2 * PI * notes[n] * sample / sampleRate) * envelope * 12_000).toInt().toShort()
                    repeat(channels) { b.putShort(v) }
                    sample++
                }
            }
            return captureTimeNs
          }
        }
    }
}
