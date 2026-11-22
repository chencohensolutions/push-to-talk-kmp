package com.chencohen.ptt.talk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * The phone's own recorder: the voice-call microphone (with the system's echo canceller, noise suppressor and
 * automatic gain control where the phone has them), 48 kHz mono, encoded to Opus on a thread of its own. With
 * [speakerphone], the speakerphone's microphone tuning while it records (SpeakerphoneMic), so a voice at arm's length
 * is heard. The first second's level is logged (TalkCapture, "first second RMS").
 */
class AndroidTalkCapture(private val speakerphone: SpeakerphoneMic? = null) : TalkCapture {
    private val lock = Any()
    private var running: Running? = null

    private class Running(val thread: Thread, val record: AudioRecord, val effects: List<AudioEffect>) {
        @Volatile var stopping = false
    }

    /** False when the microphone can't be opened; also while already recording. */
    override fun start(onPacket: (ByteArray) -> Unit): Boolean = synchronized(lock) {
        if (running != null) return false
        speakerphone?.begin()
        val record = openRecord() ?: return false.also { speakerphone?.end() }
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull() else null,
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull() else null,
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(record.audioSessionId) }.getOrNull() else null,
        ).onEach { runCatching { it.enabled = true } }
        val encoder = try {
            OpusEncoder()
        } catch (e: Exception) {
            Log.w(TAG, "no Opus encoder", e)
            effects.forEach { it.release() }
            record.release()
            speakerphone?.end()
            return false
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "the microphone didn't start", e)
            encoder.release()
            effects.forEach { it.release() }
            record.release()
            speakerphone?.end()
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            encoder.release()
            effects.forEach { it.release() }
            record.release()
            speakerphone?.end()
            return false
        }
        lateinit var state: Running
        val thread = Thread({ record(state, encoder, onPacket) }, "talk-capture")
        state = Running(thread, record, effects)
        running = state
        thread.start()
        true
    }

    /** Waits until everything recorded until now was encoded and given to onPacket. */
    override fun stop() {
        val state = synchronized(lock) { running.also { running = null } } ?: return
        state.stopping = true
        state.thread.join()
        state.effects.forEach { runCatching { it.release() } }
        state.record.release()
        speakerphone?.end()
    }

    private fun record(state: Running, encoder: OpusEncoder, onPacket: (ByteArray) -> Unit) {
        val frame = ShortArray(TalkAudioFormat.SAMPLES_PER_FRAME)
        var filled = 0
        // The first second's level, to compare microphone tunings (logcat TalkCapture).
        var levelSamples = 0
        var levelSum = 0.0
        try {
            // A blocking read returns within a frame, so stop() is seen within 20 ms; then the rest of the
            // recorder's buffer, what was recorded before stop(), is read without waiting.
            while (true) {
                val mode = if (state.stopping) AudioRecord.READ_NON_BLOCKING else AudioRecord.READ_BLOCKING
                val read = state.record.read(frame, filled, frame.size - filled, mode)
                if (read < 0) break
                filled += read
                if (filled == frame.size) {
                    if (levelSamples < TalkAudioFormat.SAMPLE_RATE) {
                        for (v in frame) levelSum += v.toDouble() * v
                        levelSamples += frame.size
                        if (levelSamples >= TalkAudioFormat.SAMPLE_RATE) {
                            val rms = kotlin.math.sqrt(levelSum / levelSamples)
                            val dbfs = 20 * kotlin.math.log10(maxOf(rms, 1.0) / 32768.0)
                            Log.i(TAG, "first second RMS %.0f (%.1f dBFS)".format(rms, dbfs))
                        }
                    }
                    encoder.encode(frame).forEach(onPacket)
                    filled = 0
                } else if (state.stopping && read == 0) {
                    break
                }
            }
            runCatching { state.record.stop() }
            if (filled > 0) encoder.encode(frame, filled).forEach(onPacket)
            encoder.finish().forEach(onPacket)
        } catch (e: Exception) {
            Log.w(TAG, "recording failed", e)
        } finally {
            encoder.release()
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
    }
}
