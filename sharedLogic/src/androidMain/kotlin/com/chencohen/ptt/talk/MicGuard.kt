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

    /** How many recordings were going when this app's room microphone opened (its own among them). */
    @Volatile var roomBaseline = -1

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val am = audio() ?: return
        am.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
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

    /** Busy while this app's room microphone is not open: a call, or any recording that isn't this app's clip path. */
    fun busy(): Boolean {
        val am = audio() ?: return false
        if (inCall()) return true
        val others = am.activeRecordingConfigurations.filter { it.clientAudioSessionId !in ownSessions }
        if (others.isNotEmpty()) Log.i(TAG, "microphone in use: ${others.size} other recording(s)")
        return others.isNotEmpty()
    }

    /** While the room microphone is open and idle: someone else started (more recordings than when it opened), or ours was silenced. */
    fun takenFromIdle(): Boolean {
        val am = audio() ?: return false
        if (inCall()) return true
        val configs = am.activeRecordingConfigurations
        if (configs.any { it.isClientSilenced }) return true
        val base = roomBaseline
        return base >= 0 && configs.count { it.clientAudioSessionId !in ownSessions } > base
    }
}
