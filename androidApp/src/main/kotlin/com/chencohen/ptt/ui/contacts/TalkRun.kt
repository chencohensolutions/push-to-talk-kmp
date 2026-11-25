package com.chencohen.ptt.ui.contacts

/**
 * One run of mine in a chat, from a finger going down on Push to its end (2026-11-25, hen: after Stop an own voice
 * burst went on, sending what nobody meant to send). Whatever a press sets going for later (the video that follows
 * the lock, a hands-free renewal, a standby's start when their floor ends) may start a burst only while that
 * press's run is still the current one: a new finger-down begins a new run, and Stop, or letting go without the
 * lock, ends it. So Stop is final: nothing set going before it starts anything after it.
 */
internal class TalkRun {
    private var run = 0

    /** The run now: what a chain keeps when it is set going, and shows again before it starts anything. */
    val now: Int get() = run

    /** A finger went down on Push: a new run begins. */
    fun fingerDown(): Int = ++run

    /** Stop, or let go without the lock, or the run's floor is gone: the run is over. */
    fun end() {
        run++
    }

    /** What was set going in run [token] may still start a burst. */
    fun may(token: Int): Boolean = token == run
}
