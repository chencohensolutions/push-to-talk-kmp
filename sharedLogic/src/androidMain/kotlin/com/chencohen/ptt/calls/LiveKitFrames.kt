package com.chencohen.ptt.calls

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LiveKit's end-to-end frame encryption as the phones' WebRTC does it (webrtc-sdk's frame_crypto_transformer, the
 * shared-key mode `LiveKitCallTransport` uses), undone for a call the server kept.
 *
 * - The key: PBKDF2-HMAC-SHA256 over the key material (the UTF-8 bytes of the call key's base64, what
 *   `BaseKeyProvider.setSharedKey` is given), salt "LKFrameEncryptionKey", 100 000 rounds, 16 bytes: AES-128-GCM.
 * - A frame: a clear header (audio 1 byte; VP8 10 bytes on a key frame, 3 otherwise; H.264 up to 2 bytes into the
 *   first slice's NAL unit; AV1 and VP9 none), then the ciphertext with its 16-byte tag, the 12-byte IV, a byte 12
 *   and the key index. The clear header is the authenticated data. For H.264 everything after the header is
 *   escaped like a NAL unit's payload (0x03 after two zeros), so it is unescaped first.
 */
object LiveKitFrames {
    const val SALT = "LKFrameEncryptionKey"
    const val ROUNDS = 100_000
    private const val IV = 12
    private const val TAG = 16

    /** The AES-128 key for a call whose key went to LiveKit as [material] (the base64 string's bytes). */
    fun key(material: ByteArray): ByteArray = pbkdf2(material, SALT.encodeToByteArray(), ROUNDS, 16)

    /** The frame as the sender's encoder made it; null when it doesn't open with [key]. */
    fun decrypt(key: ByteArray, frame: ByteArray, codec: String): ByteArray? {
        val clear = clearBytes(frame, codec)
        if (clear < 0 || frame.size < clear + TAG + IV + 2) return null
        var rest = frame.copyOfRange(clear, frame.size)
        if (codec.equals(H264, ignoreCase = true)) rest = unescape(rest)
        if (rest.size < TAG + IV + 2) return null
        val ivSize = rest[rest.size - 2].toInt() and 0xff
        if (ivSize != IV) return null
        val iv = rest.copyOfRange(rest.size - 2 - IV, rest.size - 2)
        val sealed = rest.copyOfRange(0, rest.size - 2 - IV)
        val header = frame.copyOfRange(0, clear)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, iv))
                updateAAD(header)
                doFinal(sealed)
            }
        } catch (_: AEADBadTagException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return header + plain
    }

    /** How many bytes at the start of [frame] stay clear; -1 when it can't be told. */
    fun clearBytes(frame: ByteArray, codec: String): Int = when {
        codec.startsWith("audio/", ignoreCase = true) -> 1
        codec.equals(VP8, ignoreCase = true) -> if (frame.isNotEmpty() && isVp8KeyFrame(frame)) 10 else 3
        codec.equals(H264, ignoreCase = true) -> {
            var found = 0
            for (start in naluStarts(frame)) {
                val type = frame[start].toInt() and 0x1f
                if (type == 1 || type == 5) {
                    found = start + 2
                    break
                }
            }
            found
        }
        else -> 0
    }

    /** A VP8 frame's tag: bit 0 of the first byte is 0 on a key frame. */
    fun isVp8KeyFrame(frame: ByteArray): Boolean = frame.isNotEmpty() && (frame[0].toInt() and 1) == 0

    /** Where each NAL unit's header byte is, after a 00 00 01 start code (also the 3 last bytes of 00 00 00 01). */
    fun naluStarts(data: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                out += i + 3
                i += 3
            } else {
                i++
            }
        }
        return out
    }

    /** Drops each 0x03 that follows two zero bytes (H.264's emulation prevention). */
    fun unescape(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            if (data.size - i >= 3 && data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 3) {
                out.write(0)
                out.write(0)
                i += 3
            } else {
                out.write(data[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    /** The other way, for tests: a 0x03 before any byte up to 3 that follows two zeros. */
    fun escape(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 8)
        var zeros = 0
        for (b in data) {
            val v = b.toInt() and 0xff
            if (v <= 3 && zeros >= 2) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** As the sender does it (for tests and for checking a key): [iv] is any 12 bytes, key index 0. */
    fun encrypt(key: ByteArray, frame: ByteArray, codec: String, iv: ByteArray): ByteArray {
        val clear = clearBytes(frame, codec).coerceAtLeast(0)
        val header = frame.copyOfRange(0, clear)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, iv))
            updateAAD(header)
            doFinal(frame.copyOfRange(clear, frame.size))
        }
        val rest = sealed + iv + byteArrayOf(IV.toByte(), 0)
        return header + if (codec.equals(H264, ignoreCase = true)) escape(rest) else rest
    }

    /** PBKDF2 over raw bytes (PBEKeySpec takes characters, which would change bytes that aren't ASCII). */
    fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val out = ByteArray(length)
        var block = 1
        var at = 0
        while (at < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(rounds - 1) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(t.size, length - at)
            t.copyInto(out, at, 0, n)
            at += n
            block++
        }
        return out
    }

    /**
     * RED's primary block (RFC 2198): the phones send call audio as RED, Opus with copies of the frames before, and
     * encrypt it whole, so the server keeps it whole (audio/red) and it is unwrapped here, after decrypting: 4-byte
     * headers (F=1, type, offset, 10-bit length) for each redundant block, then 1 byte (F=0), then the blocks, the
     * primary last. Null when it isn't well formed.
     */
    fun redPrimary(payload: ByteArray): ByteArray? {
        var i = 0
        var redundant = 0
        while (true) {
            if (i >= payload.size) return null
            if (payload[i].toInt() and 0x80 == 0) {
                i++
                break
            }
            if (i + 4 > payload.size) return null
            redundant += ((payload[i + 2].toInt() and 0x03) shl 8) or (payload[i + 3].toInt() and 0xff)
            i += 4
        }
        if (i + redundant > payload.size) return null
        return payload.copyOfRange(i + redundant, payload.size)
    }

    const val VP8 = "video/VP8"
    const val H264 = "video/H264"
    const val OPUS = "audio/opus"
    const val RED = "audio/red"
}
