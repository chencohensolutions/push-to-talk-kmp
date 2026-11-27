package com.chencohen.ptt.talk

/**
 * How incoming talk sounds (2026-11-27, hen: "sometimes i hear it as low as a phone call and sometimes loud as audio
 * from the speaker. it suppose always to be loud as a speaker"). The rule, kept here so it can be tested: talk plays
 * as media on the loudspeaker at the media volume (a headset when one is on), in every state: on screen, in the
 * back, the screen off, live through the room or as a clip, hands-free, both talking. The exceptions are Android's:
 * off screen with no right to play media, only a Telecom talk session may sound, and that is call audio; it is then
 * moved to the loudspeaker, its voice boosted, and the call volume raised to the media's level where Android allows.
 *
 * What was found on hen's phone that night (its audio log): a press switched the phone to communication mode for the
 * speakerphone's microphone tuning, and the request stayed behind when the press ended while the mode already read
 * "normal" (the microphone had just stopped, so Android didn't count the request as active). From then on the phone
 * went back to communication mode whenever the microphone opened, with no speaker chosen: the room's media played on
 * the earpiece, as a phone call does.
 */
object TalkSound {
    /** What a player of incoming talk is: media (the loudspeaker, the media volume), or a call's voice. */
    enum class Kind { Media, Call }

    /**
     * How the room and the clips play now. Media whenever Android lets it sound: the app is on screen, or a
     * foreground service that may play from the back runs (Talk is ready with its rights, or Stay connected). A call's
     * voice only where nothing else may sound: off screen without either, in the Telecom talk session.
     */
    fun kind(onScreen: Boolean, readyWithRights: Boolean, stayConnected: Boolean): Kind =
        if (onScreen || readyWithRights || stayConnected) Kind.Media else Kind.Call

    /**
     * Whether a press may switch the phone to communication mode for the speakerphone's microphone tuning. Never while
     * incoming talk is heard or about to be ([hearing]: someone live in the room, a clip playing): communication mode
     * is what makes it sound like a phone call. Not with a headset (its own microphone), in a talk session (Telecom
     * has the mode), or while the mode isn't normal already (a phone call, or another app's).
     */
    fun micTuning(modeNormal: Boolean, headset: Boolean, hearing: Boolean, talkSession: Boolean): Boolean =
        modeNormal && !headset && !hearing && !talkSession

    /**
     * Whether incoming talk's own samples are made louder in the app (VoiceBoost): with the Boost voice setting, and
     * always as a call's voice. A call plays lower than media at the same volume step, and Android 17 ignores an
     * app's change of the call volume from off screen ("AudioHardening volume control ignored", seen 2026-11-27 in a
     * running talk session), so the app's own gain is the one thing left to make it as loud.
     */
    fun boost(setting: Boolean, kind: Kind): Boolean = setting || kind == Kind.Call

    /**
     * The call volume for a talk session, so it is as loud as media would be: the Talk volume's share of the most
     * ([talkVolume], null when the setting is off: the media volume's own share), never under the call volume found
     * ([callNow]) and never under [callMin] (Android refuses 0 for the call stream).
     */
    fun callLevel(mediaLevel: Int, mediaMax: Int, callNow: Int, callMin: Int, callMax: Int, talkVolume: Double?): Int {
        val share = (talkVolume ?: if (mediaMax > 0) mediaLevel.toDouble() / mediaMax else 1.0).coerceIn(0.0, 1.0)
        val floor = maxOf(callMin, 1)
        val top = maxOf(callMax, floor)
        return maxOf(kotlin.math.round(top * share).toInt(), callNow).coerceIn(floor, top)
    }
}
