package com.chencohen.ptt.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentCodecTest {
    @Test
    fun aFailedH265DecoderStopsSayingItAndOthersSendVp8() {
        val before = ContentCodec.myCaps
        try {
            ContentCodec.myCaps = ContentCodec.CAPS_SAID or ContentCodec.CAPS_H265
            var told = 0
            ContentCodec.onH265Failed = { told++ }
            // What this phone sends says H.265 until its decoder fails.
            val first = ContentCodec.decode(ContentCodec.encode(Content()))!!
            ContentCodec.note("me-test", first)
            assertTrue(ContentCodec.decodesH265("me-test"))
            ContentCodec.h265Failed()
            ContentCodec.h265Failed()
            assertEquals(1, told, "remembered once")
            val next = ContentCodec.decode(ContentCodec.encode(Content()))!!
            ContentCodec.note("me-test", next)
            assertFalse(ContentCodec.decodesH265("me-test"), "the others now send it VP8")
        } finally {
            ContentCodec.myCaps = before
            ContentCodec.onH265Failed = null
        }
    }

    @Test
    fun sharedMediaAndItsRequestsGoThroughTheCodec() {
        val item = AttachmentContent(
            id = "a1", kind = MediaKind.VIDEO, key = ByteArray(32) { it.toByte() }, sha256 = ByteArray(32) { 7 },
            size = 5_000_000_000, mime = "video/mp4", name = "VID_20261128.mp4", width = 2160, height = 3840,
            durationMs = 61_000, takenAt = 1_795_000_000_000, takenOffsetMin = 120, latE7 = 320_853_000,
            lonE7 = 347_818_000, hasPlace = true,
            previews = listOf(PartContent("p1", 23_000_000, 1080, 1920, "video/mp4", MediaCodec.HEVC, MediaHdr.HLG, growing = true)),
            poster = PartContent("p2", 90_000, 1080, 1920, "image/jxl"), originDevice = 2, thumb = byteArrayOf(1, 2, 3),
        )
        val sent = Content(
            text = TextContent("Video"), media = MediaContent("m1", 1_795_000_100_000, "the sea", listOf(item)),
            about = AboutContent("1.4.32", 1440, 3120),
        )
        val got = ContentCodec.decode(ContentCodec.encode(sent))!!
        assertEquals(sent.media, got.media)
        assertEquals(sent.about, got.about)
        assertEquals(5_000_000_000, got.media!!.items.single().size)

        val ask = Content(mediaRequest = MediaRequestContent("m1", "a1", MediaPart.ORIGINAL, now = true))
        assertEquals(ask.mediaRequest, ContentCodec.decode(ContentCodec.encode(ask))!!.mediaRequest)
        val answer = Content(mediaReply = MediaReplyContent("m1", "a1", MediaPart.ORIGINAL, MediaReplyStatus.READY,
            PartContent("o1", 5_000_000_000, growing = true)))
        assertEquals(answer.mediaReply, ContentCodec.decode(ContentCodec.encode(answer))!!.mediaReply)
    }

    @Test
    fun aPhoneSaysItTakesMedia() {
        val before = ContentCodec.myCaps
        try {
            ContentCodec.myCaps = ContentCodec.CAPS_SAID or ContentCodec.CAPS_MEDIA
            ContentCodec.note("media-test", ContentCodec.decode(ContentCodec.encode(Content()))!!)
            assertTrue(ContentCodec.takesMedia("media-test"))
            assertFalse(ContentCodec.playsHevc10("media-test"))
            assertFalse(ContentCodec.takesMedia("nobody-heard"))
        } finally {
            ContentCodec.myCaps = before
        }
    }
}
