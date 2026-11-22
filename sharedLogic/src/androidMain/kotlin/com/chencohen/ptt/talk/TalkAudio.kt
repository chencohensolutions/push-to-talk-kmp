package com.chencohen.ptt.talk

/**
 * How talk is heard right now (docs/design.md, "Woken by talk"). Normally as media (USAGE_MEDIA: the loudspeaker,
 * media volume). While a woken burst plays in a Telecom talk session ([inCallSession]), as that call's voice
 * (USAGE_VOICE_COMMUNICATION): Android 17 lets an app play from the background only inside such a session, and mutes
 * media tracks while a call is on; the session itself is moved to the loudspeaker.
 */
object TalkAudio {
    @Volatile
    var inCallSession: Boolean = false
}
