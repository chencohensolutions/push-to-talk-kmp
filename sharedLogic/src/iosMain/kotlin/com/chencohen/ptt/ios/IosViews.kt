package com.chencohen.ptt.ios

import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.MessagingState
import com.chencohen.ptt.messaging.TalkSettings
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.messaging.UserPresence

/** One row of the Chats list: a contact's chat or a group's, with what the row shows. */
class ChatRow(
    /** The chat's key: the contact's user, or a group's "#id". */
    val chat: String,
    val contact: Contact?,
    val group: Group?,
    val last: TextMessage?,
    val unread: Int,
    val online: Boolean,
)

/**
 * What the SwiftUI screens read out of [MessagingState]. The state's maps hold every chat and every message; read
 * from Swift they would each be copied into a Swift dictionary on every change, so the screens ask here for the one
 * piece they draw, and the choosing and ordering (the same as Android's home screen) stay in Kotlin.
 */
object IosViews {
    /** Chats as the list shows them: the latest text first, then the newest contacts and groups. */
    fun chatRows(state: MessagingState, me: String): List<ChatRow> {
        val contacts = state.contacts.map { ChatRow(it.user, it, null, state.lastMessages[it.user], state.unread[it.user] ?: 0, state.presence[it.user]?.online == true) to it.addedAt }
        val groups = state.groups.filter { !it.isInvitation(me) }.map { ChatRow(it.chat, null, it, state.lastMessages[it.chat], state.unread[it.chat] ?: 0, false) to it.createdAt }
        return (contacts + groups)
            .sortedWith(compareByDescending<Pair<ChatRow, Long>> { it.first.last?.at ?: 0L }.thenByDescending { it.second })
            .map { it.first }
    }

    /** The groups this user is invited to and hasn't joined: a card each on Chats. */
    fun groupInvitations(state: MessagingState, me: String): List<Group> = state.groups.filter { it.isInvitation(me) }

    /**
     * A chat's last [limit] messages, oldest first. The conversation screen draws these and asks for more when it is
     * scrolled to their top, so a long chat opens as fast as a short one.
     */
    fun messages(state: MessagingState, chat: String, limit: Int): List<TextMessage> = state.conversations[chat].orEmpty().takeLast(limit)

    /** How many messages [chat] has in all. */
    fun messageCount(state: MessagingState, chat: String): Int = state.conversations[chat]?.size ?: 0

    fun contact(state: MessagingState, user: String): Contact? = state.contacts.firstOrNull { it.user == user }

    /** The group of a chat key ("#id") or of an id. */
    fun group(state: MessagingState, chat: String): Group? {
        val id = Group.idOf(chat) ?: chat
        return state.groups.firstOrNull { it.id == id }
    }

    fun isGroup(chat: String): Boolean = Group.idOf(chat) != null

    fun presence(state: MessagingState, user: String): UserPresence? = state.presence[user]

    /** Who is typing in [chat] now: the contact, or a group's members. */
    fun typing(state: MessagingState, chat: String, now: Long): List<String> =
        if (isGroup(chat)) state.typing.filter { it.key.startsWith("$chat/") && it.value > now }.map { it.key.substringAfter('/') }
        else listOfNotNull(chat.takeIf { (state.typing[chat] ?: 0L) > now })

    /** Whether [user] has a picture, and its id (it changes when the picture does). */
    fun pictureId(state: MessagingState, user: String): String? = state.pictures[user]

    /** Unread texts in all chats: the app icon's badge. */
    fun unreadTotal(state: MessagingState): Int = state.unread.values.sum()

    /** A name for [user] in a group: the contact's name when they are one, else the user name. */
    fun nameOf(state: MessagingState, user: String): String = contact(state, user)?.name ?: user

    /** [settings] with Live typing [on] (Swift has no copy with one changed field). */
    fun withLiveTyping(settings: TalkSettings, on: Boolean): TalkSettings = settings.copy(liveTyping = on)
}
