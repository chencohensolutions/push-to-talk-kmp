package com.chencohen.ptt.talk

import android.content.Context
import android.util.Log
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.InterpreterApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Silero VAD v5 (MIT, snakers4/silero-vad; assets/silero_vad_LICENSE.txt) on LiteRT from Google Play services
 * (2026-11-22, hen): the runtime comes with Play services, the app carries only the model
 * (assets/silero_vad_16k.tflite, 1.2 MB, made by tools/vad/to_tflite.py with the LSTM state as explicit inputs and
 * outputs). Inputs x [1, 576] (the previous frame's last 64 samples and this frame), h and c [1, 128]; outputs, in
 * TensorFlow's key order, c, h and the probability [1, 1]. CPU, XNNPACK.
 */
class LiteRtSpeechDetector private constructor(private val interpreter: InterpreterApi) : SpeechDetector {
    override val sampleRate = 16_000
    override val frameSamples = 512

    private val x = FloatArray(CONTEXT + 512)
    private val input = arrayOf(Array(1) { x }, Array(1) { FloatArray(128) }, Array(1) { FloatArray(128) })
    private val c = Array(1) { FloatArray(128) }
    private val h = Array(1) { FloatArray(128) }
    private val p = Array(1) { FloatArray(1) }
    private val outputs = mapOf<Int, Any>(0 to c, 1 to h, 2 to p)

    override fun probability(frame: FloatArray): Float {
        require(frame.size == frameSamples)
        frame.copyInto(x, CONTEXT)
        interpreter.runForMultipleInputsOutputs(input, outputs)
        // The state for the next frame, and its context.
        h[0].copyInto(input[1][0])
        c[0].copyInto(input[2][0])
        frame.copyInto(x, 0, frameSamples - CONTEXT, frameSamples)
        return p[0][0]
    }

    override fun reset() {
        x.fill(0f)
        input[1][0].fill(0f)
        input[2][0].fill(0f)
    }

    override fun close() = interpreter.close()

    companion object {
        private const val CONTEXT = 64
        const val MODEL = "silero_vad_16k.tflite"

        /** LiteRT in Play services, once at app start: whether it is there. */
        @Volatile
        var available = false
            private set

        fun initialize(context: Context) {
            TfLite.initialize(context.applicationContext)
                .addOnSuccessListener { available = true; Log.i("SpeechDetector", "LiteRT from Play services is ready") }
                .addOnFailureListener { Log.w("SpeechDetector", "no LiteRT in Play services: Silero runs in Kotlin", it) }
        }

        fun create(context: Context): LiteRtSpeechDetector {
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
            val options = InterpreterApi.Options().setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
            return LiteRtSpeechDetector(InterpreterApi.create(model, options))
        }
    }
}

/** The car's speech detector: Silero on LiteRT (Play services), else the same Silero in plain Kotlin. */
object SileroSpeechDetector {
    const val WEIGHTS = "silero_vad_16k.bin"

    fun orFallback(context: Context): SpeechDetector {
        if (LiteRtSpeechDetector.available) {
            runCatching { return LiteRtSpeechDetector.create(context) }
                .onFailure { Log.w("SpeechDetector", "LiteRT model didn't load: Silero in Kotlin", it) }
        }
        return runCatching {
            val bytes = context.assets.open(WEIGHTS).use { it.readBytes() }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            SileroVad(FloatArray(buffer.remaining()).also { buffer.get(it) })
        }.getOrElse {
            Log.w("SpeechDetector", "Silero weights didn't load: energy detection", it)
            EnergySpeechDetector()
        }
    }
}
