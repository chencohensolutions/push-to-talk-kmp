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
        val provider = BaseKeyProvider(enableSharedKey = true, discardFrameWhenCryptorNotReady = true)
        provider.setSharedKey(Base64.encodeToString(key, Base64.NO_WRAP), 0)
        val room = LiveKit.create(
            appContext,
            RoomOptions(
                e2eeOptions = E2EEOptions(keyProvider = provider),
                // The camera's own 4:3 picture, uncropped (2026-11-22, hen): the phone that shows it fits or fills.
                videoTrackCaptureDefaults = io.livekit.android.room.track.LocalVideoTrackOptions(
                    captureParams = io.livekit.android.room.track.VideoPreset43.H720.capture,
                ),
            ),
            LiveKitOverrides(okHttpClient = okHttp(), eglBase = egl),
        )
        events = scope.launch { room.events.events.collect { onEvent(room, it) } }
        try {
            room.connect(url, token)
            _room.value = room
            room.localParticipant.setMicrophoneEnabled(true)
            if (video) room.localParticipant.setCameraEnabled(true)
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
        _changes.update { it + 1 }
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

    private companion object {
        const val TAG = "LiveKitCall"
    }
}
