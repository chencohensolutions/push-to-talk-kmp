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

    @Test
    fun overlappingVideosPairUpSideBySide() {
        fun video(id: String, out: Boolean, at: Long, ms: Long) = TextMessage(id, "sharon", outgoing = out, body = "", at = at, clip = Clip(id, "", ClipKind.BURST, ms, video = true))
        val a = video("a", false, 1_000, 5_000)
        val b = video("b", true, 3_000, 4_000)
        val c = video("c", true, 20_000, 2_000)
        assertEquals(mapOf("b" to "a"), ReadMarks.overlappingVideos(listOf(a, b, c), now = 30_000))
        // Still going: it overlaps what started after it.
        val live = video("d", false, 40_000, 0)
        val e = video("e", true, 41_000, 1_000)
        assertEquals("d", ReadMarks.overlappingVideos(listOf(live, e), now = 42_000)["e"])
        assertTrue(ReadMarks.overlappingVideos(listOf(c, video("f", true, 20_500, 1_000)), 30_000).isEmpty(), "same side: one under the other")
    }

    // --- The bubble's outline and fill, and the faces in the gaps (2026-11-26, hen) ----------------------------------

    @Test
    fun oneToOneABubbleIsDashedThenSolidThenFilled() {
        val chat = listOf(
            mine("a", 1, MessageStatus.READ), mine("b", 2, MessageStatus.DELIVERED), mine("c", 3, MessageStatus.SENT),
            mine("d", 4, MessageStatus.PENDING), mine("e", 5, MessageStatus.FAILED), theirs("f", 6),
        )
        val reach = ReadMarks.reaches(chat)
        assertEquals(ReadMarks.Reach.RECEIVED, reach["a"], "read is received")
        assertEquals(ReadMarks.Reach.RECEIVED, reach["b"])
        assertEquals(ReadMarks.Reach.ON_SERVER, reach["c"])
        assertEquals(ReadMarks.Reach.SENDING, reach["d"])
        assertEquals(ReadMarks.Reach.FAILED, reach["e"])
        assertNull(reach["f"], "theirs has none")
    }

    @Test
    fun whoeverHasALaterMessageHasTheOnesBefore() {
        // An older one whose receipt never came, and one that waits or failed: these two stay as they are.
        val chat = listOf(mine("a", 1, MessageStatus.SENT), mine("b", 2, MessageStatus.PENDING), mine("c", 3, MessageStatus.FAILED), mine("d", 4, MessageStatus.DELIVERED))
        val reach = ReadMarks.reaches(chat)
        assertEquals(ReadMarks.Reach.RECEIVED, reach["a"])
        assertEquals(ReadMarks.Reach.SENDING, reach["b"])
        assertEquals(ReadMarks.Reach.FAILED, reach["c"])
    }

    private fun inGroup(id: String, at: Long, status: MessageStatus = MessageStatus.SENT, got: List<String> = emptyList(), read: List<String> = emptyList()) =
        TextMessage(id, "group:x", outgoing = true, body = id, at = at, status = status, deliveredTo = got.associateWith { at + 100 }, readBy = read.associateWith { at + 200 })

    @Test
    fun inAGroupABubbleFillsOnlyWhenEveryMemberHasIt() {
        val members = listOf("dana", "noa", "tal")
        val chat = listOf(
            inGroup("a", 1, got = listOf("dana", "noa", "tal")),
            inGroup("b", 2, got = listOf("dana", "noa")),
            inGroup("c", 3, got = listOf("dana"), read = listOf("noa")),
            inGroup("d", 4, status = MessageStatus.PENDING),
            inGroup("e", 5, status = MessageStatus.FAILED),
        )
        val reach = ReadMarks.reaches(chat, members)
        assertEquals(ReadMarks.Reach.RECEIVED, reach["a"])
        assertEquals(ReadMarks.Reach.ON_SERVER, reach["b"], "tal's phone doesn't have it")
        assertEquals(ReadMarks.Reach.ON_SERVER, reach["c"])
        assertEquals(ReadMarks.Reach.SENDING, reach["d"])
        assertEquals(ReadMarks.Reach.FAILED, reach["e"])
        // Tal's phone gets the last one: all before it fill too; reading counts as having it.
        val later = chat + inGroup("f", 6, got = listOf("tal"), read = listOf("dana"))
        val after = ReadMarks.reaches(later, members)
        assertEquals(ReadMarks.Reach.RECEIVED, after["b"])
        assertEquals(ReadMarks.Reach.RECEIVED, after["c"])
        assertEquals(ReadMarks.Reach.ON_SERVER, after["f"], "noa's phone doesn't have the last one")
        // Someone who left the group, or isn't in it, is not waited for; alone in a group there is no one to wait for.
        assertEquals(ReadMarks.Reach.RECEIVED, ReadMarks.reaches(chat, listOf("dana", "noa"))["b"])
        assertEquals(ReadMarks.Reach.RECEIVED, ReadMarks.reaches(listOf(inGroup("a", 1)), emptyList())["a"])
        assertEquals(ReadMarks.Reach.ON_SERVER, ReadMarks.reaches(listOf(inGroup("a", 1)), members)["a"])
    }

    @Test
    fun oneToOneTheirFacesSitWhereTheyReadAndWhereTheirPhoneGot() {
        val a = mine("a", 1, MessageStatus.READ).copy(readAt = 50, deliveredAt = 40)
        val b = mine("b", 2, MessageStatus.DELIVERED).copy(deliveredAt = 60)
        val c = mine("c", 3, MessageStatus.SENT)
        val marks = ReadMarks.marks(listOf(a, theirs("t", 2), b, c))
        assertEquals(listOf(ReadMarks.Mark("sharon", read = true, at = 50)), marks["a"])
        assertEquals(listOf(ReadMarks.Mark("sharon", read = false, at = 60)), marks["b"])
        assertNull(marks["c"])
        // They read on: one gap for both, the dashed circle under the ringed face.
        val read = ReadMarks.marks(listOf(a, b.copy(status = MessageStatus.READ, readAt = 70), c))
        assertNull(read["a"])
        assertEquals(listOf(ReadMarks.Mark("sharon", read = true, at = 70), ReadMarks.Mark("sharon", read = false, at = 60, under = true)), read["b"])
        // Their presence is hidden: no ringed face, the dashed circle where their phone got to.
        val hidden = ReadMarks.marks(listOf(a, b, c), hiddenReaders = setOf("sharon"))
        assertNull(hidden["a"])
        assertEquals(listOf(ReadMarks.Mark("sharon", read = false, at = 60)), hidden["b"])
        assertTrue(ReadMarks.marks(listOf(c, theirs("t", 4))).isEmpty(), "nothing got anywhere: no faces")
    }

    @Test
    fun inAGroupEachMembersFacesAndAGapOnlyUnderWhatShows() {
        val members = listOf("dana", "noa", "tal")
        val a = inGroup("a", 1, got = listOf("dana", "noa", "tal"), read = listOf("dana", "noa"))
        val b = inGroup("b", 2, got = listOf("noa", "tal"), read = listOf("noa"))
        val c = inGroup("c", 3, got = listOf("tal"))
        val marks = ReadMarks.marks(listOf(a, b, c), members)
        assertEquals(listOf(ReadMarks.Mark("dana", true, 201), ReadMarks.Mark("dana", false, 101, under = true)), marks["a"])
        assertEquals(listOf(ReadMarks.Mark("noa", true, 202), ReadMarks.Mark("noa", false, 102, under = true)), marks["b"])
        assertEquals(listOf(ReadMarks.Mark("tal", false, 103)), marks["c"])
        // A message with no gap of its own on screen: its faces sit under the one before it that has one.
        val moved = ReadMarks.marks(listOf(a, b, c), members) { it.id != "c" }
        assertEquals(listOf("noa", "noa", "tal"), moved["b"]!!.map { it.who })
        assertNull(moved["c"])
        // Someone who isn't a member any more shows no face.
        assertNull(ReadMarks.marks(listOf(a, b, c), listOf("dana", "noa"))["c"])
    }

    @Test
    fun facesStandSideBySideAndTheRestAsOneCount() {
        val ring = { who: String -> ReadMarks.Mark(who, true, 1) }
        val dashed = { who: String, under: Boolean -> ReadMarks.Mark(who, false, 1, under) }
        val gap = listOf(ring("a"), ring("b"), dashed("a", true), dashed("c", false))
        val slots = ReadMarks.slots(gap, most = 5)
        assertEquals(listOf(0, 1, 0, 2), gap.map { slots[it] }, "a dashed circle under its ringed face shares its place")
        assertEquals(0, ReadMarks.more(gap, 5))
        // No room for all: the first ones, and the rest together in the last place.
        val many = listOf(ring("a"), ring("b"), ring("c"), dashed("d", false), dashed("e", false), dashed("b", true))
        val tight = ReadMarks.slots(many, most = 3)
        assertEquals(listOf(0, 1, 2, 2, 2, 1), many.map { tight[it] })
        assertEquals(3, ReadMarks.more(many, 3))
        assertEquals(0, ReadMarks.more(many, 5))
    }
}
