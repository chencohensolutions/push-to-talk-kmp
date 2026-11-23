package com.chencohen.ptt.ui.contacts

import com.chencohen.ptt.messaging.TextMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Send's flight finds its message by id, never by text (2026-11-24, hen: the same text sent twice). */
class SendFlightTest {
    private fun mine(id: String, body: String) = TextMessage(id, "dana", outgoing = true, body = body, at = 0)

    @Test
    fun theSameTextSentTwiceFliesToTheNewOne() {
        val flight = SendFlight()
        val first = mine("a1", "hi")
        flight.start("hi", null, newestNow = first.id)
        flight.claim(first)
        assertNull("the earlier \"hi\" isn't the one sent", flight.id)
        flight.claim(mine("b2", "hi"))
        assertEquals("b2", flight.id)
    }

    @Test
    fun theirMessageInBetweenIsNotTheTarget() {
        val flight = SendFlight()
        flight.start("ok", null, newestNow = null)
        flight.claim(TextMessage("t1", "dana", outgoing = false, body = "ok", at = 0))
        assertNull(flight.id)
        flight.claim(mine("m1", "ok"))
        assertEquals("m1", flight.id)
    }
}
