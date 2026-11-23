package com.chencohen.ptt.messaging

/**
 * How far the other user read (2026-11-23, hen, from the approved mockups): no "Read" word; under this phone's newest
 * messages a clock while sending, one tick sent, two ticks delivered; their small circle sits right after the last
 * message of mine they read (at the very end when they read everything); messages before it count as read.
 */
object ReadMarks {
    enum class Tick { NONE, SENDING, SENT, DELIVERED, FAILED }

    /** The last message of mine they read; null: none read (or they don't send read receipts). */
    fun lastRead(messages: List<TextMessage>): TextMessage? = messages.lastOrNull { it.outgoing && it.status == MessageStatus.READ }

    /** Their circle is at the very end: the last thing in the chat is the message they read last. */
    fun atEnd(messages: List<TextMessage>): Boolean {
        val read = lastRead(messages) ?: return false
        return messages.lastOrNull()?.id == read.id
    }

    /** The mark under [message]: nothing once read (it or a later one), else how far it got. */
    fun tick(messages: List<TextMessage>, message: TextMessage): Tick {
        if (!message.outgoing) return Tick.NONE
        val read = lastRead(messages)
        if (read != null && message.at <= read.at) return Tick.NONE
        return when (message.status) {
            MessageStatus.PENDING -> Tick.SENDING
            MessageStatus.SENT -> Tick.SENT
            MessageStatus.DELIVERED -> Tick.DELIVERED
            MessageStatus.READ -> Tick.NONE
            MessageStatus.FAILED -> Tick.FAILED
        }
    }

    /** Their circle shows only when their presence isn't hidden (hidden: no circle, ticks stop at delivered). */
    fun showsReader(presence: UserPresence?): Boolean = presence == null || presence.online || presence.lastSeen != null
}
