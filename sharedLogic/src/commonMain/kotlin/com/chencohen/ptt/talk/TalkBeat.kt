package com.chencohen.ptt.talk

import kotlin.random.Random

/**
 * "Still pushing" (2026-11-25, hen: "the receiver must show live for exactly as long as the sender transmits audio or
 * video"): while this phone transmits a burst (held, hands-free video, the floating window), about once a second a
 * tiny beat with the burst's id goes over the talk room's data channel, sealed with this device's own room key like
 * its audio. A listener shows the talker live while beats come and ends it at once on any end signal, or [LOST_MS]
 * after the last beat (or the last sign of the burst at all). Silence while held stays live: the beat isn't audio.
 */
object TalkBeat {
    private const val AAD = "talk beat v1"

    /** The data channel's topic for beats. */
    const val TOPIC = "beat"

    /** How often the sender beats. */
    const val EVERY_MS = 1_000L

    /** No sign of a talker this long (and it had shown signs): their live state ends. */
    const val LOST_MS = 3_000L

    fun seal(key: ByteArray, burstId: String, random: Random = Random.Default): ByteArray {
        val nonce = random.nextBytes(12)
        return nonce + Aes256Gcm.seal(key, nonce, AAD.encodeToByteArray(), burstId.encodeToByteArray())
    }

    /** The burst id in [bytes], or null when it doesn't open with [key]. */
    fun open(key: ByteArray, bytes: ByteArray): String? {
        if (bytes.size < 12 + 16) return null
        val plain = Aes256Gcm.open(key, bytes.copyOfRange(0, 12), AAD.encodeToByteArray(), bytes.copyOfRange(12, bytes.size)) ?: return null
        return plain.decodeToString().takeIf { it.isNotEmpty() && it.length <= 64 }
    }
}

/**
 * Each talker's last sign of life on this phone (a beat, a live segment, its floor's start), and the watchdog's rule
 * (2026-11-25, hen: the receiver kept showing the sender live after they let go). A burst is watched only once it has
 * shown a sign after its start (an older app that sends no beats, heard only through the room, keeps the old way: its
 * floor's end, or the server's limit).
 */
internal class LiveSigns(private val now: () -> Long) {
    private class Sign(var at: Long, var armed: Boolean)

    private val signs = mutableMapOf<String, Sign>()

    /** [burstId]'s floor started: its time starts, not yet watched. */
    fun started(burstId: String) {
        signs.getOrPut(burstId) { Sign(now(), false) }.at = now()
    }

    /** A beat of [burstId] (or a segment of one taken off): it is alive now, and watched from here. */
    fun alive(burstId: String) {
        val s = signs.getOrPut(burstId) { Sign(now(), true) }
        s.at = now()
        s.armed = true
    }

    /** It ended: no longer watched. */
    fun forget(burstId: String) {
        signs.remove(burstId)
    }

    /** The watched bursts with no sign for [TalkBeat.LOST_MS]. */
    fun lost(): List<String> {
        val t = now()
        return signs.filter { (_, s) -> s.armed && t - s.at >= TalkBeat.LOST_MS }.keys.toList()
    }

    fun watching(): Boolean = signs.values.any { it.armed }

    /** The bursts with a sign of life within [ms]. */
    fun freshWithin(ms: Long): Set<String> {
        val t = now()
        return signs.filter { (_, s) -> s.armed && t - s.at < ms }.keys
    }
}
