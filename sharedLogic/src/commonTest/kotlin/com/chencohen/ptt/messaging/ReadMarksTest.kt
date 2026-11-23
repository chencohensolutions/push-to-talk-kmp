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

    @Test
    fun groupCirclesSitAtEachMembersLastRead() {
        val g = "group:x"
        val a = TextMessage("a", g, outgoing = true, body = "a", at = 1, readBy = mapOf("dana" to 10L, "noa" to 11L))
        val b = TextMessage("b", g, outgoing = true, body = "b", at = 2, readBy = mapOf("dana" to 12L))
        val c = TextMessage("c", g, outgoing = true, body = "c", at = 3, status = MessageStatus.DELIVERED)
        val readers = ReadMarks.groupReaders(listOf(a, b, c))
        assertEquals(listOf("noa" to 11L), readers["a"])
        assertEquals(listOf("dana" to 12L), readers["b"])
        assertNull(readers["c"])
        assertEquals(ReadMarks.Tick.NONE, ReadMarks.groupTick(listOf(a, b, c), b))
        assertEquals(ReadMarks.Tick.DELIVERED, ReadMarks.groupTick(listOf(a, b, c), c))
        assertNull(ReadMarks.groupReaders(listOf(a, b, c), hidden = setOf("noa"))["a"], "a hidden member shows no circle")
    }
}
