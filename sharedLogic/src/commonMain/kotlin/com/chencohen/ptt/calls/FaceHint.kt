package com.chencohen.ptt.calls

import com.chencohen.ptt.talk.Aes256Gcm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Where the sender's face is in the picture it sends (2026-11-23, hen: "the sender sends the face position"): the
 * centre ([x], [y]) and height [h] as shares of the upright picture as sent (after Frame my face's crop when that is
 * on), and [t], the sender's clock in milliseconds when the frame was taken. Sent about four times a second over the
 * call's data channel, sealed with the call's key; receivers frame it for their own layout (Follow face) instead of
 * looking for it themselves.
 */
@Serializable
data class FaceHint(val x: Float, val y: Float, val h: Float, val t: Long) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val AAD = "talk face hint v1"

        /** The data channel's topic for hints. */
        const val TOPIC = "face"

        fun seal(callKey: ByteArray, hint: FaceHint, random: Random = Random.Default): ByteArray {
            val nonce = random.nextBytes(12)
            return nonce + Aes256Gcm.seal(callKey, nonce, AAD.encodeToByteArray(), json.encodeToString(serializer(), hint).encodeToByteArray())
        }

        fun open(callKey: ByteArray, bytes: ByteArray): FaceHint? {
            if (bytes.size < 12 + 16) return null
            val plain = Aes256Gcm.open(callKey, bytes.copyOfRange(0, 12), AAD.encodeToByteArray(), bytes.copyOfRange(12, bytes.size)) ?: return null
            return runCatching { json.decodeFromString(serializer(), plain.decodeToString()) }.getOrNull()
        }
    }
}

/**
 * The hints received in a call, per sender. A hint stands for [EXPIRES_MS] after it arrived; [at] gives the one that
 * arrived nearest a moment (a frame's), among those still standing, so a picture is framed by its own moment's hint.
 */
class FaceHints(private val keep: Int = 8) {
    private class Got(val hint: FaceHint, val arrivedAt: Long)

    private val bySender = HashMap<String, ArrayDeque<Got>>()

    fun put(sender: String, hint: FaceHint, now: Long) {
        synchronized(this) {
            val list = bySender.getOrPut(sender) { ArrayDeque() }
            // A hint older than the newest from the same sender came out of order: dropped.
            if (list.lastOrNull()?.let { it.hint.t > hint.t } == true) return
            list.addLast(Got(hint, now))
            while (list.size > keep) list.removeFirst()
        }
    }

    /** The newest hint from [sender] still standing at [now]; null when there is none. */
    fun latest(sender: String, now: Long): FaceHint? = at(sender, now, now)

    /** The standing hint from [sender] that arrived nearest [moment]. */
    fun at(sender: String, moment: Long, now: Long): FaceHint? = synchronized(this) {
        bySender[sender]?.filter { now - it.arrivedAt <= EXPIRES_MS && it.arrivedAt <= now }
            ?.minByOrNull { kotlin.math.abs(it.arrivedAt - moment) }?.hint
    }

    fun forget(sender: String) {
        synchronized(this) { bySender.remove(sender) }
    }

    fun clear() {
        synchronized(this) { bySender.clear() }
    }

    companion object {
        const val EXPIRES_MS = 1_500L
    }
}
