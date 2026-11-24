package com.chencohen.ptt.talk

import com.chencohen.ptt.messaging.Clip
import com.chencohen.ptt.messaging.ClipContent
import com.chencohen.ptt.messaging.ClipKind
import com.chencohen.ptt.messaging.Content
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.MessageStatus
import com.chencohen.ptt.messaging.MessagingStore
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.relay.ClipSegment
import com.chencohen.ptt.relay.ClipSegmentFrame
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
    /** Who holds the floor in each conversation now, by chat. */
    val talkers: Map<String, Talker> = emptyMap(),
    val own: OwnTalk? = null,
    val playing: NowPlaying? = null,
    /** Clips waiting to play after [playing], oldest first. */
    val queued: List<String> = emptyList(),
    /** Both kept pushing at once (3 times within a minute) in this chat: a video call may suit them better. */
    val suggestCall: String? = null,
)

/** The platform's part in playing talk when the app isn't on screen (Android: Telecom, notifications). */
interface TalkHost {
    /**
     * A burst in [chat] from [from] should be heard while the app isn't on screen: true when the phone may play it now
     * (Stay connected, or a talk session started); false shows [talkNotify] instead.
     */
    suspend fun prepareToPlay(chat: String, from: String): Boolean

    /** Tell the user someone talked (Notify only, or the phone couldn't play): a notification with Play and Reply. */
    fun talkNotify(chat: String, from: String, clip: String)

    /** Nothing plays now; a talk session may end (20 s after, the plan says, so a reply can follow). */
    fun idle()
}

/** What Talk needs to know of the user's choices (PttSettings). */
interface TalkPrefs {
    val catchUp: CatchUpMode

    /** The user chose Notify only for [chat] in When I'm away. */
    fun notifyOnly(chat: String): Boolean

    /** Settings › Talk › Unmute for incoming talk (TalkUnmute). */
    val unmute: Boolean get() = false
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
    var player: TalkPlayer? = null
    var host: TalkHost? = null

