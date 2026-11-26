package com.chencohen.ptt.messaging

/**
 * After the quick-reply window was dismissed (2026-11-26, hen: "after dismissing, new text messages should appear as
 * notifications until the chat goes quiet"): that conversation's next messages don't bring the window back; they are
 * notifications, as with the window off. It lasts while the conversation goes on: until no message has come or gone in
 * it for [QUIET_MS], or the user opens that chat themselves. Each conversation by itself. Pushes (talk) aren't its
 * business: they play as always.
 */
class QuickReplyQuiet(private val quietMs: Long = QUIET_MS) {
    /** Dismissed conversations: when each last had a message (or was dismissed). */
    private val last = mutableMapOf<String, Long>()

    /** The user dismissed the window showing [chat] (×, a swipe down, Back), at [now]. */
    fun dismissed(chat: String, now: Long) {
        last[chat] = now
    }

    /**
     * A message in [chat] at [now], either way (theirs coming in, mine going out). True while the window stays away
     * for it (a notification instead); the conversation goes on, so the quiet it waits for starts again.
     */
    fun message(chat: String, now: Long): Boolean {
        if (!holds(chat, now)) return false
        last[chat] = now
        return true
    }

    /** The window stays away for [chat] at [now]: dismissed, and not yet quiet for [quietMs]. */
    fun holds(chat: String, now: Long): Boolean {
        val at = last[chat] ?: return false
        if (now - at >= quietMs) {
            last.remove(chat)
            return false
        }
        return true
    }

    /** The user opened [chat] themselves (in the app): the window may come for it again. */
    fun opened(chat: String) {
        last.remove(chat)
    }

    companion object {
        /**
         * How long without a message, either way, before the window may come back: 5 minutes. A reply usually comes
         * within a minute or two, so shorter would bring the window back in the middle of the exchange it was
         * dismissed from; longer, and a new exchange an hour's quarter later would still be held back.
         */
        const val QUIET_MS = 5 * 60_000L
    }
}
