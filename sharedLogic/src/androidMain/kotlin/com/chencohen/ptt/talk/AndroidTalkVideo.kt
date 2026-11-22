package com.chencohen.ptt.talk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Push-to-video's camera on Android (TalkVideo): the front camera into a hardware encoder, H.265 when the phone has
 * one and Settings › Calls › Video is H.265, else VP8, 640×480 at 15 fps and 350 kbit/s (a minute fits the server's 4 MB
 * clip), a key frame every second. While warm (a Talk screen with Video on shows) the camera and encoder run and
 * the preview shows; a press asks for a key frame and sends from it. The frames go as the camera captures them, with
 * the rotation to show them upright (hen: the picture isn't changed). Not warm, a press sends no video.
 */
class AndroidTalkVideoCapture(context: Context, private val wantH265: () -> Boolean) : TalkVideoCapture {
    private val app = context.applicationContext
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var preview: Surface? = null
    private var codec = TalkVideo.VP8
    private var config: ByteArray? = null
    private var sink: ((TalkVideo.Frame) -> Unit)? = null
    private var startUs = -1L
    private var sensor = 270
    @Volatile private var warmWanted = false

    /** The Talk screen's preview: the camera draws into it too while warm (null: none). */
    fun setPreview(surface: Surface?) {
        synchronized(lock) {
            if (preview === surface) return
            preview = surface
        }
        handler?.post { configure() }
    }

    /**
     * [surface] goes away: the camera stops drawing into it if it is the preview (a newer one is left as it is), then
     * [released] runs, on the camera's thread, once it is safe to release.
     */
    fun clearPreview(surface: Surface, released: () -> Unit) {
        val current = synchronized(lock) {
            (preview === surface).also { if (it) preview = null }
        }
        val h = handler
        if (h == null) {
            released()
            return
        }
        h.post {
            if (current) configure()
            // The old session lets go of its surfaces as it closes.
            h.postDelayed(released, 300)
        }
    }

    @SuppressLint("MissingPermission")
    override fun warm() {
        synchronized(lock) {
            warmWanted = true
            if (thread != null) {
                // Still running, but the camera was lost (taken while away): open it again.
                handler?.post { if (camera == null && warmWanted) open() }
                return
            }
            if (app.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            thread = HandlerThread("talk-video").also { it.start() }
            handler = Handler(thread!!.looper)
        }
        handler?.post { open() }
    }

    override fun cool() {
        synchronized(lock) { warmWanted = false }
        handler?.post { close() }
    }

    override fun start(onFrame: (TalkVideo.Frame) -> Unit): Boolean {
        val enc = synchronized(lock) {
            val enc = encoder ?: return false
            sink = onFrame
            startUs = -1L
            enc
        }
        // The listener starts at a key frame.
        runCatching { enc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        return true
    }

    override fun stop() {
        synchronized(lock) { sink = null }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (camera != null) return
        val manager = app.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: manager.cameraIdList.firstOrNull() ?: return
        sensor = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        try {
            if (encoder == null) startEncoder()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!warmWanted) {
                        device.close()
                        return
                    }
                    camera = device
                    configure()
                }

                // Taken by another app, or an error: let it go (a press then sends the voice only).
                override fun onDisconnected(device: CameraDevice) = lost(device)
                override fun onError(device: CameraDevice, error: Int) {
                    Log.w(TAG, "camera error $error")
                    lost(device)
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "no camera for push-to-video", e)
            close()
        }
    }

    /**
     * The encoder: H.265 when wanted (and there is a hardware one), else VP8; when VP8 can't be set up (the S25 has only
     * Android's software VP8), H.264, which every phone decodes.
     */
    private fun startEncoder() {
        val order = buildList {
            if (wantH265() && hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) add(TalkVideo.H265)
            add(TalkVideo.VP8)
            add(TalkVideo.H264)
        }
        for (c in order) {
            if (tryEncoder(c)) return
        }
        throw IllegalStateException("no video encoder")
    }

    private fun tryEncoder(c: Int): Boolean {
        val mime = when (c) {
            TalkVideo.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            TalkVideo.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> MediaFormat.MIMETYPE_VIDEO_VP8
        }
        val format = MediaFormat.createVideoFormat(mime, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (c != TalkVideo.VP8) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        val enc = try {
            MediaCodec.createEncoderByType(mime)
        } catch (e: Exception) {
            Log.w(TAG, "no $mime encoder", e)
            return false
        }
        enc.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        emit(bytes, info)
                    }
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { Log.w(TAG, "encoder", e) }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, handler)
        return try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = enc.createInputSurface()
            enc.start()
            codec = c
            synchronized(lock) { encoder = enc }
            Log.i(TAG, "push-to-video encoder: ${enc.name} ($mime) ${WIDTH}x$HEIGHT")
            true
        } catch (e: Exception) {
            Log.w(TAG, "${enc.name} can't be set up for $mime", e)
            runCatching { enc.release() }
            false
        }
    }

    private fun emit(bytes: ByteArray, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            config = bytes
            return
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val out = synchronized(lock) {
            val s = sink ?: return
            if (startUs < 0) {
                if (!key) return
                startUs = info.presentationTimeUs
            }
            s
        }
        // A key frame carries its parameter sets (H.265), whether the encoder put them in or not.
        val data = if (key && codec != TalkVideo.VP8 && config != null && !startsWith(bytes, config!!)) config!! + bytes else bytes
        out(TalkVideo.Frame((info.presentationTimeUs - startUs) / 1000, key, rotation(), data, codec))
    }

    /** How the Talk screen's preview turns the camera's frames upright (as the listener's screen will). */
    fun previewRotation(): Int {
        if (!sensorRead) runCatching {
            val manager = app.getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            if (id != null) sensor = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: sensor
            sensorRead = true
        }
        return rotation()
    }

    private var sensorRead = false

    /** Clockwise degrees to turn the front camera's frames upright as the phone is held now. */
    private fun rotation(): Int {
        val display = (app.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0) * 90
        return (sensor + display) % 360
    }

    private fun lost(device: CameraDevice) {
        if (camera === device) camera = null
        runCatching { session?.close() }
        session = null
        runCatching { device.close() }
    }

    /** Counts the sessions asked for: only the newest one runs (a rotation can ask twice in a row). */
    private var sessions = 0

    private fun configure() {
        val device = camera ?: return
        val enc = encoderSurface ?: return
        val asked = ++sessions
        runCatching { session?.close() }
        session = null
        val targets = listOfNotNull(enc, preview)
        try {
            val executor = Executor { r -> handler?.post(r) }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map { target ->
                        // The preview mirrored like a mirror; what is sent as the camera sees it.
                        OutputConfiguration(target).apply {
                            if (android.os.Build.VERSION.SDK_INT >= 33) mirrorMode = if (target === enc) OutputConfiguration.MIRROR_MODE_NONE else OutputConfiguration.MIRROR_MODE_AUTO
                        }
                    },
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            // The camera may have closed meanwhile (the screen left, or Video turned off).
                            if (camera !== device || asked != sessions) {
                                runCatching { s.close() }
                                return
                            }
                            session = s
                            try {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    targets.forEach { addTarget(it) }
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
                                }
                                s.setRepeatingRequest(request.build(), null, handler)
                            } catch (e: Exception) {
                                Log.w(TAG, "camera closed while starting", e)
                            }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.w(TAG, "camera session failed")
                            // A preview that went meanwhile: go on without it, so the camera keeps sending.
                            if (asked == sessions && camera === device && preview != null) {
                                synchronized(lock) { preview = null }
                                configure()
                            }
                        }
                    },
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "camera session", e)
            if (e is IllegalStateException) lost(device)
        }
    }

    private fun close() {
        if (warmWanted) return
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        val enc = synchronized(lock) { encoder.also { encoder = null; sink = null } }
        runCatching { enc?.stop() }
        runCatching { enc?.release() }
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        config = null
        val t = synchronized(lock) { thread.also { thread = null; handler = null } }
        t?.quitSafely()
    }

    private fun startsWith(a: ByteArray, prefix: ByteArray) = a.size >= prefix.size && prefix.indices.all { a[it] == prefix[it] }

    companion object {
        const val TAG = "TalkVideo"
        const val WIDTH = 640
        const val HEIGHT = 480
        const val FPS = 15
        const val BITRATE = 350_000

        fun hasEncoder(mime: String): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}

