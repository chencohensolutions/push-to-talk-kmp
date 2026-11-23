package com.chencohen.ptt.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ending an edit (2026-11-24, hen): the writing bubble, another double tap, Back or a tap outside. */
class EditSessionTest {
    @Test
    fun theWritingBubbleEndsTheEditUnchanged() {
        val edits = EditSession()
        val e = Editing("m1", "hello", 2, 300)
        edits.start(e)
        edits.abort()
        assertNull(edits.editing)
        assertTrue("it turns back (its cancel goes out as it does)", e.leaving)
        assertSame(e, edits.of("m1"))
        edits.gone(e)
        assertNull(edits.of("m1"))
    }

    @Test
    fun aDoubleTapOnAnotherEndsTheFirstAndStartsTheNew() {
        val edits = EditSession()
        val first = Editing("m1", "hello", 0, 300)
        val second = Editing("m2", "there", 0, 300)
        edits.start(first)
        edits.start(second)
        assertTrue(first.leaving)
        assertEquals("m2", edits.editing?.id)
        assertSame("the first still turns back", first, edits.of("m1"))
        assertSame(second, edits.of("m2"))
    }
}
