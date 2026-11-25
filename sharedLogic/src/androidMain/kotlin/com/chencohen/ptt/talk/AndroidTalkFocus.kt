package com.chencohen.ptt.talk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * The one transient audio focus talk holds ("others may duck": music and videos in other apps go quieter), shared by
 * its holders so they don't fight: Talk is ready's woken bursts (`TalkReady`) and pushes heard in the live room
 * (`TalkDuck`). Asked of Android when the first holder takes it, given back when the last one lets go.
 */
object AndroidTalkFocus {
    private const val TAG = "TalkFocus"
    private val holders = mutableSetOf<String>()
    private var request: AudioFocusRequest? = null

    /** [holder] wants the others ducked: true while Android grants the focus. */
    @Synchronized
    fun take(context: Context, holder: String): Boolean {
        if (request != null) {
            holders += holder
            return true
        }
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        val result = audio.requestAudioFocus(r)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "audio focus refused ($result) for $holder")
            return false
        }
        request = r
        holders += holder
        Log.i(TAG, "audio focus taken for $holder")
        return true
    }

    @Synchronized
    fun release(context: Context, holder: String) {
        if (!holders.remove(holder) || holders.isNotEmpty()) return
        request?.let { context.applicationContext.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        request = null
        Log.i(TAG, "audio focus given back ($holder last)")
    }

    /** The live room's holder, for Talk (TalkDuck). */
    fun roomDuck(context: Context): AudioDuck = object : AudioDuck {
        override fun take(): Boolean = take(context, "room")
        override fun release() = release(context, "room")
    }
}