/**
 * Where bursts' video shows on Android: the Talk screen's or the reply bubble's view gives its surface ([attach]); a
 * decoder per clip draws each frame when the voice has played up to it ([position]), so picture and voice keep
 * together. With no surface, nothing is decoded until one comes and a key frame with it.
 */
object AndroidTalkVideoOut : TalkVideoOut {
    private const val TAG = "TalkVideoOut"
    private val lock = Any()
    private var surface: Surface? = null
    private var clip: String? = null
    private var decoder: MediaCodec? = null
    private var decoderCodec = -1
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var playedMs = 0L
    private var playedAt = 0L
    private val waiting = ArrayDeque<TalkVideo.Frame>()
    private val inputs = ArrayDeque<Int>()

    /**
     * The clip's frames since its last key frame: a view that comes after the clip began (the Talk screen's or the
     * bubble's, which show only once a video burst plays) starts from them at once, instead of waiting for the next key
     * frame or, on a short burst, showing nothing (2026-11-22: later bursts showed no video).
     */
    private val sinceKey = ArrayList<TalkVideo.Frame>()

    private val _shown = MutableStateFlow<Shown?>(null)

    /** The clip being shown: its picture's size and rotation, for the view's shape. */
    val shown: StateFlow<Shown?> = _shown.asStateFlow()

    data class Shown(val clip: String, val width: Int, val height: Int, val rotation: Int)

