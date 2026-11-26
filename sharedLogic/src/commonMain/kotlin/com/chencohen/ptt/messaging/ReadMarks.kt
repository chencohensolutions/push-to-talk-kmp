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

    /** Every message's mark at once (one pass for the whole chat). */
    fun ticks(messages: List<TextMessage>): Map<String, Tick> {
        val read = lastRead(messages)
        return messages.associate { m ->
            m.id to when {
                !m.outgoing -> Tick.NONE
                read != null && m.at <= read.at -> Tick.NONE
                else -> when (m.status) {
                    MessageStatus.PENDING -> Tick.SENDING
                    MessageStatus.SENT -> Tick.SENT
                    MessageStatus.DELIVERED -> Tick.DELIVERED
                    MessageStatus.READ -> Tick.NONE
                    MessageStatus.FAILED -> Tick.FAILED
                }
            }
        }
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

    /**
     * In a group: where each member's circle sits, by message id: the last message of mine each has read, with who
     * and when, newest reader first ([hidden]: members whose circle isn't shown).
     */
    fun groupReaders(messages: List<TextMessage>, hidden: Set<String> = emptySet()): Map<String, List<Pair<String, Long>>> {
        val last = HashMap<String, Pair<TextMessage, Long>>()
        for (m in messages) {
            if (!m.outgoing) continue
            for ((who, at) in m.readBy) if (who !in hidden) last[who] = m to at
        }
        return last.entries.groupBy({ it.value.first.id }, { it.key to it.value.second }).mapValues { (_, v) -> v.sortedByDescending { it.second } }
    }

    /** A group message of mine counts as read once anyone read it or a later one. */
    fun groupTick(messages: List<TextMessage>, message: TextMessage): Tick {
        if (!message.outgoing) return Tick.NONE
        val lastReadAt = messages.lastOrNull { it.outgoing && it.readBy.isNotEmpty() }?.at
        if (lastReadAt != null && message.at <= lastReadAt) return Tick.NONE
        return tick(messages.filter { it.status != MessageStatus.READ }, message)
    }

    /**
     * How far a message of mine got, as its bubble shows it (2026-11-26, hen): a dashed outline until the server has
     * it, a solid outline on the server, filled once every participant's phone has it.
     */
    enum class Reach { SENDING, ON_SERVER, RECEIVED, FAILED }

    /**
     * One person's face in the gap under a message of mine: [read] the ringed face after the last one they read, else
     * the dashed circle under the last one their phone has. [at]: when, by this phone's clock (null: not known).
     * [under]: a dashed circle at the very place of the same person's ringed face, which covers it.
     */
    data class Mark(val who: String, val read: Boolean, val at: Long?, val under: Boolean = false)

    /** How far each participant got in [messages] (their places in it; -1: nowhere). */
    private class Progress(val received: Map<String, Int>, val read: Map<String, Int>)

    /**
     * [members]: a group's other members; null: one to one, the participant is each message's `user`. What was read
     * was received, and whoever has a later message has the ones before it.
     */
    private fun progress(messages: List<TextMessage>, members: Collection<String>?): Progress {
        val received = HashMap<String, Int>()
        val read = HashMap<String, Int>()
        messages.forEachIndexed { i, m ->
            if (!m.outgoing) return@forEachIndexed
            if (members == null) {
                if (m.status == MessageStatus.DELIVERED || m.status == MessageStatus.READ) received[m.user] = i
                if (m.status == MessageStatus.READ) read[m.user] = i
            } else {
                for (who in m.deliveredTo.keys) if (who in members) received[who] = i
                for (who in m.readBy.keys) if (who in members) {
                    received[who] = i
                    read[who] = i
                }
            }
        }
        return Progress(received, read)
    }

    /**
     * Every message of mine's [Reach], by id (one pass for the whole chat). One to one ([members] null): received once
     * their phone has it. In a group: once every one of [members] (the others) has it; a group of me alone has no one
     * to wait for.
     */
    fun reaches(messages: List<TextMessage>, members: Collection<String>? = null): Map<String, Reach> {
        val progress = progress(messages, members)
        val out = HashMap<String, Reach>()
        messages.forEachIndexed { i, m ->
            if (!m.outgoing) return@forEachIndexed
            val everyone = members ?: listOf(m.user)
            out[m.id] = when {
                m.status == MessageStatus.PENDING -> Reach.SENDING
                m.status == MessageStatus.FAILED -> Reach.FAILED
                everyone.all { (progress.received[it] ?: -1) >= i } -> Reach.RECEIVED
                else -> Reach.ON_SERVER
            }
        }
        return out
    }

    /** One face and the gap it is in: under the message of mine [host] (its id). */
    data class Place(val who: String, val read: Boolean, val at: Long?, val host: String)

    /**
     * Every face and its gap: for each participant a ringed face after the last message of mine they read (not for
     * [hiddenReaders]: they don't show how far they read) and a dashed circle under the last one their phone has.
     * [anchor]: the messages that have a gap of their own on screen; a face whose message has none sits under the last
     * one before it that has.
     */
    fun places(
        messages: List<TextMessage>,
        members: Collection<String>? = null,
        hiddenReaders: Set<String> = emptySet(),
        anchor: (TextMessage) -> Boolean = { true },
    ): List<Place> {
        val progress = progress(messages, members)
        // The last message of mine with a gap at or before each place.
        val gapAt = arrayOfNulls<TextMessage>(messages.size)
        var last: TextMessage? = null
        messages.forEachIndexed { i, m ->
            if (m.outgoing && anchor(m)) last = m
            gapAt[i] = last
        }
        val out = ArrayList<Place>()
        for ((who, receivedAt) in progress.received) {
            val readAt = progress.read[who]?.takeIf { who !in hiddenReaders }
            readAt?.let { gapAt[it] }?.let { gap ->
                val m = messages[readAt]
                out += Place(who, read = true, at = if (members == null) m.readAt else m.readBy[who], host = gap.id)
            }
            gapAt[receivedAt]?.let { gap ->
                val m = messages[receivedAt]
                out += Place(who, read = false, at = if (members == null) m.deliveredAt ?: m.readAt else m.deliveredTo[who] ?: m.readBy[who], host = gap.id)
            }
        }
        return out
    }

    /**
     * The faces of each gap, by its message's id: ringed faces first, then dashed circles, each by name (a steady
     * order: nothing shuffles as others move); a dashed circle in the gap of the same person's ringed face is
     * [Mark.under] it.
     */
    fun gaps(places: List<Place>): Map<String, List<Mark>> = places.groupBy { it.host }.mapValues { (_, here) ->
        val readers = here.filter { it.read }.map { it.who }.toSet()
        here.map { Mark(it.who, it.read, it.at, under = !it.read && it.who in readers) }.sortedWith(compareBy({ !it.read }, { it.who }))
    }

    /** The faces in the gaps, by the id of the message of mine each gap is under ([places], then [gaps]). */
    fun marks(
        messages: List<TextMessage>,
        members: Collection<String>? = null,
        hiddenReaders: Set<String> = emptySet(),
        anchor: (TextMessage) -> Boolean = { true },
    ): Map<String, List<Mark>> = gaps(places(messages, members, hiddenReaders, anchor))

    /**
     * Where each face of a gap stands, counted from my side inward: the ringed faces and the dashed circles that show,
     * in order; a dashed circle [Mark.under] its ringed face shares its place. With more than [most] places: the first
     * [most] - 1, and the rest together in the last place ("+N").
     */
    fun slots(marks: List<Mark>, most: Int): Map<Mark, Int> {
        val shown = marks.filter { !it.under }
        val last = (most - 1).coerceAtLeast(0)
        val place = shown.withIndex().associate { (i, mark) -> mark to if (shown.size > most) minOf(i, last) else i }
        return marks.associateWith { mark -> if (mark.under) place.entries.firstOrNull { it.key.read && it.key.who == mark.who }?.value ?: 0 else place.getValue(mark) }
    }

    /** How many faces of a gap don't show on their own ("+N"); 0: all show. */
    fun more(marks: List<Mark>, most: Int): Int = marks.count { !it.under }.let { if (it > most) it - (most - 1).coerceAtLeast(0) else 0 }

    /**
     * Two video bursts that overlap in time, one each side (2026-11-23, hen: "show them side by side"): by the later
     * one's id, the earlier one's. A burst still going ([now], its length unknown) runs until now.
     */
    fun overlappingVideos(messages: List<TextMessage>, now: Long): Map<String, String> {
        val videos = messages.filter { it.clip?.video == true }.sortedBy { it.at }
        val pairs = HashMap<String, String>()
        val used = HashSet<String>()
        for (i in videos.indices) {
            val a = videos[i]
            if (a.id in used) continue
            val aEnd = a.at + (a.clip?.durationMs?.takeIf { it > 0 } ?: (now - a.at).coerceAtLeast(0))
            val b = videos.drop(i + 1).firstOrNull { it.id !in used && it.outgoing != a.outgoing && it.at < aEnd } ?: continue
            pairs[b.id] = a.id
            used += a.id
            used += b.id
        }
        return pairs
    }

    /** Their circle shows only when their presence isn't hidden (hidden: no circle, ticks stop at delivered). */
    fun showsReader(presence: UserPresence?): Boolean = presence == null || presence.online || presence.lastSeen != null
}
