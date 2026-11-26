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
 * Settings › Talk › Talk volume (2026-11-25, hen: "turn up the volume when incoming voice"; on by default, the switch
 * that was Unmute for incoming talk): while someone else's push is heard (played, or live in the room), the media
 * stream is set to the Talk volume, [share] of the most (80% unless changed), and the volume from before comes back
 * when it is over (TalkDuck: 2 s after the last push). The volume keys meanwhile change the media stream, so the level
 * found at the end, if it isn't the one set, is the new Talk volume ([save]). Nothing changes under Do Not Disturb, on
 * a fixed-volume phone or in a call, and never the ringer: only the stream talk plays on.
 */
class TalkLoudness(
    private val volume: () -> TalkVolume?,
    private val enabled: () -> Boolean,
    private val share: () -> Double,
    private val save: (Double) -> Unit,
) {
    private var held = false
    private var before: Int? = null
    private var setTo: Int? = null

    /** Someone's push starts being heard. */
    fun begin() {
        if (held) return
        held = true
        if (!enabled()) return
        val v = volume() ?: return
        runCatching {
            if (!v.changeable()) return
            val max = v.max()
            val to = levelFor(share(), max)
            before = v.level()
            setTo = to
            if (v.level() != to) v.set(to)
        }
    }

    /** Nothing is heard any more: the volume from before comes back; a level changed meanwhile is the Talk volume. */
    fun end() {
        if (!held) return
        held = false
        val from = before ?: return
        val to = setTo
        before = null
        setTo = null
        val v = volume() ?: return
        runCatching {
            if (!v.changeable()) return
            val now = v.level()
            if (to != null && now != to && v.max() > 0) save(now.toDouble() / v.max())
            if (now != from) v.set(from)
        }
    }

    companion object {
        const val DEFAULT_SHARE = 0.8

        /** The stream level for [share] of [max]: never 0 (talk is meant to be heard). */
        fun levelFor(share: Double, max: Int): Int = (kotlin.math.round(max * share.coerceIn(0.0, 1.0))).toInt().coerceIn(1, maxOf(1, max))
    }
}
