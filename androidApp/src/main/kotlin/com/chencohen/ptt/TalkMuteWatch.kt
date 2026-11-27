package com.chencohen.ptt

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Says in the log when Android mutes one of this app's players (2026-11-27, hen: "audio not always working"; the
 * phone's audio log showed "AudioHardening background playback muted for com.chencohen.ptt"): which sound (its usage),
 * Android's muted state, whether the app was on screen, and whether Talk ready had its rights, so a silent push can be
 * traced to its cause.
 */
object TalkMuteWatch {
    private const val TAG = "TalkMuteWatch"
    private val logged = mutableSetOf<String>()
    private val mutedState = Regex("mutedState:(\\S+)")
    private val piid = Regex("piid:(\\d+)")

    fun start(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            audio.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    configs.forEach { c ->
                        // Only this app's own players come here; Android's muted state is in their description.
                        val text = c.toString()
                        val muted = mutedState.find(text)?.groupValues?.get(1) ?: return@forEach
                        val id = piid.find(text)?.groupValues?.get(1) ?: return@forEach
                        synchronized(logged) {
                            if (muted == "none" || muted == "[]") {
                                logged.removeAll { it.startsWith("$id:") }
                                return@forEach
                            }
                            if (!logged.add("$id:$muted")) return@forEach
                        }
                        val info = android.app.ActivityManager.RunningAppProcessInfo()
                        android.app.ActivityManager.getMyMemoryState(info)
                        Log.w(TAG, "Android muted a player here: $muted (usage ${c.audioAttributes.usage}, importance ${info.importance}, Talk ready ${TalkReadyService.running}, with rights ${TalkReadyService.withRights})")
                    }
                }
            }, Handler(Looper.getMainLooper()))
        }
    }
}
