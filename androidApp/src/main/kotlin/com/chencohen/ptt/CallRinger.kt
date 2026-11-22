package com.chencohen.ptt

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log

/**
 * The ringing of an incoming call (Talk's calls are self-managed: Android rings nothing for them). Settings › Calls ›
 * Ringtone: Talk's soft chime (res/raw/ring_soft.ogg, made by tools/ringtone/make_ring.py; the default, 2026-11-22,
 * hen) or the phone's ringtone, looping on the ringer's stream until answered, declined or 45 seconds. As the phone's
 * calls do: silent in Do Not Disturb and in silent mode, vibration only in vibrate mode; the volume is the ringer's.
 */
object CallRinger {
    private const val TAG = "CallRinger"
    private const val LONGEST_MS = 45_000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val timeout = Runnable { stop() }

    private val ringing = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(context: Context, soft: Boolean) = main.post {
        stop()
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val dnd = app.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val mode = audio.ringerMode
        Log.i(TAG, "ring: ${if (soft) "soft chime" else "phone's ringtone"}, ringer mode $mode, dnd $dnd")
        if (dnd || mode == AudioManager.RINGER_MODE_SILENT) return@post
        main.postDelayed(timeout, LONGEST_MS)
        if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            vibrate(app)
            return@post
        }
        val uri = if (soft) {
            Uri.parse("android.resource://${app.packageName}/${R.raw.ring_soft}")
        } else {
            RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE) ?: Settings.System.DEFAULT_RINGTONE_URI
        }
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(ringing)
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "can't play the ringtone", it) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context) {
        val v = context.getSystemService(Vibrator::class.java) ?: return
        vibrator = v
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 1300), 0), ringing)
    }

    fun stop() {
        main.removeCallbacks(timeout)
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
    }
}
