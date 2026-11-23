package com.chencohen.ptt

import android.os.SystemClock
import android.util.Log

/**
 * How long a woken burst takes to show (2026-11-23, hen: "it takes time to show the video"): each step's time since
 * the push or socket frame that started it, in the log ("wake timeline").
 */
object WakeTimeline {
    @Volatile private var start = 0L

    /** The push (or the burst's first frame on the socket) that starts it all. */
    fun begin(what: String) {
        start = SystemClock.elapsedRealtime()
        Log.i("WakeTimeline", "wake timeline: $what at 0 ms")
    }

    fun mark(what: String) {
        val s = start
        if (s == 0L) return
        val ms = SystemClock.elapsedRealtime() - s
        if (ms > 60_000) return
        Log.i("WakeTimeline", "wake timeline: $what at $ms ms")
    }
}
