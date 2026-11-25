package com.chencohen.ptt.talk

/**
 * Whose microphone it is (MicGuard's rules, kept here so they can be tested).
 *
 * 2026-11-25 (hen: no sound live, both ways): the room's microphone track was let go and published anew on every push,
 * and every two seconds on a phone left idle, so the listener's subscription kept pointing at a track that was gone.
 * Two misreadings caused it: any silenced recording on the phone counted as "someone took ours" (this app's own clip
 * recorder, or another app's recorder that ours silenced), and this app's own talk session (a Telecom call it answers
 * itself) counted as a phone call that has the microphone.
 */
object MicRules {
    /** One active recording on the phone: its audio session, and whether the system silenced it. */
    data class Recording(val session: Int, val silenced: Boolean)

    /** The room microphone's own recordings: the ones that appeared when it opened, not the app's clip recorder. */
    fun roomSessions(before: Set<Int>, now: List<Recording>, own: Set<Int>): Set<Int> =
        now.map { it.session }.filter { it !in before && it !in own }.toSet()

    /** A call that isn't this app's own talk session has the microphone. */
    fun phoneCall(callMode: Boolean, ownTalkSession: Boolean): Boolean = callMode && !ownTalkSession

    /** The microphone is someone else's before ours opens: a phone call, or a recording that isn't ours. */
    fun busy(now: List<Recording>, own: Set<Int>, room: Set<Int>, phoneCall: Boolean): Boolean =
        phoneCall || now.any { it.session !in own && it.session !in room }

    /**
     * Ours is open and idle and someone took it: a phone call, or the room's own recording silenced. Unknown room
     * recordings (none seen yet) never count: letting go on a guess is what made the track come and go.
     */
    fun takenFromIdle(now: List<Recording>, room: Set<Int>, phoneCall: Boolean): Boolean =
        phoneCall || now.any { it.session in room && it.silenced }
}
