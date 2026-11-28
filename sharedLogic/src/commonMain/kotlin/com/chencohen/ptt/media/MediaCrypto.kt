package com.chencohen.ptt.media

import com.chencohen.ptt.discovery.Seal
import com.chencohen.ptt.talk.Aes256Gcm

/**
 * A shared picture, video or file as the server keeps it (2026-11-28, "Sharing media" in docs/design.md): each part
 * (a preview, a poster, the original) is its own blob, AES-256-GCM in chunks of [CHUNK] plaintext bytes. The
 * attachment's random key goes only inside the Signal-encrypted message, so only the sender's and the recipients'
 * phones can open it (hen: the key never goes to Journal or the server).
 *
 * Each part has its own key, HKDF-SHA256 of the attachment's key with the blob id as salt and the part's name as
 * info, so the same key never seals two parts. The nonce is the chunk's index; the authenticated data binds the blob,
 * the index and whether it is the last chunk, so the server can't swap chunks between parts, reorder them or cut a
 * part short. A chunk on disk is [CHUNK] + [TAG] bytes (the last one shorter), so chunk i starts at i * [SEALED]: a
 * reader can open any range of the original (a video seek) without reading what comes before it.
 */
internal object MediaCrypto {
    const val KEY_BYTES = 32
    const val CHUNK = 64 * 1024
    const val TAG = 16
    const val SEALED = CHUNK + TAG

    enum class Part(val info: String) { PREVIEW("preview"), POSTER("poster"), ORIGINAL("original") }

    fun partKey(attachmentKey: ByteArray, blob: String, part: Part): ByteArray {
        require(attachmentKey.size == KEY_BYTES)
        return Seal.hkdfSha256(attachmentKey, blob.encodeToByteArray(), "talk media v1|${part.info}".encodeToByteArray(), KEY_BYTES)
    }

    fun sealChunk(partKey: ByteArray, blob: String, index: Long, last: Boolean, plain: ByteArray): ByteArray {
        require(plain.size <= CHUNK)
        require(last || plain.size == CHUNK) { "only the last chunk may be short" }
        return Aes256Gcm.seal(partKey, nonce(index), aad(blob, index, last), plain)
    }

    /** Null when it doesn't open as [blob]'s chunk [index] with that [last] flag. */
    fun openChunk(partKey: ByteArray, blob: String, index: Long, last: Boolean, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG || sealed.size > SEALED) return null
        return Aes256Gcm.open(partKey, nonce(index), aad(blob, index, last), sealed)
    }

    /** The sealed size of [plainSize] bytes (an empty part is one empty last chunk). */
    fun sealedSize(plainSize: Long): Long {
        val chunks = chunkCount(plainSize)
        return plainSize + chunks * TAG
    }

    fun chunkCount(plainSize: Long): Long = if (plainSize == 0L) 1 else (plainSize + CHUNK - 1) / CHUNK

    /** The plain size of a finished part of [sealedSize] bytes; null when no plain size seals to it. */
    fun plainSize(sealedSize: Long): Long? {
        if (sealedSize < TAG) return null
        val full = sealedSize / SEALED
        val rest = sealedSize % SEALED
        val plain = when {
            rest == 0L -> full * CHUNK
            rest < TAG -> return null
            else -> full * CHUNK + rest - TAG
        }
        return if (sealedSize(plain) == sealedSize) plain else null
    }

    /** All of [plain] sealed, as one would upload it. */
    fun sealAll(partKey: ByteArray, blob: String, plain: ByteArray): ByteArray {
        val count = chunkCount(plain.size.toLong()).toInt()
        val out = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val from = i * CHUNK
            val to = minOf(plain.size, from + CHUNK)
            out += sealChunk(partKey, blob, i.toLong(), i == count - 1, plain.copyOfRange(from, to))
        }
        return out.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    /** A whole finished part opened; null when any chunk doesn't open, or it was cut short. */
    fun openAll(partKey: ByteArray, blob: String, sealed: ByteArray): ByteArray? {
        plainSize(sealed.size.toLong()) ?: return null
        val count = ((sealed.size + SEALED - 1) / SEALED).coerceAtLeast(1)
        val out = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val from = i * SEALED
            val to = minOf(sealed.size, from + SEALED)
            out += openChunk(partKey, blob, i.toLong(), i == count - 1, sealed.copyOfRange(from, to)) ?: return null
        }
        return out.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    /** Which sealed bytes hold plain bytes [from] until [until] of a part of [plainSize]: the chunks to fetch. */
    fun sealedRange(from: Long, until: Long, plainSize: Long): LongRange {
        require(from in 0..until && until <= plainSize)
        val first = from / CHUNK
        val last = if (until == from) first else (until - 1) / CHUNK
        val end = minOf(sealedSize(plainSize), (last + 1) * SEALED)
        return (first * SEALED) until end
    }

    fun nonce(index: Long): ByteArray = ByteArray(12).also {
        for (b in 0 until 8) it[4 + b] = (index ushr (56 - 8 * b)).toByte()
    }

    fun aad(blob: String, index: Long, last: Boolean): ByteArray =
        "talk media v1|$blob|$index|${if (last) 1 else 0}".encodeToByteArray()
}
