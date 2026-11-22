package com.chencohen.ptt.talk

import com.chencohen.ptt.relay.ClipSegment
import com.chencohen.ptt.relay.Frames

/** AES-256-GCM with a 12-byte nonce and a 16-byte tag (javax.crypto on Android). */
internal expect object Aes256Gcm {
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** Null when it doesn't open (another key, changed bytes, another index or flag). */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?
}

/**
 * A clip's segments (the server's docs/messaging.md, "Talk"): AES-256-GCM under the clip's own key, which only the
 * phones have (it goes in the encrypted "clip start" message). The nonce is the segment's index (big-endian, in the
 * last four of its twelve bytes): each key encrypts one clip, so an index is never used twice. The authenticated data
 * binds the clip's id, the index and the last flag, so the server can't move a segment to another clip, reorder
 * segments or cut a clip short without the phone noticing. Inside, a segment is its 20 ms Opus packets, each with a
 * 2-byte big-endian length.
 */
internal object ClipCrypto {
    const val KEY_BYTES = 32

    /** A ciphertext is at most 64 KiB (the server's limit since bf1b11e, for push-to-video); ten Opus packets are about 600 bytes. */
    const val MAX_SEGMENT = 64 * 1024

    fun seal(key: ByteArray, clip: String, index: Int, last: Boolean, packets: List<ByteArray>): ByteArray =
        Aes256Gcm.seal(key, nonce(index), aad(clip, index, last), pack(packets))

    /** The packets of a segment; null when it doesn't open as that clip's segment [index] with that [last] flag. */
    fun open(key: ByteArray, clip: String, index: Int, last: Boolean, ciphertext: ByteArray): List<ByteArray>? {
        val plain = Aes256Gcm.open(key, nonce(index), aad(clip, index, last), ciphertext) ?: return null
        return unpack(plain)
    }

    fun nonce(index: Int): ByteArray = ByteArray(12).also {
        it[8] = (index ushr 24).toByte()
        it[9] = (index ushr 16).toByte()
        it[10] = (index ushr 8).toByte()
        it[11] = index.toByte()
    }

    fun aad(clip: String, index: Int, last: Boolean): ByteArray =
        "ptt clip v1|$clip|$index|${if (last) 1 else 0}".encodeToByteArray()

    fun pack(packets: List<ByteArray>): ByteArray {
        val out = ByteArray(packets.sumOf { 2 + it.size })
        var at = 0
        for (p in packets) {
            require(p.size <= 0xffff)
            out[at++] = (p.size ushr 8).toByte()
            out[at++] = p.size.toByte()
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    fun unpack(bytes: ByteArray): List<ByteArray>? {
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            if (at + 2 > bytes.size) return null
            val n = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
            at += 2
            if (at + n > bytes.size) return null
            out += bytes.copyOfRange(at, at + n)
            at += n
        }
        return out
    }
}

/**
 * A clip as a file: the server's clip records (a 4-byte big-endian length, then a ClipSegment without its burst id),
 * one after another. GET /blob/<clip> answers a burst this way, a voice message is uploaded this way, and the phone
 * keeps its clips this way.
 */
internal object ClipRecords {
    fun record(index: Int, last: Boolean, ciphertext: ByteArray): ByteArray {
        val body = Frames.encodeSegment(ClipSegment(index = index, last = last, ciphertext = ciphertext))
        val out = ByteArray(4 + body.size)
        out[0] = (body.size ushr 24).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        body.copyInto(out, 4)
        return out
    }

    /** Reads records from a stream of bytes, as they arrive. */
    class Reader {
        private var buffer = ByteArray(0)

        /** The whole records in [bytes] and what came before them; a part of one waits for the rest. */
        fun feed(bytes: ByteArray, count: Int = bytes.size): List<ClipSegment> {
            buffer += bytes.copyOf(count)
            val out = mutableListOf<ClipSegment>()
            var at = 0
            while (buffer.size - at >= 4) {
                val n = ((buffer[at].toInt() and 0xff) shl 24) or ((buffer[at + 1].toInt() and 0xff) shl 16) or
                    ((buffer[at + 2].toInt() and 0xff) shl 8) or (buffer[at + 3].toInt() and 0xff)
                if (n < 0 || n > ClipCrypto.MAX_SEGMENT + 64) throw IllegalArgumentException("Not a clip record")
                if (buffer.size - at - 4 < n) break
                val segment = Frames.decodeSegment(buffer.copyOfRange(at + 4, at + 4 + n)) ?: throw IllegalArgumentException("Not a clip record")
                out += segment
                at += 4 + n
            }
            buffer = buffer.copyOfRange(at, buffer.size)
            return out
        }
    }

    fun parse(bytes: ByteArray): List<ClipSegment> = Reader().feed(bytes)
}
