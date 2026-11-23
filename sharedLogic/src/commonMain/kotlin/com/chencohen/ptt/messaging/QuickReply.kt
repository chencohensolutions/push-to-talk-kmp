package com.chencohen.ptt.messaging

/**
 * The quick-reply window (2026-11-23, hen): a message that comes in while the phone is in use shows that conversation
 * in a floating window at the bottom of the screen, to read the last few messages, type a reply or hold to talk.
 * This decides which conversation it shows; the window itself is the phone's (Android: QuickReplyWindow).
 *
 * One conversation at a time. A message in the one shown updates it; from another, the window switches to it unless
 * the user is busy in it (typing or talking), then that one waits its turn and shows when the window closes.
 */
class QuickReply {
    /** What a message's arrival does to the window. */
    sealed interface Step {
        /** Nothing: the setting is off, or that conversation is open in the app, in front. */
        data object None : Step

        /** Show [chat] (the window opens, or switches to it). */
        data class Show(val chat: String) : Step

        /** The conversation shown has more: the window updates. */
        data object Update : Step

        /** Shown after the one the user is busy in. */
        data object Wait : Step
    }

    var shown: String? = null
        private set
    private val waiting = ArrayDeque<String>()

    /** The conversations waiting their turn, oldest first. */
    val queue: List<String> get() = waiting.toList()

    /**
     * A message came into [chat]. [busy]: the user types or talks in the window; [openInApp]: the conversation open
     * on the app's screen while it is in front (null: none, or the app is behind).
     */
    fun arrived(chat: String, on: Boolean, openInApp: String?, busy: Boolean): Step {
        if (!on || openInApp == chat) return Step.None
        val now = shown
        return when {
            now == null -> Step.Show(chat).also { shown = chat }
            now == chat -> Step.Update
            busy -> {
                if (chat !in waiting) waiting.addLast(chat)
                Step.Wait
            }
            else -> {
                waiting.remove(chat)
                shown = chat
                Step.Show(chat)
            }
        }
    }

    /** The window closed (by the user, by itself, or the conversation opened in the app): the next waiting, if any. */
    fun closed(openInApp: String? = null): String? {
        shown = null
        while (waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            if (next != openInApp) {
                shown = next
                return next
            }
        }
        return null
    }

    companion object {
        /** Untouched this long, with nothing typed and nobody talking, the window closes by itself. */
        const val IDLE_MS = 20_000L

        fun idle(now: Long, touchedAt: Long, typed: String, talking: Boolean): Boolean =
            !talking && typed.isBlank() && now - touchedAt >= IDLE_MS
    }
}
