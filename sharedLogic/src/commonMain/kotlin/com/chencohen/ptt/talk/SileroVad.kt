package com.chencohen.ptt.talk

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Silero VAD v5's 16 kHz network in plain Kotlin (MIT, snakers4/silero-vad; weights in the app's
 * assets/silero_vad_16k.bin, 1.2 MB, taken from the published silero_vad.onnx by tools/vad/ref.py), so no inference
 * runtime is bundled (2026-11-22, hen). Each 512-sample frame (32 ms) goes with the last 64 samples of the previous
 * one through a magnitude STFT (258 kernels of 256, hop 128), four convolutions with ReLU, an LSTM cell (128) whose
 * state carries over, and ReLU, a 1x1 convolution and a sigmoid. Matches the ONNX model to about 1e-6
 * (SileroVadTest). Allocation-free per frame.
 */
class SileroVad(weights: FloatArray) : SpeechDetector {
    override val sampleRate = 16_000
    override val frameSamples = 512

    private val basis: FloatArray // [258][256]
    private val w0: FloatArray; private val b0: FloatArray // [128][129][3]
    private val w1: FloatArray; private val b1: FloatArray // [64][128][3]
    private val w2: FloatArray; private val b2: FloatArray // [64][64][3]
    private val w3: FloatArray; private val b3: FloatArray // [128][64][3]
    private val wIh: FloatArray; private val wHh: FloatArray // [512][128]
    private val bIh: FloatArray; private val bHh: FloatArray
    private val wOut: FloatArray // [128]
    private val bOut: Float

    init {
        var at = 0
        fun take(n: Int) = weights.copyOfRange(at, at + n).also { at += n }
        basis = take(258 * 256)
        w0 = take(128 * 129 * 3); b0 = take(128)
        w1 = take(64 * 128 * 3); b1 = take(64)
        w2 = take(64 * 64 * 3); b2 = take(64)
        w3 = take(128 * 64 * 3); b3 = take(128)
        wIh = take(512 * 128); wHh = take(512 * 128)
        bIh = take(512); bHh = take(512)
        wOut = take(128)
        bOut = take(1)[0]
        require(at == weights.size) { "Silero weights: ${weights.size} floats, expected $at" }
    }

    // Working memory, reused every frame.
    private val x = FloatArray(CONTEXT + 512 + 64) // 640 after the reflection pad
    private val mag = FloatArray(129 * 4)
    private val e0 = FloatArray(128 * 4)
    private val e1 = FloatArray(64 * 2)
    private val e2 = FloatArray(64 * 1)
    private val e3 = FloatArray(128)
    private val h = FloatArray(128)
    private val c = FloatArray(128)
    private val gates = FloatArray(512)

    override fun probability(frame: FloatArray): Float {
        require(frame.size == frameSamples)
        // Context (the previous frame's last 64) + the frame, then 64 samples reflected at the end.
        frame.copyInto(x, CONTEXT)
        val n = CONTEXT + 512
        for (i in 0 until 64) x[n + i] = x[n - 2 - i]
        // Magnitude STFT: 4 frames of 129 bins.
        for (t in 0 until 4) {
            val off = t * 128
            for (k in 0 until 129) {
                var re = 0f
                var im = 0f
                val br = k * 256
                val bi = (k + 129) * 256
                for (j in 0 until 256) {
                    val v = x[off + j]
                    re += basis[br + j] * v
                    im += basis[bi + j] * v
                }
                mag[k * 4 + t] = sqrt(re * re + im * im)
            }
        }
        conv(mag, 129, 4, w0, b0, 128, 1, e0)
        conv(e0, 128, 4, w1, b1, 64, 2, e1)
        conv(e1, 64, 2, w2, b2, 64, 2, e2)
        conv(e2, 64, 1, w3, b3, 128, 1, e3)
        // The LSTM cell (gates i, f, g, o).
        for (r in 0 until 512) {
            var s = bIh[r] + bHh[r]
            val row = r * 128
            for (j in 0 until 128) s += wIh[row + j] * e3[j] + wHh[row + j] * h[j]
            gates[r] = s
        }
        var out = bOut
        for (j in 0 until 128) {
            val i = sigmoid(gates[j])
            val f = sigmoid(gates[128 + j])
            val g = tanh(gates[256 + j])
            val o = sigmoid(gates[384 + j])
            c[j] = f * c[j] + i * g
            h[j] = o * tanh(c[j])
            out += wOut[j] * maxOf(h[j], 0f)
        }
        // The next frame's context.
        frame.copyInto(x, 0, frameSamples - CONTEXT, frameSamples)
        return sigmoid(out)
    }

    /** A 1-D convolution, kernel 3, padding 1, then ReLU: [cin][len] to [cout][len / stride]. */
    private fun conv(input: FloatArray, cin: Int, len: Int, w: FloatArray, b: FloatArray, cout: Int, stride: Int, out: FloatArray) {
        val outLen = (len + 2 - 3) / stride + 1
        for (o in 0 until cout) {
            for (t in 0 until outLen) {
                var s = b[o]
                val start = t * stride - 1
                for (ci in 0 until cin) {
                    val wb = (o * cin + ci) * 3
                    val ib = ci * len
                    for (k in 0 until 3) {
                        val p = start + k
                        if (p in 0 until len) s += w[wb + k] * input[ib + p]
                    }
                }
                out[o * outLen + t] = if (s > 0f) s else 0f
            }
        }
    }

    private fun sigmoid(z: Float): Float = 1f / (1f + exp(-z))

    override fun reset() {
        x.fill(0f)
        h.fill(0f)
        c.fill(0f)
    }

    companion object {
        private const val CONTEXT = 64
        /** The weights file's size: 309 633 floats (little-endian float32). */
        const val WEIGHTS = 258 * 256 + 128 * 129 * 3 + 128 + 64 * 128 * 3 + 64 + 64 * 64 * 3 + 64 + 128 * 64 * 3 + 128 +
            512 * 128 * 2 + 512 * 2 + 128 + 1
    }
}
