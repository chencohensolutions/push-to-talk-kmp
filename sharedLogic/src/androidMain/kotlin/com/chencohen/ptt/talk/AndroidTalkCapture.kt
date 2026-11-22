package com.chencohen.ptt.talk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.CountDownLatch

/**
 * The phone's own recorder: the voice-call microphone (with the system's echo canceller, noise suppressor and
 * automatic gain control where the phone has them), 48 kHz mono, encoded to Opus on a thread of its own. With
 * [speakerphone], the speakerphone's microphone tuning while it records (SpeakerphoneMic), so a voice at arm's length
 * is heard. The first second's level is logged (TalkCapture, "first second RMS").
 *
 * [warm] opens the microphone ahead of a press (the Talk screens, 2026-11-22, hen: a press transmits at once): it
 * reads all the time and keeps only the last [RING_MS] in memory, sending nothing; [start] sends those first, so the
 * first syllable isn't cut. [cool] closes it again (the screen left).
 */
class AndroidTalkCapture(private val speakerphone: SpeakerphoneMic? = null) : TalkCapture {
    private val lock = Any()
    private var mic: Mic? = null
    private var warmWanted = false

    /** Packets go to [onPacket] through [encoder] while it is set; [ending] drains and finishes it. */
    private class Sink(val encoder: OpusEncoder, val onPacket: (ByteArray) -> Unit) {
        @Volatile var ending = false
        var flushed = false
        val done = CountDownLatch(1)
        var levelSamples = 0
        var levelSum = 0.0
    }

    private class Mic(val record: AudioRecord, val effects: List<AudioEffect>) {
        lateinit var thread: Thread
        @Volatile var closing = false
        @Volatile var sink: Sink? = null
        val ring = ArrayDeque<ShortArray>()
    }

    override fun warm() {
        synchronized(lock) {
            warmWanted = true
            if (mic == null) mic = open()?.also { Log.i(TAG, "microphone warm") }
        }
    }

    override fun cool() {
        val m = synchronized(lock) {
            warmWanted = false
            val m = mic ?: return
            if (m.sink != null) return
            mic = null
            m
        }
        close(m)
        Log.i(TAG, "microphone closed")
    }

    /** False when the microphone can't be opened; also while already recording. */
    override fun start(onPacket: (ByteArray) -> Unit): Boolean = synchronized(lock) {
        if (mic?.sink != null) return false
        speakerphone?.begin()
        val m = mic ?: open()?.also { mic = it } ?: return false.also { speakerphone?.end() }
        val encoder = try {
            OpusEncoder()
        } catch (e: Exception) {
            Log.w(TAG, "no Opus encoder", e)
            if (!warmWanted) {
                mic = null
                close(m)
            }
            speakerphone?.end()
            return false
        }
        m.sink = Sink(encoder, onPacket)
        true
    }

    /** Waits until everything recorded until now was encoded and given to onPacket. */
    override fun stop() {
        val (m, sink) = synchronized(lock) {
            val m = mic ?: return
            val sink = m.sink ?: return
            m to sink
        }
        sink.ending = true
        sink.done.await()
        speakerphone?.end()
        synchronized(lock) {
            if (!warmWanted && mic === m) {
                mic = null
                close(m)
            }
        }
    }

    private fun open(): Mic? {
        val record = openRecord() ?: return null
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull() else null,
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull() else null,
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(record.audioSessionId) }.getOrNull() else null,
        ).onEach { runCatching { it.enabled = true } }
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "the microphone didn't start", e)
            effects.forEach { it.release() }
            record.release()
            return null
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            effects.forEach { it.release() }
            record.release()
            return null
        }
        val m = Mic(record, effects)
        m.thread = Thread({ read(m) }, "talk-capture").also { it.start() }
        return m
    }

    private fun close(m: Mic) {
        m.closing = true
        m.thread.join()
        runCatching { m.record.stop() }
        m.effects.forEach { runCatching { it.release() } }
        m.record.release()
    }

    private fun read(m: Mic) {
        val frame = ShortArray(TalkAudioFormat.SAMPLES_PER_FRAME)
        var filled = 0
        try {
            while (!m.closing) {
                val sink = m.sink
                // A blocking read returns within a frame, so a stop is seen within 20 ms; then the rest of the
                // recorder's buffer, what was recorded before stop(), is read without waiting.
                val mode = if (sink?.ending == true) AudioRecord.READ_NON_BLOCKING else AudioRecord.READ_BLOCKING
                val read = m.record.read(frame, filled, frame.size - filled, mode)
                if (read < 0) break
                filled += read
                if (sink == null) {
                    // Warm: keep the last RING_MS, send nothing.
                    if (filled == frame.size) {
                        m.ring.addLast(frame.copyOf())
                        while (m.ring.size > RING_FRAMES) m.ring.removeFirst()
                        filled = 0
                    }
                    continue
                }
                if (!sink.flushed) {
                    sink.flushed = true
                    m.ring.forEach { encode(sink, it, it.size) }
                    m.ring.clear()
                }
                if (filled == frame.size) {
                    encode(sink, frame, frame.size)
                    filled = 0
                } else if (sink.ending && read == 0) {
                    if (filled > 0) encode(sink, frame, filled)
                    filled = 0
                    finish(m, sink)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "recording failed", e)
        } finally {
            m.sink?.let { finish(m, it) }
        }
    }

    private fun encode(sink: Sink, frame: ShortArray, count: Int) {
        if (sink.levelSamples < TalkAudioFormat.SAMPLE_RATE) {
            for (i in 0 until count) sink.levelSum += frame[i].toDouble() * frame[i]
            sink.levelSamples += count
            if (sink.levelSamples >= TalkAudioFormat.SAMPLE_RATE) {
                val rms = kotlin.math.sqrt(sink.levelSum / sink.levelSamples)
                val dbfs = 20 * kotlin.math.log10(maxOf(rms, 1.0) / 32768.0)
                Log.i(TAG, "first second RMS %.0f (%.1f dBFS)".format(rms, dbfs))
            }
        }
        (if (count == frame.size) sink.encoder.encode(frame) else sink.encoder.encode(frame, count)).forEach(sink.onPacket)
    }

    private fun finish(m: Mic, sink: Sink) {
        try {
            sink.encoder.finish().forEach(sink.onPacket)
        } catch (e: Exception) {
            Log.w(TAG, "finishing the clip", e)
        } finally {
            sink.encoder.release()
            m.sink = null
            sink.done.countDown()
        }
    }

    private fun openRecord(): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(TalkAudioFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return null
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(TalkAudioFormat.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                // Room for a stall of the encoding thread without losing samples.
                .setBufferSizeInBytes(maxOf(minBytes, TalkAudioFormat.SAMPLE_RATE * 2 / 2))
                .build()
        } catch (e: SecurityException) {
            Log.w(TAG, "no RECORD_AUDIO permission", e)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "no microphone", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    private companion object {
        const val TAG = "TalkCapture"

        /** Kept while warm, and sent first on a press: about 300 ms. */
        const val RING_MS = 300
        const val RING_FRAMES = RING_MS / TalkAudioFormat.FRAME_MS
    }
}
