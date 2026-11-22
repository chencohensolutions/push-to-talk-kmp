package com.chencohen.ptt.talk

import kotlin.math.exp

/**
 * A speech detector: the chance a frame is speech, 0 to 1. On Android, Silero VAD (a small trained model, robust to
 * road, engine and fan noise; SileroSpeechDetector); [EnergySpeechDetector] when the model can't load.
 */
interface SpeechDetector {
    val sampleRate: Int

    /** Samples per frame ([sampleRate]); Silero's is 512 at 16 kHz (32 ms). */
    val frameSamples: Int

    /** [frame]: [frameSamples] samples, -1 to 1. */
    fun probability(frame: FloatArray): Float

    /** A new burst: forget what was heard. */
    fun reset()

    fun close() {}
}

/** The thresholds of [VoiceEnd], in milliseconds (docs/screens/android-auto.md). */
data class VoiceEndConfig(
    /** The first stretch after the beep, before anyone speaks: the noise's own speech probability is measured. */
    val calibrateMs: Int = 300,
    /** Speech for this long, in a row, counts as started. */
    val startMs: Int = 200,
    /** After speech, this long without it ends the burst (pauses inside a sentence are shorter). */
    val endSilenceMs: Int = 1_500,
    /** No speech by then: cancelled, "Nothing heard". */
    val nothingHeardMs: Int = 4_000,
    /** Sent at this length whatever happens. */
    val maxMs: Int = 60_000,
    /** The speech threshold when the noise looks like nothing (raised above a noisy floor). */
    val threshold: Float = 0.5f,
)

/**
 * When a hands-free burst ends (Android Auto; later Talk back and the widget): fed each frame's speech probability.
 * The threshold adapts to the noise measured in the first [VoiceEndConfig.calibrateMs] (floor + 0.3, between
 * [VoiceEndConfig.threshold] and 0.85), and a frame counts as silence again only below the threshold less 0.15.
 */
class VoiceEnd(private val frameMs: Double, private val config: VoiceEndConfig = VoiceEndConfig()) {
    enum class Result { GOING, NOTHING_HEARD, ENDED, TOO_LONG }

    private var elapsedMs = 0.0
    private var calibration = 0.0
    private var calibrationFrames = 0
    private var threshold = config.threshold
    private var speechMs = 0.0
    private var silenceMs = 0.0
    private var inSpeech = false

    /** Speech was heard ([VoiceEndConfig.startMs] of it). */
    var started = false
        private set

    fun feed(probability: Float): Result {
        elapsedMs += frameMs
        if (elapsedMs <= config.calibrateMs && !inSpeech && probability < config.threshold) {
            calibration += probability
            calibrationFrames++
            threshold = (calibration / calibrationFrames + 0.3).toFloat().coerceIn(config.threshold, 0.85f)
        }
        inSpeech = if (inSpeech) probability >= threshold - 0.15f else probability >= threshold
        if (inSpeech) {
            speechMs += frameMs
            silenceMs = 0.0
            if (speechMs >= config.startMs) started = true
        } else {
            speechMs = 0.0
            silenceMs += frameMs
        }
        return when {
            elapsedMs >= config.maxMs -> Result.TOO_LONG
            !started && elapsedMs >= config.nothingHeardMs -> Result.NOTHING_HEARD
            started && silenceMs >= config.endSilenceMs -> Result.ENDED
            else -> Result.GOING
        }
    }
}

/**
 * A plain energy detector, only when the trained model can't run: a frame's level above a noise floor that follows
 * the quiet frames, as a probability.
 */
class EnergySpeechDetector(override val sampleRate: Int = 16_000, override val frameSamples: Int = 512) : SpeechDetector {
    private var floorDb = Double.NaN

    override fun probability(frame: FloatArray): Float {
        var sum = 0.0
        for (v in frame) sum += v.toDouble() * v
        val rms = kotlin.math.sqrt(sum / frame.size.coerceAtLeast(1))
        val db = if (rms < 1e-6) -120.0 else 20 * kotlin.math.log10(rms)
        if (floorDb.isNaN()) floorDb = db
        val above = db - maxOf(floorDb, -70.0)
        floorDb = if (db < floorDb) floorDb + (db - floorDb) * 0.3 else floorDb + (db - floorDb) * 0.01
        return (1 / (1 + exp(-(above - 12) / 2))).toFloat()
    }

    override fun reset() {
        floorDb = Double.NaN
    }
}

/** 48 kHz to 16 kHz (the phone's microphone to Silero): the average of each three samples, as floats. */
object Downsample {
    fun to16k(pcm48: ShortArray, count: Int, out: FloatArray): Int {
        val n = count / 3
        for (i in 0 until n) out[i] = (pcm48[3 * i] + pcm48[3 * i + 1] + pcm48[3 * i + 2]) / (3f * 32768f)
        return n
    }
}
