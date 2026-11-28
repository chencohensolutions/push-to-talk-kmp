package com.chencohen.ptt.talk

import com.chencohen.ptt.ios.IosTalkRoom
import com.chencohen.ptt.ios.IosTalkRoomEvents
import com.chencohen.ptt.ios.toByteArray
import com.chencohen.ptt.ios.toNSData
import com.chencohen.ptt.util.SyncLock
import com.chencohen.ptt.util.locked
import com.chencohen.ptt.util.logFailures
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSData

/**
 * The live path on iOS: the rules of Android's LiveKitTalkTransport over a room Swift holds ([IosTalkRoom], LiveKit's
 * Swift SDK).
 *
 * - Joined listening only. The microphone's track is published, muted, while a Push button is on screen
 *   ([prepareAudio]), so a press only unmutes it; the release mutes it [TAIL_MS] later, so the last words leave.
 * - Never taken from a call or another app ([micBusy]): not opened ahead, and given back when it is wanted elsewhere.
 * - "Heard live" is: their audio track decrypts with their key and isn't muted ([hears]).
 *
 * Voice only for now: push-to-video's camera isn't sent from an iPhone yet (docs/ios.md).
 */
internal class IosTalkTransport(private val room: IosTalkRoom) : TalkTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logFailures)
    private val lock = SyncLock()

    override var listener: TalkTransportListener? = null

    /** In a room (joined and not left or lost). */
    @Volatile private var joined = false

    /** Each join's number: what an older room still says is told from the one joined since. */
    @Volatile private var joinNumber = 0

    /** Keys set before the room was there: set again on each join. */
    private val keys = mutableMapOf<String, Pair<String, Int>>()

    /** Remote "user.device" whose current audio track decrypts. */
    private val decrypting = mutableSetOf<String>()

    /** Talkers whose live audio is silenced now (their clip plays instead), until their next burst. */
    private val silenced = mutableSetOf<String>()

    private val preparingMic = Mutex()
    @Volatile private var micThere = false
    @Volatile private var micWanted = false
    @Volatile private var talkingNow = false
    @Volatile private var liveNow = false
    @Volatile private var micHeard = false
    private var muting: Job? = null
    private var micRetry: Job? = null

    init {
        room.events = object : IosTalkRoomEvents {
            override fun joined(identity: String) {
                listener?.joined(identity)
            }

            override fun gone(identity: String) {
                micHeard = false
                lock.locked { decrypting -= identity }
                listener?.gone(identity)
            }

            override fun audioDecrypts(identity: String, ok: Boolean) {
                val first = lock.locked { if (ok) decrypting.add(identity) else false.also { decrypting -= identity } }
                if (first) listener?.liveAudio(identity)
            }

            override fun audioStopped(identity: String) {
                listener?.stopped(identity)
            }

            override fun data(identity: String, topic: String, bytes: NSData) {
                if (topic == TalkBeat.TOPIC) listener?.beat(identity, bytes.toByteArray())
            }

            override fun micSubscribed() {
                micHeard = true
            }

            override fun lost() {
                if (!joined) return
                forget()
                listener?.left()
            }
        }
    }

    override suspend fun join(url: String, token: String, identity: String, ownKey: ByteArray, ownIndex: Int): Boolean {
        leave()
        val number = ++joinNumber
        lock.locked { keys.toMap() }.forEach { (who, key) -> room.setKey(who, key.first, key.second) }
        val ok = suspendCancellableCoroutine { waiting ->
            room.join(url, token, identity, encode(ownKey), ownIndex, LiveTalk.KEY_RING) { ok -> if (waiting.isActive) waiting.resume(ok) }
        }
        // Left, or joined again, while this join was on its way: its room isn't this phone's any more.
        if (number != joinNumber) return false
        joined = ok
        if (ok) lock.locked { keys.toMap() }.forEach { (who, key) -> room.setKey(who, key.first, key.second) }
        return ok
    }

    override fun leave() {
        joinNumber++
        forget()
        room.leave()
    }

    /** Everything of the room this phone was in goes: no press stays "live" into nothing. */
    private fun forget() {
        joined = false
        talkingNow = false
        liveNow = false
        micThere = false
        micHeard = false
        muting?.cancel()
        micRetry?.cancel()
        lock.locked {
            decrypting.clear()
            silenced.clear()
        }
    }

    override fun setKey(identity: String, key: ByteArray, index: Int) {
        val encoded = encode(key)
        lock.locked { keys[identity] = encoded to index }
        if (joined) room.setKey(identity, encoded, index)
    }

    override fun micBusy(): Boolean = !talkingNow && room.micBusy()

    /** The microphone's track, published muted; null when it can't be had ([press]: wanted for a press now). */
    private suspend fun prepareMic(press: Boolean): Boolean = preparingMic.withLock {
        if (micThere) return@withLock true
        if (!joined) return@withLock false
        // Not ahead of a press while it's someone else's; a press itself is refused before this (micBusy).
        if (!press && room.micBusy()) return@withLock false
        val number = joinNumber
        val ok = suspendCancellableCoroutine { waiting -> room.publishMic { ok -> if (waiting.isActive) waiting.resume(ok) } }
        if (number != joinNumber) return@withLock false
        micThere = ok
        ok
    }

    private suspend fun releaseMic() = preparingMic.withLock {
        if (!micThere || talkingNow) return@withLock
        micThere = false
        liveNow = false
        micHeard = false
        suspendCancellableCoroutine { waiting -> room.unpublishMic { if (waiting.isActive) waiting.resume(Unit) } }
    }

    /** The microphone was someone else's when it was wanted: tried again while it still is wanted. */
    private fun retryMic() {
        micRetry?.cancel()
        micRetry = scope.launch {
            while (micWanted && !micThere && joined) {
                delay(MIC_RETRY_MS)
                if (micWanted && joined && !room.micBusy()) prepareMic(press = false)
            }
        }
    }

    override suspend fun prepareAudio(on: Boolean) {
        if (!joined) return
        micWanted = on
        if (on) {
            if (!prepareMic(press = false)) retryMic()
        } else {
            micRetry?.cancel()
            releaseMic()
        }
    }

    override suspend fun setTalking(on: Boolean) {
        if (!joined) return
        if (on) {
            muting?.cancel()
            talkingNow = true
            if (!prepareMic(press = true)) return
            // Let go while the track was being published: it stays muted.
            if (!talkingNow) return
            val live = suspendCancellableCoroutine { waiting -> room.setMicLive(true) { ok -> if (waiting.isActive) waiting.resume(ok) } }
            if (live && talkingNow && joined) {
                liveNow = true
            } else if (live) {
                room.setMicLive(false) {}
            }
        } else {
            talkingNow = false
            muting?.cancel()
            muting = scope.launch {
                delay(TAIL_MS)
                if (talkingNow) return@launch
                liveNow = false
                room.setMicLive(false) {}
            }
        }
    }

    override val micReady: Boolean get() = micThere

    override val talkingLive: Boolean get() = liveNow && micThere && joined

    override suspend fun readyMic(): Boolean = joined && prepareMic(press = true)

    override fun subscribed(video: Boolean): Boolean = !video && micHeard

    override fun hears(identity: String): Boolean = joined && lock.locked { identity in decrypting } && room.audioLive(identity)

    override fun silence(identity: String) {
        if (!joined) return
        lock.locked { silenced += identity }
        room.setVolume(identity, 0.0)
    }

    override fun unsilence(identity: String) {
        if (!lock.locked { silenced.remove(identity) } || !joined) return
        room.setVolume(identity, 1.0)
    }

    override suspend fun sendBeat(bytes: ByteArray) {
        if (joined) room.sendData(bytes.toNSData(), TalkBeat.TOPIC)
    }

    /**
     * The clip's microphone while in a room. A push goes into the room only (2026-11-27, hen: "livekit only"), where
     * nothing is recorded on the phone, so this gives no packets; it says yes so the press goes on to the room. The old
     * clip upload isn't on iOS.
     */
    override val capture: TalkCapture = object : TalkCapture {
        override fun start(onPacket: (ByteArray) -> Unit): Boolean = true

        override fun stop() {}
    }

    private companion object {
        /** The microphone stays unmuted this long after the release, so the last words still on their way leave. */
        const val TAIL_MS = 300L

        const val MIC_RETRY_MS = 2_000L

        /** LiveKit takes the key as a string it derives the AES key from: the 32 random bytes, in base64. */
        @OptIn(ExperimentalEncodingApi::class)
        fun encode(key: ByteArray): String = Base64.encode(key)
    }
}
