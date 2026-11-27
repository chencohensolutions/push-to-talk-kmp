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

    @Test
    fun syncedColoursArriveSwappedAndTheNewerChoiceWins() {
        // I chose at 100: green for me, blue for them.
        var s = TalkSettings().withColors("testbot", 2, 3, at = 100)
        // testbot chose at 200: orange for themselves, pink for me. Here that's pink for mine, orange for theirs.
        s = s.withSynced("testbot", mine = 0, theirs = 5, at = 200)
        assertEquals(5 to 0, s.ownColorsOf("testbot"))
        assertEquals(5 to 0, s.colorsOf("testbot"))
        // What I chose is kept underneath.
        assertEquals(2 to 3, s.chosenColorsOf("testbot"))
        // An older sync doesn't replace a newer one.
        s = s.withSynced("testbot", mine = 1, theirs = 1, at = 150)
        assertEquals(5 to 0, s.ownColorsOf("testbot"))
        // I choose again after: mine wins.
        s = s.withColors("testbot", 4, 0, at = 300)
        assertEquals(4 to 0, s.ownColorsOf("testbot"))
        // "Like everyone" synced as cleared.
        s = s.withSynced("testbot", mine = -1, theirs = -1, at = 400)
        assertEquals(-1 to -1, s.ownColorsOf("testbot"))
    }

    @Test
    fun syncedColoursCanBeIgnored() {
        var s = TalkSettings().withColors("testbot", 2, 3, at = 100).withSynced("testbot", mine = 0, theirs = 5, at = 200)
        s = s.copy(respectSyncedColors = false)
        assertEquals(2 to 3, s.ownColorsOf("testbot"))
        // Turned on again, theirs show again.
        assertEquals(5 to 0, s.copy(respectSyncedColors = true).ownColorsOf("testbot"))
    }

    @Test
    fun aGroupMemberTakesTheColourTheySynced() {
        // testbot2 chose violet for their own bubbles in our chat: in a group, testbot2 is violet here too.
        val s = TalkSettings().withSynced("testbot2", mine = 4, theirs = -1, at = 10)
        assertEquals(4, s.memberHues("grp", listOf("testbot", "testbot2"))["testbot2"])
    }

    @Test
    fun aChatsOwnBrightness() {
        var s = TalkSettings()
        assertEquals(null, s.brightOf("testbot"))
        s = s.withBright("testbot", 30, -1)
        assertEquals(listOf(30, -1), s.brightOf("testbot"))
        assertEquals(null, s.brightOf(GLOBAL_COLORS))
        assertEquals("*bright", brightKey(GLOBAL_COLORS))
        s = s.withBright("testbot", -1, -1)
        assertEquals(null, s.brightOf("testbot"))
        // A brightness key isn't a chat's colours.
        assertEquals(-1 to -1, s.ownColorsOf("testbot"))
    }
}
