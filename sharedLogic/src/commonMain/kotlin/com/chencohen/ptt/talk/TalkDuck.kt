package com.chencohen.ptt.talk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Other apps' sound, lowered while talk is heard (Android: transient audio focus, "may duck"). */
interface AudioDuck {
    /** Lower the others; false when the phone refused (talk plays anyway). */
    fun take(): Boolean

    /** Give their sound back. */
    fun release()
}

/**
 * Dims other apps while someone else's push is heard (played, or live in the room) (2026-11-25, hen: "Sharon now listen to audio on
 * other app and barely hear me"): the first heard burst takes the duck, and it goes back [RELEASE_AFTER_MS] after the
 * last heard one ends, so a new push right after keeps the music down instead of bouncing it. A refused duck isn't
 * asked again until it was released. Own pushes never come here.
 */
class TalkDuck(
    private val scope: CoroutineScope,
    private val duck: () -> AudioDuck?,
    /** Talk starts / stops being heard, on the same timing (TalkLoudness: the Talk volume). */
    private val onHeard: () -> Unit,
    private val onQuiet: () -> Unit,
) {
    private val heard = mutableSetOf<String>()
    private var held = false
    /** The one taken (given back even if the setting was turned off meanwhile). */
    private var taken: AudioDuck? = null
    private var releasing: Job? = null

    /** Whether other apps are dimmed for talk now (or asked to be). */
    val holding: Boolean get() = held

    /** Burst [id] is heard live now. */
    fun heard(id: String) {
        heard += id
        releasing?.cancel()
        releasing = null
        if (held) return
        held = true
        runCatching { onHeard() }
        val d = duck() ?: return
        if (runCatching { d.take() }.getOrDefault(false)) taken = d
    }

    /** Burst [id] ended (its floor ended, went stale, or the room was left); unknown ones change nothing. */
    fun quiet(id: String) {
        if (!heard.remove(id) || heard.isNotEmpty() || !held) return
        releasing?.cancel()
        releasing = scope.launch {
            delay(RELEASE_AFTER_MS)
            releasing = null
            if (heard.isEmpty()) release()
        }
    }

    /** The room is gone: nothing is heard, the others' sound comes back now. */
    fun clear() {
        heard.clear()
        releasing?.cancel()
        releasing = null
        release()
    }

    private fun release() {
        if (!held) return
        held = false
        runCatching { onQuiet() }
        val d = taken ?: return
        taken = null
        runCatching { d.release() }
    }

    companion object {
        const val RELEASE_AFTER_MS = 2_000L
    }
}
