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
import kotlinx.coroutines.sync.withLock
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
    /** The camera published for a push-to-video burst (2026-11-24, hen: push-to-video through LiveKit). */
    private var camera: io.livekit.android.room.track.LocalVideoTrack? = null

    /** Keys set before the room was there: set again on each join. */
    private val pendingKeys = ConcurrentHashMap<String, Pair<String, Int>>()

    /** Remote "user.device" whose current audio track decrypts. */
    private val decrypting = ConcurrentHashMap.newKeySet<String>()

    private val sampleCapture = SampleCapture()
    override val capture: TalkCapture get() = sampleCapture

    /** The speakerphone's microphone tuning while the button is held, as for clips (SpeakerphoneMic). */
    private val speakerphone = SpeakerphoneMic(appContext)

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
            val asCall = TalkAudio.playAsCall()
            joinedAsCall = asCall
            if (asCall) Log.i(TAG, "joining with the room's audio as call audio (no background media rights)")
            pendingKeys.forEach { (who, k) -> provider.setKey(k.first, who, k.second) }
            val room = LiveKit.create(
                appContext,
                RoomOptions(
                    // A weak link gets a lower simulcast layer instead of a growing lag (push-to-video): the server picks
                    // the layer by the link (dynacast pauses layers nobody takes). Not adaptive stream: it pauses a
                    // track no visible view shows, and the frames are watched (LiveTalkVideo) before one does.
                    adaptiveStream = false,
                    dynacast = true,
                    e2eeOptions = E2EEOptions(keyProvider = provider),
                ),
                LiveKitOverrides(
                    okHttpClient = okHttp(),
                    audioOptions = AudioOptions(
                        // As media normally; as call audio while the app may not play media from the background.
                        audioOutputType = if (asCall) AudioType.CallAudioType() else AudioType.MediaAudioType(),
                        audioHandler = NoAudioHandler(),
                        // Both talk at once (2026-11-25, hen: "my mic will not send the other person's voice from my
                        // speaker"): WebRTC's own echo canceller (AEC3) and noise suppression, which take what this
                        // room plays as their reference. The phone's built-in ones are made for call mode; the room
                        // plays as media (the speaker, normal mode), where they don't see it.
                        // No microphone opened ahead of a track (a phone warmed in the background only listens).
                        disableAudioPrewarming = true,
                        javaAudioDeviceModuleCustomizer = { builder ->
                            builder.setUseHardwareAcousticEchoCanceler(false)
                            builder.setUseHardwareNoiseSuppressor(false)
                        },
                    ),
                ),
            )
            this@LiveKitTalkTransport.room = room
            LiveTalkVideo.room = room
            keys = provider
            events = scope.launch { room.events.events.collect { onEvent(it) } }
            try {
                room.connect(url, token)
                // Who is in the room already (the chat header's ring).
                room.remoteParticipants.values.forEach { p -> p.identity?.value?.let { listener?.joined(it) } }
                // Joined listen-only (2026-11-25, hen: "don't open microphone if the user entered the room in the
                // background"): no microphone or camera track until this phone's chat is on screen (prepareAudio).
                Log.i(TAG, "in the room, listening only")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the room didn't open", e)
                leave()
                false
            }
        }

    /** The room's audio plays as call audio (TalkAudio.playAsCall when it was joined). */
    @Volatile private var joinedAsCall = false

    override fun stale(): Boolean = room != null && joinedAsCall && !TalkAudio.playAsCall()

    override fun leave() {
        speakerphone.end()
        MicGuard.roomClosed()
        sampleCapture.detach()
        mic = null
        micHeard = false
        cameraSeen = false
        talkingNow = false
        muting?.cancel()
        stopping?.cancel()
        camera?.let { c -> runCatching { c.stop() } }
        camera = null
        capturing = false
        liveVideo = false
        ready = false
        LiveTalkVideo.local.value = null
        LiveTalkVideo.watchLocal(null)
        LiveTalkVideo.gone(null)
        LiveTalkVideo.room = null
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

    /**
     * The microphone's track (2026-11-25, hen: voice back in the room): published once, muted, while the chat is on
     * screen ([prepareAudio]), so a press only unmutes it (the first words go at once) and the release mutes it
     * [TAIL_MS] later (the last ones leave). The clip records the same samples, with the last [SampleCapture.BEFORE_MS]
     * before the press.
     */
    private val preparingMic = kotlinx.coroutines.sync.Mutex()
    private var talkingNow = false
    private var muting: Job? = null

    /** The muted microphone is wanted ready (the chat or the floating window's Push on screen). */
    @Volatile private var micWanted = false
    private var micRetry: Job? = null

    init {
        MicGuard.init(appContext)
        // Another app (or a call) takes the microphone while ours is open and idle: ours goes at once, and comes back
        // once it is free and still wanted (2026-11-25, hen: "don't hijack it").
        MicGuard.onChange {
            if (mic != null && !talkingNow && MicGuard.takenFromIdle()) {
                Log.i(TAG, "the microphone is wanted elsewhere: ours let go")
                scope.launch { releaseMic() }
                retryMic()
            }
        }
    }

    private fun retryMic() {
        micRetry?.cancel()
        micRetry = scope.launch {
            while (micWanted && mic == null) {
                kotlinx.coroutines.delay(2_000)
                val r = room ?: break
                if (micWanted && !MicGuard.busy()) prepareMic(r)
            }
        }
    }

    /** The microphone can't be had now (a call, another app): a press is refused, not taken. */
    override fun micBusy(): Boolean = mic == null && MicGuard.busy() || mic != null && !talkingNow && MicGuard.takenFromIdle()

    private suspend fun releaseMic() {
        val room = room ?: return
        val track = mic ?: return
        if (talkingNow) return
        mic = null
        micHeard = false
        MicGuard.roomClosed()
        sampleCapture.detach()
        runCatching { room.localParticipant.unpublishTrack(track, true) }
    }

    private suspend fun prepareMic(room: Room, press: Boolean = false): LocalAudioTrack? = preparingMic.withLock {
        mic?.let { return@withLock it }
        if (this.room !== room) return@withLock null
        // Not ahead of a press while it's someone else's; a press itself is refused before this (micBusy).
        if (!press && MicGuard.busy()) {
            Log.i(TAG, "microphone not opened ahead: in use elsewhere")
            return@withLock null
        }
        MicGuard.roomOpening()
        val local = room.localParticipant
        // LiveKit's own capture options: echo cancelling, noise suppression and automatic gain control (WebRTC's).
        val track = local.createAudioTrack(
            "talk",
            io.livekit.android.room.track.LocalAudioTrackOptions(
                noiseSuppression = true, echoCancellation = true, autoGainControl = true, highPassFilter = true, typingNoiseDetection = true,
            ),
        )
        Log.i(TAG, "microphone in the room: echo cancelling and noise suppression on (WebRTC's own, the room's playback as reference)")
        sampleCapture.attach(track)
        if (!local.publishAudioTrack(track)) {
            Log.w(TAG, "the microphone wasn't published")
            sampleCapture.detach()
            runCatching { track.stop() }
            MicGuard.roomClosed()
            return@withLock null
        }
        local.getTrackPublication(Track.Source.MICROPHONE)?.muted = true
        mic = track
        Log.i(TAG, "microphone track ready in the room (muted)")
        track
    }

    override suspend fun prepareAudio(on: Boolean) {
        val room = room ?: return
        micWanted = on
        if (on) {
            // The chat or the floating window's Push on screen: the microphone published muted (a press only unmutes
            // it), and the camera's track ready for a video burst; not while it's someone else's.
            if (prepareMic(room) != null) Log.i(TAG, "Push on screen: microphone published (muted)") else retryMic()
            scope.launch { runCatching { prepareVideo(room) } }
        } else {
            micRetry?.cancel()
            // Off screen: the microphone goes (nothing of it stays open).
            releaseMic()
        }
    }

    override suspend fun setTalking(on: Boolean) {
        val room = room ?: return
        val publication = { room.localParticipant.getTrackPublication(Track.Source.MICROPHONE) }
        if (on) {
            muting?.cancel()
            talkingNow = true
            prepareMic(room, press = true) ?: return
            speakerphone.begin()
            publication()?.muted = false
            Log.i(TAG, "microphone live in the room")
        } else {
            talkingNow = false
            muting?.cancel()
            muting = scope.launch {
                kotlinx.coroutines.delay(TAIL_MS)
                if (talkingNow) return@launch
                publication()?.muted = true
                speakerphone.end()
            }
        }
    }

    /**
     * The push-to-video camera's track: published muted as soon as the room is joined, with no frames (the camera isn't
     * read until a burst), so a burst only starts reading and unmutes (a few hundred ms) instead of negotiating a new
     * track (about two seconds; measured 2026-11-24).
     */
    private val preparing = kotlinx.coroutines.sync.Mutex()

    private suspend fun prepareVideo(room: Room): io.livekit.android.room.track.LocalVideoTrack? = preparing.withLock { prepareVideoNow(room) }

    private suspend fun prepareVideoNow(room: Room): io.livekit.android.room.track.LocalVideoTrack? {
        camera?.let { return it }
        if (this.room !== room) return null
        val local = room.localParticipant
        val track = runCatching {
            local.createVideoTrack(
                "talk-camera",
                TalkCameraCapturer { com.chencohen.ptt.talkVideoCapture },
                io.livekit.android.room.track.LocalVideoTrackOptions(captureParams = io.livekit.android.room.track.VideoCaptureParameter(AndroidTalkVideoCapture.WIDTH, AndroidTalkVideoCapture.HEIGHT, AndroidTalkVideoCapture.FPS)),
                null,
            )
        }.getOrElse {
            Log.w(TAG, "no camera track for the room", it)
            return null
        }
        val published = runCatching {
            local.publishVideoTrack(
                track,
                io.livekit.android.room.participant.VideoTrackPublishOptions(
                    name = "talk-camera",
                    simulcast = true,
                    source = Track.Source.CAMERA,
                    degradationPreference = livekit.org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE,
                ),
            )
        }.getOrDefault(false)
        if (!published) {
            Log.w(TAG, "the camera wasn't published")
            runCatching { track.stop() }
            return null
        }
        local.getTrackPublication(Track.Source.CAMERA)?.muted = true
        camera = track
        Log.i(TAG, "camera track ready in the room (muted)")
        return track
    }

    /**
     * The push-to-video camera goes to the room too ([on]) or stops: the same camera as the clip's (TalkCameraCapturer),
     * 640×480 in simulcast layers, so a weak link gets a lower one instead of falling behind.
     */
    /** The camera is warm: read into the muted track ([ready]), so a video burst only unmutes. */
    private var ready = false
    private var liveVideo = false
    private var capturing = false

    /** One start or stop of the camera's reading at a time: a quick stop and start again raced, and the box went black. */
    private val capturingLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun capture(track: io.livekit.android.room.track.LocalVideoTrack, on: Boolean) = capturingLock.withLock {
        if (on == capturing) return@withLock
        capturing = on
        if (!on) {
            LiveTalkVideo.local.value = null
            LiveTalkVideo.watchLocal(null)
        }
        withContext(Dispatchers.IO) { runCatching { if (on) track.startCapture() else track.stopCapture() } }
        if (on) {
            LiveTalkVideo.local.value = track
            LiveTalkVideo.watchLocal(track)
        }
    }

    override suspend fun readyVideo(on: Boolean) {
        val room = room ?: return
        ready = on
        if (on) {
            stopping?.cancel()
            val track = prepareVideo(room) ?: return
            capture(track, true)
        } else if (!liveVideo) {
            stopLater()
        }
    }

    /** A pending stop of the camera's reading ([LINGER_MS] after the last release), let go by a new press. */
    private var stopping: Job? = null

    /**
     * Stops reading the camera a little after a release: a quick press again uses the camera as it runs (2026-11-25,
     * hen: "quick press should reuse the running camera"), instead of closing it and opening it anew.
     */
    private fun stopLater() {
        stopping?.cancel()
        stopping = scope.launch {
            kotlinx.coroutines.delay(LINGER_MS)
            if (!ready && !liveVideo) camera?.let { capture(it, false) }
        }
    }

    override suspend fun setVideo(on: Boolean) {
        val room = room ?: return
        val publication = { room.localParticipant.getTrackPublication(Track.Source.CAMERA) }
        if (on) {
            stopping?.cancel()
            val track = prepareVideo(room) ?: return
            liveVideo = true
            capture(track, true)
            publication()?.muted = false
            Log.i(TAG, "camera live in the room")
        } else {
            val track = camera ?: return
            liveVideo = false
            publication()?.muted = true
            if (!ready && track === camera) stopLater()
        }
    }

    /** Their microphone's audio decrypts and is unmuted (a press), not just published and waiting. */
    override val micReady: Boolean get() = mic != null

    override fun hears(identity: String): Boolean {
        if (identity !in decrypting) return false
        val p = room?.remoteParticipants?.values?.firstOrNull { it.identity?.value == identity } ?: return false
        return p.audioTrackPublications.any { (pub, _) -> !pub.muted }
    }

    override suspend fun sendBeat(bytes: ByteArray) {
        val room = room ?: return
        room.localParticipant.publishData(bytes, io.livekit.android.room.track.DataPublishReliability.RELIABLE, TalkBeat.TOPIC)
    }

    override fun silence(identity: String) {
        val room = room ?: return
        room.remoteParticipants.values.filter { it.identity?.value == identity }.forEach { p ->
            p.audioTrackPublications.forEach { (_, track) -> (track as? RemoteAudioTrack)?.setVolume(0.0) }
        }
    }

    /** The other side subscribed this phone's microphone / camera track (one to one: the clip copy can wait). */
    @Volatile private var micHeard = false
    @Volatile private var cameraSeen = false

    override fun subscribed(video: Boolean): Boolean = if (video) cameraSeen else micHeard

    private fun onEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.LocalTrackSubscribed -> when (event.publication.source) {
                Track.Source.MICROPHONE -> micHeard = true
                Track.Source.CAMERA -> cameraSeen = true
                else -> {}
            }
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
            // Push-to-video through the room: their camera is watched for frames (LiveTalkVideo).
            is RoomEvent.TrackSubscribed -> {
                val who = event.participant.identity?.value ?: return
                // Boost voice: WebRTC's own gain for their voice (its mixer saturates, no soft limiter there).
                (event.track as? RemoteAudioTrack)?.let { if (TalkAudio.boost()) it.setVolume(VoiceBoost.GAIN) }
                (event.track as? io.livekit.android.room.track.VideoTrack)?.let {
                    // The full layer for the box on screen (2026-11-25, hen: "very blocky": the low one, 180×240, shown
                    // large); the server still drops to a lower one when the link can't carry it.
                    (event.publication as? io.livekit.android.room.track.RemoteTrackPublication)?.setVideoQuality(io.livekit.android.room.track.VideoQuality.HIGH)
                    LiveTalkVideo.subscribed(who, it)
                }
            }
            is RoomEvent.TrackUnsubscribed -> event.participant.identity?.value?.let {
                if (event.track.kind == Track.Kind.VIDEO) LiveTalkVideo.gone(it) else decrypting.remove(it)
            }
            is RoomEvent.TrackUnpublished -> event.participant.identity?.value?.let {
                if (event.publication.kind == Track.Kind.VIDEO) LiveTalkVideo.gone(it) else {
                    decrypting.remove(it)
                    listener?.stopped(it)
                }
            }
            // Their microphone muted (released, after its tail): they stopped transmitting (2026-11-25, hen).
            is RoomEvent.TrackMuted -> {
                val who = event.participant.identity?.value
                if (who != null && event.participant != room?.localParticipant && event.publication.kind == Track.Kind.AUDIO) listener?.stopped(who)
            }
            // "Still pushing" (TalkBeat), sealed with their room key.
            is RoomEvent.DataReceived -> if (event.topic == TalkBeat.TOPIC) {
                event.participant?.identity?.value?.let { listener?.beat(it, event.data) }
            }
            is RoomEvent.ParticipantDisconnected -> event.participant.identity?.value?.let {
                micHeard = false
                cameraSeen = false
                listener?.gone(it)
                decrypting.remove(it)
                LiveTalkVideo.gone(it)
            }
            is RoomEvent.Disconnected -> {
                LiveTalkVideo.gone(null)
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
        /** The last [BEFORE_MS] before a press, kept while the microphone runs muted: the clip starts with them. */
        private val before = ShortArray(TalkAudioFormat.SAMPLE_RATE * BEFORE_MS / 1000)
        private var beforeAt = 0
        private var beforeFull = false

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
            // The moment before the press first (as the app's own warm microphone does).
            val enc = encoder!!
            val n = if (beforeFull) before.size else beforeAt
            val start = if (beforeFull) beforeAt else 0
            for (k in 0 until n) {
                frame[filled++] = before[(start + k) % before.size]
                if (filled == frame.size) {
                    enc.encode(frame).forEach(onPacket)
                    filled = 0
                }
            }
            beforeAt = 0
            beforeFull = false
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
                val enc = encoder
                val out = onPacket
                val pcm = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val step = rate.toDouble() / TalkAudioFormat.SAMPLE_RATE
                if (enc == null || out == null) {
                    // Not recording a clip: the ring of the moment before keeps going.
                    var at = resampleAt
                    while (at < frames) {
                        val i = at.toInt()
                        var sum = 0
                        for (c in 0 until channels) sum += pcm.get(i * channels + c)
                        before[beforeAt++] = (sum / channels).toShort()
                        if (beforeAt == before.size) {
                            beforeAt = 0
                            beforeFull = true
                        }
                        at += step
                    }
                    resampleAt = at - frames
                    return
                }
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

        /** How much of the moment before a press the clip starts with (as the app's own warm microphone). */
        const val BEFORE_MS = 300

        /** The microphone stays unmuted this long after the release, so the last words still on their way leave. */
        const val TAIL_MS = 300L
        /**
         * How long the camera keeps reading into the room after a release: none (2026-11-25, hen: the camera only while
         * the slide-up target shows or video is live). A quick press again opens it anew, one step at a time
         * (capturingLock), so it never comes up black.
         */
        const val LINGER_MS = 0L

        /** LiveKit takes the key as a string it derives the AES key from: the 32 random bytes, in base64. */
        fun encode(key: ByteArray): String = Base64.encodeToString(key, Base64.NO_WRAP)
    }
}
