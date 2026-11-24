package com.chencohen.ptt

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * A message's sound when the quick-reply window shows it instead of a notification (2026-11-23, hen): the chosen
 * message sound (Talk's soft chime or the phone's), on the notification stream, as the notification would; vibration
 * only in vibrate mode; nothing in Do Not Disturb or silent mode.
 */
object MessageAlert {
    fun play(context: Context) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val dnd = app.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        when {
            dnd || audio.ringerMode == AudioManager.RINGER_MODE_SILENT -> Log.i("MessageAlert", "quiet (do not disturb or silent)")
            audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> vibrate(app)
            else -> runCatching {
                val soft = messagingState(app).value.talkSettings.softMessage
                val uri = if (soft) Uri.parse("android.resource://${app.packageName}/${R.raw.message_tone}") else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(app, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }?.play()
            }.onFailure { Log.w("MessageAlert", "no sound", it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context) {
        val v = context.getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
    }
}
