package com.chencohen.ptt.talk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TalkListTest {
    private fun row(chat: String, talking: Boolean = false, lastAt: Long = 0, since: Long = 0, unread: Int = 0, hearing: Boolean = false, group: Boolean = false) =
        TalkList.Row(chat, group, talking, lastAt, since, unread, hearing)

    @Test
    fun talkingFirstThenNewest() {
        val rows = listOf(row("dana", lastAt = 10), row("avi", lastAt = 30), row("yossi", talking = true, lastAt = 1), row("noa", since = 50))
        assertEquals(listOf("yossi", "avi", "dana", "noa"), TalkList.order(rows).map { it.chat })
    }

    @Test
    fun filters() {
        val rows = listOf(row("dana", hearing = true), row("avi", unread = 2), row("g:shift", group = true, hearing = true))
        assertEquals(listOf("dana", "g:shift"), TalkList.filter(rows, TalkList.Filter.HEARING).map { it.chat })
        assertEquals(listOf("avi"), TalkList.filter(rows, TalkList.Filter.UNREAD).map { it.chat })
        assertEquals(listOf("g:shift"), TalkList.filter(rows, TalkList.Filter.GROUPS).map { it.chat })
        assertEquals(3, TalkList.filter(rows, TalkList.Filter.ALL).size)
    }

    @Test
    fun theOpenRow() {
        val shown = listOf(row("dana"), row("avi"))
        assertEquals("avi", TalkList.open("avi", shown, null, busy = false))
        // Asked for one no longer shown (a filter): the first.
        assertEquals("dana", TalkList.open("noa", shown, null, busy = false))
        // Someone starts talking: theirs opens, not while my push runs.
        assertEquals("dana", TalkList.open("avi", shown, "dana", busy = false))
        assertEquals("avi", TalkList.open("avi", shown, "dana", busy = true))
        assertNull(TalkList.open(null, emptyList(), null, busy = false))
    }
}
