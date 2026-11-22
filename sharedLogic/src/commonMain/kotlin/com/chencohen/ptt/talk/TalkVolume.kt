package com.chencohen.ptt.talk

/** The volume talk plays at (Android: the media stream), as the phone allows it to be changed. */
interface TalkVolume {
    /** The stream's level now, 0 to [max]. */
    fun level(): Int

    fun max(): Int

    /** Sets it (shown to the user as the system volume change would be). */
    fun set(level: Int)

    /** False while the app may not change it: Do Not Disturb, or a fixed volume. */
    fun changeable(): Boolean
}

/**
 * Settings › Talk › Unmute for incoming talk (2026-11-22, hen; on by default): a burst that starts playing while the
 * media volume is at 0 raises it to [UNMUTED_SHARE] of the most for as long as bursts play, the queued ones and the
 * catch-up too, then puts it back to 0. A volume the user changed meanwhile is theirs and stays. Nothing changes under
 * Do Not Disturb, and never the ringer: only the stream talk plays on.
 */
class TalkUnmute(private val volume: () -> TalkVolume?, private val enabled: () -> Boolean) {
    private var held = false
    private var raisedTo: Int? = null

    /** A burst starts playing. */
    fun begin() {
        if (held) return
        held = true
        if (!enabled()) return
        val v = volume() ?: return
        runCatching {
            if (!v.changeable() || v.level() > 0) return
            val to = maxOf(1, (v.max() * UNMUTED_SHARE).toInt())
            v.set(to)
            raisedTo = to
        }
    }

    /** Nothing more plays (the last queued burst ended, or playing was stopped). */
    fun end() {
        if (!held) return
        held = false
        val to = raisedTo ?: return
        raisedTo = null
        val v = volume() ?: return
        runCatching { if (v.changeable() && v.level() == to) v.set(0) }
    }

    companion object {
        const val UNMUTED_SHARE = 0.6
    }
}
