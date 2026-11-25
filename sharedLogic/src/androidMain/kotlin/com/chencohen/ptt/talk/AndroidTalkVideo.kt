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
 * one and Settings › Calls › Video is H.265, else VP8, 640×480 at 30 fps and 700 kbit/s (2026-11-22, hen: "not 15"),
 * or 60 fps and 1 Mbit/s with Settings › Calls › 60 fps video where the camera can; a key frame every second. The
 * burst's video stops at Talk.VIDEO_BUDGET (the server's 8 MiB clip): about 85 s at 30 fps, 60 s at 60. While warm (a Talk screen with Video on shows) the camera and encoder run and
 * the preview shows; a press asks for a key frame and sends from it. The frames go as the camera captures them, with
 * the rotation to show them upright (hen: the picture isn't changed). Not warm, a press sends no video.
 */
class AndroidTalkVideoCapture(
    context: Context,
    private val wantH265: () -> Boolean,
    private val want60: () -> Boolean = { false },
) : TalkVideoCapture {
    /** This warm-up's frame rate and bit rate: 60 fps only when chosen and the camera can. */
    private var fps = FPS
    private var bitrate = BITRATE
    private val app = context.applicationContext

    /**
     * Cameras another app holds now (2026-11-25, hen: don't take what another app uses): the system says which are
     * unavailable; ours counts only while this capture has none open.
     */
    private val takenIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The front camera is another app's now: video can't start (voice only), and the slide up says so. */
    val cameraBusy = kotlinx.coroutines.flow.MutableStateFlow(false)

    init {
        runCatching {
            app.getSystemService(CameraManager::class.java).registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    takenIds -= cameraId
                }

                override fun onCameraUnavailable(cameraId: String) {
                    takenIds += cameraId
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    /** [id] is held by someone else (unavailable while this capture holds no camera and isn't opening one). */
    private fun heldElsewhere(id: String) = id in takenIds && camera == null && !opening

    @Volatile private var opening = false
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var preview: Surface? = null
    /**
     * LiveKit's capture surface while a burst also goes live through the talk room (2026-11-24, hen: push-to-video
     * through LiveKit): it takes Follow face's small copy's place in the session, so the camera keeps three outputs.
     */
    private var liveTarget: Surface? = null
    /** A small copy of the camera's frames for Follow face (null: none, or the camera can't add it). */
    private var faceReader: android.media.ImageReader? = null
    private var faceReaderOk = true
    @Volatile private var faceSink: ((TalkVideo.Face) -> Unit)? = null
    private val faceHandler by lazy { Handler(HandlerThread("talk-faces").also { it.start() }.looper) }
    @Volatile private var lastPtsMs = 0L
    private val faceFinder by lazy {
        com.chencohen.ptt.calls.FaceFinder("TalkFaces", everyMs = 500) { box ->
            if (box != null) faceSink?.invoke(TalkVideo.Face(lastPtsMs, box.centerX(), box.centerY(), box.height()))
        }
    }
    private var codec = TalkVideo.VP8
    private var config: ByteArray? = null
    private var sink: ((TalkVideo.Frame) -> Unit)? = null
    private var startUs = -1L
    private var sensor = 270
    @Volatile private var warmWanted = false

    /** Warm and ready: a press now sends video from its first moment. */
    val ready: Boolean get() = synchronized(lock) { encoder != null }

    /** The Talk screen's preview: the camera draws into it too while warm (null: none). */
    fun setPreview(surface: Surface?) {
        val live = synchronized(lock) {
            if (preview === surface) return
            preview = surface
            liveTarget != null
        }
        // While the talk room reads the camera the session has no preview output (its track is the preview): nothing to
        // set up again, so the picture doesn't stop for a moment (2026-11-25: a new session at the slide up).
        if (live) return
        handler?.post { configure() }
    }

    /**
     * The talk room's camera input ([surface], LiveKit's) for this burst, or none: the session is set up again with it.
     * [done] runs on the camera's thread once the session no longer draws into the old one.
     */
    fun setLiveTarget(surface: Surface?, done: () -> Unit = {}) {
        synchronized(lock) {
            if (liveTarget === surface) return done()
            liveTarget = surface
        }
        val h = handler ?: return done()
        h.post {
            configure()
            h.postDelayed(done, 300)
        }
    }

    /**
     * [surface] goes away: the camera stops drawing into it if it is the preview (a newer one is left as it is), then
     * [released] runs, on the camera's thread, once it is safe to release.
     */
    fun clearPreview(surface: Surface, released: () -> Unit) {
        val current = synchronized(lock) {
            (preview === surface).also { if (it) preview = null } && liveTarget == null
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

    /**
     * A call's camera is about to open (2026-11-24, hen: no own video in calls): the push-to-video camera lets go now,
     * and this returns once it has (at most [waitMs]), so the two never fight over the front camera. (No warm comes
     * during a call: Messaging keeps it cool while there is one.)
     */
    fun handOver(waitMs: Long = 800) {
        val h = synchronized(lock) {
            warmWanted = false
            handler
        } ?: return
        val done = java.util.concurrent.CountDownLatch(1)
        if (!h.post { close(); done.countDown() }) return
        if (!done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) Log.w(TAG, "push-to-video camera still closing at the call's start")
        else Log.i(TAG, "push-to-video camera handed to the call")
    }


    override fun start(onFrame: (TalkVideo.Frame) -> Unit): Boolean {
        // Let go while idle (a minute untouched): opened again now, and waited for up to half a second, so the burst
        // starts as video rather than voice only (2026-11-24, hen). The microphone starts after this, so the sound
        // waits too; nothing is lost.
        if (synchronized(lock) { encoder == null }) {
            warm()
            val until = System.currentTimeMillis() + WAIT_FOR_CAMERA_MS
            while (synchronized(lock) { encoder == null } && System.currentTimeMillis() < until) Thread.sleep(20)
        }
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

    override fun faces(onFace: ((TalkVideo.Face) -> Unit)?) {
        faceSink = onFace
    }

    override fun stop() {
        synchronized(lock) { sink = null }
        faceSink = null
    }

    /** A frame for Follow face: twice a second while sending, a 320×240 copy to the face finder. */
    private fun faceImage(reader: android.media.ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (faceSink == null || !faceFinder.due()) return
            val w = image.width
            val h = image.height
            val bytes = runCatching { nv21(image, w, h) }.onFailure { Log.w(TAG, "face image", it) }.getOrNull()
            if (bytes != null) faceFinder.offerNv21(bytes, w, h, rotation()) else faceFinder.offerNothing()
        } finally {
            image.close()
        }
    }

    private fun nv21(image: android.media.Image, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        val yb = y.buffer
        for (row in 0 until h) {
            yb.position(row * y.rowStride)
            yb.get(out, row * w, w)
        }
        val u = image.planes[1]
        val v = image.planes[2]
        var at = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[at++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
                out[at++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (camera != null) return
        val manager = app.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: manager.cameraIdList.firstOrNull() ?: return
        sensor = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        if (heldElsewhere(id)) {
            Log.i(TAG, "the camera is another app's now: not taken (voice only)")
            cameraBusy.value = true
            return
        }
        cameraBusy.value = false
        opening = true
        try {
            val sixty = want60() && supports60(app)
            fps = if (sixty) FPS_60 else FPS
            bitrate = if (sixty) BITRATE_60 else BITRATE
            if (encoder == null) startEncoder()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false
                    if (!warmWanted) {
                        device.close()
                        return
                    }
                    camera = device
                    configure()
                }

                // Taken by another app, or an error: let it go (a press then sends the voice only).
                override fun onDisconnected(device: CameraDevice) {
                    opening = false
                    // Taken by another app while ours was open: let go, and say so.
                    cameraBusy.value = true
                    lost(device)
                }
                override fun onError(device: CameraDevice, error: Int) {
                    opening = false
                    Log.w(TAG, "camera error $error")
                    lost(device)
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
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
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
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
        lastPtsMs = (info.presentationTimeUs - startUs) / 1000
        out(TalkVideo.Frame(lastPtsMs, key, rotation(), data, codec))
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

    override val uprightShape: Int
        get() = if (previewRotation() % 180 != 0) HEIGHT * 1000 / WIDTH else WIDTH * 1000 / HEIGHT

    /** The open camera's sensor orientation (clockwise degrees from the phone's natural orientation). */
    fun sensorOrientation(): Int = sensor

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
        if (faceReaderOk && faceReader == null) {
            faceReader = android.media.ImageReader.newInstance(FACE_WIDTH, FACE_HEIGHT, android.graphics.ImageFormat.YUV_420_888, 2).apply {
                // On a thread of its own: the encoder's thread never waits for it.
                setOnImageAvailableListener({ faceImage(it) }, faceHandler)
            }
        }
        val live = liveTarget
        val faces = if (live != null) null else faceReader?.surface?.takeIf { faceReaderOk }
        // Feeding the talk room: two outputs only, the clip's encoder and the room's (its track is the preview then,
        // LiveTalkVideo.local). A third and a fourth output stalled the S25's front camera (2026-11-25, buffer errors,
        // the own box black).
        val targets = if (live != null) listOf(enc, live) else listOfNotNull(enc, preview, faces)
        try {
            val executor = Executor { r -> handler?.post(r) }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map { target ->
                        // The preview mirrored like a mirror; what is sent as the camera sees it.
                        OutputConfiguration(target).apply {
                            if (android.os.Build.VERSION.SDK_INT >= 33) mirrorMode = if (target === preview) OutputConfiguration.MIRROR_MODE_AUTO else OutputConfiguration.MIRROR_MODE_NONE
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
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                                }
                                s.setRepeatingRequest(request.build(), null, handler)
                            } catch (e: Exception) {
                                Log.w(TAG, "camera closed while starting", e)
                            }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.w(TAG, "camera session failed")
                            // The camera can't add the small copy for Follow face: on without it.
                            if (asked == sessions && camera === device && faces != null) {
                                Log.w(TAG, "no face copy: Follow face looks on the other phone")
                                faceReaderOk = false
                                configure()
                                return
                            }
                            // LiveKit's surface can't be added: the burst goes by its clips only.
                            if (asked == sessions && camera === device && live != null) {
                                Log.w(TAG, "the camera can't feed the talk room too: clips only")
                                synchronized(lock) { liveTarget = null }
                                configure()
                                return
                            }
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
        runCatching { faceReader?.close() }
        faceReader = null
        config = null
        // A press that came while this was closing wants the camera again: the thread stays and the camera opens anew
        // (2026-11-25, hen: "after several tries ... the camera is not initiated": the thread was let go under a new
        // opening, which then never finished).
        val t = synchronized(lock) { if (warmWanted) null else thread.also { thread = null; handler = null } }
        if (t == null) {
            if (warmWanted) handler?.post { if (camera == null && warmWanted) open() }
            return
        }
        t.quitSafely()
    }

    private fun startsWith(a: ByteArray, prefix: ByteArray) = a.size >= prefix.size && prefix.indices.all { a[it] == prefix[it] }

    companion object {
        const val TAG = "TalkVideo"
        const val WIDTH = 640
        const val HEIGHT = 480
        private const val FACE_WIDTH = 320
        private const val FACE_HEIGHT = 240
        const val FPS = 30
        const val BITRATE = 700_000
        const val FPS_60 = 60
        const val BITRATE_60 = 1_000_000

        /** Whether the front camera runs at a steady 60 fps at 640×480 (Settings › Calls › 60 fps video). */
        fun supports60(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
                ?: return false
            val c = manager.getCameraCharacteristics(id)
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val frame = map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, android.util.Size(WIDTH, HEIGHT))
            ranges.any { it.lower == 60 && it.upper == 60 } && frame in 1..16_700_000L
        }.getOrDefault(false)

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
    /** Each step of a burst's video, for the wake timeline (the app's WakeTimeline). */
    @Volatile var onStep: ((String) -> Unit)? = null

    private const val TAG = "TalkVideoOut"
    private val lock = Any()
    private var surface: Surface? = null
    private var clip: String? = null
    private var decoder: MediaCodec? = null
    private var decoderCodec = -1
    /** The last decoder's codec and picture size: a warm one gives no new size, and the next starts as this one. */
    private var lastCodec = -1
    private var lastWidth = 0
    private var lastHeight = 0

    /**
     * Where the decoder draws with no view on screen (2026-11-23, hen: "the delay is big"): it stays set up between
     * bursts and before the view comes, drawing here (dropped), so a burst's first picture needs no new decoder.
     */
    private var parking: android.media.ImageReader? = null

    private fun parkingSurface(): Surface? {
        parking?.let { return it.surface }
        val h = handler ?: return null
        return runCatching {
            android.media.ImageReader.newInstance(AndroidTalkVideoCapture.WIDTH, AndroidTalkVideoCapture.HEIGHT, android.graphics.ImageFormat.PRIVATE, 3).also { r ->
                r.setOnImageAvailableListener({ runCatching { it.acquireLatestImage()?.close() } }, h)
                parking = r
            }.surface
        }.onFailure { Log.w(TAG, "no parking surface", it) }.getOrNull()
    }

    /** Where the decoder draws now: the view, or the parking surface. */
    private fun target(): Surface? = surface ?: parkingSurface()

    /** Unused for a minute: the decoder goes (the app in the background, or no bursts). */
    private val idle = Runnable {
        synchronized(lock) {
            if (clip == null && decoder != null) {
                Log.i(TAG, "decoder idle: released")
                releaseDecoder()
            }
        }
    }
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

    /** The sender's face positions in the clip shown (Follow face), in time order. */
    private val faces = ArrayList<TalkVideo.Face>()

    override fun face(clip: String, face: TalkVideo.Face) {
        synchronized(lock) {
            if (this.clip != clip) return
            faces += face
            if (faces.size > 1_000) faces.subList(0, 500).clear()
        }
    }

    /** The clip shown has the sender's face positions (else Follow face looks on this phone). */
    fun hasFaces(): Boolean = synchronized(lock) { faces.isNotEmpty() }

    /**
     * Where the sender's face is in the picture showing [aheadMs] from now, by the clip's own clock (2026-11-23, hen:
     * "the face focus happens with a small delay"): between the two positions around it, in proportion; before the
     * first, the first (so it starts framed); null when there is none, or the last is 1.5 s old.
     */
    fun faceAt(aheadMs: Long = 0): TalkVideo.Face? = synchronized(lock) {
        if (clip == null || faces.isEmpty()) return null
        val at = playedMs + SystemClock.elapsedRealtime() - playedAt + aheadMs
        val i = faces.indexOfLast { it.ptsMs <= at }
        if (i < 0) return faces.first()
        val a = faces[i]
        val b = faces.getOrNull(i + 1)
        if (b == null) return a.takeIf { at - it.ptsMs < 1_500 }
        val t = ((at - a.ptsMs).toFloat() / (b.ptsMs - a.ptsMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        TalkVideo.Face(at, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.h + (b.h - a.h) * t)
    }

    /** Where the sender's face is in the picture showing now; null: none for 1.5 s. */
    fun faceNow(): TalkVideo.Face? = synchronized(lock) {
        if (clip == null) return null
        val at = playedMs + SystemClock.elapsedRealtime() - playedAt
        faces.lastOrNull { it.ptsMs <= at + 100 }?.takeIf { at - it.ptsMs < 1_500 }
    }

    private val _shown = MutableStateFlow<Shown?>(null)

    /** The clip being shown: its picture's size and rotation, for the view's shape. */
    val shown: StateFlow<Shown?> = _shown.asStateFlow()

    data class Shown(val clip: String, val width: Int, val height: Int, val rotation: Int)

    fun attach(target: Surface) {
        synchronized(lock) {
            if (surface === target) return
            surface = target
            val dec = decoder
            if (dec != null && runCatching { dec.setOutputSurface(target) }.isSuccess) {
                Log.i(TAG, "view attached to the running decoder (clip ${clip != null})")
                return
            }
            releaseDecoder()
            Log.i(TAG, "view attached (clip ${clip != null}, ${sinceKey.size} frames since the key frame)")
            restart()
        }
    }

    /** With a view and a key frame of the clip: a decoder from that key frame on. */
    private fun restart() {
        val target = target() ?: return
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
            // The decoder stays, drawing to the parking surface, unless it can't move there.
            val dec = decoder ?: return
            val park = parkingSurface()
            if (park == null || runCatching { dec.setOutputSurface(park) }.isFailure) releaseDecoder()
        }
    }

    override fun begin(clip: String) {
        synchronized(lock) {
            this.clip = clip
            playedMs = 0
            playedAt = SystemClock.elapsedRealtime()
            waiting.clear()
            sinceKey.clear()
            faces.clear()
            if (thread == null) thread = HandlerThread("talk-video-out").also { it.start(); handler = Handler(it.looper) }
            handler?.removeCallbacks(idle)
            val dec = decoder
            if (dec != null) {
                // Warm from the last burst: emptied, ready for this one's key frame.
                inputs.clear()
                if (runCatching { dec.flush(); dec.start() }.isFailure) releaseDecoder() else onStep?.invoke("decoder kept from the last burst")
            } else if (lastCodec >= 0) {
                // Set up now, before the first frame comes, for the codec the last burst had.
                target()?.let { startDecoder(lastCodec, it) }
            }
            Log.i(TAG, "video burst $clip begins (view ${surface != null})")
            onStep?.invoke("video begins (view ${surface != null})")
        }
    }

    override fun frame(clip: String, frame: TalkVideo.Frame) {
        synchronized(lock) {
            if (this.clip != clip) return
            val same = _shown.value?.takeIf { it.clip == clip }
            _shown.value = Shown(clip, same?.width ?: lastWidth, same?.height ?: lastHeight, frame.rotation)
            if (frame.key) sinceKey.clear()
            if (sinceKey.isNotEmpty() || frame.key) sinceKey += frame
            if (sinceKey.size > MAX_SINCE_KEY) sinceKey.subList(0, sinceKey.size - MAX_SINCE_KEY).clear()
            val target = target() ?: return
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
            faces.clear()
            // Kept for the next burst, a minute at most.
            handler?.removeCallbacks(idle)
            handler?.postDelayed(idle, IDLE_MS)
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
                    lastWidth = w
                    lastHeight = h
                    onStep?.invoke("first video frame decoded (${w}x$h)")
                }
            }, handler)
            dec.configure(MediaFormat.createVideoFormat(mime, AndroidTalkVideoCapture.WIDTH, AndroidTalkVideoCapture.HEIGHT), target, null, 0)
            dec.start()
            Log.i(TAG, "decoding ${dec.name}")
            onStep?.invoke("decoder started (${dec.name})")
            decoder = dec
            decoderCodec = codec
            lastCodec = codec
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
    private const val IDLE_MS = 60_000L
    private const val MAX_WAIT_MS = 1_000L
}

/** How long a press waits for a camera let go while idle to open again. */
private const val WAIT_FOR_CAMERA_MS = 1_000L
