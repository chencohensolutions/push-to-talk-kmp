package com.chencohen.ptt.calls

import com.chencohen.ptt.talk.Aes256Gcm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * What a phone logs into a kept call (logCallEvent; 2026-11-22, hen), so a saved call can be played as it was seen:
 * its camera's rotation and size ("camera"), and how it shows someone's video ("view": Fill or Fit, the tile's shape).
 * Encrypted with the call's key, which the server never has.
 */
@Serializable
data class CallEvent(
    /** "camera" or "view". */
    val t: String,
    /** camera: clockwise degrees to turn the picture upright, and the frames' size as captured. */
    val rot: Int? = null,
    val w: Int? = null,
    val h: Int? = null,
    /** view: whose video (a user), cropped to fill ([fill]) or whole, in a tile [aspect] wide for 1 high. */
    val of: String? = null,
    val fill: Boolean? = null,
    val aspect: Float? = null,
) {
    companion object {
        const val CAMERA = "camera"
        const val VIEW = "view"
        private const val AAD = "talk call event v1"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** 12 random bytes, then the event as JSON sealed with AES-256-GCM under [callKey]. */
        fun seal(callKey: ByteArray, event: CallEvent, random: Random): ByteArray {
            val nonce = random.nextBytes(12)
            return nonce + Aes256Gcm.seal(callKey, nonce, AAD.encodeToByteArray(), json.encodeToString(serializer(), event).encodeToByteArray())
        }

        /** Null when it doesn't open with [callKey] or isn't an event. */
        fun open(callKey: ByteArray, bytes: ByteArray): CallEvent? {
            if (bytes.size < 12 + 16) return null
            val plain = Aes256Gcm.open(callKey, bytes.copyOfRange(0, 12), AAD.encodeToByteArray(), bytes.copyOfRange(12, bytes.size)) ?: return null
            return runCatching { json.decodeFromString(serializer(), plain.decodeToString()) }.getOrNull()
        }

        fun toJson(event: CallEvent): String = json.encodeToString(serializer(), event)
    }
}

/** How another phone shows this phone's video: cropped to fill a tile [aspect] wide for 1 high, or whole. */
data class CallView(val fill: Boolean, val aspect: Float)
