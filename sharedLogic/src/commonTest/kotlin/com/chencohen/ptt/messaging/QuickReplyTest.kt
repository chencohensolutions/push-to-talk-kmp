package com.chencohen.ptt.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickReplyTest {
    @Test
    fun aMessageOpensTheWindowAndMoreFromThemUpdateIt() {
        val q = QuickReply()
        assertEquals(QuickReply.Step.Show("dana"), q.arrived("dana", on = true, openInApp = null, busy = false))
        assertEquals(QuickReply.Step.Update, q.arrived("dana", on = true, openInApp = null, busy = false))
        assertEquals("dana", q.shown)
    }

    @Test
    fun nothingWhenOffOrWhenThatConversationIsOpenInTheApp() {
        val q = QuickReply()
        assertEquals(QuickReply.Step.None, q.arrived("dana", on = false, openInApp = null, busy = false))
        assertEquals(QuickReply.Step.None, q.arrived("dana", on = true, openInApp = "dana", busy = false))
        assertNull(q.shown)
        // Another conversation open in the app doesn't stop it.
        assertEquals(QuickReply.Step.Show("dana"), q.arrived("dana", on = true, openInApp = "ori", busy = false))
    }

    @Test
    fun someoneElseSwitchesTheWindowUnlessTheUserIsBusyThenWaits() {
        val q = QuickReply()
        q.arrived("dana", on = true, openInApp = null, busy = false)
        assertEquals(QuickReply.Step.Show("ori"), q.arrived("ori", on = true, openInApp = null, busy = false))
        assertEquals("ori", q.shown)
        // Typing to Ori: Dana and a group wait, once each, in order.
        assertEquals(QuickReply.Step.Wait, q.arrived("dana", on = true, openInApp = null, busy = true))
        assertEquals(QuickReply.Step.Wait, q.arrived("g:family", on = true, openInApp = null, busy = true))
        assertEquals(QuickReply.Step.Wait, q.arrived("dana", on = true, openInApp = null, busy = true))
        assertEquals(listOf("dana", "g:family"), q.queue)
        // Closing shows the next; one opened in the app meanwhile is skipped.
        assertEquals("g:family", q.closed(openInApp = "dana"))
        assertNull(q.closed())
        assertNull(q.shown)
    }

    @Test
    fun itClosesByItselfOnlyWhenUntouchedEmptyAndQuiet() {
        assertTrue(QuickReply.idle(now = 21_000, touchedAt = 1_000, typed = "", talking = false))
        assertFalse(QuickReply.idle(now = 20_999, touchedAt = 1_000, typed = "", talking = false))
        assertFalse(QuickReply.idle(now = 60_000, touchedAt = 1_000, typed = "hi", talking = false))
        assertFalse(QuickReply.idle(now = 60_000, touchedAt = 1_000, typed = "", talking = true))
    }
}
