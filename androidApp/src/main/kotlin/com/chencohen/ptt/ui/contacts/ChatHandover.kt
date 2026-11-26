package com.chencohen.ptt.ui.contacts

import androidx.compose.runtime.mutableStateOf

/**
 * The floating chat growing into the app's chat, and back (2026-11-26, hen: "the navigation from floating to chat
 * screen should feel seamless, an expanding transformation"). The floating window grows over the whole screen showing
 * the chat as the app does; the app opens under it, unseen, and says here when its chat is drawn; only then the window
 * goes. What the two must agree on, so nothing moves at that moment, goes through here.
 */
internal object ChatHandover {
    /** [chat] is handed to the app with [draft] written in the window, at [at] (which tells one handing from the next). */
    data class Landing(val chat: String, val draft: String, val at: Long, val write: Boolean = false)

    /**
     * The chat the floating window hands to the app now: the app's chat comes up as the window shows it (at its end,
     * the keyboard away, the same words in the draft). The app's chat takes it once (and only while it is fresh: a
     * handing that never arrived isn't taken by a chat opened later).
     */
    val landing = mutableStateOf<Landing?>(null)

    /** The app's chat is on the screen, drawn (MainActivity): the floating window may go. */
    var onLanded: ((String) -> Unit)? = null

    /** The app left the screen (MainActivity.onStop): the window covering it may shrink back to floating. */
    var onLeft: (() -> Unit)? = null
}

/**
 * The Talk tab opening a chat (2026-11-26, hen picked option A of docs/screens/mocks/talk-live-list-review.png): where
 * each Quick talk row is on the screen, and the one about to open, so the chat comes up from it: the row rises into
 * the header, the messages come in, the bottom stays (380 ms), and Back goes the same way down.
 */
internal object TalkTabMove {
    /** Each row's top, in window pixels, as last laid out. */
    val rowTops = mutableMapOf<String, Float>()
    private var pending: Pair<String, Float>? = null

    fun go(chat: String) {
        pending = rowTops[chat]?.let { chat to it }
    }

    /** The row's top for [chat] when it is the chat about to open (once). */
    fun take(chat: String): Float? = pending?.takeIf { it.first == chat }?.second.also { pending = null }
}
