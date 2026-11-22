package com.chencohen.ptt.talk

/**
 * What a home-screen talk widget for one conversation shows (docs/screens/widget.md): a widget can't be held, so a
 * tap starts talking and a second tap sends.
 */
sealed interface TalkWidgetView {
    /** "Tap to talk". */
    data object Idle : TalkWidgetView

    /** This phone talks there: "Talking 0:03 · tap to send", teal; ✕ cancels. */
    data class Talking(val seconds: Long) : TalkWidgetView

    /** Someone else holds the floor there: "Dana is talking", the circle muted. */
    data class Incoming(val user: String) : TalkWidgetView

    /** A press found the floor taken. */
    data class Busy(val user: String) : TalkWidgetView

    companion object {
        /** For [chat] from Talk's state; [startedAt] of this phone's own talking, to count seconds from. */
        fun of(chat: String, talk: TalkUiState, me: String, now: Long): TalkWidgetView {
            val own = talk.own?.takeIf { it.chat == chat }
            if (own != null) {
                return when (own.phase) {
                    TalkPhase.BUSY -> Busy(own.busyWith.orEmpty())
                    TalkPhase.REQUESTING, TalkPhase.TALKING -> Talking(((now - own.startedAt) / 1000).coerceAtLeast(0))
                    else -> Idle
                }
            }
            val talker = talk.talkers[chat]?.takeIf { it.user != me }
            if (talker != null) return Incoming(talker.user)
            val playing = talk.playing?.takeIf { it.chat == chat && it.from != me }
            if (playing != null) return Incoming(playing.from)
            return Idle
        }
    }
}

/** The chats a widget may be set up for: Quick talk ones first in their order, then the rest by name. */
object TalkWidgetChoices {
    fun order(available: List<Pair<String, String>>, quickTalk: List<String>): List<Pair<String, String>> {
        val byChat = available.toMap()
        val quick = quickTalk.filter { it in byChat }.map { it to byChat.getValue(it) }
        val rest = available.filter { it.first !in quickTalk }.sortedBy { it.second.lowercase() }
        return quick + rest
    }
}
