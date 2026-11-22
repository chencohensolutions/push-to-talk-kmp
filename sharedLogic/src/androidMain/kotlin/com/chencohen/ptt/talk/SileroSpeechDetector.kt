package com.chencohen.ptt.talk

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 (MIT, snakers4/silero-vad; the app's assets/silero_vad.onnx, 2.3 MB) on ONNX Runtime: the chance a
 * 32 ms frame at 16 kHz is speech, robust to road, engine and fan noise. Each call carries the model's state and the
 * last 64 samples of the previous frame, as Silero's own wrapper does.
 */
class SileroSpeechDetector(context: Context) : SpeechDetector {
    override val sampleRate = 16_000
    override val frameSamples = 512

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open(MODEL).use { env.createSession(it.readBytes(), OrtSession.SessionOptions()) }
    private val state = FloatArray(2 * 1 * 128)
    private val input = FloatArray(CONTEXT + 512)

    override fun probability(frame: FloatArray): Float {
        require(frame.size == frameSamples)
        frame.copyInto(input, CONTEXT)
        val x = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong()))
        val s = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
        val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sampleRate.toLong())), longArrayOf())
        try {
            session.run(mapOf("input" to x, "state" to s, "sr" to sr)).use { out ->
                val p = (out[0].value as Array<*>).let { (it[0] as FloatArray)[0] }
                @Suppress("UNCHECKED_CAST")
                val next = out[1].value as Array<Array<FloatArray>>
                var i = 0
                for (a in next) for (b in a) for (v in b) state[i++] = v
                // The context: this frame's last 64 samples.
                frame.copyInto(input, 0, frameSamples - CONTEXT, frameSamples)
                return p
            }
        } finally {
            x.close()
            s.close()
            sr.close()
        }
    }

    override fun reset() {
        state.fill(0f)
        input.fill(0f)
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL = "silero_vad.onnx"
        private const val CONTEXT = 64

        /** Silero, or the energy detector when the model can't load. */
        fun orFallback(context: Context): SpeechDetector =
            runCatching { SileroSpeechDetector(context) }.getOrElse {
                android.util.Log.w("SpeechDetector", "Silero VAD didn't load: energy detection", it)
                EnergySpeechDetector()
            }
    }
}
