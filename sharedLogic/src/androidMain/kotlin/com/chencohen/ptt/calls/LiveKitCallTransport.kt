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

    override suspend fun join(url: String, token: String, key: ByteArray, video: Boolean): Boolean = withContext(Dispatchers.IO) {
        leave()
        val provider = BaseKeyProvider(enableSharedKey = true, discardFrameWhenCryptorNotReady = true)
        provider.setSharedKey(Base64.encodeToString(key, Base64.NO_WRAP), 0)
        val room = LiveKit.create(
            appContext,
            RoomOptions(e2eeOptions = E2EEOptions(keyProvider = provider)),
            LiveKitOverrides(okHttpClient = okHttp()),
        )
        events = scope.launch { room.events.events.collect { onEvent(room, it) } }
        try {
            room.connect(url, token)
            _room.value = room
            room.localParticipant.setMicrophoneEnabled(true)
            if (video) room.localParticipant.setCameraEnabled(true)
            front = true
            others(room)
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

    private fun onEvent(room: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected -> others(room)
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
        }
    }

    private fun others(room: Room) {
        listener?.others(room.remoteParticipants.values.mapNotNull { it.identity?.value })
    }

    private companion object {
        const val TAG = "LiveKitCall"
    }
}
