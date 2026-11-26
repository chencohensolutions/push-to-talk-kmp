package com.chencohen.ptt.talk

/**
 * Whether a user can hear a push now (2026-11-25, hen: "who can hear you"; the server's docs/messaging.md, "Can
 * hear"). This phone's own, sent to the server as HearState; a contact's, from their Presence.
 */
enum class Hear {
    /** Not said (an older app, or hidden). */
    UNKNOWN,
    /** Talk plays out loud. */
    OK,
    /** The ringer is silent or on vibrate, or the media volume is at 0 (with Talk volume off): saved for later. */
    SILENT,
    /** Do Not Disturb is on. */
    DND,
    /** In a phone call. */
    IN_CALL,
    /** No device of theirs is connected: they get it later. */
    OFFLINE;

    /** It plays out loud there now (unknown counts: an older app plays as before). */
    val hears: Boolean get() = this == OK || this == UNKNOWN

    /** The proto's `Hear` (HEAR_UNSPECIFIED 0, OK 1, SILENT 2, DND 3, IN_CALL 4). */
    val proto: Int get() = when (this) {
        OK -> 1
        SILENT -> 2
        DND -> 3
        IN_CALL -> 4
        UNKNOWN, OFFLINE -> 0
    }

    companion object {
        fun fromProto(value: Int): Hear = when (value) {
            1 -> OK
            2 -> SILENT
            3 -> DND
            4 -> IN_CALL
            else -> UNKNOWN
        }

        /** A contact as their presence shows them: offline, or what their phone said (unknown when it said nothing). */
        fun of(online: Boolean?, said: Hear?): Hear = when {
            online == false -> OFFLINE
            online == null -> UNKNOWN
            else -> said ?: UNKNOWN
        }

        /**
         * This phone's own state from what the platform sees: a call first, then Do Not Disturb, then silence.
         * [mediaMuted]: the media volume is at 0 and Talk volume won't raise it.
         */
        fun of(inCall: Boolean, dnd: Boolean, ringerSilent: Boolean, mediaMuted: Boolean): Hear = when {
            inCall -> IN_CALL
            dnd -> DND
            ringerSilent || mediaMuted -> SILENT
            else -> OK
        }
    }
}

/** What became of a push at its listener, sent back to the talker end to end (TalkHeardContent). */
object HeardStatus {
    /** It plays out loud there now. */
    const val HEARING = 1
    /** Saved: Do Not Disturb. */
    const val SAVED_DND = 2
    /** Saved: the phone is on silent. */
    const val SAVED_SILENT = 3
    /** Saved: they are in a call. */
    const val SAVED_IN_CALL = 4
    /** An urgent push, heard through Do Not Disturb. */
    const val HEARD_URGENT_DND = 5
    /** An urgent push, saved: they don't let urgent pushes from this talker through. */
    const val SAVED_URGENT_NOT_ALLOWED = 6
    /** An urgent push, heard though the phone is on silent. */
    const val HEARD_URGENT_SILENT = 7

    /** The highest known. */
    const val LAST = HEARD_URGENT_SILENT

    /** It reached their ears. */
    fun heard(status: Int): Boolean = status == HEARING || status == HEARD_URGENT_DND || status == HEARD_URGENT_SILENT
}

/** How a push that came in is to be heard here. */
sealed interface HearDecision {
    /** Out loud, as always. */
    data object Play : HearDecision
    /** Out loud through Do Not Disturb (an urgent push the user lets through for this talker). */
    data object ThroughDnd : HearDecision
    /** Not out loud: kept for later, a silent "Missed push" notification, and [status] back to the talker. */
    data class Save(val status: Int) : HearDecision
}

object CanHearRules {
    /**
     * A push arriving while this phone is [hear] (2026-11-25, hen): out loud only when it can hear; an [urgent] push
     * goes through Do Not Disturb or silence when the user let urgent pushes from its talker through ([allowed]) and
     * the app may open Do Not Disturb ([canOpenDnd]); otherwise it is saved.
     */
    fun decide(hear: Hear, urgent: Boolean, allowed: Boolean, canOpenDnd: Boolean): HearDecision = when (hear) {
        Hear.OK, Hear.UNKNOWN, Hear.OFFLINE -> HearDecision.Play
        Hear.IN_CALL -> HearDecision.Save(HeardStatus.SAVED_IN_CALL)
        Hear.DND, Hear.SILENT -> when {
            urgent && allowed && (hear == Hear.SILENT || canOpenDnd) -> HearDecision.ThroughDnd
            urgent -> HearDecision.Save(HeardStatus.SAVED_URGENT_NOT_ALLOWED)
            hear == Hear.DND -> HearDecision.Save(HeardStatus.SAVED_DND)
            else -> HearDecision.Save(HeardStatus.SAVED_SILENT)
        }
    }

    /** A group's "2 of 4 can hear now": how many of [members] (not this user) can hear, and how many there are. */
    fun canHearCount(members: List<Hear>): Pair<Int, Int> = members.count { it.hears } to members.size
}

/**
 * Do Not Disturb, opened for an urgent push the user lets through (Android: with Do Not Disturb access, the
 * interruption filter set to all while it plays, then put back).
 */
interface DndGate {
    /** The app may open it (Android: Do Not Disturb access granted). */
    fun canOpen(): Boolean

    /** Opens it; false when it couldn't. */
    fun open(): Boolean

    /** Puts it back as it was. */
    fun close()
}
