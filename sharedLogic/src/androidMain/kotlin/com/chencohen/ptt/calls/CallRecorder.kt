package com.chencohen.ptt.calls

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.GlRectDrawer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoFrameDrawer
import livekit.org.webrtc.VideoSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves a call on this phone (Settings › Calls › Save calls; the plan's "Saving calls"): the call picture drawn into
 * one hardware H.264 encoder (the others' cameras in a grid, this phone's own in a corner) and every voice mixed to
 * AAC, in an MP4 in the app's private storage. A voice call saves only the audio. The frames come from the room's own
 * EGL context ([eglContext]), so drawing them costs no copy.
 */
internal class CallRecorder(private val file: File, eglContext: EglBase.Context, private val video: Boolean) {
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val muxLock = Any()
    private var muxing = false
    private var videoTrack = -1
    private var audioTrack = -1
    private val startNs = System.nanoTime()

    // --- Video ------------------------------------------------------------------------------------------------------

    private val videoThread = HandlerThread("call-record-video").apply { start() }
    private val videoHandler = Handler(videoThread.looper)
    private var videoCodec: MediaCodec? = null
    private var egl: EglBase? = null
    private val drawer by lazy { GlRectDrawer() }
    private val frameDrawer by lazy { VideoFrameDrawer() }

    /** The latest frame of each camera, by its key ("own" for this phone's). */
    private val frames = ConcurrentHashMap<String, VideoFrame>()
    private val sinks = ConcurrentHashMap<String, VideoSink>()

    // --- Audio ------------------------------------------------------------------------------------------------------

    private val audioThread = HandlerThread("call-record-audio").apply { start() }
    private val audioHandler = Handler(audioThread.looper)
    private val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val voices = ConcurrentHashMap<String, Voice>()
    private var samplesOut = 0L

    @Volatile
    private var stopping = false

    init {
        audioCodec.configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        audioCodec.start()
        if (video) {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            val surface: Surface = codec.createInputSurface()
            codec.start()
            videoCodec = codec
            videoHandler.post {
                egl = EglBase.create(eglContext, EglBase.CONFIG_RECORDABLE).also {
                    it.createSurface(surface)
                    it.makeCurrent()
                }
                videoHandler.post(::drawVideo)
            }
        }
        audioHandler.post(::mixAudio)
    }

    /** A camera to draw ([key] "own" is this phone's, drawn small in a corner). */
    fun addVideo(key: String, track: io.livekit.android.room.track.VideoTrack) {
        if (!video || sinks.containsKey(key)) return
        val sink = VideoSink { frame ->
            frame.retain()
            frames.put(key, frame)?.release()
        }
        sinks[key] = sink
        track.addRenderer(sink)
    }

    fun hasVideo(key: String) = sinks.containsKey(key)

    fun videoKeys(): Set<String> = sinks.keys.toSet()

    fun voiceKeys(): Set<String> = voices.keys.toSet()

    fun removeVideo(key: String, track: io.livekit.android.room.track.VideoTrack?) {
        sinks.remove(key)?.let { sink -> runCatching { track?.removeRenderer(sink) } }
        frames.remove(key)?.release()
    }

    /** A voice to mix: the sink to add to its audio track. */
    fun voice(key: String): AudioTrackSink = voices.getOrPut(key) { Voice() }

    fun hasVoice(key: String) = voices.containsKey(key)

    fun removeVoice(key: String) {
        voices.remove(key)
    }

    /** Finishes the file; false when nothing was written (it is deleted then). */
    fun stop(): Boolean {
        stopping = true
        val done = java.util.concurrent.CountDownLatch(2)
        videoHandler.post {
            runCatching { if (videoCodec != null) videoCodec?.signalEndOfInputStream() }
            drainVideo(end = true)
            frames.values.forEach { it.release() }
            frames.clear()
            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            runCatching { drawer.release() }
            runCatching { frameDrawer.release() }
            runCatching { egl?.release() }
            done.countDown()
        }
        audioHandler.post {
            runCatching {
                val index = audioCodec.dequeueInputBuffer(10_000)
                if (index >= 0) audioCodec.queueInputBuffer(index, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainAudio(end = true)
            runCatching { audioCodec.stop() }
            runCatching { audioCodec.release() }
            done.countDown()
        }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        videoThread.quitSafely()
        audioThread.quitSafely()
        val wrote = synchronized(muxLock) {
            val started = muxing
            if (started) runCatching { muxer.stop() }.onFailure { Log.w(TAG, "the file didn't close", it) }
            runCatching { muxer.release() }
            started
        }
        if (!wrote) file.delete()
        return wrote
    }

    private fun ptsUs() = (System.nanoTime() - startNs) / 1_000

    private fun drawVideo() {
        if (stopping) return
        val egl = egl ?: return
        try {
            GLES20.glClearColor(0.06f, 0.08f, 0.075f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val others = frames.keys.filter { it != OWN }.sorted()
            val columns = if (others.size > 2) 2 else 1
            val rows = if (others.isEmpty()) 1 else (others.size + columns - 1) / columns
            others.forEachIndexed { i, key ->
                val frame = frames[key] ?: return@forEachIndexed
                val w = WIDTH / columns
                val h = HEIGHT / rows
                // GL's origin is the bottom left: the first row is drawn at the top.
                draw(frame, (i % columns) * w, HEIGHT - (i / columns + 1) * h, w, h)
            }
            frames[OWN]?.let { own ->
                if (others.isEmpty()) draw(own, 0, 0, WIDTH, HEIGHT)
                else draw(own, WIDTH - WIDTH / 4 - 24, 24, WIDTH / 4, HEIGHT / 4)
            }
            egl.swapBuffers((System.nanoTime() - startNs))
            drainVideo(end = false)
        } catch (e: Exception) {
            Log.w(TAG, "a frame wasn't drawn", e)
        }
        videoHandler.postDelayed(::drawVideo, 1_000L / FPS)
    }

    /** [frame] fitted into the box, its shape kept. */
    private fun draw(frame: VideoFrame, x: Int, y: Int, w: Int, h: Int) {
        val fw = frame.rotatedWidth.toFloat()
        val fh = frame.rotatedHeight.toFloat()
        if (fw <= 0 || fh <= 0) return
        val scale = minOf(w / fw, h / fh)
        val dw = (fw * scale).toInt()
        val dh = (fh * scale).toInt()
        frameDrawer.drawFrame(frame, drawer, null, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh)
    }

    private fun drainVideo(end: Boolean) {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = runCatching { codec.dequeueOutputBuffer(info, if (end) 10_000 else 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return else if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    startMuxer()
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) write(videoTrack, buffer, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
            if (end && index == MediaCodec.INFO_TRY_AGAIN_LATER) return
        }
    }

    /** Every 20 ms: 960 samples from each voice (silence where one has none), summed, to the AAC encoder. */
    private fun mixAudio() {
        if (stopping) return
        try {
            val mixed = IntArray(CHUNK)
            voices.values.forEach { it.takeInto(mixed) }
            val index = audioCodec.dequeueInputBuffer(0)
            if (index >= 0) {
                val buffer = audioCodec.getInputBuffer(index)!!
                buffer.clear()
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (v in mixed) buffer.putShort(v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                audioCodec.queueInputBuffer(index, 0, CHUNK * 2, samplesOut * 1_000_000 / RATE, 0)
                samplesOut += CHUNK
            }
            drainAudio(end = false)
        } catch (e: Exception) {
            Log.w(TAG, "audio wasn't mixed", e)
        }
        audioHandler.postDelayed(::mixAudio, 20)
    }

    private fun drainAudio(end: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = runCatching { audioCodec.dequeueOutputBuffer(info, if (end) 10_000 else 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                    audioTrack = muxer.addTrack(audioCodec.outputFormat)
                    startMuxer()
                }
                index >= 0 -> {
                    val buffer = audioCodec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) write(audioTrack, buffer, info)
                    audioCodec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** The muxer starts once every track it will have is known; what comes before is dropped. */
    private fun startMuxer() {
        if (muxing || audioTrack < 0 || (video && videoTrack < 0)) return
        muxer.start()
        muxing = true
    }

    private fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(muxLock) {
        if (!muxing || track < 0) return@synchronized
        runCatching { muxer.writeSampleData(track, buffer, info) }
    }

    /** One voice's samples as they come (16-bit PCM from its sink), mono 48 kHz, kept at most half a second ahead. */
    private class Voice : AudioTrackSink {
        private val lock = Any()
        private val buffer = ShortArray(RATE)
        private var start = 0
        private var count = 0
        private var at = 0.0

        override fun onData(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int, timestamp: Long) {
            if (bits != 16 || channels < 1 || rate <= 0) return
            val pcm = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val step = rate.toDouble() / RATE
            synchronized(lock) {
                while (at < frames) {
                    val i = at.toInt()
                    var sum = 0
                    for (c in 0 until channels) sum += pcm.get(i * channels + c)
                    if (count == buffer.size / 2) {
                        // Too far ahead of the mixer: the oldest goes.
                        start = (start + 1) % buffer.size
                        count--
                    }
                    buffer[(start + count) % buffer.size] = (sum / channels).toShort()
                    count++
                    at += step
                }
                at -= frames
            }
        }

        fun takeInto(mixed: IntArray) = synchronized(lock) {
            val n = minOf(count, mixed.size)
            for (i in 0 until n) mixed[i] += buffer[(start + i) % buffer.size]
            start = (start + n) % buffer.size
            count -= n
        }
    }

    companion object {
        private const val TAG = "CallRecorder"
        const val OWN = "own"
        private const val WIDTH = 720
        private const val HEIGHT = 1280
        private const val FPS = 30
        private const val RATE = 48_000
        private const val CHUNK = RATE / 50
    }
}
