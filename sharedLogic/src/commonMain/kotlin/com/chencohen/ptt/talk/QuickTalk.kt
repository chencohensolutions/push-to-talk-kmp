package com.chencohen.ptt.talk

/**
 * Quick talk (docs/screens/quick-talk.md): the contacts and groups marked for the Talk tab, one page each, in the
 * order they were marked. The marks and the last page shown are kept on this phone (Settings, TalkSettings).
 */
object QuickTalk {
    /** [chats] with [chat] marked ([on]: added at the end, once) or unmarked. */
    fun set(chats: List<String>, chat: String, on: Boolean): List<String> =
        if (on) (chats - chat) + chat else chats - chat

    /** The Talk tab's pages: the marked chats that can still be talked in ([available]), in marked order. */
    fun pages(chats: List<String>, available: Set<String>): List<String> = chats.distinct().filter { it in available }

    /** Which page the Talk tab opens on: the one shown last, or the first. */
    fun startPage(pages: List<String>, remembered: String?): Int = pages.indexOf(remembered).coerceAtLeast(0)
}
