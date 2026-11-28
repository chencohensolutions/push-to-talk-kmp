package com.chencohen.ptt.media

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MediaCryptoTest {
    private val key = Random(7).nextBytes(MediaCrypto.KEY_BYTES)
    private val blob = "0123456789abcdef0123456789abcdef"

    @Test
    fun aPartOpensAgainAtEverySize() {
        val k = MediaCrypto.partKey(key, blob, MediaCrypto.Part.ORIGINAL)
        for (size in listOf(0, 1, MediaCrypto.CHUNK - 1, MediaCrypto.CHUNK, MediaCrypto.CHUNK + 1, 3 * MediaCrypto.CHUNK + 5)) {
            val plain = Random(size).nextBytes(size)
            val sealed = MediaCrypto.sealAll(k, blob, plain)
            assertEquals(MediaCrypto.sealedSize(size.toLong()), sealed.size.toLong(), "sealed size of $size")
            assertEquals(size.toLong(), MediaCrypto.plainSize(sealed.size.toLong()), "plain size of $size")
            assertContentEquals(plain, MediaCrypto.openAll(k, blob, sealed), "size $size")
        }
    }

    @Test
    fun eachPartHasItsOwnKey() {
        val preview = MediaCrypto.partKey(key, blob, MediaCrypto.Part.PREVIEW)
        val original = MediaCrypto.partKey(key, blob, MediaCrypto.Part.ORIGINAL)
        val otherBlob = MediaCrypto.partKey(key, blob.reversed(), MediaCrypto.Part.ORIGINAL)
        assertFalse(preview.contentEquals(original))
        assertFalse(original.contentEquals(otherBlob))
        val sealed = MediaCrypto.sealAll(original, blob, "picture".encodeToByteArray())
        assertNull(MediaCrypto.openAll(preview, blob, sealed))
    }

    @Test
    fun theServerCantChangeMoveReorderOrCutAPart() {
        val k = MediaCrypto.partKey(key, blob, MediaCrypto.Part.ORIGINAL)
        val plain = Random(1).nextBytes(2 * MediaCrypto.CHUNK + 100)
        val sealed = MediaCrypto.sealAll(k, blob, plain)
        // A changed byte.
        assertNull(MediaCrypto.openAll(k, blob, sealed.copyOf().also { it[10] = (it[10] + 1).toByte() }))
        // Another blob's id.
        assertNull(MediaCrypto.openAll(k, blob.reversed(), sealed))
        // The first two chunks swapped.
        val s = MediaCrypto.SEALED
        val swapped = sealed.copyOfRange(s, 2 * s) + sealed.copyOfRange(0, s) + sealed.copyOfRange(2 * s, sealed.size)
        assertNull(MediaCrypto.openAll(k, blob, swapped))
        // Cut after a whole chunk: the new last chunk wasn't sealed as last.
        assertNull(MediaCrypto.openAll(k, blob, sealed.copyOfRange(0, 2 * s)))
        // Cut inside a chunk.
        assertNull(MediaCrypto.openAll(k, blob, sealed.copyOfRange(0, sealed.size - 1)))
    }

    @Test
    fun aRangeOpensWithoutWhatComesBefore() {
        val k = MediaCrypto.partKey(key, blob, MediaCrypto.Part.ORIGINAL)
        val plain = Random(2).nextBytes(5 * MediaCrypto.CHUNK + 33)
        val sealed = MediaCrypto.sealAll(k, blob, plain)
        val from = 3L * MediaCrypto.CHUNK + 10
        val until = 4L * MediaCrypto.CHUNK + 20
        val range = MediaCrypto.sealedRange(from, until, plain.size.toLong())
        assertEquals(3L * MediaCrypto.SEALED, range.first)
        assertEquals(5L * MediaCrypto.SEALED - 1, range.last)
        val c3 = MediaCrypto.openChunk(k, blob, 3, false, sealed.copyOfRange(range.first.toInt(), (range.first + MediaCrypto.SEALED).toInt()))
        assertNotNull(c3)
        assertContentEquals(plain.copyOfRange(3 * MediaCrypto.CHUNK, 4 * MediaCrypto.CHUNK), c3)
        // The last chunk's range ends with the part.
        val tail = MediaCrypto.sealedRange(plain.size - 1L, plain.size.toLong(), plain.size.toLong())
        assertEquals(sealed.size.toLong() - 1, tail.last)
    }

    @Test
    fun sizesThatNoPartSealsToAreRefused() {
        assertNull(MediaCrypto.plainSize(5))
        assertNull(MediaCrypto.plainSize(MediaCrypto.SEALED + 3L))
        assertEquals(0L, MediaCrypto.plainSize(MediaCrypto.TAG.toLong()))
    }
}
