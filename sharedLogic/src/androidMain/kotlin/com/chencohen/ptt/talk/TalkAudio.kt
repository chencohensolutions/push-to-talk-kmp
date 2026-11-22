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

    /** The voice playing now and the microphone's, 0 to 1 (VoiceLevel.fromRms), for the talk button; 0 when quiet. */
    @Volatile
    var playLevel: Float = 0f

    @Volatile
    var micLevel: Float = 0f

    /** For the player's diagnostics (debug test of background playback). */
    @Volatile
    var appContext: android.content.Context? = null
}
