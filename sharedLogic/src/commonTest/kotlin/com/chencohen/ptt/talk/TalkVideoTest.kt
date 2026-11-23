package com.chencohen.ptt.talk

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TalkVideoTest {
    private val frame = TalkVideo.Frame(40, key = true, rotation = 270, data = ByteArray(5000) { it.toByte() }, codec = TalkVideo.H265)
    private val opus = byteArrayOf(9, 8, 7)

    @Test
    fun aFacePositionGoesThereAndBack() {
        val sent = TalkVideo.Face(1234, 0.31f, 0.42f, 0.27f)
        val got = ArrayList<TalkVideo.Face>()
        TalkVideo.Splitter().split(listOf(TalkVideo.face(sent)), {}) { got += it }
        val f = assertNotNull(got.single())
        assertEquals(1234, f.ptsMs)
        assertEquals(0.31f, f.x, 0.0001f)
        assertEquals(0.42f, f.y, 0.0001f)
        assertEquals(0.27f, f.h, 0.0001f)
    }

    @Test
    fun aReaderThatTakesNoFacesPlaysTheClipAsBefore() {
        // As 1.4.13 reads it: only the voice and the pictures; the face packets (and any later kind) are skipped.
        val plain = listOf(TalkVideo.audio(opus)) + TalkVideo.pieces(1, frame) + listOf(TalkVideo.audio(opus))
        val withFaces = listOf(TalkVideo.audio(opus), TalkVideo.face(TalkVideo.Face(30, 0.5f, 0.4f, 0.3f))) +
            TalkVideo.pieces(1, frame) + listOf(byteArrayOf(7, 1, 2, 3), TalkVideo.audio(opus))
        fun read(packets: List<ByteArray>): Pair<List<ByteArray>, List<TalkVideo.Frame>> {
            val frames = ArrayList<TalkVideo.Frame>()
            val audio = TalkVideo.Splitter().split(packets, { frames += it })
            return audio to frames
        }
        val (a1, f1) = read(plain)
        val (a2, f2) = read(withFaces)
        assertEquals(a1.size, a2.size)
        a1.zip(a2).forEach { (x, y) -> assertContentEquals(x, y) }
        assertEquals(1, f2.size)
        assertContentEquals(f1.single().data, f2.single().data)
        assertEquals(f1.single().rotation, f2.single().rotation)
    }

    @Test
    fun aShortFacePacketIsSkipped() {
        val got = ArrayList<TalkVideo.Face>()
        TalkVideo.Splitter().split(listOf(byteArrayOf(TalkVideo.FACE, 0, 0)), {}) { got += it }
        assertEquals(0, got.size)
    }
}
