package com.chencohen.ptt.talk

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Whether the microphone is someone else's now (2026-11-25, hen: "if the phone already uses the microphone for some
 * reason don't hijack it"): a phone call, or another app recording. Talk then doesn't open it ahead of a press, lets go
 * of the one it holds idle when another app starts, and a press is refused ("The microphone isn't free") rather than
 * going live.
 */
object MicGuard {
    private const val TAG = "MicGuard"
    private var app: Context? = null

    /** This app's own clip-path recordings (AndroidTalkCapture), by session: not someone else's. */
    val ownSessions: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    /** The recordings going when the room microphone started opening (null: it isn't open). */
    @Volatile private var roomBefore: Set<Int>? = null

    /** The room microphone's own recordings, found once they show up (MicRules.roomSessions). */
    private val roomSessions: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    /** The room microphone is about to open: what records now isn't it. */
    fun roomOpening() {
        roomSessions.clear()
        val now = recordings()
        roomBefore = now.map { it.session }.toSet()
        openedAt = System.currentTimeMillis()
    }

    @Volatile private var openedAt = 0L
    @Volatile private var closedAt = 0L
    @Volatile private var openBefore: Set<Int> = emptySet()

    /** When each recording was first seen here (a room's own shows up a moment after it opens, or after it closes). */
    private val firstSeen = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * The room microphone went. Its recordings stay this app's: Android can keep one going a moment after it is let go,
     * and the next open (the screen changing while the app starts) found it and called the microphone busy, every two
     * seconds, with only Talk recording (2026-11-28, the S25's audio log at 13:10:58).
     */
    fun roomClosed() {
        roomBefore?.let { before ->
            room(recordings())
            openBefore = before
            closedAt = System.currentTimeMillis()
        }
        synchronized(roomSessions) { formerRoom += roomSessions }
        roomBefore = null
        roomSessions.clear()
    }

    /** Recordings the room microphone made before (this app's own, if Android still lists them). */
    private val formerRoom: MutableSet<Int> = java.util.Collections.synchronizedSet(LinkedHashSet())

    /** This app's own recordings: the clip recorder's and the room's earlier ones. */
    private fun own(): Set<Int> = synchronized(ownSessions) { ownSessions.toSet() } + synchronized(formerRoom) { formerRoom.toSet() }

    private fun recordings(): List<MicRules.Recording> {
        val now = audio()?.activeRecordingConfigurations.orEmpty().map { MicRules.Recording(it.clientAudioSessionId, it.isClientSilenced) }
        val at = System.currentTimeMillis()
        for (r in now) {
            val seen = firstSeen.getOrPut(r.session) { at }
            // Started while the room microphone was opening or open (up to a second after it closed, as Android lists it
            // late), and not there before it opened: the room's own, even if it was let go before it was recognised.
            if (closedAt > 0 && seen >= openedAt && seen <= closedAt + LATE_MS && r.session !in openBefore) formerRoom += r.session
        }
        if (firstSeen.size > 64) firstSeen.keys.retainAll(now.map { it.session }.toSet())
        return now
    }

    private const val LATE_MS = 1_000L

    /** The room's recordings so far, adding any that appeared since it opened. */
    private fun room(now: List<MicRules.Recording>): Set<Int> {
        val before = roomBefore ?: return emptySet()
        roomSessions += MicRules.roomSessions(before, now, own())
        return synchronized(roomSessions) { roomSessions.toSet() }
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val am = audio() ?: return
        am.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                // Each change seen as it happens, so a recording's first-seen time is when it started.
                runCatching { recordings() }
                listeners.forEach { runCatching { it() } }
            }
        }, Handler(Looper.getMainLooper()))
    }

    /** [change] runs on each change of who records (main thread). */
    fun onChange(change: () -> Unit) {
        listeners += change
    }

    private fun audio(): AudioManager? = app?.getSystemService(AudioManager::class.java)

    /** In a phone call (or screening one): the microphone is the call's. */
    fun inCall(): Boolean = audio()?.mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_CALL_SCREENING }

    /**
     * A call that isn't this app's own talk session (a self-answered Telecom call: never someone else's microphone).
     */
    private fun phoneCall(): Boolean {
        // Not communication mode: this app's own press sets it (SpeakerphoneMic), as its talk session does.
        return MicRules.phoneCall(inCall(), TalkAudio.inCallSession)
    }

    /** Busy while this app's room microphone is not open: a call, or any recording that isn't this app's. */
    fun busy(): Boolean {
        if (audio() == null) return false
        val now = recordings()
        val busy = MicRules.busy(now, own(), room(now), phoneCall())
        if (busy) Log.i(TAG, "microphone in use: ${now.size} recording(s), call ${phoneCall()}")
        return busy
    }

    /** While the room microphone is open and idle: a call came, or the room's own recording was silenced. */
    fun takenFromIdle(): Boolean {
        if (audio() == null) return false
        val now = recordings()
        // Only the room's own recording silenced says someone else took it: this app's clip recorder, or another app
        // ours silenced, being silenced said nothing (2026-11-25: the track came and went every two seconds).
        return MicRules.takenFromIdle(now, room(now), phoneCall())
    }
}
