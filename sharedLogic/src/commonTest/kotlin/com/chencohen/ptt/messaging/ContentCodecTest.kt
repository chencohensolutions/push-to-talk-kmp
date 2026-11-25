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
}
