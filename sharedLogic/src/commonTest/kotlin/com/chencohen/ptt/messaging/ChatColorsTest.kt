package com.chencohen.ptt.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatColorsTest {
    @Test
    fun colorsPerChatAndBackToDefault() {
        var s = TalkSettings()
        assertEquals(-1 to -1, s.colorsOf("testbot"))
        s = s.withColors("testbot", 3, -1)
        assertEquals(3 to -1, s.colorsOf("testbot"))
        assertEquals(-1 to -1, s.colorsOf("testbot2"))
        s = s.withColors("testbot", 3, 5)
        assertEquals(3 to 5, s.colorsOf("testbot"))
        // Both default: nothing kept for it.
        s = s.withColors("testbot", -1, -1)
        assertEquals(emptyMap(), s.colorsFor)
    }

    @Test
    fun everyChatTakesTheGlobalColoursUnlessItHasItsOwn() {
        var s = TalkSettings().withColors(GLOBAL_COLORS, 2, 7)
        assertEquals(2 to 7, s.colorsOf("testbot"))
        s = s.withColors("testbot", 4, -1)
        // Its own for mine, everyone's for theirs.
        assertEquals(4 to 7, s.colorsOf("testbot"))
        assertEquals(4 to -1, s.ownColorsOf("testbot"))
        assertEquals(2 to 7, s.colorsOf("testbot2"))
        s = s.withColors(GLOBAL_COLORS, -1, -1)
        assertEquals(4 to -1, s.colorsOf("testbot"))
    }

    @Test
    fun everyGroupMemberHasAColour() {
        var s = TalkSettings()
        val members = listOf("testbot", "testbot2", "testbot3")
        val auto = s.memberHues("g:test", members)
        // Each its own, never my green (hue 2), and the same each time.
        assertEquals(3, auto.values.toSet().size)
        kotlin.test.assertTrue(2 !in auto.values)
        assertEquals(auto, s.memberHues("g:test", members.reversed()))
        // A colour chosen on testbot2's page wins.
        s = s.withColors("testbot2", -1, 4)
        assertEquals(4, s.memberHues("g:test", members)["testbot2"])
        // And nobody else gets that one automatically.
        assertEquals(1, s.memberHues("g:test", members).values.count { it == 4 })
        // My messages in blue (hue 3): no member gets blue automatically.
        s = TalkSettings().withColors("g:test", 3, -1)
        kotlin.test.assertTrue(3 !in s.memberHues("g:test", members).values)
    }

    @Test
    fun versionsAsSaved() {
        val saved = mapOf("testbot" to mapOf("1" to listOf("1.4.28", "1000"), "2" to listOf("1.4.27"), "x" to listOf("1.0")))
        val v = versionsOf(saved)
        assertEquals(DeviceVersion("1.4.28", 1000), v["testbot"]?.get(1))
        assertEquals(DeviceVersion("1.4.27", 0), v["testbot"]?.get(2))
        assertEquals(2, v["testbot"]?.size)
    }
}
