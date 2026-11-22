package com.chencohen.ptt.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import com.chencohen.ptt.talk.OpusEncoder
import com.chencohen.ptt.talk.SpeechDetector
import com.chencohen.ptt.talk.TalkAudio
import com.chencohen.ptt.talk.TalkAudioFormat
import com.chencohen.ptt.talk.TalkCapture
import com.chencohen.ptt.talk.VoiceEnd
import com.chencohen.ptt.talk.VoiceLevel

/**
 * The car's microphone for a burst (Android Auto; docs/screens/android-auto.md): CarAudioRecord at 16 kHz (the head
 * unit's own processing on it), raised to 48 kHz for Talk's Opus, and each 32 ms through [detector] and [VoiceEnd]
 * so the burst ends by itself: [onEnd] with ENDED, NOTHING_HEARD or TOO_LONG.
 */
class CarTalkCapture(
    private val carContext: CarContext,
    private val detector: SpeechDetector,
    private val onEnd: (VoiceEnd.Result) -> Unit,
) : TalkCapture {
    private var record: CarAudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var stopping = false
    private var encoder: OpusEncoder? = null
    private var onPacket: ((ByteArray) -> Unit)? = null

    override fun start(onPacket: (ByteArray) -> Unit): Boolean {
        if (thread != null) return false
        if (carContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no microphone permission: allow it on the phone")
            return false
        }
        val r = try {
            CarAudioRecord.create(carContext).also { it.startRecording() }
        } catch (e: Exception) {
            Log.w(TAG, "no car microphone", e)
            return false
        }
        record = r
        encoder = OpusEncoder()
        this.onPacket = onPacket
        detector.reset()
        stopping = false
        thread = Thread({ read(r) }, "car-capture").also { it.start() }
        return true
    }

    override fun stop() {
        stopping = true
        thread?.join()
        thread = null
        runCatching { record?.stopRecording() }
        record = null
        val e = encoder ?: return
        encoder = null
        runCatching { e.finish().forEach { onPacket?.invoke(it) } }
        e.release()
        TalkAudio.micLevel = 0f
    }

    private fun read(r: CarAudioRecord) {
        val bytes = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        val vadFrame = FloatArray(detector.frameSamples)
        var vadFill = 0
        val out48 = ShortArray(TalkAudioFormat.SAMPLES_PER_FRAME)
        var outFill = 0
        var last = 0
        val end = VoiceEnd(detector.frameSamples * 1000.0 / detector.sampleRate)
        var ended = false
        try {
            while (!stopping) {
                val n = r.read(bytes, 0, bytes.size)
                if (n <= 0) break
                for (i in 0 until n / 2) {
                    val s = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff))
                    // 16 kHz to 48 kHz: three samples, straight between the last one and this.
                    for (k in 1..3) {
                        out48[outFill++] = (last + (s - last) * k / 3).toShort()
                        if (outFill == out48.size) {
                            TalkAudio.micLevel = VoiceLevel.fromRms(VoiceLevel.rms(out48))
                            encoder?.encode(out48)?.forEach { onPacket?.invoke(it) }
                            outFill = 0
                        }
                    }
                    last = s
                    vadFrame[vadFill++] = s / 32768f
                    if (vadFill == vadFrame.size) {
                        vadFill = 0
                        if (!ended) {
                            val result = end.feed(detector.probability(vadFrame))
                            if (result != VoiceEnd.Result.GOING) {
                                ended = true
                                onEnd(result)
                            }
                        }
                    }
                }
            }
            if (outFill > 0) encoder?.encode(out48, outFill)?.forEach { onPacket?.invoke(it) }
        } catch (e: Exception) {
            Log.w(TAG, "car recording failed", e)
        }
    }

    private companion object {
        const val TAG = "CarTalk"
    }
}
