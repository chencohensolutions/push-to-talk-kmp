package com.chencohen.ptt.talk

import com.chencohen.ptt.messaging.Clip
import com.chencohen.ptt.messaging.ClipContent
import com.chencohen.ptt.messaging.ClipKind
import com.chencohen.ptt.messaging.Content
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.MessageStatus
import com.chencohen.ptt.messaging.MessagingStore
import com.chencohen.ptt.messaging.TalkHeardContent
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.relay.ClipSegment
import com.chencohen.ptt.relay.ClipSegmentFrame
import com.chencohen.ptt.relay.BufferedTalk
import com.chencohen.ptt.relay.BufferedTalkFrame
import com.chencohen.ptt.relay.FrameKind
import com.chencohen.ptt.relay.PushKind
import com.chencohen.ptt.relay.SendStatus
import com.chencohen.ptt.relay.TalkAck
import com.chencohen.ptt.relay.TalkAckFrame
import com.chencohen.ptt.relay.TalkEnd
import com.chencohen.ptt.relay.TalkEndFrame
import com.chencohen.ptt.relay.TalkEndReason
import com.chencohen.ptt.relay.TalkEvent
import com.chencohen.ptt.relay.TalkStart
import com.chencohen.ptt.relay.TalkStartFrame
import com.chencohen.ptt.relay.TalkState
import com.chencohen.ptt.relay.TalkStateFrame
import com.chencohen.ptt.settings.EnrolledDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.random.Random

/** Where this phone's own talking stands. */
enum class TalkPhase {
    /** Recording; waiting for the server to give the floor. */
    REQUESTING,

    /** The floor is this phone's: what it records goes out live. */
    TALKING,

    /** Someone else holds the floor ([OwnTalk.busyWith]); nothing was sent. */
    BUSY,

    /** The server refused it ([OwnTalk.refusal]), or the microphone couldn't open. */
    REFUSED,

    /** Not connected: recording, sent as a voice message once let go. */
    OFFLINE,

    /** Recording a voice message (the composer's microphone): no floor. */
    VOICE,

    /**
     * Someone else held the floor at the press (2026-11-23, hen: both pushing at once): still recording, sent right
     * after their burst ends, so both phones see them in press order ([OwnTalk.busyWith]: whom it waits for).
     */
    WAITING,
}

/** This phone talking, or recording a voice message, in [chat]. */
data class OwnTalk(
    val chat: String,
    val clip: String,
    val phase: TalkPhase,
    val startedAt: Long,
    val busyWith: String? = null,
    /** One of `SendStatus`, or [MIC] when the microphone didn't open. */
    val refusal: Int = 0,
    /** An urgent push (2026-11-25, hen: the red bell). */
    val urgent: Boolean = false,
) {
    companion object {
        const val MIC = -1
    }
}

/** Someone holds the floor of a conversation now. */
data class Talker(val user: String, val device: Int, val clip: String, val since: Long)

/** A clip playing now. */
data class NowPlaying(
    val chat: String,
    val clip: String,
    val from: String,
    /** A burst still being said, played with catch-up. */
    val live: Boolean,
    val playedMs: Long = 0,
    /** How far behind the newest audio the playback is (the debug readout's catch-up time). */
    val behindMs: Long = 0,
    /** Push-to-video: its pictures show where the burst is shown. */
    val video: Boolean = false,
)

/** What the talk screens render. */
data class TalkUiState(
    /** Who holds the floor in each conversation now, by chat: the one who started last of [talking]. */
    val talkers: Map<String, Talker> = emptyMap(),
    /**
     * Everyone talking in each conversation now, by chat, in the order they started (2026-11-25, hen: the server gives
     * every talker a floor of their own, in groups too, so several members may push at once and all are shown).
     */
    val talking: Map<String, List<Talker>> = emptyMap(),
    val own: OwnTalk? = null,
    val playing: NowPlaying? = null,
    /** Clips waiting to play after [playing], oldest first. */
    val queued: List<String> = emptyList(),
    /** Both kept pushing at once (3 times within a minute) in this chat: a video call may suit them better. */
    val suggestCall: String? = null,
    /**
     * This phone's pushes: what each listener said became of it (2026-11-25, hen: "who can hear you"), by clip, then
     * user (HeardStatus). The chips under my pill show it while I push.
     */
    val heard: Map<String, Map<String, Int>> = emptyMap(),
)

/** [talker] started in [chat]: one more of its talkers (the same burst again replaces itself), and the newest. */
internal fun TalkUiState.withTalker(chat: String, talker: Talker): TalkUiState {
    val all = talking[chat].orEmpty().filter { it.clip != talker.clip } + talker
    return copy(talkers = talkers + (chat to talker), talking = talking + (chat to all))
}

/**
 * Burst [clip]'s floor ended (or went stale): gone from its chat's talkers. Where it was the chat's [TalkUiState.talkers]
 * one, the newest still talking takes its place, so a group with others still live doesn't look quiet.
 */
internal fun TalkUiState.withoutTalker(clip: String): TalkUiState {
    if (talking.values.none { list -> list.any { it.clip == clip } } && talkers.values.none { it.clip == clip }) return this
    val left = talking.mapValues { (_, list) -> list.filter { it.clip != clip } }.filterValues { it.isNotEmpty() }
    val newest = talkers.mapNotNull { (chat, t) ->
        if (t.clip != clip) chat to t else left[chat]?.lastOrNull()?.let { chat to it }
    }.toMap()
    return copy(talkers = newest, talking = left)
}

/** The platform's part in playing talk when the app isn't on screen (Android: Telecom, notifications). */
interface TalkHost {
    /**
     * A burst in [chat] from [from] should be heard while the app isn't on screen: true when the phone may play it now
     * (Stay connected, or a talk session started); false shows [talkNotify] instead.
     */
    suspend fun prepareToPlay(chat: String, from: String): Boolean

    /**
     * A push this phone didn't let the user hear (Notify only, muted, the phone couldn't play it, or it came too late to
     * play): "Missed push from <name>" ([video]: a video push), opening the chat; it goes when the chat is read. A push
     * that was heard (played, or live in the room) never comes here (2026-11-25, hen: "show notification only if i
     * missed the stream").
     */
    fun talkNotify(chat: String, from: String, clip: String, video: Boolean, status: Int)

    /** Nothing plays now; a talk session may end (20 s after, the plan says, so a reply can follow). */
    fun idle()
}

/** What Talk needs to know of the user's choices (PttSettings). */
interface TalkPrefs {
    val catchUp: CatchUpMode

    /** The user chose Notify only for [chat] in When I'm away. */
    fun notifyOnly(chat: String): Boolean

    /** Settings › Talk › Talk volume, on (TalkLoudness). */
    val unmute: Boolean get() = false

    /** Settings › Talk › Talk volume's level, a share of the most (TalkLoudness). */
    val talkVolume: Double get() = TalkLoudness.DEFAULT_SHARE

    /** The volume keys set a new Talk volume while a push was heard. */
    fun saveTalkVolume(share: Double) {}

    /** Settings › Talk › Lower other audio while someone talks (TalkDuck). */
    val lowerOthers: Boolean get() = true

    /** [chat]'s page › Let urgent pushes through Do Not Disturb (off by default; 2026-11-25, hen). */
    fun urgentThroughDnd(chat: String): Boolean = false
}

/**
 * Push-to-talk's clip path (docs/design.md, "Phase 5a"; the server's docs/messaging.md, "Talk" and "Blobs"). The
 * talker's phone records from the press, asks for the floor with TalkStart, and once granted sends the clip's key in an
 * encrypted "clip start" message and its 200 ms segments as ClipSegment frames (what it recorded before the grant
 * first). A listener plays segments as they come, from the server's forwarding or from GET /blob, which a woken or
 * late phone reads from the start, catching up to live. Not connected, the press records a voice message instead,
 * which waits in the outbox. Runs on Messaging's scope (one thing at a time).
 */
/** A kept burst opened (Save as file): its Opus packets in order (20 ms each), its video frames, and when it was sent. */
class ClipContents(val audio: List<ByteArray>, val frames: List<TalkVideo.Frame>, val at: Long)

