package com.chencohen.ptt.talk

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays clips on an AudioTrack, one thread per clip. A walkie-talkie is heard on the loudspeaker, so the track is
 * media (USAGE_MEDIA): a voice-call track goes to the earpiece unless the app also turns the speakerphone on, and
 * talk is half duplex, so nothing is gained from the call path's echo cancelling while a clip plays. It follows the
 * media volume. onProgress and onDone are called on the clip's thread.
 */
class AndroidTalkPlayer : TalkPlayer {
    override fun open(
        live: Boolean,
        catchUp: CatchUpMode,
        onProgress: (playedMs: Long, behindMs: Long) -> Unit,
        onDone: () -> Unit,
    ): Playback = TrackPlayback(if (live) CatchUp(catchUp) else null, onProgress, onDone).also { it.begin() }
}

/** One clip: packets wait in [inbox] until the clip's thread decodes and plays them, so [write] never waits. */
private class TrackPlayback(
    private val catchUp: CatchUp?,
    private val onProgress: (playedMs: Long, behindMs: Long) -> Unit,
    private val onDone: () -> Unit,
) : Playback {
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private val packetsWritten = AtomicLong()
    private val done = AtomicBoolean()
    @Volatile private var finished = false
    @Volatile private var stopped = false
    private val thread = Thread(::run, "talk-playback")

    // The clip's thread only. Counts in samples at 48 kHz.
    private lateinit var track: AudioTrack
    private var samplesToTrack = 0L
    private var samplesDropped = 0L
    private var speed = 1f
    private var speedRefused = false
    private var lastProgressNs = 0L
    private val frame = ShortArray(TalkAudioFormat.SAMPLES_PER_FRAME)
    private var frameFill = 0

    fun begin() = thread.start()

    override fun write(packets: List<ByteArray>) {
        if (finished || stopped || packets.isEmpty()) return
        packetsWritten.addAndGet(packets.size.toLong())
        inbox.addAll(packets)
    }

    override fun finish() {
        finished = true
    }

    override fun stop() {
        stopped = true
    }

    private fun run() {
        var decoder: OpusDecoder? = null
        try {
            decoder = OpusDecoder()
            track = openTrack()
            track.play()
            diagnose(track)
            // A gap on a live clip only waits here: the clip ends by finish() or stop(), never by silence.
            while (!stopped) {
                val packet = inbox.poll(FRAME_POLL_MS, TimeUnit.MILLISECONDS)
                if (packet != null) {
                    decoder.decode(packet).forEach(::feed)
                } else if (finished && inbox.isEmpty()) {
                    break
                }
                progressIfDue()
            }
            if (!stopped) {
                decoder.finish().forEach(::feed)
                playOut()
            }
        } catch (e: Exception) {
            Log.w(TAG, "playback failed", e)
        } finally {
            TalkAudio.playLevel = 0f
            if (::track.isInitialized) {
                if (stopped) runCatching { track.pause(); track.flush() }
                track.release()
            }
            decoder?.release()
            if (done.compareAndSet(false, true)) onDone()
        }
    }

    /** Cuts decoded PCM into 20 ms frames, the unit catch-up decides on. */
    private fun feed(pcm: ShortArray) {
        var at = 0
        while (at < pcm.size && !stopped) {
            val n = minOf(pcm.size - at, frame.size - frameFill)
            pcm.copyInto(frame, frameFill, at, at + n)
            frameFill += n
            at += n
            if (frameFill == frame.size) {
                playFrame()
                frameFill = 0
            }
        }
    }

    private fun playFrame() {
        if (catchUp != null) {
            when (val step = catchUp.next(behindMs(), Silence.isSilent(frame))) {
                CatchUpStep.Drop -> {
                    samplesDropped += frame.size
                    return
                }
                is CatchUpStep.Play -> setSpeed(step.speed)
            }
        }
        writeToTrack(frame, frame.size)
    }

    /** The end of the clip: its last part frame, then waits until the track has played everything written to it. */
    private fun playOut() {
        if (frameFill > 0) {
            frame.fill(0, frameFill)
            writeToTrack(frame, frame.size)
            frameFill = 0
        }
        // The track starts once START_SAMPLES are in it; a shorter clip is made that long with silence.
        frame.fill(0)
        while (samplesToTrack in 1 until START_SAMPLES) writeToTrack(frame, frame.size)
        // Then a little silence: the head counts what the mixer took, and the speaker is still behind it, so the last
        // word would be cut by releasing the track the moment the head gets there.
        repeat(TAIL_FRAMES) { writeToTrack(frame, frame.size) }
        var lastHead = -1L
        var stalledSinceNs = System.nanoTime()
        while (!stopped) {
            val head = headPosition()
            if (head >= samplesToTrack) break
            if (head != lastHead) {
                lastHead = head
                stalledSinceNs = System.nanoTime()
            } else if (System.nanoTime() - stalledSinceNs > STALL_NS) {
                Log.w(TAG, "the track stopped at $head of $samplesToTrack samples")
                break
            }
            progressIfDue()
            Thread.sleep(FRAME_POLL_MS)
        }
        progress()
    }

    /** What Android does with the track a moment after it starts: muted or silenced, and why (logcat TalkPlayer). */
    private fun diagnose(t: AudioTrack) {
        Thread {
            Thread.sleep(700)
            runCatching {
                val usage = t.audioAttributes.usage
                Log.i(TAG, "track: playState ${t.playState}, usage $usage, session ${t.audioSessionId}, routed to ${t.routedDevice?.type}")
                val audio = (TalkAudio.appContext ?: return@runCatching).getSystemService(android.media.AudioManager::class.java)
                audio.activePlaybackConfigurations.filter { it.audioAttributes.usage == usage }.forEach { Log.i(TAG, "playback: $it") }
                Log.i(TAG, "volumes: media ${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}, call ${audio.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)}/${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)}, mode ${audio.mode}")
            }.onFailure { Log.w(TAG, "diagnose", it) }
        }.start()
    }

    private fun writeToTrack(samples: ShortArray, count: Int) {
        TalkAudio.playLevel = VoiceLevel.fromRms(VoiceLevel.rms(samples, count))
        var at = 0
        while (at < count && !stopped) {
            val n = track.write(samples, at, count - at, AudioTrack.WRITE_BLOCKING)
            if (n < 0) throw IllegalStateException("AudioTrack.write: $n")
            at += n
            samplesToTrack += n
        }
        progressIfDue()
    }

    private fun setSpeed(next: Float) {
        if (next == speed || speedRefused) return
        try {
            track.playbackParams = PlaybackParams().setSpeed(next).setPitch(1f)
            speed = next
        } catch (e: Exception) {
            // Catch-up still shortens the pauses; the track would refuse every later speed the same way.
            speedRefused = true
            Log.w(TAG, "speed $next refused", e)
        }
    }

    /** Samples the track has played; a 32-bit counter that wraps, read as unsigned. */
    private fun headPosition(): Long = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL

    /** What was written to the clip and not heard yet, in samples: in the inbox, cut into frames, or in the track. */
    private fun behindSamples(): Long {
        val written = packetsWritten.get()
        if (written == 0L) return 0
        val heard = samplesDropped + minOf(headPosition(), samplesToTrack)
        return (written * TalkAudioFormat.SAMPLES_PER_FRAME - PRE_SKIP - heard).coerceAtLeast(0)
    }

    private fun behindMs(): Long = behindSamples() * 1000 / TalkAudioFormat.SAMPLE_RATE

    private fun progressIfDue() {
        if (System.nanoTime() - lastProgressNs >= PROGRESS_NS) progress()
    }

    private fun progress() {
        lastProgressNs = System.nanoTime()
        val behind = behindSamples()
        // How far into the clip the listener is: what was written, less what is still to be heard.
        val played = (packetsWritten.get() * TalkAudioFormat.SAMPLES_PER_FRAME - PRE_SKIP - behind).coerceAtLeast(0)
        onProgress(played * 1000 / TalkAudioFormat.SAMPLE_RATE, behind * 1000 / TalkAudioFormat.SAMPLE_RATE)
    }

    private fun openTrack(): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(TalkAudioFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Inside a Telecom talk session (a woken burst in the background), the call's own voice:
                    // Android mutes media during a call (TalkAudio).
                    .setUsage(if (TalkAudio.inCallSession) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(TalkAudioFormat.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // A short buffer, so a change of speed is heard soon and catch-up sees the backlog in its own queue; but
            // twice the smallest, since a track plays faster than 1.0 only with room for the faster rate.
            .setBufferSizeInBytes(maxOf(2 * minBytes, TRACK_BUFFER_MS * TalkAudioFormat.SAMPLE_RATE / 1000 * 2))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack didn't initialize")
        }
        // Starts (and starts again after a gap) as soon as there are two frames, rather than a full buffer.
        runCatching { track.setStartThresholdInFrames(START_SAMPLES) }
        return track
    }

    private companion object {
        const val TAG = "TalkPlayer"
        const val PRE_SKIP = 312
        const val TRACK_BUFFER_MS = 100
        const val START_SAMPLES = TalkAudioFormat.SAMPLES_PER_FRAME * 2

        /** Silence after the last frame, 200 ms: the speaker's own delay after the mixer. */
        const val TAIL_FRAMES = 10
        const val FRAME_POLL_MS = 10L
        const val PROGRESS_NS = 100_000_000L
        const val STALL_NS = 1_000_000_000L
    }
}
