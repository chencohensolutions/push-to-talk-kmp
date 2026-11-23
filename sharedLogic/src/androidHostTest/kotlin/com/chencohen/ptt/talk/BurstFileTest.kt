package com.chencohen.ptt.talk

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class BurstFileTest {
    private fun mkv(contents: ClipContents): ByteArray {
        val scratch = File.createTempFile("burst", ".body")
        val out = ByteArrayOutputStream()
        BurstFile.make(contents, scratch, out)
        scratch.delete()
        return out.toByteArray()
    }

    private fun has(bytes: ByteArray, text: String): Boolean = String(bytes, Charsets.ISO_8859_1).contains(text)

    @Test
    fun aVoiceBurstIsAMatroskaFileWithOpus() {
        val bytes = mkv(ClipContents(List(50) { ByteArray(40) { 7 } }, emptyList(), 0))
        assertTrue(bytes.size > 50 * 40, "every packet in it")
        assertTrue(bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte(), "an EBML header")
        assertTrue(has(bytes, "A_OPUS"))
        assertTrue(!has(bytes, "V_"), "no video track")
    }

    @Test
    fun aVideoBurstHasItsVideoAsSent() {
        val frames = List(15) { i -> TalkVideo.Frame(i * 66L, i == 0, 90, ByteArray(300) { i.toByte() }, TalkVideo.VP8) }
        val bytes = mkv(ClipContents(List(50) { ByteArray(40) { 7 } }, frames, 0))
        assertTrue(has(bytes, "A_OPUS") && has(bytes, "V_VP8"))
        assertTrue(bytes.size > 15 * 300 + 50 * 40)
    }
}
