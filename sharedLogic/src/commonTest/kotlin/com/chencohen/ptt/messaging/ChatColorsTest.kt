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
    fun versionsAsSaved() {
        val saved = mapOf("testbot" to mapOf("1" to listOf("1.4.28", "1000"), "2" to listOf("1.4.27"), "x" to listOf("1.0")))
        val v = versionsOf(saved)
        assertEquals(DeviceVersion("1.4.28", 1000), v["testbot"]?.get(1))
        assertEquals(DeviceVersion("1.4.27", 0), v["testbot"]?.get(2))
        assertEquals(2, v["testbot"]?.size)
    }
}