internal class Talk(
    private val scope: CoroutineScope,
    private val store: MessagingStore,
    private val clips: ClipFiles,
    private val blobs: BlobApi,
    private val sendFrame: suspend (FrameKind) -> Boolean,
    private val connected: () -> Boolean,
    /** Sends [Content] to a conversation: pairwise to a user, or to a group by its chat. */
    private val sendContent: suspend (chat: String, content: Content, pushKind: Int) -> Unit,
    private val me: () -> EnrolledDevice?,
    private val now: () -> Long,
    private val random: Random,
    private val prefs: TalkPrefs,
    private val publish: () -> Unit,
    /** The app is on screen (or Stay connected keeps it running): bursts play without asking the host. */
    private val awake: () -> Boolean,
) {
    var capture: TalkCapture? = null

    /** A capture for the next bursts instead of the phone's (Android Auto's car microphone); null: the usual. */
    var captureOverride: TalkCapture? = null

    /** The microphone is someone else's now (a phone call, another app recording): a press is refused, not taken. */
    var micBusy: () -> Boolean = { false }

    /** The chat keeps its pushes ([video]: video ones) as messages: their clip is always uploaded (the kept copy). */
    var keepsPush: (chat: String, video: Boolean) -> Boolean = { _, _ -> true }

    /**
     * Settings › Talk › Send through the server when they're away (off; 2026-11-25, hen: the server buffer): the old
     * clip upload, for a server without the talk buffer. Off, a push goes into the room only, and the server's
     * recorder keeps it for whoever is away.
     */
    var clipUpload: (chat: String) -> Boolean = { true }
    var player: TalkPlayer? = null
    var host: TalkHost? = null

    /** The media volume, for the Talk volume (TalkLoudness); null: left alone. */
    var volume: TalkVolume? = null
    private val loudness = TalkLoudness({ volume }, { prefs.unmute }, { prefs.talkVolume }, { prefs.saveTalkVolume(it) })

    /** Whether this phone can hear talk now (2026-11-25, hen): pushes that come while it can't are saved. */
    var hearNow: () -> Hear = { Hear.OK }

    /** Do Not Disturb, opened for an urgent push the user lets through; null: it can't be. */
    var dndGate: DndGate? = null

    /** Other apps' sound, dimmed while someone is heard (TalkDuck); null: left alone. */
    var duck: AudioDuck? = null
    private val dimmer = TalkDuck(scope, { duck?.takeIf { prefs.lowerOthers } }, { loudness.begin() }, {
        loudness.end()
        // Do Not Disturb opened for an urgent push comes back as it was.
        if (dndOpened) {
            dndOpened = false
            runCatching { dndGate?.close() }
        }
    })
    private var dndOpened = false

    /** Other apps are dimmed for a push heard live now (TalkDuck). */
    val ducking: Boolean get() = dimmer.holding

    /** The live path (phase 5b); null without it. */
    var live: LiveTalk? = null

    /** Push-to-video: the camera, whether a press sends it (the Talk screen's Video), and where bursts' video shows. */
    var videoCapture: TalkVideoCapture? = null
    var sendVideo: (chat: String) -> Boolean = { false }

    /** The chat whose Talk screen keeps the camera warm (push-to-video); null: none. */
    var videoChat: String? = null
    var videoOut: TalkVideoOut? = null

    private val _state = MutableStateFlow(TalkUiState())
    val state: StateFlow<TalkUiState> = _state.asStateFlow()

    // --- Talking --------------------------------------------------------------------------------------------------

    private sealed interface Captured {
        class Packet(val bytes: ByteArray, val audio: Boolean = true) : Captured
        data object End : Captured
    }

    private inner class Outgoing(val chat: String, val voiceOnly: Boolean) {
        val id = newClipId()
        val key = random.nextBytes(ClipCrypto.KEY_BYTES)
        var startedAt = now()
        val packets = Channel<Captured>(Channel.UNLIMITED)
        var phase = if (voiceOnly) TalkPhase.VOICE else TalkPhase.REQUESTING
        var granted = false
        var decided = voiceOnly
        val unsent = ArrayDeque<ClipSegment>()
        /** Waiting for someone else's floor to end ([TalkPhase.WAITING]). */
        var waiting = false
        /** One sender at a time: the segments go out in order (2026-11-23: a race sent one ahead of those waiting). */
        val sending = kotlinx.coroutines.sync.Mutex()
        val records = mutableListOf<ByteArray>()
        var pending = mutableListOf<ByteArray>()
        var index = 0
        var frames = 0
        var finished = false
        var cancelled = false
        /** The button came up (or Stop): nothing of it may go live in the room after this. */
        var released = false
        /**
         * Its clip isn't uploaded while everyone hears it live in the room (2026-11-25, hen: the clip copy only as a
         * fallback); it is all kept here, and goes from its first segment the moment someone can't.
         */
        var holdClip = false
        /** Into the room only: the server's recorder keeps it for whoever is away (no clip upload at all). */
        var roomOnly = false
        /** Still this phone's live press: the room's microphone may come on for it. */
        fun liveNow() = outgoing === this && !released && !waiting
        var consumer: Job? = null
        var mic: TalkCapture? = null
        /** The camera goes too (push-to-video): every packet is tagged (TalkVideo). */
        var video = false
        var videoNumber = 0
        /** The video's bytes so far: past [VIDEO_BUDGET] the burst goes on with the voice only. */
        var videoBytes = 0L
        var pendingBytes = 0
        var pendingAudio = 0
        /** An urgent push (the red bell). */
        var urgent = false
    }

    private var outgoing: Outgoing? = null

    /** The Talk button went down in [chat] (a user, or a group's chat). */
    /** [video]: this press's own choice (the floating window's slide up), over the chat's setting. */
    fun press(chat: String, video: Boolean? = null, urgent: Boolean = false) = begin(chat, voiceOnly = false, video = video, urgent = urgent)

    /**
     * The press became urgent while held (2026-11-25, hen: released on the red bell, or kept there): the floor and
     * the clip say so from here. True when a press is going on.
     */
    fun markUrgent(): Boolean {
        val out = outgoing?.takeIf { !it.voiceOnly } ?: return false
        if (out.urgent) return true
        out.urgent = true
        _state.update { s -> s.copy(own = s.own?.takeIf { it.clip == out.id }?.copy(urgent = true) ?: s.own) }
        store.message(out.id)?.let { m -> m.clip?.let { c -> store.putMessage(m.copy(clip = c.copy(urgent = true))) } }
        // The listeners decided at its start: its clip start again, urgent, lets a saved one through after all.
        if (out.granted) scope.launch {
            runCatching { sendContent(out.chat, Content(clip = ClipContent(out.id, out.key, ClipContent.BURST, out.startedAt, video = out.video, urgent = true)), PushKind.NONE) }
        }
        return true
    }

    /** A Talk screen is on screen ([on]) or left: the microphone is kept open for an instant press, or closed. */
    fun warmMicrophone(on: Boolean) {
        val c = capture ?: return
        if (on) c.warm() else c.cool()
    }

    /** The composer's microphone went down: a voice message, without the floor. */
    fun startVoice(chat: String) = begin(chat, voiceOnly = true)

    private fun begin(chat: String, voiceOnly: Boolean, video: Boolean? = null, urgent: Boolean = false) {
        if (outgoing != null) return
        // Never taken from a call or another app (2026-11-25, hen: "don't hijack it"): refused, so nothing looks live.
        if (micBusy()) return refuse(chat, OwnTalk.MIC)
        // In the conversation's room, the room's microphone feeds the clip too.
        val mic = captureOverride ?: (if (voiceOnly) null else live?.captureFor(chat)) ?: capture ?: return refuse(chat, OwnTalk.MIC)
        val out = Outgoing(chat, voiceOnly)
        out.urgent = urgent && !voiceOnly
        out.mic = mic
        // One to one, both talk at once: what they say now goes on playing while I talk (2026-11-25, hen). Anything
        // else playing (an older clip, a group's burst) stops.
        if (Group.idOf(chat) != null || _state.value.playing?.live != true) stopPlaying()
        // The camera first, so the voice's packets are tagged from the first one.
        val camera = videoCapture?.takeIf { !voiceOnly && (video ?: sendVideo(chat)) }
        if (camera != null) {
            out.video = camera.start { frame ->
                out.videoBytes += frame.data.size
                if (out.videoBytes > VIDEO_BUDGET) {
                    camera.stop()
                    return@start
                }
                TalkVideo.pieces(out.videoNumber++ and 0xffff, frame).forEach { out.packets.trySend(Captured.Packet(it, audio = false)) }
            }
            // Where the face is, with the pictures (Follow face on the other phones; 2026-11-23, hen).
            if (out.video) camera.faces { face -> out.packets.trySend(Captured.Packet(TalkVideo.face(face), audio = false)) }
        }
        if (!mic.start { out.packets.trySend(Captured.Packet(if (out.video) TalkVideo.audio(it) else it)) }) {
            if (out.video) camera?.stop()
            return refuse(chat, OwnTalk.MIC)
        }
        outgoing = out
        if (!voiceOnly) {
            live?.burst(chat, null)
            // The live path doesn't wait for the grant; a Busy stops it again.
            scope.launch { live?.talking(chat, true, out.video) { out.liveNow() } }
        }
        out.consumer = scope.launch { consume(out) }
        if (!voiceOnly) {
            if (connected()) {
                scope.launch {
                    if (!sendFrame(TalkStartFrame(talkStart(chat, out.id, out.urgent)))) toVoice(out)
                }
                // No answer in a few seconds: the connection is gone in all but name.
                scope.launch {
                    delay(GRANT_WAIT)
                    if (!out.decided && !out.waiting) toVoice(out)
                }
            } else {
                toVoice(out)
            }
        }
        showOwn(out)
    }

    private val overlaps = HashMap<String, MutableList<Long>>()

    /** A press waited for someone else's: three within a minute in one chat suggests a video call. */
    private fun noteOverlap(chat: String) {
        val now = now()
        val times = overlaps.getOrPut(chat) { mutableListOf() }
        times += now
        times.removeAll { now - it > 60_000 }
        if (times.size >= 3) {
            times.clear()
            _state.update { it.copy(suggestCall = chat) }
            scope.launch {
                delay(10_000)
                _state.update { if (it.suggestCall == chat) it.copy(suggestCall = null) else it }
            }
        }
    }

    /** A burst or voice message deleted: its file goes too. */
    fun forgetClip(id: String) = runCatching { clips.delete(id) }

    /** The video call suggestion was answered or dismissed. */
    fun dismissCallSuggestion() = _state.update { it.copy(suggestCall = null) }

    private fun refuse(chat: String, refusal: Int) {
        _state.update { it.copy(own = OwnTalk(chat, "", TalkPhase.REFUSED, now(), refusal = refusal)) }
        clearOwnLater()
    }

    /** The button came up: the clip ends (and a voice message is sent, unless [send] is false: cancelled). */
    fun release(send: Boolean = true) {
        val out = outgoing ?: return
        out.released = true
        if (!send) out.cancelled = true
        // Let go while waiting for their floor: nothing of it went out, and nothing is kept.
        if (out.waiting) {
            out.waiting = false
            out.cancelled = true
            out.decided = true
        }
        if (out.video) {
            videoCapture?.faces(null)
            videoCapture?.stop()
        }
        out.mic?.stop()
        out.packets.trySend(Captured.End)
        if (!out.voiceOnly) scope.launch { live?.talking(out.chat, false) }
    }

    private suspend fun consume(out: Outgoing) {
        for (item in out.packets) {
            when (item) {
                is Captured.Packet -> {
                    // Waiting for their floor: not live on screen, so nothing is recorded or sent.
                    if (out.waiting) continue
                    out.pending += item.bytes
                    out.pendingBytes += item.bytes.size
                    if (item.audio) {
                        out.frames++
                        out.pendingAudio++
                    }
                    // 200 ms of voice, or less when the camera's pieces fill it (under the server's 16 KB); the first
                    // after 60 ms, so the listener starts sooner (2026-11-23, hen: "the delay is big").
                    val due = if (out.index == 0) FIRST_SEGMENT_FRAMES else TalkAudioFormat.FRAMES_PER_SEGMENT
                    if (out.pendingAudio >= due || out.pendingBytes > SEGMENT_BYTES) segment(out, last = false)
                }
                Captured.End -> {
                    segment(out, last = true)
                    out.finished = true
                    finish(out)
                    return
                }
            }
        }
    }

    private suspend fun segment(out: Outgoing, last: Boolean) {
        val ciphertext = ClipCrypto.seal(out.key, out.id, out.index, last, out.pending)
        out.records += ClipRecords.record(out.index, last, ciphertext)
        val segment = ClipSegment(out.id, out.index, last, ciphertext)
        out.index++
        out.pending = mutableListOf()
        out.pendingBytes = 0
        out.pendingAudio = 0
        if (out.voiceOnly || out.phase == TalkPhase.OFFLINE || out.cancelled) return
        out.unsent += segment
        // Someone stopped hearing it live (left the room, the room failed): the clip goes now, from its start.
        if (out.holdClip && !out.roomOnly && live?.allHearing(out.chat, out.video) != true) out.holdClip = false
        if (out.granted && !out.holdClip) drain(out)
    }

    /**
     * The segments waiting, in order, while the floor is this phone's: always through [Outgoing.unsent] and one sender
     * at a time, so a new segment never goes ahead of earlier ones still waiting (the server refuses a gap, and the
     * burst was then stuck holding the floor; 2026-11-23, hen: "something is blocking calling and sending").
     */
    private suspend fun drain(out: Outgoing) {
        out.sending.withLock {
            while (out.granted && out.unsent.isNotEmpty()) {
                if (!sendFrame(ClipSegmentFrame(out.unsent.removeFirst()))) {
                    toVoice(out)
                    return
                }
            }
        }
    }

    /**
     * Not connected, or no answer to the floor: the press goes nothing further and nothing of it is kept (2026-11-25,
     * hen: "only when the UI says it's transmitting, then transmit": a push that never went live was kept as a voice
     * message). What already went out with the floor stays as it is.
     */
    private fun toVoice(out: Outgoing) {
        if (out.phase == TalkPhase.BUSY || out.phase == TalkPhase.REFUSED) return
        if (out.granted) {
            // Live until now: what went out is the clip.
            if (outgoing === out) release()
            return
        }
        lost(out, TalkPhase.OFFLINE)
    }

    private suspend fun finish(out: Outgoing) {
        if (!out.finished) return
        // Still waiting for the server's answer: finished once it comes (or GRANT_WAIT passes).
        if (!out.decided) return
        if (outgoing === out) outgoing = null
        val duration = out.frames.toLong() * TalkAudioFormat.FRAME_MS
        when {
            out.phase == TalkPhase.BUSY || out.phase == TalkPhase.REFUSED -> Unit
            out.cancelled || duration < MIN_CLIP_MS && !out.granted || !out.granted && !out.voiceOnly -> {
                if (out.granted) endFloor(out.id)
            }
            out.granted -> {
                // Heard live by everyone to the end: no clip went up; the floor ends here instead of at its last
                // segment.
                if (out.holdClip && (out.roomOnly || live?.allHearing(out.chat, out.video) == true)) {
                    out.unsent.clear()
                    endFloor(out.id)
                } else if (out.holdClip) {
                    out.holdClip = false
                    drain(out)
                }
                clips.write(out.id, concat(out.records))
                val kept = store.message(out.id)
                if (kept != null) store.putMessage(kept.copy(clip = kept.clip?.copy(durationMs = duration, kept = true)))
                publish()
            }
            else -> {
                // A voice message (recorded so, or a burst that couldn't go live): kept, then sent from the outbox.
                clips.write(out.id, concat(out.records))
                store.putMessage(
                    TextMessage(
                        out.id, out.chat, outgoing = true, body = "", at = out.startedAt, status = MessageStatus.PENDING,
                        author = if (Group.idOf(out.chat) != null) me()?.user else null,
                        clip = Clip(out.id, Base64.encode(out.key), ClipKind.VOICE, duration, kept = true, video = out.video),
                    )
                )
                publish()
                scope.launch { deliverVoice(out.id) }
            }
        }
        if (_state.value.own?.clip == out.id && out.phase != TalkPhase.BUSY && out.phase != TalkPhase.REFUSED) {
            _state.update { it.copy(own = null) }
        }
    }

    /**
     * Sends a kept voice message: uploads its file (PUT /blob), then its key in an encrypted message. Answers its new
     * status: PENDING when not connected (the outbox tries again), FAILED when the server refused it.
     */
    suspend fun deliverVoice(id: String): MessageStatus {
        val message = store.message(id) ?: return MessageStatus.FAILED
        val clip = message.clip ?: return MessageStatus.FAILED
        if (message.status != MessageStatus.PENDING) return message.status
        if (!connected()) return MessageStatus.PENDING
        val status = try {
            if (!clip.uploaded) {
                val bytes = clips.read(id) ?: throw BlobException(0, "The recording is gone")
                val group = Group.idOf(message.user)
                blobs.put(id, if (group == null) message.user else null, group, bytes)
                store.putMessage(message.copy(clip = clip.copy(uploaded = true)))
            }
            val key = Base64.decode(clip.key)
            sendContent(message.user, Content(clip = ClipContent(id, key, ClipContent.VOICE, message.at, clip.durationMs, video = clip.video)), PushKind.MESSAGE)
            MessageStatus.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: BlobException) {
            if (e.status == 403 || e.status == 0 || e.status == 413) MessageStatus.FAILED else MessageStatus.PENDING
        } catch (e: com.chencohen.ptt.relay.SocketUnavailableException) {
            MessageStatus.PENDING
        } catch (e: com.chencohen.ptt.messaging.SendRefusedException) {
            MessageStatus.FAILED
        } catch (_: Exception) {
            MessageStatus.PENDING
        }
        val now = store.message(id) ?: return status
        val updated = now.copy(status = if (now.status.ordinal > status.ordinal && status != MessageStatus.FAILED) now.status else status)
        store.putMessage(updated)
        publish()
        return updated.status
    }

    private fun granted(out: Outgoing) {
        if (out.decided) return
        out.decided = true
        out.granted = true
        out.phase = TalkPhase.TALKING
        if (outgoing === out) showOwn(out)
        beatWhileTransmitting(out)
        // The chat's history: the burst, kept as this phone records it.
        store.putMessage(
            TextMessage(
                out.id, out.chat, outgoing = true, body = "", at = out.startedAt, status = MessageStatus.SENT,
                author = if (Group.idOf(out.chat) != null) me()?.user else null,
                clip = Clip(out.id, Base64.encode(out.key), ClipKind.BURST, video = out.video, urgent = out.urgent),
            )
        )
        publish()
        // Into the room only (the server buffer keeps it for whoever is away), unless the old clip upload is on.
        if (live?.transport != null && !clipUpload(out.chat)) {
            out.holdClip = true
            out.roomOnly = true
        } else if (!keepsPush(out.chat, out.video) && live != null) out.holdClip = true
        scope.launch {
            // Everyone hears it in the room (one to one, subscribed), and the chat doesn't keep it: no clip upload.
            // Asked for up to CLIP_DECIDE_MS (the other side's subscription may still be on its way).
            if (out.holdClip && !out.roomOnly) {
                var waited = 0L
                while (live?.allHearing(out.chat, out.video) != true && waited < CLIP_DECIDE_MS && !out.finished) {
                    delay(50)
                    waited += 50
                }
                out.holdClip = live?.allHearing(out.chat, out.video) == true
            }
            // What was recorded before the grant, then the rest as it comes (in order: drain).
            if (!out.holdClip) drain(out)
            if (out.finished) finish(out)
        }
        scope.launch {
            try {
                sendContent(out.chat, Content(clip = ClipContent(out.id, out.key, ClipContent.BURST, out.startedAt, video = out.video, urgent = out.urgent)), PushKind.TALK)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The listeners can't open it without the key: it goes again as a voice message.
            }
        }
    }

    private fun lost(out: Outgoing, phase: TalkPhase, busyWith: String? = null, refusal: Int = 0) {
        out.decided = true
        out.phase = phase
        out.unsent.clear()
        if (outgoing === out) {
            outgoing = null
            if (out.video) {
            videoCapture?.faces(null)
            videoCapture?.stop()
        }
            out.mic?.stop()
            out.packets.close()
            scope.launch { live?.talking(out.chat, false) }
        }
        _state.update { it.copy(own = OwnTalk(out.chat, out.id, phase, out.startedAt, busyWith, refusal, urgent = out.urgent)) }
        clearOwnLater()
    }

    private fun showOwn(out: Outgoing) {
        _state.update { it.copy(own = OwnTalk(out.chat, out.id, out.phase, out.startedAt, urgent = out.urgent)) }
    }

    private fun clearOwnLater() {
        val shown = _state.value.own
        scope.launch {
            delay(SHOW_REFUSAL)
            _state.update { if (it.own == shown) it.copy(own = null) else it }
        }
    }

    // --- The floor's news -----------------------------------------------------------------------------------------

    /**
     * A burst of someone else's ended here ([chat], [burstId]): their floor's end, or the watchdog's (no sign of it for
     * TalkBeat.LOST_MS, their microphone gone quiet in the room): gone from the live row at once, others' audio
     * undimmed, its message's length and heard set, and the server's copy fetched for what didn't come.
     */
    private fun endTheirs(chat: String, burstId: String) {
        val talker = _state.value.talking[chat]?.firstOrNull { it.clip == burstId } ?: _state.value.talkers[chat]?.takeIf { it.clip == burstId }
        _state.update { state -> state.withoutTalker(burstId) }
        dimmer.quiet("live:$burstId")
        // Heard live (LiveKit): heard, and, with no clip stream counted, as long as the floor was held.
        if (talker != null) {
            store.message(burstId)?.let { m ->
                val c = m.clip
                val heardLive = bursts[burstId]?.heardLive == true
                if (c != null && (c.durationMs <= 0 || heardLive && !c.heard)) {
                    val length = c.durationMs.takeIf { it > 0 } ?: (now() - talker.since).coerceAtLeast(TalkAudioFormat.FRAME_MS.toLong())
                    store.putMessage(m.copy(read = m.read || heardLive, clip = c.copy(durationMs = length, heard = c.heard || heardLive)))
                    publish()
                }
            }
        }
        bursts[burstId]?.let { burst ->
            burst.ended = true
            // Ended without its last segment here: the server's copy has the rest.
            // Heard live through the room: there may be no clip at all (the sender uploads it only when
            // someone can't hear it live), and nothing is missing.
            if (burst.lastIndex == null && !burst.heardLive) scope.launch { delay(END_GRACE); if (burst.lastIndex == null) follow(burst) }
            // Still playing once its floor ended with nothing more coming (its key never came, or no segment for
            // END_STUCK_MS, the server's copy not there either): its playback ends here, so it doesn't stay "live" on
            // screen for ever (2026-11-26: testbot showed "hen talking" for 6 minutes after a 2 s video push).
            val at = burst.next
            scope.launch {
                delay(END_STUCK_MS)
                if (!burst.playing || burst.next != at || burst.kept || !burst.ended) return@launch
                burst.fetch?.cancel()
                if (burst.lastIndex == null) burst.lastIndex = burst.next - 1
                if (burst.key == null) burst.playback?.finish() else drain(burst)
            }
        }
    }

    // --- Live for exactly as long as they transmit (2026-11-25, hen) --------------------------------------------------

    /** Each talker's last sign of life (TalkBeat): beats, live segments, its floor's start. */
    private val signs = LiveSigns { now() }

    /** Talkers the watchdog took off the live row, by burst, kept so a sign coming again puts them back. */
    private val cleared = mutableMapOf<String, Pair<String, Talker>>()
    private var watchdog: Job? = null

    /** [identity]'s beat for [burstId] came through the room. */
    fun onBeat(identity: String, burstId: String) {
        if (!validId(burstId)) return
        val known = _state.value.talking.values.any { list -> list.any { it.clip == burstId && "${it.user}.${it.device}" == identity } } ||
            cleared[burstId]?.second?.let { "${it.user}.${it.device}" == identity } == true
        if (known) alive(burstId)
    }

    /** [identity] stopped transmitting in the room (microphone muted or unpublished, or gone): their live ends now. */
    fun onStopped(identity: String) {
        _state.value.talking.forEach { (chat, list) ->
            list.filter { "${it.user}.${it.device}" == identity }.forEach { t -> loseTheirs(chat, t) }
        }
    }

    /** A sign of life of [burstId]: watched from now; back on the live row if the watchdog had taken it off. */
    private fun alive(burstId: String) {
        signs.alive(burstId)
        cleared.remove(burstId)?.let { (chat, talker) ->
            bursts[burstId]?.ended = false
            _state.update { it.withTalker(chat, talker) }
        }
        if (watchdog?.isActive != true) watchdog = scope.launch {
            while (signs.watching()) {
                delay(WATCH_EVERY)
                for (id in signs.lost()) {
                    val chat = _state.value.talking.entries.firstOrNull { (_, list) -> list.any { it.clip == id } }?.key
                    val talker = chat?.let { c -> _state.value.talking[c]?.firstOrNull { it.clip == id } }
                    signs.forget(id)
                    if (chat != null && talker != null) loseTheirs(chat, talker)
                }
            }
        }
    }

    /** Their live state ends without the floor's end (the watchdog, or the room said they stopped): kept to come back. */
    private fun loseTheirs(chat: String, talker: Talker) {
        signs.forget(talker.clip)
        cleared[talker.clip] = chat to talker
        endTheirs(chat, talker.clip)
    }

    /**
     * /ws is live again after a drop: floors whose end may have been missed meanwhile go now unless a sign of them came
     * in the last TalkBeat.LOST_MS (they come back with the next one), and an end of mine that didn't go out goes now.
     */
    fun reconnected() {
        val fresh = signs.freshWithin(TalkBeat.LOST_MS)
        _state.value.talking.forEach { (chat, list) ->
            list.filter { it.clip !in fresh }.forEach { t -> loseTheirs(chat, t) }
        }
        val ends = unsentEnds.toList()
        unsentEnds.clear()
        scope.launch { ends.forEach { endFloor(it) } }
    }

    /** Ends of mine that couldn't be sent (no connection): sent when it comes back, so their floor doesn't hang there. */
    private val unsentEnds = mutableSetOf<String>()

    /** Lets go of my floor for [burstId]; kept for [reconnected] when it can't go now. */
    private suspend fun endFloor(burstId: String) {
        if (!sendFrame(TalkEndFrame(TalkEnd(burstId)))) unsentEnds += burstId
    }

    /** "Still pushing" while [out] transmits (held, hands-free video, the floating window): once a second, until it stops. */
    private fun beatWhileTransmitting(out: Outgoing) {
        scope.launch {
            // Only through the room: off it, the clip's own segments (every 200 ms, silence too) are the sign.
            while (outgoing === out && out.granted && !out.released && !out.finished && !out.waiting) {
                if (runCatching { live?.beat(out.chat, out.id) }.getOrNull() != true) break
                delay(TalkBeat.EVERY_MS)
            }
        }
    }

    /** A TalkState or ClipSegment frame from /ws. */
    fun onFrame(kind: FrameKind) {
        when (kind) {
            is TalkStateFrame -> onState(kind.talkState)
            is ClipSegmentFrame -> onSegment(kind.clipSegment, live = true)
            is BufferedTalkFrame -> onBuffered(kind.bufferedTalk)
            else -> Unit
        }
    }

    private fun onState(s: TalkState) {
        val chat = if (s.group.isNotEmpty()) Group.chatOf(s.group) else s.peer
        val out = outgoing?.takeIf { it.id == s.burstId }
        when (s.event) {
            TalkEvent.GRANTED -> out?.let { granted(it) }
            TalkEvent.BUSY -> out?.let { o ->
                if (o.voiceOnly || o.waiting) return@let lost(o, TalkPhase.BUSY, busyWith = s.talkerUser)
                // Their floor: shown as waiting, nothing recorded or sent, and asked again the moment it ends; it
                // starts from then (2026-11-25, hen: transmit only while the screen says so).
                o.waiting = true
                o.phase = TalkPhase.WAITING
                o.records.clear()
                o.pending = mutableListOf()
                o.pendingBytes = 0
                o.pendingAudio = 0
                o.unsent.clear()
                o.index = 0
                o.frames = 0
                _state.update { it.copy(own = OwnTalk(o.chat, o.id, TalkPhase.WAITING, o.startedAt, s.talkerUser, urgent = o.urgent)) }
                scope.launch { live?.talking(o.chat, false) }
                noteOverlap(o.chat)
                scope.launch {
                    // Their floor can't outlast the server's limit; if no end comes, the press is let go.
                    delay(STALE_TALKER_MS)
                    if (o.waiting) {
                        o.waiting = false
                        lost(o, TalkPhase.BUSY, busyWith = s.talkerUser)
                    }
                }
            }
            TalkEvent.REFUSED -> out?.let {
                // Refused after the floor was given (a segment the server couldn't take): let go of it at once.
                if (it.granted) scope.launch { endFloor(it.id) }
                it.granted = false
                lost(it, TalkPhase.REFUSED, refusal = s.refusal)
            }
            TalkEvent.STARTED -> {
                val talker = Talker(s.talkerUser, s.talkerDevice, s.burstId, s.startedAt.takeIf { t -> t > 0 } ?: now())
                _state.update { it.withTalker(chat, talker) }
                signs.started(s.burstId)
                // A floor never longer than the server's limit: if its end never came (a dropped frame), it goes anyway.
                scope.launch {
                    delay(STALE_TALKER_MS)
                    _state.update { st -> st.withoutTalker(s.burstId) }
                    dimmer.quiet("live:${s.burstId}")
                }
                val self = me() ?: return
                // Another phone of this user talking: shown, not played.
                if (s.talkerUser == self.user) return
                live?.burst(chat, s.talkerUser)
                val burst = incoming(s.burstId, chat, s.talkerUser)
                burst.announced = true
                burst.device = s.talkerDevice
                if (s.urgent) burst.urgent = true
                scope.launch { consider(burst) }
            }
            TalkEvent.ENDED -> {
                signs.forget(s.burstId)
                cleared.remove(s.burstId)
                endTheirs(chat, s.burstId)
                // Someone else's floor ended: a burst waiting for it asks now (sent right after theirs).
                outgoing?.takeIf { it.waiting && it.chat == chat && it.id != s.burstId }?.let { w ->
                    w.waiting = false
                    w.phase = TalkPhase.REQUESTING
                    w.startedAt = now()
                    showOwn(w)
                    // Live again from this moment, on screen and in the room together.
                    scope.launch { live?.talking(w.chat, true, w.video) { w.liveNow() } }
                    scope.launch { if (!sendFrame(TalkStartFrame(talkStart(w.chat, w.id, w.urgent)))) toVoice(w) }
                    scope.launch {
                        delay(GRANT_WAIT)
                        if (!w.decided && !w.waiting) toVoice(w)
                    }
                }
                if (out != null && s.endReason != TalkEndReason.RELEASED) {
                    // The server ended it (60 s, or the connection dropped): what is recorded until now is the clip.
                    if (s.endReason == TalkEndReason.DROPPED) toVoice(out) else release()
                }
            }
        }
    }

    // --- Listening ------------------------------------------------------------------------------------------------

    private inner class Incoming(val id: String, var chat: String, var from: String) {
        var key: ByteArray? = null
        val segments = mutableMapOf<Int, ClipSegment>()
        var next = 0
        var lastIndex: Int? = null
        var announced = false
        var ended = false
        var playing = false
        var play = false
        var decidedPlay = false
        var playback: Playback? = null
        var fetch: Job? = null
        var sentAt = 0L
        var frames = 0
        var kept = false
        var device = 0
        /** Heard live through the room: the clip is only kept. */
        var heardLive = false
        /** Push-to-video: its packets are tagged, the pictures go to videoOut. */
        var video = false
        /** An urgent push (STARTED, or its clip start). */
        var urgent = false
        /** Played through Do Not Disturb or silence (an urgent push let through): its HeardStatus; 0 not. */
        var throughDnd = 0
        /** What this phone told the talker became of it (HeardStatus; 0 nothing yet). */
        var told = 0
        val splitter = TalkVideo.Splitter()
        val createdAt = now()
    }

    private val bursts = mutableMapOf<String, Incoming>()

    /**
     * Reads a push the server kept for this phone (the server's docs/messaging.md, "Talk buffer"): the Android side
     * follows /blob/<id> as it grows and decrypts each frame with the talker's room key; [onFrame] gets an Opus
     * packet (audio) or a picture.
     */
    interface BufferReader {
        suspend fun read(blobId: String, talker: String, codec: String, onAudio: (ByteArray) -> Unit, onVideo: (TalkVideo.Frame) -> Unit)
    }

    var bufferReader: BufferReader? = null

    /** A buffered push turned back into its burst's clip segments (sealed with the burst's own key), per burst. */
    private inner class Rebuild(val burst: Incoming) {
        val lock = kotlinx.coroutines.sync.Mutex()
        var pending = mutableListOf<ByteArray>()
        var audio = 0
        var index = 0
        var readers = 0
        var pictures = 0
    }

    private val rebuilds = mutableMapOf<String, Rebuild>()

    /** [burst]'s key just came: a buffered push held for it (the store) becomes its clip now, a missed push to play. */
    private fun keyCame(burst: Incoming) {
        // Those held past LATE_KEY_MS go.
        store.lateKeys().forEach { id -> store.lateKey(id)?.let { if (now() - it.at > LATE_KEY_MS) store.removeLateKey(id) } }
        val late = store.lateKey(burst.id) ?: return
        store.removeLateKey(burst.id)
        val key = burst.key ?: return
        if (burst.chat.isEmpty()) burst.chat = late.chat
        if (burst.from.isEmpty()) burst.from = late.from
        val packets = late.packets.mapIndexed { i, b -> Base64.decode(b) to (late.audio.getOrNull(i) ?: true) }
        val rebuild = Rebuild(burst)
        burst.play = false
        burst.ended = true
        // Whatever was given up on meanwhile (no key: taken as ending with nothing) starts again from the held copy.
        burst.fetch?.cancel()
        burst.segments.clear()
        burst.next = 0
        burst.lastIndex = null
        burst.frames = 0
        burst.kept = false
        scope.launch {
            rebuild.lock.withLock { rebuild.index = 0; rebuild.pending = mutableListOf(); rebuild.audio = 0 }
            packets.forEach { (packet, audio) -> rebuild.add(key, packet, audio) }
            rebuild.lock.withLock { rebuild.flush(key, last = true) }
            host?.talkNotify(burst.chat, burst.from, burst.id, burst.video, 0)
        }
    }

    /**
     * A push this phone's user wasn't in the room for (2026-11-25, hen: the server buffer): it plays as its burst,
     * from its first word, through the usual path (catch-up, pictures, the chat's keeping). Heard live already: nothing.
     */
    private fun onBuffered(b: BufferedTalk) {
        val reader = bufferReader ?: return
        val chat = if (b.group.isNotEmpty()) Group.chatOf(b.group) else b.peer
        val talker = b.talker.substringBeforeLast('.')
        val id = b.burstId.ifEmpty { _state.value.talkers[chat]?.clip.orEmpty() }
        if (!validId(id)) return
        val burst = incoming(id, chat, talker)
        if (burst.heardLive) return
        val rebuild = rebuilds.getOrPut(id) { Rebuild(burst) }
        rebuild.readers++
        scope.launch {
            // The burst's key comes in its "clip start" message, end to end.
            var waited = 0
            while (burst.key == null && waited < BUFFER_KEY_WAIT) {
                delay(100)
                waited += 100
            }
            val key = burst.key ?: run {
                // Its key is late (2026-11-26: testbot, just restarted, showed hen "talking" for minutes waiting on it):
                // not live any more, at once; what the server kept is read now (the room key opens it) and held, and
                // when the key comes it becomes a missed push to play. Held up to LATE_KEY_MS.
                if (burst.playing) burst.playback?.stop()
                burst.play = false
                val held = mutableListOf<Pair<ByteArray, Boolean>>()
                var pictures = 0
                try {
                    reader.read(b.blobId, b.talker, b.codec, onAudio = { opus ->
                        held += (if (burst.video) TalkVideo.audio(opus) else opus) to true
                    }, onVideo = { frame ->
                        TalkVideo.pieces(pictures++ and 0xffff, frame).forEach { held += it to false }
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                rebuild.lock.withLock {
                    rebuild.readers--
                    if (rebuild.readers == 0) rebuilds.remove(id)
                }
                if (held.isNotEmpty()) {
                    // Kept in the encrypted store: an app restart before the key comes doesn't lose it.
                    store.putLateKey(id, com.chencohen.ptt.messaging.LateHeld(burst.chat, burst.from, burst.video, now(), held.map { Base64.encode(it.first) }, held.map { it.second }))
                }
                return@launch
            }
            try {
                reader.read(b.blobId, b.talker, b.codec, onAudio = { opus ->
                    scope.launch { rebuild.add(key, if (burst.video) TalkVideo.audio(opus) else opus, audio = true) }
                }, onVideo = { frame ->
                    scope.launch { TalkVideo.pieces(rebuild.pictures++ and 0xffff, frame).forEach { rebuild.add(key, it, audio = false) } }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            rebuild.lock.withLock {
                rebuild.readers--
                if (rebuild.readers == 0) {
                    rebuild.flush(key, last = true)
                    rebuilds.remove(id)
                }
            }
        }
    }

    private suspend fun Rebuild.add(key: ByteArray, packet: ByteArray, audio: Boolean) = lock.withLock {
        pending += packet
        if (audio) this.audio++
        if (this.audio >= TalkAudioFormat.FRAMES_PER_SEGMENT || pending.sumOf { it.size } > SEGMENT_BYTES) flush(key, last = false)
    }

    private fun Rebuild.flush(key: ByteArray, last: Boolean) {
        if (pending.isEmpty() && !last) return
        val ciphertext = ClipCrypto.seal(key, burst.id, index, last, pending)
        val segment = ClipSegment(burst.id, index, last, ciphertext)
        index++
        pending = mutableListOf()
        audio = 0
        onSegment(segment, live = true)
    }

    private fun incoming(id: String, chat: String, from: String): Incoming {
        dropOld()
        return bursts.getOrPut(id) { Incoming(id, chat, from) }.also {
            if (chat.isNotEmpty()) it.chat = chat
            if (from.isNotEmpty()) it.from = from
        }
    }

    private fun dropOld() {
        val cutoff = now() - KEEP_BURST
        bursts.entries.removeAll { (_, b) -> b.createdAt < cutoff && !b.playing }
    }

    private fun onSegment(segment: ClipSegment, live: Boolean) {
        // A live segment puts a talker the watchdog took off back on the row (it is plainly still going). It doesn't
        // start the watch: off the room the server's own end comes within 3 s of the talker's connection closing.
        if (live && segment.burstId in cleared) alive(segment.burstId)
        val burst = bursts[segment.burstId] ?: if (live) incoming(segment.burstId, "", "") else return
        if (segment.index < burst.next || burst.segments.containsKey(segment.index)) return
        if (burst.lastIndex != null && segment.index > burst.lastIndex!!) return
        if (burst.segments.size > MAX_BUFFERED) return
        burst.segments[segment.index] = segment
        if (segment.last) burst.lastIndex = segment.index
        // A gap in what the server forwarded (a slow phone misses some): the server's copy fills it.
        if (live && segment.index > burst.next + GAP && burst.fetch == null && burst.key != null) follow(burst)
        drain(burst)
    }

    /**
     * The "clip start" message of a burst, or a voice message, from [from] in [chat] ([outgoingCopy]: a copy of what
     * another phone of this user sent). Kept in the chat; a recent burst plays.
     */
    fun receiveClip(chat: String, from: String, clip: ClipContent, serverTimestamp: Long, outgoingCopy: Boolean) {
        if (clip.key.size != ClipCrypto.KEY_BYTES || !validId(clip.id)) return
        val kind = if (clip.kind == ClipContent.VOICE) ClipKind.VOICE else ClipKind.BURST
        if (store.message(clip.id) == null) {
            store.putMessage(
                TextMessage(
                    clip.id, chat, outgoing = outgoingCopy, body = "", at = if (outgoingCopy) clip.sentAt else serverTimestamp,
                    status = MessageStatus.SENT, read = outgoingCopy,
                    author = if (Group.idOf(chat) != null) from else null,
                    clip = Clip(clip.id, Base64.encode(clip.key), kind, clip.durationMs, video = clip.video, urgent = clip.urgent),
                )
            )
        }
        if (outgoingCopy || kind == ClipKind.VOICE) return
        val burst = incoming(clip.id, chat, from)
        burst.key = clip.key
        burst.sentAt = clip.sentAt
        burst.video = clip.video
        // A buffered push read before this key came: its clip now.
        keyCame(burst)
        if (clip.urgent && !burst.urgent) {
            burst.urgent = true
            // Marked urgent after it was saved here: let through after all, if this user allows it.
            if (burst.decidedPlay && !burst.play && !burst.heardLive && burst.told != 0 && !HeardStatus.heard(burst.told)) scope.launch { reconsiderUrgent(burst) }
        }
        // Already playing (the floor's news came first, and it started then): its pictures begin now, before any
        // packet (none opens without this key). 2026-11-22: later bursts showed no video.
        // Heard live through the room (sound only there): its pictures still come from its segments (2026-11-24: the
        // top panel stayed empty while testbot sent video hands-free).
        if (clip.video && burst.heardLive && !burst.playing) videoOut?.begin(burst.id)
        if (clip.video && burst.playing) {
            videoOut?.begin(burst.id)
            _state.update { s -> if (s.playing?.clip == burst.id) s.copy(playing = s.playing.copy(video = true)) else s }
        }
        scope.launch { consider(burst) }
        drain(burst)
    }

    /**
     * [burst] came while this phone can't hear (Do Not Disturb, silent, a call): saved, with a silent "Missed push"
     * and its talker told why; true then. An urgent one the user lets through opens Do Not Disturb and plays.
     */
    private fun saveIfCantHear(burst: Incoming): Boolean {
        val allowed = prefs.urgentThroughDnd(burst.from)
        when (val d = CanHearRules.decide(hearNow(), burst.urgent, allowed, dndGate?.canOpen() == true)) {
            HearDecision.Play -> return false
            HearDecision.ThroughDnd -> {
                if (hearNow() == Hear.DND && !dndOpened && runCatching { dndGate?.open() }.getOrNull() == true) dndOpened = true
                burst.throughDnd = if (hearNow() == Hear.DND) HeardStatus.HEARD_URGENT_DND else HeardStatus.HEARD_URGENT_SILENT
                return false
            }
            is HearDecision.Save -> {
                if (burst.announced && live?.state?.value?.room == burst.chat) live?.silence(burst.chat, "${burst.from}.${burst.device}")
                host?.talkNotify(burst.chat, burst.from, burst.id, burst.video, d.status)
                tell(burst, d.status)
                drain(burst)
                return true
            }
        }
    }

    /** A saved [burst] its talker then marked urgent: played from its start when this user lets urgent ones through. */
    private suspend fun reconsiderUrgent(burst: Incoming) {
        if (saveIfCantHear(burst)) return
        if (!awake() && host?.prepareToPlay(burst.chat, burst.from) != true) return
        burst.play = true
        follow(burst)
        enqueue(burst)
    }

    /** What to tell [burst]'s talker once it is heard here. */
    private fun heardStatus(burst: Incoming): Int = burst.throughDnd.takeIf { it != 0 } ?: HeardStatus.HEARING

    /** Tells [burst]'s talker what became of it here (HeardStatus), once each. */
    private fun tell(burst: Incoming, status: Int) {
        if (burst.told == status) return
        burst.told = status
        scope.launch {
            try {
                sendContent(burst.chat, Content(talkHeard = TalkHeardContent(burst.id, status)), PushKind.NONE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * [from] told what became of this phone's push [heard] at their end (TalkHeardContent): kept on its message
     * (the bubble's "Saved: Do Not Disturb") and in [TalkUiState.heard] (the chips while pushing).
     */
    internal fun takeHeard(from: String, heard: TalkHeardContent) {
        if (!validId(heard.burstId) || heard.status !in HeardStatus.HEARING..HeardStatus.LAST) return
        val message = store.message(heard.burstId)?.takeIf { it.outgoing } ?: return
        val clip = message.clip ?: return
        store.putMessage(message.copy(clip = clip.copy(heardBy = clip.heardBy + (from to heard.status))))
        _state.update { s -> s.copy(heard = s.heard + (heard.burstId to (s.heard[heard.burstId].orEmpty() + (from to heard.status)))) }
        publish()
    }

    /** Whether to play [burst] now, asked once its conversation is known. */
    private suspend fun consider(burst: Incoming) {
        if (burst.decidedPlay || burst.chat.isEmpty()) return
        burst.decidedPlay = true
        val age = now() - (burst.sentAt.takeIf { it > 0 } ?: burst.createdAt)
        val fresh = burst.announced || age < PLAY_IF_NEWER
        // Do Not Disturb, silent, a call (2026-11-25, hen): not out loud, saved, unless it is urgent and let through.
        if (fresh && saveIfCantHear(burst)) return
        // Readied to play off screen (the talk session or Talk ready) for the room's audio, if it was.
        var prepared: Boolean? = null
        // In the conversation's room the talker may be heard live already (their phone doesn't wait for the grant).
        if (fresh && burst.announced && live?.audio == true && live?.state?.value?.room == burst.chat) {
            val identity = "${burst.from}.${burst.device}"
            // Their last burst may have been silenced (its clip played instead): this one is heard again.
            live?.unsilence(burst.chat, identity)
            // Off screen, the talk session or Talk ready comes first, before any of it plays (2026-11-27: Android mutes
            // an app's sound in the background without one, "AudioHardening background playback muted"; it was asked
            // only once the room's audio was already playing).
            prepared = if (!awake()) runCatching { host?.prepareToPlay(burst.chat, burst.from) }.getOrNull() else null
            var waited = 0L
            while (live?.hearsLive(burst.chat, identity) != true && waited < LIVE_WAIT) {
                delay(50)
                waited += 50
            }
            if (live?.hearsLive(burst.chat, identity) == true) {
                burst.heardLive = true
                live?.heard(waited)
                tell(burst, heardStatus(burst))
                // Others' music dims while it's heard (2026-11-25, hen).
                dimmer.heard("live:${burst.id}")
                // Heard through the room, the app off screen: the same as a burst that plays (the talk session or
                // Talk ready for its sound, the floating window) (2026-11-25, hen: "i didn't see the testbot push").
                if (!awake() && prepared == null) runCatching { host?.prepareToPlay(burst.chat, burst.from) }
                if (burst.video) videoOut?.begin(burst.id)
                sendFrame(TalkAckFrame(TalkAck(burst.id)))
                return drain(burst)
            }
            live?.silence(burst.chat, identity)
        }
        when {
            !fresh -> {
                // Too late to play as it happens, nobody looking: missed.
                if (!awake()) host?.talkNotify(burst.chat, burst.from, burst.id, burst.video, 0)
                return drain(burst)
            }
            awake() -> burst.play = true
            prefs.notifyOnly(burst.chat) -> host?.talkNotify(burst.chat, burst.from, burst.id, burst.video, 0)
            // Already readied above, for the room's audio: not asked twice (a second talk session).
            prepared == true -> burst.play = true
            host?.prepareToPlay(burst.chat, burst.from) == true -> burst.play = true
            else -> host?.talkNotify(burst.chat, burst.from, burst.id, burst.video, 0)
        }
        if (!burst.play) return drain(burst)
        if (burst.announced) sendFrame(TalkAckFrame(TalkAck(burst.id)))
        // Woken, or late: nothing forwarded comes, so read the server's copy from its start.
        if (!burst.announced || burst.ended) follow(burst)
        enqueue(burst)
    }

    private fun follow(burst: Incoming) {
        if (burst.fetch?.isActive == true) return
        burst.fetch = scope.launch {
            try {
                val reader = ClipRecords.Reader()
                blobs.read(burst.id) { bytes ->
                    reader.feed(bytes).forEach { onSegment(it.copy(burstId = burst.id), live = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The server has no copy (gone, or not allowed): what came live is all there is.
                if (burst.lastIndex == null) burst.lastIndex = burst.next - 1
                drain(burst)
            }
        }
    }

    private fun enqueue(burst: Incoming) {
        val now = _state.value
        if (now.playing == null) start(burst) else if (burst.id !in now.queued && now.playing.clip != burst.id) {
            _state.update { it.copy(queued = it.queued + burst.id) }
        }
    }

    private fun start(burst: Incoming) {
        val player = player ?: return
        // Heard while it plays (a played burst's end is its playback's, not its floor's).
        dimmer.heard("play:${burst.id}")
        tell(burst, heardStatus(burst))
        burst.playing = true
        _state.update { it.copy(playing = NowPlaying(burst.chat, burst.id, burst.from, live = true, video = burst.video), queued = it.queued - burst.id) }
        if (burst.video) videoOut?.begin(burst.id)
        burst.playback = player.open(
            // With video, at its own pace: catching up would leave the pictures behind.
            live = true, catchUp = if (burst.video) CatchUpMode.OFF else prefs.catchUp,
            onProgress = { played, behind -> scope.launch { progress(burst.id, played, behind) } },
            onDone = { scope.launch { done(burst.id) } },
        )
        drain(burst)
    }

    /** Plays what can be played of [burst], in order; keeps the clip once it is whole. */
    private fun drain(burst: Incoming) {
        val key = burst.key ?: return
        // Waiting its turn to play (or for the decision): its segments wait too.
        if ((burst.play || !burst.decidedPlay) && !burst.playing) return
        while (true) {
            val segment = burst.segments[burst.next] ?: break
            val packets = ClipCrypto.open(key, burst.id, segment.index, segment.last, segment.ciphertext)
            if (packets == null) {
                // Not this clip's (a forged or moved segment): skipped.
                burst.segments.remove(burst.next)
                if (segment.last) burst.lastIndex = null
                break
            }
            val audio = if (burst.video) {
                val pictures = burst.playing || burst.heardLive
                burst.splitter.split(packets, { frame -> if (pictures) videoOut?.frame(burst.id, frame) }) { face -> if (pictures) videoOut?.face(burst.id, face) }
            } else {
                packets
            }
            burst.frames += audio.size
            if (burst.playing) burst.playback?.write(audio)
            burst.next++
            if (segment.last) break
        }
        val last = burst.lastIndex ?: return
        if (burst.next <= last) return
        if (burst.playing) burst.playback?.finish()
        if (burst.heardLive && burst.video && !burst.playing) videoOut?.end(burst.id)
        if (burst.heardLive) dimmer.quiet("live:${burst.id}")
        keep(burst)
    }

    private fun keep(burst: Incoming) {
        if (burst.kept) return
        val last = burst.lastIndex ?: return
        val segments = (0..last).map { burst.segments[it] ?: return }
        burst.kept = true
        burst.fetch?.cancel()
        clips.write(burst.id, concat(segments.map { ClipRecords.record(it.index, it.last, it.ciphertext) }))
        store.message(burst.id)?.let { m ->
            store.putMessage(m.copy(clip = m.clip?.let { c -> c.copy(durationMs = burst.frames.toLong() * TalkAudioFormat.FRAME_MS, kept = true, heard = c.heard || burst.heardLive) }, read = m.read || burst.play || burst.heardLive))
        }
        publish()
    }

    // --- Playing from the history ---------------------------------------------------------------------------------

    private var historyPlayback: Playback? = null

    /** Plays a clip of the chat (a burst or a voice message), from this phone's copy or the server's. */
    fun play(id: String) {
        val message = store.message(id) ?: return
        val clip = message.clip ?: return
        stopPlaying()
        val player = player ?: return
        val from = if (message.outgoing) me()?.user.orEmpty() else message.author ?: message.user
        _state.update { it.copy(playing = NowPlaying(message.user, id, from, live = false, video = clip.video)) }
        if (clip.video) videoOut?.begin(id)
        val splitter = TalkVideo.Splitter()
        val playback = player.open(
            live = false, catchUp = CatchUpMode.OFF,
            onProgress = { played, behind -> scope.launch { progress(id, played, behind) } },
            onDone = { scope.launch { done(id) } },
        )
        historyPlayback = playback
        val key = Base64.decode(clip.key)
        scope.launch {
            val bytes = clips.read(id) ?: try {
                val buffer = mutableListOf<ByteArray>()
                blobs.read(id) { buffer += it }
                concat(buffer).also { whole -> if (runCatching { ClipRecords.parse(whole) }.getOrNull()?.any { it.last } == true) clips.write(id, whole) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val segments = bytes?.let { runCatching { ClipRecords.parse(it) }.getOrNull() }.orEmpty()
            var frames = 0
            for (segment in segments.sortedBy { it.index }) {
                val packets = ClipCrypto.open(key, id, segment.index, segment.last, segment.ciphertext) ?: break
                val audio = if (clip.video) splitter.split(packets, { frame -> videoOut?.frame(id, frame) }) { face -> videoOut?.face(id, face) } else packets
                frames += audio.size
                playback.write(audio)
            }
            playback.finish()
            store.message(id)?.let { m ->
                val c = m.clip ?: return@let
                store.putMessage(m.copy(read = true, clip = c.copy(heard = true, kept = bytes != null, durationMs = c.durationMs.takeIf { it > 0 } ?: frames.toLong() * TalkAudioFormat.FRAME_MS)))
                publish()
            }
        }
    }

    /** [id]'s burst or voice message, from this phone's copy or the server's, opened (Save as file; 2026-11-23, hen). */
    suspend fun clipContents(id: String): ClipContents? {
        val message = store.message(id) ?: return null
        val clip = message.clip ?: return null
        val key = Base64.decode(clip.key)
        val bytes = clips.read(id) ?: try {
            val buffer = mutableListOf<ByteArray>()
            blobs.read(id) { buffer += it }
            // Kept only when whole (its last segment there): a part of one isn't kept for good.
            concat(buffer).also { whole -> if (runCatching { ClipRecords.parse(whole) }.getOrNull()?.any { it.last } == true) clips.write(id, whole) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val segments = runCatching { ClipRecords.parse(bytes) }.getOrNull() ?: return null
        val splitter = TalkVideo.Splitter()
        val audio = ArrayList<ByteArray>()
        val frames = ArrayList<TalkVideo.Frame>()
        for (segment in segments.sortedBy { it.index }) {
            val packets = ClipCrypto.open(key, id, segment.index, segment.last, segment.ciphertext) ?: break
            audio += if (clip.video) splitter.split(packets, { frames += it }) else packets
        }
        return ClipContents(audio, frames, message.at)
    }

    /** Stops what is playing (and what waits to play). */
    fun stopPlaying() {
        _state.value.playing?.takeIf { it.video }?.let { videoOut?.end(it.clip) }
        historyPlayback?.stop()
        historyPlayback = null
        val stopped = _state.value.playing?.clip
        stopped?.let { bursts[it]?.playback?.stop() }
        _state.update { it.copy(playing = null, queued = emptyList()) }
        stopped?.let { dimmer.quiet("play:$it") }
    }

    private fun progress(id: String, played: Long, behind: Long) {
        if (_state.value.playing?.let { it.clip == id && it.video } == true) videoOut?.position(id, played)
        _state.update { s -> if (s.playing?.clip == id) s.copy(playing = s.playing.copy(playedMs = played, behindMs = behind)) else s }
    }

    private fun done(id: String) {
        dimmer.quiet("play:$id")
        if (_state.value.playing?.let { it.clip == id && it.video } == true) videoOut?.end(id)
        bursts[id]?.let {
            it.playing = false
            it.playback = null
            store.message(id)?.let { m -> m.clip?.let { c -> store.putMessage(m.copy(read = true, clip = c.copy(heard = true))) } }
            publish()
        }
        if (_state.value.playing?.clip == id) {
            if (historyPlayback != null) historyPlayback = null
            _state.update { it.copy(playing = null) }
        }
        val next = _state.value.queued.firstOrNull()?.let { bursts[it] }
        if (next != null) {
            start(next)
        } else {
            host?.idle()
        }
    }

    /** Something plays, is being recorded, or is about to: a woken phone stays connected for it. */
    val busy: Boolean
        get() {
            val s = _state.value
            return s.playing != null || s.queued.isNotEmpty() || outgoing != null || s.talkers.isNotEmpty() ||
                bursts.values.any { it.play && !it.kept && now() - it.createdAt < KEEP_BURST }
        }

    private fun talkStart(chat: String, id: String, urgent: Boolean): TalkStart {
        val group = Group.idOf(chat)
        return if (group != null) TalkStart(id, group = group, urgent = urgent) else TalkStart(id, peer = chat, urgent = urgent)
    }

    private fun newClipId(): String = random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        /**
         * A segment's packets are cut before this many bytes when the camera's pieces fill it (the server takes 64 KiB
         * since bf1b11e; a key frame can be 20 to 40 KB).
         */
        const val SEGMENT_BYTES = 48_000

        /** The first segment goes after this many 20 ms frames (60 ms), the rest after a full 200 ms. */
        const val FIRST_SEGMENT_FRAMES = 3

        /** Someone's floor shown at most this long (the server's 60 s limit, and some): a lost end can't block it. */
        const val STALE_TALKER_MS = 75_000L

        /** A burst whose floor ended and that got nothing more for this long stops playing (it isn't live any more). */
        const val END_STUCK_MS = 6_000L

        /** A buffered push whose key is late is held this long for it. */
        const val LATE_KEY_MS = 24 * 3_600_000L

        /** How often the watchdog looks for talkers gone quiet (TalkBeat.LOST_MS). */
        const val WATCH_EVERY = 500L

        /**
         * Push-to-video's video in one burst, at most: the server keeps a clip up to 8 MiB, and the voice and the
         * records' framing need some of it. At 30 fps and 700 kbit/s that is about 85 seconds; at 60 fps and 1 Mbit/s,
         * about 60. Past it the burst goes on with the voice only.
         */
        const val VIDEO_BUDGET = 7_400_000L

        /** How long a press waits for the floor before it records a voice message instead. */
        const val GRANT_WAIT = 3_000L
        /** How long a buffered push waits for its burst's key (its "clip start" message). */
        const val BUFFER_KEY_WAIT = 5_000
        /** How long a grant waits to learn whether everyone hears it in the room (else the clip goes). */
        const val CLIP_DECIDE_MS = 500L

        /** A clip shorter than this that never went live isn't sent (a tap, not a message). */
        const val MIN_CLIP_MS = 400L

        /** How long Busy or a refusal shows on the button. */
        const val SHOW_REFUSAL = 2_500L

        /** A burst whose start this phone didn't see plays by itself only when newer than this (the plan's 30 s). */
        const val PLAY_IF_NEWER = 30_000L

        /** How long a burst is remembered for its segments and key to meet. */
        const val KEEP_BURST = 120_000L

        /** How long after ENDED the last segment may still come forwarded, before the server's copy is read. */
        const val END_GRACE = 1_500L

        /** How long a phone in the room waits at a STARTED for the talker's live audio before it plays the clip. */
        const val LIVE_WAIT = 400L

        /** How many segments past the next one to play mean some were missed. */
        const val GAP = 2

        /** At most this many segments wait for a key (a minute and a half of audio). */
        const val MAX_BUFFERED = 450

        fun validId(id: String): Boolean = id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }

        @Suppress("unused")
        private val refusals = listOf(SendStatus.NOT_PAIRED, SendStatus.BLOCKED, SendStatus.NOT_MEMBER, SendStatus.UNKNOWN_USER, SendStatus.INVALID)
    }
}

internal fun concat(parts: List<ByteArray>): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var at = 0
    for (p in parts) {
        p.copyInto(out, at)
        at += p.size
    }
    return out
}