    fun attach(target: Surface) {
        synchronized(lock) {
            if (surface === target) return
            surface = target
            releaseDecoder()
            Log.i(TAG, "view attached (clip ${clip != null}, ${sinceKey.size} frames since the key frame)")
            restart()
        }
    }

    /** With a view and a key frame of the clip: a decoder from that key frame on. */
    private fun restart() {
        val target = surface ?: return
        if (clip == null || decoder != null) return
        val first = sinceKey.firstOrNull()?.takeIf { it.key } ?: return
        startDecoder(first.codec, target) ?: return
        waiting.clear()
        waiting.addAll(sinceKey)
        feed()
    }

    fun detach(target: Surface) {
        synchronized(lock) {
            if (surface !== target) return
            surface = null
            releaseDecoder()
        }
    }

    override fun begin(clip: String) {
        synchronized(lock) {
            this.clip = clip
            playedMs = 0
            playedAt = SystemClock.elapsedRealtime()
            waiting.clear()
            sinceKey.clear()
            releaseDecoder()
            if (thread == null) thread = HandlerThread("talk-video-out").also { it.start(); handler = Handler(it.looper) }
            Log.i(TAG, "video burst $clip begins (view ${surface != null})")
        }
    }

    override fun frame(clip: String, frame: TalkVideo.Frame) {
        synchronized(lock) {
            if (this.clip != clip) return
            _shown.value = Shown(clip, _shown.value?.takeIf { it.clip == clip }?.width ?: 0, _shown.value?.takeIf { it.clip == clip }?.height ?: 0, frame.rotation)
            if (frame.key) sinceKey.clear()
            if (sinceKey.isNotEmpty() || frame.key) sinceKey += frame
            if (sinceKey.size > MAX_SINCE_KEY) sinceKey.subList(0, sinceKey.size - MAX_SINCE_KEY).clear()
            val target = surface ?: return
            if (decoder == null || decoderCodec != frame.codec) {
                if (!frame.key) return
                releaseDecoder()
                startDecoder(frame.codec, target) ?: return
            }
            waiting.addLast(frame)
            feed()
        }
    }

    override fun position(clip: String, playedMs: Long) {
        synchronized(lock) {
            if (this.clip != clip) return
            this.playedMs = playedMs
            playedAt = SystemClock.elapsedRealtime()
        }
    }

    override fun end(clip: String) {
        synchronized(lock) {
            if (this.clip != clip) return
            this.clip = null
            waiting.clear()
            sinceKey.clear()
            releaseDecoder()
            _shown.value = null
        }
    }

    private fun startDecoder(codec: Int, target: Surface): MediaCodec? {
        val mime = when (codec) {
            TalkVideo.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            TalkVideo.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> MediaFormat.MIMETYPE_VIDEO_VP8
        }
        return try {
            val dec = MediaCodec.createDecoderByType(mime)
            dec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    synchronized(lock) {
                        if (decoder !== codec) return
                        inputs.addLast(index)
                        feed()
                    }
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    // Drawn when the voice gets there; late by more than a moment, dropped.
                    val waitMs = synchronized(lock) {
                        if (decoder !== codec) return
                        info.presentationTimeUs / 1000 - (playedMs + SystemClock.elapsedRealtime() - playedAt)
                    }
                    val h = handler ?: return
                    val render = Runnable {
                        synchronized(lock) {
                            if (decoder !== codec) return@Runnable
                            runCatching { codec.releaseOutputBuffer(index, waitMs > -LATE_MS) }
                        }
                    }
                    if (waitMs > 0) h.postDelayed(render, minOf(waitMs, MAX_WAIT_MS)) else render.run()
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { Log.w(TAG, "decoder", e) }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    _shown.value = _shown.value?.copy(width = w, height = h)
                }
            }, handler)
            dec.configure(MediaFormat.createVideoFormat(mime, AndroidTalkVideoCapture.WIDTH, AndroidTalkVideoCapture.HEIGHT), target, null, 0)
            dec.start()
            Log.i(TAG, "decoding ${dec.name}")
            decoder = dec
            decoderCodec = codec
            inputs.clear()
            dec
        } catch (e: Exception) {
            Log.w(TAG, "no decoder for $mime", e)
            null
        }
    }

    private fun feed() {
        val dec = decoder ?: return
        while (waiting.isNotEmpty() && inputs.isNotEmpty()) {
            val frame = waiting.removeFirst()
            val index = inputs.removeFirst()
            try {
                val buffer = dec.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(frame.data)
                dec.queueInputBuffer(index, 0, frame.data.size, frame.ptsMs * 1000, if (frame.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            } catch (e: Exception) {
                Log.w(TAG, "decode", e)
            }
        }
    }

    private fun releaseDecoder() {
        val dec = decoder ?: return
        decoder = null
        decoderCodec = -1
        inputs.clear()
        runCatching { dec.stop() }
        runCatching { dec.release() }
    }

    private const val LATE_MS = 150L
    private const val MAX_SINCE_KEY = 90
    private const val MAX_WAIT_MS = 1_000L
}
