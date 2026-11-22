package com.chencohen.ptt.talk

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * The media stream, the one talk plays on (AndroidTalkPlayer, USAGE_MEDIA), for Unmute for incoming talk. Not
 * changeable under Do Not Disturb (any filter but "all"), on a fixed-volume device, or during a call, when talk plays
 * on the call stream instead. The ringer is never touched.
 */
class AndroidTalkVolume(context: Context) : TalkVolume {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val notifications = context.applicationContext.getSystemService(NotificationManager::class.java)

    override fun level(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun max(): Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun set(level: Int) {
        Log.i(TAG, "media volume ${level()} -> $level")
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
    }

    override fun changeable(): Boolean =
        !audio.isVolumeFixed && !TalkAudio.inCallSession &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

    private companion object {
        const val TAG = "TalkVolume"
    }
}
