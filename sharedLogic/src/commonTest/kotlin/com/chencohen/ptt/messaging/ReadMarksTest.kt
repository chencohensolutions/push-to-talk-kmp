package com.chencohen.ptt.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadMarksTest {
    private fun mine(id: String, at: Long, status: MessageStatus) = TextMessage(id, "sharon", outgoing = true, body = id, at = at, status = status)
    private fun theirs(id: String, at: Long) = TextMessage(id, "sharon", outgoing = false, body = id, at = at)

    @Test
    fun allReadShowsNoMarksAndTheCircleAtTheEnd() {
        val chat = listOf(theirs("a", 1), mine("b", 2, MessageStatus.READ), mine("c", 3, MessageStatus.READ))
        assertEquals("c", ReadMarks.lastRead(chat)?.id)
        assertTrue(ReadMarks.atEnd(chat))
        chat.forEach { assertEquals(ReadMarks.Tick.NONE, ReadMarks.tick(chat, it)) }
    }

    @Test
    fun partlyReadMarksOnlyWhatCameAfter() {
        val chat = listOf(mine("b", 2, MessageStatus.DELIVERED), mine("c", 3, MessageStatus.READ), mine("d", 4, MessageStatus.DELIVERED), mine("e", 5, MessageStatus.SENT), mine("f", 6, MessageStatus.PENDING))
        assertEquals("c", ReadMarks.lastRead(chat)?.id)
        assertFalse(ReadMarks.atEnd(chat))
        assertEquals(ReadMarks.Tick.NONE, ReadMarks.tick(chat, chat[0]), "before what they read counts as read")
        assertEquals(ReadMarks.Tick.DELIVERED, ReadMarks.tick(chat, chat[2]))
        assertEquals(ReadMarks.Tick.SENT, ReadMarks.tick(chat, chat[3]))
        assertEquals(ReadMarks.Tick.SENDING, ReadMarks.tick(chat, chat[4]))
    }

    @Test
    fun theirLaterMessageMovesTheCircleOffTheEnd() {
        val chat = listOf(mine("b", 2, MessageStatus.READ), theirs("c", 3))
        assertFalse(ReadMarks.atEnd(chat))
        assertNull(ReadMarks.lastRead(listOf(theirs("x", 1))))
    }

    @Test
    fun aHiddenPresenceShowsNoCircle() {
        assertFalse(ReadMarks.showsReader(UserPresence("sharon", online = false, lastSeen = null)))
        assertTrue(ReadMarks.showsReader(UserPresence("sharon", online = false, lastSeen = 5)))
        assertTrue(ReadMarks.showsReader(null))
    }
}
