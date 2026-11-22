package com.chencohen.ptt.talk

import com.chencohen.ptt.settings.KeyValueStore
import com.chencohen.ptt.settings.PttSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickTalkTest {
    @Test
    fun marksKeepTheOrderTheyWereMadeIn() {
        var chats = emptyList<String>()
        chats = QuickTalk.set(chats, "dana", true)
        chats = QuickTalk.set(chats, "g:family", true)
        chats = QuickTalk.set(chats, "avi", true)
        assertEquals(listOf("dana", "g:family", "avi"), chats)
        // Marking again doesn't add it twice; it goes to the end, as a new mark.
        assertEquals(listOf("g:family", "avi", "dana"), QuickTalk.set(chats, "dana", true))
        assertEquals(listOf("dana", "avi"), QuickTalk.set(chats, "g:family", false))
        assertEquals(chats, QuickTalk.set(chats, "noa", false))
    }

    @Test
    fun pagesAreTheMarkedChatsThatCanStillTalk() {
        val chats = listOf("dana", "g:old", "avi", "dana")
        assertEquals(listOf("dana", "avi"), QuickTalk.pages(chats, setOf("avi", "dana", "noa")))
        assertEquals(emptyList(), QuickTalk.pages(chats, emptySet()))
    }

    @Test
    fun theTabOpensOnThePageShownLast() {
        val pages = listOf("dana", "g:family", "avi")
        assertEquals(1, QuickTalk.startPage(pages, "g:family"))
        assertEquals(0, QuickTalk.startPage(pages, null))
        assertEquals(0, QuickTalk.startPage(pages, "gone"))
        assertEquals(0, QuickTalk.startPage(emptyList(), "dana"))
    }

    @Test
    fun marksAndThePageAreKeptOnThePhone() {
        val store = Store()
        val settings = PttSettings(store)
        assertEquals(emptyList(), settings.quickTalkChats)
        assertNull(settings.quickTalkPage)
        settings.quickTalkChats = listOf("dana", "g:family")
        settings.quickTalkPage = "g:family"
        val again = PttSettings(store)
        assertEquals(listOf("dana", "g:family"), again.quickTalkChats)
        assertEquals("g:family", again.quickTalkPage)
    }

    private class Store : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
