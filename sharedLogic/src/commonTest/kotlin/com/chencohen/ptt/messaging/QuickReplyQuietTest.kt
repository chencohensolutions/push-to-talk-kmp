package com.chencohen.ptt.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickReplyQuietTest {
    private val minute = 60_000L

    @Test
    fun nothingIsHeldBackBeforeADismissal() {
        val q = QuickReplyQuiet()
        assertFalse(q.message("testbot", 0))
        assertFalse(q.holds("testbot", 0))
    }

    @Test
    fun afterADismissalMessagesAreNotificationsUntilFiveQuietMinutes() {
        val q = QuickReplyQuiet()
        q.dismissed("testbot", 0)
        assertTrue(q.message("testbot", 1 * minute))
        assertTrue(q.message("testbot", 4 * minute))
        // Each message starts the quiet again: 4 minutes after the last is still within it.
        assertTrue(q.message("testbot", 8 * minute))
        // Five minutes with nothing: the window may come back, and stays free after.
        assertFalse(q.message("testbot", 13 * minute))
        assertFalse(q.message("testbot", 13 * minute + 1))
    }

    @Test
    fun myOwnMessagesKeepTheConversationGoing() {
        val q = QuickReplyQuiet()
        q.dismissed("testbot", 0)
        // A reply from the notification at 4 minutes: theirs at 8 is still the same exchange.
        assertTrue(q.message("testbot", 4 * minute))
        assertTrue(q.message("testbot", 8 * minute))
    }

    @Test
    fun quietExactlyAtFiveMinutes() {
        val q = QuickReplyQuiet()
        q.dismissed("testbot", 0)
        assertTrue(q.holds("testbot", 5 * minute - 1))
        assertFalse(q.holds("testbot", 5 * minute))
    }

    @Test
    fun openingTheChatEndsItAtOnce() {
        val q = QuickReplyQuiet()
        q.dismissed("testbot", 0)
        q.opened("testbot")
        assertFalse(q.message("testbot", 1 * minute))
    }

    @Test
    fun eachConversationByItself() {
        val q = QuickReplyQuiet()
        q.dismissed("testbot", 0)
        assertFalse(q.message("testbot2", 1 * minute))
        assertTrue(q.message("testbot", 1 * minute))
        q.opened("testbot2")
        assertTrue(q.holds("testbot", 2 * minute))
    }
}
