package com.chencohen.ptt.talk

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Talk's Opus (TalkAudioFormat) on Android's own MediaCodec Opus, c2.android.opus, used synchronously from one
// thread: put a frame or a packet in, take what came out. Neither class is thread-safe.

private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS

/** How long one call waits for the codec to take an input or give an output, in microseconds. */
private const val WAIT_US = 20_000L

/** The encoder's delay, in samples at 48 kHz: libopus's look-ahead, given to the decoder as the OpusHead pre-skip. */
private const val PRE_SKIP = 312

private const val BYTES_PER_FRAME = TalkAudioFormat.SAMPLES_PER_FRAME * 2

private fun createCodec(name: String, encoder: Boolean): MediaCodec =
    try {
        MediaCodec.createByCodecName(name)
    } catch (e: Exception) {
        if (encoder) MediaCodec.createEncoderByType(MIME) else MediaCodec.createDecoderByType(MIME)
    }

private fun frameTimeUs(index: Long): Long = index * TalkAudioFormat.FRAME_MS * 1000L

/**
 * 16-bit PCM frames of 20 ms in, one Opus packet per frame out (24 kbit/s, mono, 48 kHz). The codec works a frame or
 * two behind, so [encode] gives the packets that are ready (none, one or more), in order; [finish] gives the rest.
 * The codec's own header (its codec-config buffers) is left out: [OpusDecoder] builds the same header itself.
 */
class OpusEncoder {
    private val codec = createCodec("c2.android.opus.encoder", encoder = true)
    private val info = MediaCodec.BufferInfo()
    private var framesIn = 0L
    private var ended = false

    init {
        val format = MediaFormat.createAudioFormat(MIME, TalkAudioFormat.SAMPLE_RATE, TalkAudioFormat.CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, TalkAudioFormat.BITRATE)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BYTES_PER_FRAME)
            // Speech at 24 kbit/s: libopus's SILK and hybrid modes gain from the higher settings, and one mono voice
            // costs a phone little even there.
            setInteger(MediaFormat.KEY_COMPLEXITY, complexity(codec))
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Encodes one frame of [TalkAudioFormat.SAMPLES_PER_FRAME] samples (a shorter last frame is padded with silence). */
    fun encode(frame: ShortArray, count: Int = frame.size): List<ByteArray> {
        check(!ended) { "the encoder was finished" }
        require(count <= TalkAudioFormat.SAMPLES_PER_FRAME) { "a frame is ${TalkAudioFormat.SAMPLES_PER_FRAME} samples" }
        val out = ArrayList<ByteArray>(2)
        var index = codec.dequeueInputBuffer(WAIT_US)
        while (index < 0) {
            // The codec's inputs are all taken: take its outputs until one comes back.
            drainTo(out, WAIT_US)
            index = codec.dequeueInputBuffer(WAIT_US)
        }
        val buffer = codec.getInputBuffer(index)!!
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putShort(frame[i])
        for (i in count until TalkAudioFormat.SAMPLES_PER_FRAME) buffer.putShort(0)
        codec.queueInputBuffer(index, 0, BYTES_PER_FRAME, frameTimeUs(framesIn++), 0)
        drainTo(out, 0)
        return out
    }

