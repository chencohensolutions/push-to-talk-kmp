package com.chencohen.ptt.calls

import com.chencohen.ptt.messaging.KeptTrack
import com.chencohen.ptt.talk.BlobApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A kept call made into a Matroska file, from a fixture encrypted by Go's own crypto (tools/keptcall). */
class KeptCallFileTest {
    private fun resource(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("kept-call/$name")!!.readBytes()

    private val blobs = object : BlobApi {
        override suspend fun read(id: String, onBytes: suspend (ByteArray) -> Unit) {
            val all = resource("$id.blob")
            // In pieces, as the network gives them.
            var at = 0
            while (at < all.size) {
                val end = minOf(all.size, at + 5000)
                onBytes(all.copyOfRange(at, end))
                at = end
            }
        }

        override suspend fun put(id: String, peer: String?, group: String?, bytes: ByteArray) = error("not used")
    }

    @Test
    fun pbkdf2MatchesJavas() {
        val password = "AQgPFh0kKzI5QEdOVVxjanF4f4aNlJuiqbC3vsXM09o="
        val java = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), LiveKitFrames.SALT.encodeToByteArray(), 1000, 128)).encoded
        assertContentEquals(java, LiveKitFrames.pbkdf2(password.encodeToByteArray(), LiveKitFrames.SALT.encodeToByteArray(), 1000, 16))
    }

    @Test
    fun h264FramesAreUnescapedBeforeOpening() {
        val key = ByteArray(16) { it.toByte() }
        // SPS, PPS, then an IDR slice whose payload has runs of zeros.
        val frame = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 3, 0, 0, 0, 1, 0x68, 4, 0, 0, 0, 1, 0x65, 0x88.toByte()) +
            ByteArray(40) + byteArrayOf(1, 2, 3)
        assertEquals(20, LiveKitFrames.clearBytes(frame, LiveKitFrames.H264))
        val sealed = LiveKitFrames.encrypt(key, frame, LiveKitFrames.H264, ByteArray(12))
        assertContentEquals(frame, LiveKitFrames.decrypt(key, sealed, LiveKitFrames.H264))
        assertNull(LiveKitFrames.decrypt(ByteArray(16), sealed, LiveKitFrames.H264), "another key doesn't open it")
        assertEquals(2, Matroska.nalUnits(frame).take(2).size)
        assertContentEquals(byteArrayOf(0x67, 1, 2, 3), Matroska.nalUnits(frame)[0])
    }

    @Test
    fun aKeptCallBecomesOneMatroskaFileWithEveryonesTracks() = runTest {
        val manifest = Json.parseToJsonElement(resource("manifest.json").decodeToString()).jsonObject
        val tracks = manifest["tracks"]!!.jsonArray.map { it.jsonObject }.map {
            KeptTrack(
                it["Blob"]!!.jsonPrimitive.content, it["Identity"]!!.jsonPrimitive.content, it["Kind"]!!.jsonPrimitive.content,
                it["Codec"]!!.jsonPrimitive.content, it["RecordingStartedAt"]!!.jsonPrimitive.content, it["Size"]!!.jsonPrimitive.int, true,
            )
        }
        val scratch = Files.createTempDirectory("kept").toFile()
        val out = File("build/kept-call.mkv").absoluteFile.also { it.parentFile.mkdirs() }
        var last = 0f
        val made = KeptCallFile(blobs::read, scratch).make(manifest["key"]!!.jsonPrimitive.content, tracks, { it.replaceFirstChar(Char::uppercase) }, out.outputStream()) { last = it }
        assertEquals(1f, last)
        assertEquals(0, made.unreadable, "every frame opens with the call's key")
        assertEquals(listOf("Hen", "Sharon"), made.people.map { it.name })
        assertEquals(KeptCallFile.Person("hen.1", "Hen", 1, 2), made.people[0])
        assertEquals(KeptCallFile.Person("sharon.2", "Sharon", 3, 4), made.people[1])
        // Sharon's keeping began half a second later: her last frames end the file.
        assertTrue(made.durationMs in 2400..2600, "duration ${made.durationMs}")
        RandomAccessFile(out, "r").use { f ->
            val (ebml, ebmlAt, ebmlSize) = Matroska.readElement(f, 0)
            assertEquals(Matroska.ID_EBML, ebml)
            val (segment, segmentAt, segmentSize) = Matroska.readElement(f, ebmlAt + ebmlSize)
            assertEquals(Matroska.ID_SEGMENT, segment)
            assertEquals(f.length(), segmentAt + segmentSize, "the segment's size is the rest of the file")
            val ids = mutableListOf<Int>()
            var at = segmentAt
            while (at < f.length()) {
                val (id, dataAt, size) = Matroska.readElement(f, at)
                ids += id
                at = dataAt + size
            }
            assertEquals(listOf(Matroska.ID_SEEK_HEAD, Matroska.ID_INFO, Matroska.ID_TRACKS, Matroska.ID_CUES), ids.take(4))
            assertTrue(ids.drop(4).all { it == Matroska.ID_CLUSTER } && ids.size > 5)
        }
        scratch.deleteRecursively()
    }

    @Test
    fun redAudioIsUnwrappedAfterDecrypting() {
        val primary = byteArrayOf(0x78, 1, 2, 3, 4)
        val older = byteArrayOf(0x78, 9, 9)
        // One redundant block (type 111, offset 960, length 3), then the primary (type 111).
        val red = byteArrayOf((0x80 or 111).toByte(), 0x0F, 0x00, 3, 111) + older + primary
        assertContentEquals(primary, LiveKitFrames.redPrimary(red))
        assertContentEquals(primary, LiveKitFrames.redPrimary(byteArrayOf(111) + primary), "RED without redundancy")
        assertNull(LiveKitFrames.redPrimary(byteArrayOf((0x80 or 111).toByte(), 0, 0, 50, 111, 1)), "blocks longer than the packet")
    }

    @Test
    fun aKeptCallWithRedAudioPlaysItsOpus() = runTest {
        val manifest = Json.parseToJsonElement(resource("manifest.json").decodeToString()).jsonObject
        val callKey = manifest["key"]!!.jsonPrimitive.content
        val key = LiveKitFrames.key(callKey.encodeToByteArray())
        // Hen's audio as the phones send it now: each Opus frame wrapped in RED with the one before, then encrypted whole.
        val records = java.io.ByteArrayOutputStream()
        val input = java.io.DataInputStream(resource("hen-audio.blob").inputStream())
        var previous: ByteArray? = null
        var n = 0
        while (input.available() > 0) {
            val length = input.readInt()
            val us = input.readLong()
            val flags = input.readUnsignedByte()
            val frame = ByteArray(length - 9).also { input.readFully(it) }
            val opus = LiveKitFrames.decrypt(key, frame, LiveKitFrames.OPUS)!!
            val red = previous?.let { p -> byteArrayOf((0x80 or 111).toByte(), 0x03, (0xC0 or (p.size shr 8)).toByte(), p.size.toByte(), 111) + p + opus }
                ?: (byteArrayOf(111) + opus)
            val sealed = LiveKitFrames.encrypt(key, red, LiveKitFrames.RED, ByteArray(12) { (it + n).toByte() })
            java.io.DataOutputStream(records).apply { writeInt(9 + sealed.size); writeLong(us); writeByte(flags); write(sealed) }
            previous = opus
            n++
        }
        val blob = records.toByteArray()
        val tracks = listOf(KeptTrack("red", "hen.1", "audio", "audio/red", "2026-11-22T16:00:00Z", blob.size, true))
        val out = java.io.ByteArrayOutputStream()
        val made = KeptCallFile({ _, onBytes -> onBytes(blob) }, Files.createTempDirectory("kept").toFile()).make(callKey, tracks, { it }, out)
        assertEquals(0, made.unreadable)
        assertEquals(n, made.frames, "every RED packet gave its Opus frame")
        assertEquals(KeptCallFile.Person("hen.1", "hen", null, 1), made.people.single())
        File("build/kept-red.mkv").writeBytes(out.toByteArray())
    }

    @Test
    fun theEventLogsGiveRotationsAndViewsAndATextTrack() = runTest {
        val manifest = Json.parseToJsonElement(resource("manifest.json").decodeToString()).jsonObject
        val callKey = manifest["key"]!!.jsonPrimitive.content
        val raw = kotlin.io.encoding.Base64.decode(callKey)
        fun log(vararg events: Pair<Long, CallEvent>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            events.forEach { (ms, e) ->
                val sealed = CallEvent.seal(raw, e, kotlin.random.Random(ms))
                java.io.DataOutputStream(out).apply { writeInt(9 + sealed.size); writeLong(ms * 1000); writeByte(0); write(sealed) }
            }
            return out.toByteArray()
        }
        val henLog = log(0L to CallEvent(CallEvent.CAMERA, rot = 90, w = 160, h = 120), 1500L to CallEvent(CallEvent.CAMERA, rot = 0, w = 160, h = 120))
        val sharonLog = log(1000L to CallEvent(CallEvent.VIEW, of = "hen", fill = true, aspect = 0.5f))
        val blobs = mapOf("hen-events" to henLog, "sharon-events" to sharonLog)
        val tracks = manifest["tracks"]!!.jsonArray.map { it.jsonObject }.map {
            KeptTrack(
                it["Blob"]!!.jsonPrimitive.content, it["Identity"]!!.jsonPrimitive.content, it["Kind"]!!.jsonPrimitive.content,
                it["Codec"]!!.jsonPrimitive.content, it["RecordingStartedAt"]!!.jsonPrimitive.content, it["Size"]!!.jsonPrimitive.int, true,
            )
        } + listOf(
            KeptTrack("hen-events", "hen.1", "events", "application/x-talk-events", "2026-11-22T16:00:00Z", henLog.size, true),
            KeptTrack("sharon-events", "sharon.2", "events", "application/x-talk-events", "2026-11-22T16:00:00.500Z", sharonLog.size, true),
        )
        val read: suspend (String, suspend (ByteArray) -> Unit) -> Unit = { id, onBytes -> onBytes(blobs[id] ?: resource("$id.blob")) }
        val out = File("build/kept-events.mkv").absoluteFile
        val made = KeptCallFile(read, Files.createTempDirectory("kept").toFile()).make(callKey, tracks, { it }, out.outputStream())
        assertEquals(listOf(0L to 90, 1500L to 0), made.rotations["hen.1"])
        assertEquals(listOf(KeptCallFile.ViewAt(1500, "sharon.2", "hen", true, 0.5f)), made.views, "sharon's log began half a second later")
    }
}
