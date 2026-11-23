package com.chencohen.ptt.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FaceHintTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun aHintIsSealedWithTheCallKey() {
        val hint = FaceHint(0.4f, 0.3f, 0.25f, 1_234L)
        val sealed = FaceHint.seal(key, hint, Random(1))
        assertEquals(hint, FaceHint.open(key, sealed))
        assertNull(FaceHint.open(ByteArray(32), sealed), "another call's key opens nothing")
        assertNull(FaceHint.open(key, sealed.copyOf(sealed.size - 1)), "a cut hint opens nothing")
    }

    @Test
    fun aHintStandsForASecondAndAHalf() {
        val hints = FaceHints()
        hints.put("dana", FaceHint(0.5f, 0.4f, 0.3f, 100), now = 1_000)
        assertEquals(0.5f, hints.latest("dana", now = 2_400)?.x)
        assertNull(hints.latest("dana", now = 2_501), "expired")
        assertNull(hints.latest("ori", now = 1_000), "nothing from someone else")
    }

    @Test
    fun theHintNearestAFramesMomentIsUsed() {
        val hints = FaceHints()
        hints.put("dana", FaceHint(0.1f, 0.4f, 0.3f, 100), now = 1_000)
        hints.put("dana", FaceHint(0.2f, 0.4f, 0.3f, 350), now = 1_250)
        hints.put("dana", FaceHint(0.3f, 0.4f, 0.3f, 600), now = 1_500)
        assertEquals(0.2f, hints.at("dana", moment = 1_300, now = 1_600)?.x)
        assertEquals(0.3f, hints.at("dana", moment = 1_480, now = 1_600)?.x)
        assertEquals(0.3f, hints.latest("dana", now = 1_600)?.x)
        // Only standing hints count: at 2_800 the first two are gone.
        assertEquals(0.3f, hints.at("dana", moment = 1_000, now = 2_800)?.x)
    }

    @Test
    fun anOutOfOrderHintIsDroppedAndOnlyAFewAreKept() {
        val hints = FaceHints(keep = 3)
        hints.put("dana", FaceHint(0.6f, 0.4f, 0.3f, 500), now = 1_000)
        hints.put("dana", FaceHint(0.1f, 0.4f, 0.3f, 400), now = 1_010)
        assertEquals(0.6f, hints.latest("dana", now = 1_020)?.x)
        for (i in 1..5) hints.put("dana", FaceHint(i / 10f, 0.4f, 0.3f, 500L + i), now = 1_100L + i)
        assertEquals(0.3f, hints.at("dana", moment = 0, now = 1_200)?.x, "the oldest kept is the third of the five")
    }
}
