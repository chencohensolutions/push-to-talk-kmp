package com.chencohen.ptt.talk

import com.chencohen.ptt.messaging.Content
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.LiveToken
import com.chencohen.ptt.messaging.TalkKeyContent
import com.chencohen.ptt.relay.PushKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The live path's carrier (the plan's `TalkTransport`): LiveKit on Android. Floor control, clips and FCM stay on the
 * clip path, so another transport (phase 7's QUIC) would only replace this.
 */
interface TalkTransport {
    /**
     * Joins a room at [url] (the server's own address; LiveKit's client adds the path) with [token], encrypting this
     * device's audio with [ownKey] (key index [ownIndex]) as [identity]. False when it can't.
     */
    suspend fun join(url: String, token: String, identity: String, ownKey: ByteArray, ownIndex: Int): Boolean

    fun leave()

    /** Another device's key ("user.device"), for its audio in the room. */
    fun setKey(identity: String, key: ByteArray, index: Int)

    /** The microphone goes out to the room (a press) or stops. */
    suspend fun setTalking(on: Boolean)

    /** The push-to-video camera goes out to the room too (a video burst) or stops. */
    suspend fun setVideo(on: Boolean) {}

    /** The camera is warm ([on]): the room's camera track reads it, muted, so a video burst only unmutes it. */
    suspend fun readyVideo(on: Boolean) {}

    /** [identity]'s audio is coming through the room now, and opens (its key works). */
    fun hears(identity: String): Boolean

    /** [identity]'s current live audio isn't played (its clip plays instead); its next burst is heard again. */
    fun silence(identity: String)

    /** The room's events: someone joined, someone's live audio started. */
    var listener: TalkTransportListener?

    /** While in a room, the clip records from the room's own microphone (so it isn't opened twice). */
    val capture: TalkCapture
}

interface TalkTransportListener {
    /** A device joined the room: it needs this device's key. */
    fun joined(identity: String)

    /** [identity]'s audio started coming through (unmuted and decrypting). */
    fun liveAudio(identity: String)

    /** The room was lost (the server took this device out, or the network). */
    fun left()
}

/** Where the live path stands, for the debug readout and the Talk screen. */
data class LiveState(
    /** The chat whose room this phone is in; null when none. */
    val room: String? = null,
    /** From a burst's STARTED to its live audio: the press-to-hear on this phone (debug builds show it). */
    val lastLatencyMs: Long? = null,
    val joining: Boolean = false,
)

/**
 * Live talk (docs/design.md, "Phase 5b"; the server's docs/messaging.md, "Live talk"): one LiveKit room per
 * conversation, at most one at a time. The phone joins the room of the conversation on screen, of Talk mode, or of a
 * burst that just came, and leaves 60 s after the last burst once that conversation is off screen. Each device has its
 * own key for its audio in a room, sent end to end to the conversation when it joins and when someone else joins, so
 * LiveKit only relays ciphertext. The clip path still runs for every burst; a listener that hears the talker live
 * doesn't play the clip.
 */
internal class LiveTalk(
    private val scope: CoroutineScope,
    private val tokens: suspend (peer: String?, group: String?) -> LiveToken,
    private val available: suspend () -> Boolean,
    private val serverUrl: () -> String,
    private val sendContent: suspend (chat: String, content: Content, pushKind: Int) -> Unit,
    private val now: () -> Long,
    private val random: Random,
    /**
     * Talk's voice goes through the room too (off since 1.4.0: LIVE_TALK_AUDIO). Off, the room carries only
     * push-to-video's pictures (2026-11-24, hen): the voice stays on the clip path, from the first word to the last.
     */
    val audio: Boolean = true,
) {
    var transport: TalkTransport? = null
        set(value) {
            field = value
            value?.listener = events
        }

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    /** Others' keys, by "user.device", as they last sent them: set again on each join. */
    private val keys = mutableMapOf<String, Pair<ByteArray, Int>>()
    private var ownKey: ByteArray? = null
    private var ownIndex = 0
    private var identity: String? = null
    private var leaving: Job? = null
    private var onScreen: String? = null
    private var lastBurst = 0L
    private var liveOn: Boolean? = null
    private val startedAt = mutableMapOf<String, Long>()

    /** The chat on screen (a conversation, or its Talk mode); null when none is. Its room is joined. */
    fun show(chat: String?) {
        onScreen = chat
        if (chat != null) scope.launch { enter(chat) } else leaveLater()
    }

    /** A burst started in [chat] (someone else's, or this phone's own press): its room is joined for the next one. */
    fun burst(chat: String, talker: String?) {
        lastBurst = now()
        if (talker != null) startedAt[talker] = now()
        if (_state.value.room != chat) scope.launch { enter(chat) }
        leaveLater()
    }

    /** In [chat]'s room, the microphone goes there too, and the camera with a video burst ([video]). */
    suspend fun talking(chat: String, on: Boolean, video: Boolean = false) {
        val t = transport ?: return
        // A video burst from outside the chat (the floating window): the room is still being joined; wait a little.
        if (on && video && _state.value.room != chat) {
            var waited = 0
            while (_state.value.room != chat && waited < JOIN_WAIT) {
                delay(100)
                waited += 100
            }
        }
        if (_state.value.room != chat) return
        if (audio) runCatching { t.setTalking(on) }
        if (on && video) runCatching { t.setVideo(true) } else if (!on) runCatching { t.setVideo(false) }
    }

    /** The camera is warm for [chat] (null: not): its room's camera track reads it, muted, ready for a video burst. */
    fun readyVideo(chat: String?) {
        val t = transport ?: return
        val on = chat != null && _state.value.room == chat
        scope.launch { runCatching { t.readyVideo(on) } }
    }

    /** The microphone of the room [chat] is in; null when not in it (the app's own recorder is used). */
    fun captureFor(chat: String): TalkCapture? = transport?.takeIf { audio && _state.value.room == chat && identity != null }?.capture

    /** [identity] ("user.device") is heard live in [chat]'s room: its clip needn't play. */
    fun hearsLive(chat: String, identity: String): Boolean = audio && _state.value.room == chat && transport?.hears(identity) == true

    /** The clip of [identity]'s burst plays: its live audio, if it comes late, isn't played over it. */
    fun silence(chat: String, identity: String) {
        if (audio && _state.value.room == chat) transport?.silence(identity)
    }

    /** Another device's key came (Content.talkKey). */
    fun takeKey(identity: String, key: TalkKeyContent) {
        if (key.key.size != KEY_BYTES || key.index !in 0 until KEY_RING) return
        keys[identity] = key.key to key.index
        transport?.setKey(identity, key.key, key.index)
    }

    private suspend fun enter(chat: String) {
        val t = transport ?: return
        leaving?.cancel()
        if (_state.value.room == chat || _state.value.joining) return
        if (liveOn == null) liveOn = runCatching { available() }.getOrDefault(false)
        if (liveOn != true) return
        _state.update { it.copy(joining = true) }
        try {
            if (_state.value.room != null) {
                t.leave()
                _state.update { it.copy(room = null) }
            }
            val group = Group.idOf(chat)
            val token = tokens(if (group == null) chat else null, group)
            val key = random.nextBytes(KEY_BYTES)
            ownKey = key
            ownIndex = (ownIndex + 1) % KEY_RING
            identity = token.identity
            if (!t.join(origin(serverUrl()), token.token, token.identity, key, ownIndex)) return
            keys.forEach { (who, k) -> t.setKey(who, k.first, k.second) }
            _state.update { it.copy(room = chat) }
            sendKey(chat)
            leaveLater()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // LIVE_OFF, not allowed, or LiveKit unreachable: talk goes by the clip path.
            if ((e as? com.chencohen.ptt.messaging.ApiException)?.code == "LIVE_OFF") liveOn = false
        } finally {
            _state.update { it.copy(joining = false) }
        }
    }

    private suspend fun sendKey(chat: String) {
        val key = ownKey ?: return
        try {
            // Stale by the time a sleeping phone wakes: it gets a new one when it joins.
            sendContent(chat, Content(talkKey = TalkKeyContent(key, ownIndex)), PushKind.NONE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun leaveLater() {
        leaving?.cancel()
        val room = _state.value.room ?: return
        leaving = scope.launch {
            while (true) {
                delay(STAY_AFTER_BURST)
                if (onScreen != room && now() - lastBurst >= STAY_AFTER_BURST) break
            }
            transport?.leave()
            _state.update { it.copy(room = null) }
        }
    }

    /** Leaves at once (the app's connection stopped for good, or revoked). */
    fun leaveNow() {
        leaving?.cancel()
        transport?.leave()
        _state.update { it.copy(room = null) }
    }

    private val events = object : TalkTransportListener {
        override fun joined(identity: String) {
            val room = _state.value.room ?: return
            scope.launch { sendKey(room) }
        }

        override fun liveAudio(identity: String) {
            val user = identity.substringBeforeLast('.')
            val start = startedAt.remove(user) ?: return
            scope.launch { _state.update { it.copy(lastLatencyMs = now() - start) } }
        }

        override fun left() {
            scope.launch { _state.update { it.copy(room = null) } }
        }
    }

    companion object {
        const val KEY_BYTES = 32

        /** How many keys of a device a room keeps (LiveKit's key ring); the key index goes round within it. */
        const val KEY_RING = 16

        /** How long the room is kept after the last burst once the conversation is off screen. */
        const val STAY_AFTER_BURST = 60_000L

        /** How long a video burst waits for the room to be joined before its pictures go by the clips only. */
        const val JOIN_WAIT = 3_000

        /** `wss://host:port` of the GraphQL endpoint `https://host:port/query`. */
        fun origin(serverUrl: String): String = "wss://" + serverUrl.removePrefix("https://").substringBefore('/')
    }
}