    /** No more frames: gives every packet still inside the codec. The encoder can't be used after it, only [release]d. */
    fun finish(): List<ByteArray> {
        if (ended) return emptyList()
        ended = true
        val out = ArrayList<ByteArray>()
        var index = codec.dequeueInputBuffer(WAIT_US)
        while (index < 0) {
            drainTo(out, WAIT_US)
            index = codec.dequeueInputBuffer(WAIT_US)
        }
        codec.queueInputBuffer(index, 0, 0, frameTimeUs(framesIn), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        // Until the codec says the stream ended; a second's wait at most, so a codec gone wrong can't hang stop().
        val deadline = System.nanoTime() + 1_000_000_000L
        while (!drainTo(out, WAIT_US) && System.nanoTime() < deadline) Unit
        return out
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    /** Adds the packets that are ready to [out]; true once the end of the stream came out. */
    private fun drainTo(out: MutableList<ByteArray>, firstWaitUs: Long): Boolean {
        var wait = firstWaitUs
        while (true) {
            val index = codec.dequeueOutputBuffer(info, wait)
            wait = 0
            if (index < 0) {
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
                continue // the output format or buffers changed
            }
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val buffer = codec.getOutputBuffer(index)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                out += ByteArray(info.size).also { buffer.get(it) }
            }
            codec.releaseOutputBuffer(index, false)
            if (end) return true
        }
    }

    private companion object {
        const val SPEECH_COMPLEXITY = 8

        fun complexity(codec: MediaCodec): Int {
            val range = runCatching { codec.codecInfo.getCapabilitiesForType(MIME).encoderCapabilities?.complexityRange }
                .getOrNull() ?: return SPEECH_COMPLEXITY
            return SPEECH_COMPLEXITY.coerceIn(range.lower, range.upper)
        }
    }
}

/**
 * Opus packets in, 16-bit PCM out (mono, 48 kHz). [decode] waits briefly for the packet's own samples, so each packet
 * usually gives its 20 ms at once; the first gives 312 samples less (the encoder's delay, which the decoder skips).
 * [finish] gives what is left inside the codec.
 */
class OpusDecoder {
    private val codec = createCodec("c2.android.opus.decoder", encoder = false)
    private val info = MediaCodec.BufferInfo()
    private var packetsIn = 0L
    private var ended = false

    init {
        val format = MediaFormat.createAudioFormat(MIME, TalkAudioFormat.SAMPLE_RATE, TalkAudioFormat.CHANNELS).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
            setByteBuffer("csd-1", longLe(PRE_SKIP * 1_000_000_000L / TalkAudioFormat.SAMPLE_RATE))
            setByteBuffer("csd-2", longLe(SEEK_PRE_ROLL_NS))
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** Decodes one packet; gives the PCM that came out (usually that packet's 20 ms). */
    fun decode(packet: ByteArray): List<ShortArray> {
        check(!ended) { "the decoder was finished" }
        val out = ArrayList<ShortArray>(2)
        var index = codec.dequeueInputBuffer(WAIT_US)
        while (index < 0) {
            drainTo(out, WAIT_US)
            index = codec.dequeueInputBuffer(WAIT_US)
        }
        val buffer = codec.getInputBuffer(index)!!
        buffer.clear()
        buffer.put(packet)
        codec.queueInputBuffer(index, 0, packet.size, frameTimeUs(packetsIn++), 0)
        // Waits for this packet's samples, so playback isn't a packet behind the stream; then whatever else is ready.
        drainTo(out, WAIT_US)
        return out
    }

    /** No more packets: gives the PCM still inside the codec. The decoder can't be used after it, only [release]d. */
    fun finish(): List<ShortArray> {
        if (ended) return emptyList()
        ended = true
        val out = ArrayList<ShortArray>()
        var index = codec.dequeueInputBuffer(WAIT_US)
        while (index < 0) {
            drainTo(out, WAIT_US)
            index = codec.dequeueInputBuffer(WAIT_US)
        }
        codec.queueInputBuffer(index, 0, 0, frameTimeUs(packetsIn), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        val deadline = System.nanoTime() + 1_000_000_000L
        while (!drainTo(out, WAIT_US) && System.nanoTime() < deadline) Unit
        return out
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    private fun drainTo(out: MutableList<ShortArray>, firstWaitUs: Long): Boolean {
        var wait = firstWaitUs
        while (true) {
            val index = codec.dequeueOutputBuffer(info, wait)
            wait = 0
            if (index < 0) {
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
                continue
            }
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size > 0) {
                val buffer = codec.getOutputBuffer(index)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val samples = ShortArray(info.size / 2)
                buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                out += samples
            }
            codec.releaseOutputBuffer(index, false)
            if (end) return true
        }
    }

    internal companion object {
        const val SEEK_PRE_ROLL_NS = 80_000_000L

        /** RFC 7845's identification header for talk's stream: version 1, mono, pre-skip 312, 48 kHz, no gain, family 0. */
        fun opusHead(): ByteArray =
            ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII))
                put(1) // version
                put(TalkAudioFormat.CHANNELS.toByte())
                putShort(PRE_SKIP.toShort())
                putInt(TalkAudioFormat.SAMPLE_RATE)
                putShort(0) // output gain
                put(0) // channel mapping family
            }.array()

        private fun longLe(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).apply { flip() }
    }
}
