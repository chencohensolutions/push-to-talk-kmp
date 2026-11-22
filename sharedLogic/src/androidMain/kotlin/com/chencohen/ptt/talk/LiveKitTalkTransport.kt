package com.chencohen.ptt.talk

import android.content.Context
import android.util.Base64
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import livekit.org.webrtc.AudioTrackSink
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * The live path on Android (docs/design.md, "Phase 5b"): LiveKit's client, its signalling over the server's `/rtc`
 * through the same mTLS OkHttp client as everything else, its audio encrypted per sender with the talk keys.
 *
 * - The microphone is published for a burst only and unpublished (and stopped) after it, so the system's microphone
 *   indicator shows only while talking (the plan's R3), and a listener's "a track of theirs is subscribed and opens"
 *   means "heard live now".
 * - Frames are dropped, never sent in the clear, while a cryptor isn't ready (`discardFrameWhenCryptorNotReady`).
 * - No audio handler: the room takes no audio focus and doesn't change the phone's audio mode while it only listens
 *   (the app's player keeps the focus for clips). The microphone is the voice-call one, with the system's echo
 *   canceller.
 * - [capture] encodes the published microphone's own samples to Opus for the clip, so the microphone isn't opened
 *   twice.
 */
class LiveKitTalkTransport(
    context: Context,
    /** The mTLS client (blocks: Keystore), called off the main thread. */
    private val okHttp: () -> OkHttpClient,
) : TalkTransport {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override var listener: TalkTransportListener? = null

    private var room: Room? = null
    private var keys: BaseKeyProvider? = null
    private var events: Job? = null
    private var mic: LocalAudioTrack? = null

    /** Keys set before the room was there: set again on each join. */
    private val pendingKeys = ConcurrentHashMap<String, Pair<String, Int>>()

    /** Remote "user.device" whose current audio track decrypts. */
    private val decrypting = ConcurrentHashMap.newKeySet<String>()

    private val sampleCapture = SampleCapture()
    override val capture: TalkCapture get() = sampleCapture

    override suspend fun join(url: String, token: String, identity: String, ownKey: ByteArray, ownIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            leave()
            // The key provider is native WebRTC code: load the library first (LiveKit.create would, but only after it).
            LiveKit.init(appContext)
            val provider = BaseKeyProvider(
                enableSharedKey = false,
                keyRingSize = LiveTalk.KEY_RING,
                discardFrameWhenCryptorNotReady = true,
            )
            provider.setKey(encode(ownKey), identity, ownIndex)
            pendingKeys.forEach { (who, k) -> provider.setKey(k.first, who, k.second) }
            val room = LiveKit.create(
                appContext,
                RoomOptions(
                    adaptiveStream = false,
                    dynacast = false,
                    e2eeOptions = E2EEOptions(keyProvider = provider),
                ),
                LiveKitOverrides(
                    okHttpClient = okHttp(),
                    audioOptions = AudioOptions(
                        audioOutputType = AudioType.MediaAudioType(),
                        audioHandler = NoAudioHandler(),
                    ),
                ),
            )
            this@LiveKitTalkTransport.room = room
            keys = provider
            events = scope.launch { room.events.events.collect { onEvent(it) } }
            try {
                room.connect(url, token)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the room didn't open", e)
                leave()
                false
            }
        }

    override fun leave() {
        sampleCapture.detach()
        mic = null
        events?.cancel()
        events = null
        room?.let { r ->
            runCatching { r.disconnect() }
            runCatching { r.release() }
        }
        room = null
        keys = null
        decrypting.clear()
    }

    override fun setKey(identity: String, key: ByteArray, index: Int) {
        val k = encode(key)
        pendingKeys[identity] = k to index
        keys?.setKey(k, identity, index)
    }

    override suspend fun setTalking(on: Boolean) {
        val room = room ?: return
        val local = room.localParticipant
        if (on) {
            if (mic != null) return
            val track = local.createAudioTrack("talk")
            mic = track
            sampleCapture.attach(track)
            if (!local.publishAudioTrack(track)) {
                Log.w(TAG, "the microphone wasn't published")
                sampleCapture.detach()
                mic = null
                runCatching { track.stop() }
            }
        } else {
            val track = mic ?: return
            mic = null
            sampleCapture.detach()
            local.unpublishTrack(track, true)
        }
    }

    override fun hears(identity: String): Boolean = identity in decrypting

    override fun silence(identity: String) {
        val room = room ?: return
        room.remoteParticipants.values.filter { it.identity?.value == identity }.forEach { p ->
            p.audioTrackPublications.forEach { (_, track) -> (track as? RemoteAudioTrack)?.setVolume(0.0) }
        }
    }

    private fun onEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected -> event.participant.identity?.value?.let { listener?.joined(it) }
            is RoomEvent.TrackE2EEStateEvent -> {
                val who = event.participant.identity?.value ?: return
                if (event.participant == room?.localParticipant || event.track?.kind != Track.Kind.AUDIO) return
                if (event.state == E2EEState.OK) {
                    if (decrypting.add(who)) listener?.liveAudio(who)
                } else if (event.state != E2EEState.NEW && event.state != E2EEState.KEY_RATCHETED) {
                    // Its key is missing or wrong: what it says is heard by the clip.
                    decrypting.remove(who)
                }
            }
            is RoomEvent.TrackUnsubscribed -> event.participant.identity?.value?.let { decrypting.remove(it) }
            is RoomEvent.TrackUnpublished -> event.participant.identity?.value?.let { decrypting.remove(it) }
            is RoomEvent.ParticipantDisconnected -> event.participant.identity?.value?.let { decrypting.remove(it) }
            is RoomEvent.Disconnected -> {
                decrypting.clear()
                listener?.left()
            }
            else -> {}
        }
    }

    /**
     * The published microphone's samples, encoded to Opus for the clip: 16-bit PCM from the track's sink, mixed to
     * mono and brought to 48 kHz when the microphone gives something else.
     */
    private class SampleCapture : TalkCapture {
        private val lock = Any()
        private var onPacket: ((ByteArray) -> Unit)? = null
        private var encoder: OpusEncoder? = null
        private var track: LocalAudioTrack? = null
        private val frame = ShortArray(TalkAudioFormat.SAMPLES_PER_FRAME)
        private var filled = 0
        private var resampleAt = 0.0

        private val sink = AudioTrackSink { data, bits, rate, channels, frames, _ -> take(data, bits, rate, channels, frames) }

        override fun start(onPacket: (ByteArray) -> Unit): Boolean = synchronized(lock) {
            if (this.onPacket != null) return false
            encoder = try {
                OpusEncoder()
            } catch (e: Exception) {
                Log.w(TAG, "no Opus encoder", e)
                return false
            }
            this.onPacket = onPacket
            filled = 0
            resampleAt = 0.0
            true
        }

        override fun stop() {
            synchronized(lock) {
                val enc = encoder ?: return
                val out = onPacket
                encoder = null
                onPacket = null
                if (out != null) {
                    if (filled > 0) enc.encode(frame, filled).forEach(out)
                    enc.finish().forEach(out)
                }
                filled = 0
                enc.release()
            }
        }

        fun attach(track: LocalAudioTrack) {
            synchronized(lock) { this.track = track }
            track.addSink(sink)
        }

        fun detach() {
            val t = synchronized(lock) { track.also { track = null } } ?: return
            runCatching { t.removeSink(sink) }
        }

        private fun take(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int) {
            if (bits != 16 || channels < 1 || rate <= 0) return
            synchronized(lock) {
                val enc = encoder ?: return
                val out = onPacket ?: return
                val pcm = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val step = rate.toDouble() / TalkAudioFormat.SAMPLE_RATE
                // Picks the nearest input sample for each 48 kHz output sample (the microphone is 48 kHz on phones,
                // so this is normally a straight copy).
                while (resampleAt < frames) {
                    val i = resampleAt.toInt()
                    var sum = 0
                    for (c in 0 until channels) sum += pcm.get(i * channels + c)
                    frame[filled++] = (sum / channels).toShort()
                    if (filled == frame.size) {
                        enc.encode(frame).forEach(out)
                        filled = 0
                    }
                    resampleAt += step
                }
                resampleAt -= frames
            }
        }
    }

    private companion object {
        const val TAG = "LiveKitTalk"

        /** LiveKit takes the key as a string it derives the AES key from: the 32 random bytes, in base64. */
        fun encode(key: ByteArray): String = Base64.encodeToString(key, Base64.NO_WRAP)
    }
}
