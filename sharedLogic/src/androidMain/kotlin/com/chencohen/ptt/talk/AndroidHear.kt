package com.chencohen.ptt.talk

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log

/**
 * Whether this phone's user can hear talk now (2026-11-25, hen: "who can hear you"): a phone call, Do Not Disturb
 * (any interruption filter but "all"), the ringer on silent or vibrate, or the media volume at 0 with Talk volume
 * off. Watched through Android's broadcasts; [onChange] gets each new state.
 */
class AndroidHear(context: Context, private val talkVolumeOn: () -> Boolean, private val onChange: (Hear) -> Unit) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private var last: Hear? = null

    fun now(): Hear = Hear.of(
        inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_CALL_SCREENING,
        dnd = notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
        ringerSilent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL,
        mediaMuted = !talkVolumeOn() && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0,
    )

    /** Looks again and tells [onChange] when it changed. */
    fun check() {
        val h = runCatching { now() }.getOrDefault(Hear.OK)
        if (h == last) return
        last = h
        Log.i(TAG, "can hear: $h")
        onChange(h)
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = check()
        }, filter, Context.RECEIVER_NOT_EXPORTED)
        // A call (or its end): the audio mode.
        audio.addOnModeChangedListener(app.mainExecutor) { check() }
        check()
    }

    private companion object {
        const val TAG = "CanHear"
    }
}

/**
 * Do Not Disturb opened for an urgent push the user lets through: with Do Not Disturb access (Settings › Do Not
 * Disturb access › Talk), the interruption filter is set to all while it plays and put back after.
 */
class AndroidDndGate(context: Context) : DndGate {
    private val notifications = context.applicationContext.getSystemService(NotificationManager::class.java)
    private var before: Int? = null

    override fun canOpen(): Boolean = notifications.isNotificationPolicyAccessGranted

    override fun open(): Boolean = runCatching {
        if (!canOpen()) return false
        val filter = notifications.currentInterruptionFilter
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) return true
        before = filter
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        Log.i("CanHear", "Do Not Disturb opened for an urgent push")
        true
    }.getOrDefault(false)

    override fun close() {
        val filter = before ?: return
        before = null
        runCatching {
            if (canOpen()) notifications.setInterruptionFilter(filter)
            Log.i("CanHear", "Do Not Disturb back")
        }
    }
}
