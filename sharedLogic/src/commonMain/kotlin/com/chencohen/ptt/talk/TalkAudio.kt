package com.chencohen.ptt.talk

import kotlin.math.sqrt

// The audio of talk (docs/design.md, "Phase 5a"): the platform records and plays, the shared code decides. A clip is
// Opus, 24 kbit/s, 20 ms frames, mono, 48 kHz; ten frames make one 200 ms segment.

object TalkAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val FRAME_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000
    const val BITRATE = 24_000
    const val FRAMES_PER_SEGMENT = 10
    const val SEGMENT_MS = FRAME_MS * FRAMES_PER_SEGMENT
}

/**
 * The microphone, as Opus packets (`TalkCapture` in the plan): the app's own recorder, and from phase 5b LiveKit's
 * captured samples while the phone is in a room, so the microphone is never opened twice.
 */
interface TalkCapture {
    /**
     * Opens the microphone and encodes from now on: [onPacket] gets each 20 ms Opus packet, in order, on a thread of
     * the capture's own. False when the microphone can't be opened (no permission, taken by a call).
     */
    fun start(onPacket: (ByteArray) -> Unit): Boolean

    /** Closes the microphone; the packets of what was recorded until now are all given to onPacket before it returns. */
    fun stop()

    /** Opens the microphone ahead of a press (a Talk screen is shown), sending nothing; [start] then sends at once. */
    fun warm() {}

    /** The Talk screen left: the microphone closes (after a burst in progress, if any). */
    fun cool() {}
}

/** Plays clips: decodes Opus packets and plays them on the speaker (or the headset), catching up when [Playback.live]. */
interface TalkPlayer {
    /**
     * A new clip to play. [live]: a burst still being spoken, played with [catchUp] so the listener comes back to the
     * live delay; false for a clip from the history, played at normal speed. [onProgress] is called about every 100 ms
     * with how much was played and how far behind the newest audio written the playback is (both milliseconds).
     * [onDone] once everything written before [Playback.finish] was played, or after [Playback.stop].
     */
    fun open(live: Boolean, catchUp: CatchUpMode, onProgress: (playedMs: Long, behindMs: Long) -> Unit, onDone: () -> Unit): Playback
}

/** One clip being played. */
interface Playback {
    /** More Opus packets, in order. */
    fun write(packets: List<ByteArray>)

    /** Nothing more comes: play what was written, then onDone. */
    fun finish()

    /** Stop now. */
    fun stop()
}

/** Settings › Talk › Catch up to live. */
enum class CatchUpMode {
    /** Shorten the pauses, then play about 1.1 times faster with the pitch kept (the default). */
    SHORTEN_PAUSES_AND_SPEED_UP,

    /** Shorten the pauses only. */
    SHORTEN_PAUSES,

    /** Always play at normal speed, staying behind by the start delay. */
    OFF,
}

/** What to do with the next 20 ms frame of a live clip. */
sealed interface CatchUpStep {
    /** Leave it out (a frame of a pause, while behind). */
    data object Drop : CatchUpStep

    /** Play it at [speed] (1.0 normal), the pitch kept. */
    data class Play(val speed: Float) : CatchUpStep
}

/**
 * Catch-up playback (the plan's "Audio"): while a live clip plays more than [targetMs] + [slackMs] behind, pauses are
 * shortened (a pause keeps its first [keepPauseFrames] frames, the rest is left out), and with
 * [CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP] speech plays at [speed] (or [fastSpeed] when more than [farMs] behind),
 * until it is back at [targetMs]. The player calls [next] once per frame, in order.
 */
class CatchUp(
    private val mode: CatchUpMode,
    private val targetMs: Long = 250,
    private val slackMs: Long = 100,
    private val keepPauseFrames: Int = 5,
    private val speed: Float = 1.1f,
    private val fastSpeed: Float = 1.25f,
    private val farMs: Long = 2_000,
) {
    private var catchingUp = false
    private var pauseFrames = 0

    /** Whether the last frames played were a catch-up (for the debug readout). */
    val active: Boolean get() = catchingUp

    fun next(behindMs: Long, silent: Boolean): CatchUpStep {
        pauseFrames = if (silent) pauseFrames + 1 else 0
        if (mode == CatchUpMode.OFF) return CatchUpStep.Play(1f)
        if (!catchingUp && behindMs > targetMs + slackMs) catchingUp = true
        if (catchingUp && behindMs <= targetMs) catchingUp = false
        if (!catchingUp) return CatchUpStep.Play(1f)
        if (silent && pauseFrames > keepPauseFrames) return CatchUpStep.Drop
        if (mode == CatchUpMode.SHORTEN_PAUSES) return CatchUpStep.Play(1f)
        return CatchUpStep.Play(if (behindMs > farMs) fastSpeed else speed)
    }
}

/** Speech or a pause: a 20 ms frame of 16-bit PCM is silent below about -45 dBFS. */
object Silence {
    const val THRESHOLD = 180.0

    fun isSilent(samples: ShortArray, count: Int = samples.size): Boolean {
        if (count == 0) return true
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / count) < THRESHOLD
    }
}