    /** The media volume, for Unmute for incoming talk; null: left alone. */
    var volume: TalkVolume? = null
    private val unmute = TalkUnmute({ volume }, { prefs.unmute })

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
        val startedAt = now()
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
        var consumer: Job? = null
        var mic: TalkCapture? = null
        /** The camera goes too (push-to-video): every packet is tagged (TalkVideo). */
        var video = false
        var videoNumber = 0
        /** The video's bytes so far: past [VIDEO_BUDGET] the burst goes on with the voice only. */
        var videoBytes = 0L
        var pendingBytes = 0
        var pendingAudio = 0
    }

    private var outgoing: Outgoing? = null

    /** The Talk button went down in [chat] (a user, or a group's chat). */
    fun press(chat: String) = begin(chat, voiceOnly = false)

    /** A Talk screen is on screen ([on]) or left: the microphone is kept open for an instant press, or closed. */
    fun warmMicrophone(on: Boolean) {
        val c = capture ?: return
        if (on) c.warm() else c.cool()
    }

    /** The composer's microphone went down: a voice message, without the floor. */
    fun startVoice(chat: String) = begin(chat, voiceOnly = true)

    private fun begin(chat: String, voiceOnly: Boolean) {
        if (outgoing != null) return
        // In the conversation's room, the room's microphone feeds the clip too.
        val mic = captureOverride ?: (if (voiceOnly) null else live?.captureFor(chat)) ?: capture ?: return refuse(chat, OwnTalk.MIC)
        val out = Outgoing(chat, voiceOnly)
        out.mic = mic
        stopPlaying()
        // The camera first, so the voice's packets are tagged from the first one.
        val camera = videoCapture?.takeIf { !voiceOnly && sendVideo(chat) }
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
            scope.launch { live?.talking(chat, true) }
        }
        out.consumer = scope.launch { consume(out) }
        if (!voiceOnly) {
            if (connected()) {
                scope.launch {
                    if (!sendFrame(TalkStartFrame(talkStart(chat, out.id)))) toVoice(out)
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
        if (!send) out.cancelled = true
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
        if (out.granted) drain(out)
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

    /** Not connected, or the connection broke: what is recorded goes as a voice message instead. */
    private fun toVoice(out: Outgoing) {
        if (out.phase == TalkPhase.BUSY || out.phase == TalkPhase.REFUSED) return
        out.decided = true
        if (out.granted) scope.launch { sendFrame(TalkEndFrame(TalkEnd(out.id))) }
        out.granted = false
        out.phase = TalkPhase.OFFLINE
        out.unsent.clear()
        if (outgoing === out) showOwn(out)
        if (out.finished) scope.launch { finish(out) }
    }

    private suspend fun finish(out: Outgoing) {
        if (!out.finished) return
        // Still waiting for the server's answer: finished once it comes (or GRANT_WAIT passes).
        if (!out.decided) return
        if (outgoing === out) outgoing = null
        val duration = out.frames.toLong() * TalkAudioFormat.FRAME_MS
        when {
            out.phase == TalkPhase.BUSY || out.phase == TalkPhase.REFUSED -> Unit
            out.cancelled || duration < MIN_CLIP_MS && !out.granted -> {
                if (out.granted) sendFrame(TalkEndFrame(TalkEnd(out.id)))
            }
            out.granted -> {
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
        // The chat's history: the burst, kept as this phone records it.
        store.putMessage(
            TextMessage(
                out.id, out.chat, outgoing = true, body = "", at = out.startedAt, status = MessageStatus.SENT,
                author = if (Group.idOf(out.chat) != null) me()?.user else null,
                clip = Clip(out.id, Base64.encode(out.key), ClipKind.BURST, video = out.video),
            )
        )
        publish()
        scope.launch {
            // What was recorded before the grant, then the rest as it comes (in order: drain).
            drain(out)
            if (out.finished) finish(out)
        }
        scope.launch {
            try {
                sendContent(out.chat, Content(clip = ClipContent(out.id, out.key, ClipContent.BURST, out.startedAt, video = out.video)), PushKind.TALK)
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
        _state.update { it.copy(own = OwnTalk(out.chat, out.id, phase, out.startedAt, busyWith, refusal)) }
        clearOwnLater()
    }

    private fun showOwn(out: Outgoing) {
        _state.update { it.copy(own = OwnTalk(out.chat, out.id, out.phase, out.startedAt)) }
    }

    private fun clearOwnLater() {
        val shown = _state.value.own
        scope.launch {
            delay(SHOW_REFUSAL)
            _state.update { if (it.own == shown) it.copy(own = null) else it }
        }
    }

    // --- The floor's news -----------------------------------------------------------------------------------------

    /** A TalkState or ClipSegment frame from /ws. */
    fun onFrame(kind: FrameKind) {
        when (kind) {
            is TalkStateFrame -> onState(kind.talkState)
            is ClipSegmentFrame -> onSegment(kind.clipSegment, live = true)
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
                // Their floor: keep recording, and ask again the moment it ends.
                o.waiting = true
                o.phase = TalkPhase.WAITING
                _state.update { it.copy(own = OwnTalk(o.chat, o.id, TalkPhase.WAITING, o.startedAt, s.talkerUser)) }
                scope.launch { live?.talking(o.chat, false) }
                noteOverlap(o.chat)
                scope.launch {
                    // Their floor can't outlast the server's limit; if no end comes, it goes as a voice message.
                    delay(STALE_TALKER_MS)
                    if (o.waiting) {
                        o.waiting = false
                        toVoice(o)
                    }
                }
            }
            TalkEvent.REFUSED -> out?.let {
                // Refused after the floor was given (a segment the server couldn't take): let go of it at once.
                if (it.granted) scope.launch { sendFrame(TalkEndFrame(TalkEnd(it.id))) }
                it.granted = false
                lost(it, TalkPhase.REFUSED, refusal = s.refusal)
            }
            TalkEvent.STARTED -> {
                _state.update { it.copy(talkers = it.talkers + (chat to Talker(s.talkerUser, s.talkerDevice, s.burstId, s.startedAt.takeIf { t -> t > 0 } ?: now()))) }
                // A floor never longer than the server's limit: if its end never came (a dropped frame), it goes anyway.
                scope.launch {
                    delay(STALE_TALKER_MS)
                    _state.update { st -> if (st.talkers[chat]?.clip == s.burstId) st.copy(talkers = st.talkers - chat) else st }
                }
                val self = me() ?: return
                // Another phone of this user talking: shown, not played.
                if (s.talkerUser == self.user) return
                live?.burst(chat, s.talkerUser)
                val burst = incoming(s.burstId, chat, s.talkerUser)
                burst.announced = true
                burst.device = s.talkerDevice
                scope.launch { consider(burst) }
            }
            TalkEvent.ENDED -> {
                val talker = _state.value.talkers[chat]?.takeIf { it.clip == s.burstId }
                _state.update { state -> state.copy(talkers = state.talkers.filterValues { it.clip != s.burstId }) }
                // Heard live (LiveKit): heard, and, with no clip stream counted, as long as the floor was held.
                if (talker != null) {
                    store.message(s.burstId)?.let { m ->
                        val c = m.clip
                        val heardLive = bursts[s.burstId]?.heardLive == true
                        if (c != null && (c.durationMs <= 0 || heardLive && !c.heard)) {
                            val length = c.durationMs.takeIf { it > 0 } ?: (now() - talker.since).coerceAtLeast(TalkAudioFormat.FRAME_MS.toLong())
                            store.putMessage(m.copy(read = m.read || heardLive, clip = c.copy(durationMs = length, heard = c.heard || heardLive)))
                            publish()
                        }
                    }
                }
                // Someone else's floor ended: a burst waiting for it asks now (sent right after theirs).
                outgoing?.takeIf { it.waiting && it.chat == chat && it.id != s.burstId }?.let { w ->
                    w.waiting = false
                    w.phase = TalkPhase.REQUESTING
                    showOwn(w)
                    scope.launch { if (!sendFrame(TalkStartFrame(talkStart(w.chat, w.id)))) toVoice(w) }
                    scope.launch {
                        delay(GRANT_WAIT)
                        if (!w.decided && !w.waiting) toVoice(w)
                    }
                }
                if (out != null && s.endReason != TalkEndReason.RELEASED) {
                    // The server ended it (60 s, or the connection dropped): what is recorded until now is the clip.
                    if (s.endReason == TalkEndReason.DROPPED) toVoice(out) else release()
                }
                bursts[s.burstId]?.let { burst ->
                    burst.ended = true
                    // Ended without its last segment here: the server's copy has the rest.
                    if (burst.lastIndex == null) scope.launch { delay(END_GRACE); if (burst.lastIndex == null) follow(burst) }
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
        val splitter = TalkVideo.Splitter()
        val createdAt = now()
    }

    private val bursts = mutableMapOf<String, Incoming>()

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
                    clip = Clip(clip.id, Base64.encode(clip.key), kind, clip.durationMs, video = clip.video),
                )
            )
        }
        if (outgoingCopy || kind == ClipKind.VOICE) return
        val burst = incoming(clip.id, chat, from)
        burst.key = clip.key
        burst.sentAt = clip.sentAt
        burst.video = clip.video
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

    /** Whether to play [burst] now, asked once its conversation is known. */
    private suspend fun consider(burst: Incoming) {
        if (burst.decidedPlay || burst.chat.isEmpty()) return
        burst.decidedPlay = true
        val age = now() - (burst.sentAt.takeIf { it > 0 } ?: burst.createdAt)
        val fresh = burst.announced || age < PLAY_IF_NEWER
        // In the conversation's room the talker may be heard live already (their phone doesn't wait for the grant).
        if (fresh && burst.announced && live?.state?.value?.room == burst.chat) {
            val identity = "${burst.from}.${burst.device}"
            var waited = 0L
            while (live?.hearsLive(burst.chat, identity) != true && waited < LIVE_WAIT) {
                delay(50)
                waited += 50
            }
            if (live?.hearsLive(burst.chat, identity) == true) {
                burst.heardLive = true
                if (burst.video) videoOut?.begin(burst.id)
                sendFrame(TalkAckFrame(TalkAck(burst.id)))
                return drain(burst)
            }
            live?.silence(burst.chat, identity)
        }
        when {
            !fresh -> return drain(burst)
            awake() -> burst.play = true
            prefs.notifyOnly(burst.chat) -> host?.talkNotify(burst.chat, burst.from, burst.id)
            host?.prepareToPlay(burst.chat, burst.from) == true -> burst.play = true
            else -> host?.talkNotify(burst.chat, burst.from, burst.id)
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
        unmute.begin()
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
        _state.value.playing?.let { bursts[it.clip]?.playback?.stop() }
        _state.update { it.copy(playing = null, queued = emptyList()) }
        unmute.end()
    }

    private fun progress(id: String, played: Long, behind: Long) {
        if (_state.value.playing?.let { it.clip == id && it.video } == true) videoOut?.position(id, played)
        _state.update { s -> if (s.playing?.clip == id) s.copy(playing = s.playing.copy(playedMs = played, behindMs = behind)) else s }
    }

    private fun done(id: String) {
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
            unmute.end()
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

    private fun talkStart(chat: String, id: String): TalkStart {
        val group = Group.idOf(chat)
        return if (group != null) TalkStart(id, group = group) else TalkStart(id, peer = chat)
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

        /**
         * Push-to-video's video in one burst, at most: the server keeps a clip up to 8 MiB, and the voice and the
         * records' framing need some of it. At 30 fps and 700 kbit/s that is about 85 seconds; at 60 fps and 1 Mbit/s,
         * about 60. Past it the burst goes on with the voice only.
         */
        const val VIDEO_BUDGET = 7_400_000L

        /** How long a press waits for the floor before it records a voice message instead. */
        const val GRANT_WAIT = 3_000L

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
