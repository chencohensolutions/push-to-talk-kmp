package com.chencohen.ptt.calls

import android.content.Context
import android.util.Base64
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * A call's room on Android (docs/design.md, "Phase 6"): LiveKit with the same mTLS OkHttp client, every sender's
 * audio and video encrypted with the call's one key from its invite (LiveKit's shared key; frames are dropped, never
 * sent in the clear, while a cryptor isn't ready). Unlike the talk room it uses LiveKit's own audio handling: the
 * call takes the audio focus and the phone's call audio mode. Video goes out in simulcast layers (LiveKit's
 * defaults), so in a group each viewer gets the layer their network takes.
 */
class LiveKitCallTransport(
    context: Context,
    /** "vp8" or "h265" (Settings › Calls › Video); with H.265, VP8 goes too for phones that can't decode it. */
    private val videoCodec: () -> String = { "vp8" },
    /** Settings › Calls › 60 fps video (off by default; 2026-11-22, hen): used only where the camera can. */
    private val want60: () -> Boolean = { false },
    private val okHttp: () -> OkHttpClient,
) : CallTransport {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override var listener: CallTransportListener? = null

    private val _room = MutableStateFlow<Room?>(null)

    /** The room while in a call, for the call screen's video. */
    val room: StateFlow<Room?> = _room.asStateFlow()

    private val _changes = MutableStateFlow(0)

    /** Goes up whenever someone's tracks change, so the call screen draws them again. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private var events: Job? = null
    private var front = true

    /** The rooms' EGL context, shared with the recorder so it can draw their frames. */
    private val egl: livekit.org.webrtc.EglBase by lazy { livekit.org.webrtc.EglBase.create() }
    private var recorder: CallRecorder? = null
    private var recordingName: String? = null

    override suspend fun join(url: String, token: String, key: ByteArray, video: Boolean): Boolean = withContext(Dispatchers.IO) {
        leave()
        // The key provider is native WebRTC code: load the library first (LiveKit.create would, but only after it).
        LiveKit.init(appContext)
        front = true // each call starts on the front camera, so the first Flip turns to the back one
        val provider = BaseKeyProvider(enableSharedKey = true, discardFrameWhenCryptorNotReady = true)
        provider.setSharedKey(Base64.encodeToString(key, Base64.NO_WRAP), 0)
        val sixty = video && want60() && supports60(appContext)
        Log.i(TAG, "call video: ${videoCodec()}, ${if (sixty) 60 else 30} fps")
        val encoding = if (sixty) io.livekit.android.room.track.VideoEncoding(SIXTY_BITRATE, 60) else null
        val room = LiveKit.create(
            appContext,
            RoomOptions(
                e2eeOptions = E2EEOptions(keyProvider = provider),
                // The camera's own 4:3 picture, uncropped (2026-11-22, hen): the phone that shows it fits or fills.
                videoTrackCaptureDefaults = io.livekit.android.room.track.LocalVideoTrackOptions(
                    captureParams = if (sixty) {
                        io.livekit.android.room.track.VideoCaptureParameter(960, 720, 60)
                    } else {
                        io.livekit.android.room.track.VideoPreset43.H720.capture
                    },
                ),
                videoTrackPublishDefaults = videoCodec().let { codec ->
                    if (codec == "h265") {
                        io.livekit.android.room.participant.VideoTrackPublishDefaults(
                            videoEncoding = encoding,
                            videoCodec = "h265",
                            backupCodec = io.livekit.android.room.participant.BackupVideoCodec(codec = "vp8", encoding = encoding),
                        )
                    } else {
                        io.livekit.android.room.participant.VideoTrackPublishDefaults(videoEncoding = encoding)
                    }
                },
            ),
            LiveKitOverrides(okHttpClient = okHttp(), eglBase = egl),
        )
        events = scope.launch { room.events.events.collect { onEvent(room, it) } }
        try {
            room.connect(url, token)
            _room.value = room
            room.localParticipant.setMicrophoneEnabled(true)
            if (video) {
                room.localParticipant.setCameraEnabled(true)
                watchCamera(room)
            }
            front = true
            others(room)
            metadata(room)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the call's room didn't open", e)
            events?.cancel()
            runCatching { room.disconnect() }
            runCatching { room.release() }
            _room.value = null
            false
        }
    }

    override fun leave() {
        stopRecording()
        cameraWatch?.cancel()
        cameraWatch = null
        watched = null
        events?.cancel()
        events = null
        _room.value?.let { r ->
            runCatching { r.disconnect() }
            runCatching { r.release() }
        }
        _room.value = null
    }

    override suspend fun setMicrophone(on: Boolean) {
        _room.value?.localParticipant?.setMicrophoneEnabled(on)
    }

    override suspend fun setCamera(on: Boolean) {
        _room.value?.localParticipant?.setCameraEnabled(on)
        if (on) _room.value?.let { watchCamera(it) }
        _changes.update { it + 1 }
    }

    /**
     * The camera's frames go out as captured with their rotation alongside (the picture isn't turned; hen, 19:42): each
     * change of rotation or size is told (cameraFormat), for this phone's preview and the kept call's log.
     */
    private var watched: LocalVideoTrack? = null
    private val cameraSink = object : livekit.org.webrtc.VideoSink {
        private var last = Triple(-1, -1, -1)
        override fun onFrame(frame: livekit.org.webrtc.VideoFrame) {
            lastCameraFrame = android.os.SystemClock.elapsedRealtime()
            val now = Triple(frame.rotation, frame.buffer.width, frame.buffer.height)
            if (now == last) return
            last = now
            Log.i(TAG, "camera sends ${now.second}x${now.third}, rotation ${now.first}")
            listener?.cameraFormat(now.first, now.second, now.third)
        }
    }

    @Volatile private var lastCameraFrame = 0L
    private var cameraWatch: kotlinx.coroutines.Job? = null

    /**
     * The camera's frames stopping while it is on (2026-11-22, hen: after a rotation the video stopped until the camera
     * was turned off and on): after [CAMERA_STALL_MS] without a frame, the camera is turned off and on here, once per
     * stall.
     */
    private fun watchCameraFrames(room: Room) {
        cameraWatch?.cancel()
        lastCameraFrame = android.os.SystemClock.elapsedRealtime()
        cameraWatch = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                if (_room.value !== room) return@launch
                val on = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.let { !it.muted } == true
                val quiet = android.os.SystemClock.elapsedRealtime() - lastCameraFrame
                if (!on || quiet < CAMERA_STALL_MS) continue
                Log.w(TAG, "no camera frame for $quiet ms: turning the camera off and on")
                runCatching {
                    room.localParticipant.setCameraEnabled(false)
                    room.localParticipant.setCameraEnabled(true)
                    watchCamera(room)
                }.onFailure { Log.w(TAG, "camera restart", it) }
                lastCameraFrame = android.os.SystemClock.elapsedRealtime()
                _changes.update { it + 1 }
            }
        }
    }

    private fun watchCamera(room: Room) {
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        if (cameraWatch?.isActive != true) watchCameraFrames(room)
        if (watched === track) return
        watched?.let { runCatching { it.removeRenderer(cameraSink) } }
        watched = track
        track.addRenderer(cameraSink)
    }

    override fun switchCamera() {
        val track = _room.value?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        front = !front
        track.switchCamera(position = if (front) CameraPosition.FRONT else CameraPosition.BACK)
    }

    /** Saves the call to the app's private `calls` folder (Share is the only way out of it). */
    override fun startRecording(): Boolean {
        val room = _room.value ?: return false
        if (recorder != null) return true
        val dir = java.io.File(appContext.filesDir, "calls").apply { mkdirs() }
        val name = "call-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + ".mp4"
        val video = room.localParticipant.getTrackPublication(Track.Source.CAMERA) != null ||
            room.remoteParticipants.values.any { it.getTrackPublication(Track.Source.CAMERA) != null }
        return try {
            recorder = CallRecorder(java.io.File(dir, name), egl.eglBaseContext, video)
            recordingName = name
            syncRecording(room)
            true
        } catch (e: Exception) {
            Log.w(TAG, "saving didn't start", e)
            recorder = null
            false
        }
    }

    override fun stopRecording(): String? {
        val r = recorder ?: return null
        recorder = null
        _room.value?.let { room -> detachAll(room, r) }
        val name = recordingName
        recordingName = null
        return name.takeIf { runCatching { r.stop() }.getOrDefault(false) }
    }

    /** The recorder follows who is in the room: each camera and voice is added once, and taken out when gone. */
    private fun syncRecording(room: Room) {
        val r = recorder ?: return
        val videos = mutableMapOf<String, io.livekit.android.room.track.VideoTrack>()
        val audios = mutableMapOf<String, Track>()
        (room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? io.livekit.android.room.track.VideoTrack)?.let { videos[CallRecorder.OWN] = it }
        room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track?.let { audios[CallRecorder.OWN] = it }
        room.remoteParticipants.values.forEach { p ->
            val who = p.identity?.value ?: return@forEach
            (p.getTrackPublication(Track.Source.CAMERA)?.track as? io.livekit.android.room.track.VideoTrack)?.let { videos[who] = it }
            p.getTrackPublication(Track.Source.MICROPHONE)?.track?.let { audios[who] = it }
        }
        videos.forEach { (key, track) -> r.addVideo(key, track) }
        (r.videoKeys() - videos.keys).forEach { r.removeVideo(it, null) }
        audios.forEach { (key, track) ->
            if (!r.hasVoice(key)) {
                val sink = r.voice(key)
                when (track) {
                    is io.livekit.android.room.track.LocalAudioTrack -> track.addSink(sink)
                    is io.livekit.android.room.track.RemoteAudioTrack -> track.addSink(sink)
                }
            }
        }
        (r.voiceKeys() - audios.keys).forEach { r.removeVoice(it) }
    }

    private fun detachAll(room: Room, r: CallRecorder) {
        val local = room.localParticipant
        (local.getTrackPublication(Track.Source.CAMERA)?.track as? io.livekit.android.room.track.VideoTrack)?.let { r.removeVideo(CallRecorder.OWN, it) }
        (local.getTrackPublication(Track.Source.MICROPHONE)?.track as? io.livekit.android.room.track.LocalAudioTrack)?.let { t ->
            if (r.hasVoice(CallRecorder.OWN)) t.removeSink(r.voice(CallRecorder.OWN))
        }
        room.remoteParticipants.values.forEach { p ->
            val who = p.identity?.value ?: return@forEach
            (p.getTrackPublication(Track.Source.CAMERA)?.track as? io.livekit.android.room.track.VideoTrack)?.let { r.removeVideo(who, it) }
            (p.getTrackPublication(Track.Source.MICROPHONE)?.track as? io.livekit.android.room.track.RemoteAudioTrack)?.let { t ->
                if (r.hasVoice(who)) t.removeSink(r.voice(who))
            }
        }
    }

    private fun onEvent(room: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected -> others(room)
            is RoomEvent.RoomMetadataChanged -> metadata(room)
            is RoomEvent.Disconnected -> {
                if (_room.value === room) listener?.disconnected()
            }
            else -> {}
        }
        if (event is RoomEvent.TrackSubscribed || event is RoomEvent.TrackUnsubscribed || event is RoomEvent.TrackMuted ||
            event is RoomEvent.TrackUnmuted || event is RoomEvent.TrackPublished || event is RoomEvent.TrackUnpublished ||
            event is RoomEvent.LocalTrackSubscribed || event is RoomEvent.ParticipantConnected || event is RoomEvent.ParticipantDisconnected
        ) {
            _changes.update { it + 1 }
            syncRecording(room)
        }
    }

    private fun others(room: Room) {
        listener?.others(room.remoteParticipants.values.mapNotNull { it.identity?.value })
    }

    /** {"recording":true,"by":"user.device","startedAt":…} while the server keeps the call; {"recording":false} after. */
    private fun metadata(room: Room) {
        listener?.serverRecording(RoomMetadata.recorderOf(room.metadata))
    }

    companion object {
        /** A hardware H.265 encoder: only then is H.265 offered (software would heat the phone and drop frames). */
        fun hasHevcEncoder(): Boolean = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
            }
        }.getOrDefault(false)

        const val TAG = "LiveKitCall"

        private const val CAMERA_STALL_MS = 2_500L

        /** The top layer's bit rate at 60 fps (30 fps uses LiveKit's 4:3 720p preset). */
        private const val SIXTY_BITRATE = 2_500_000

        /** Whether the front camera runs at a steady 60 fps at the call's 960×720. */
        fun supports60(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val front = android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == front }
                ?: return false
            val c = manager.getCameraCharacteristics(id)
            val ranges = c.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val map = c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val frame = map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, android.util.Size(960, 720))
            ranges.any { it.upper == 60 } && frame in 1..16_700_000L
        }.getOrDefault(false)
    }
}
