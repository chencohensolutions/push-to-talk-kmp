package com.chencohen.ptt.talk

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * The speakerphone's microphone tuning while a talk button is held (2026-11-22, hen: talk should hear a voice at arm's
 * length, as a speaker call does, not only close to the mouth). The voice-call microphone in the normal audio mode
 * gets the handset's close-talk tuning on Samsung phones; in communication mode with the built-in speaker as the
 * communication device it gets the speakerphone's (more gain, the far-field noise suppressor). With a wired or
 * Bluetooth headset nothing changes: its own microphone is used. Only for the press: [end] puts the mode back to
 * normal and clears the device, so what plays afterwards is media again. A phone call (any mode but normal at the
 * press) is left alone.
 */
class SpeakerphoneMic(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var set = false

    fun begin(): Unit = synchronized(lock) {
        if (set || Build.VERSION.SDK_INT < 31) return
        if (audio.mode != AudioManager.MODE_NORMAL) return
        if (headset()) return
        val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = runCatching { audio.setCommunicationDevice(speaker) }.getOrDefault(false)
        if (!ok) {
            audio.mode = AudioManager.MODE_NORMAL
            Log.w(TAG, "the speaker isn't a communication device here")
            return
        }
        set = true
        Log.i(TAG, "talk microphone: speakerphone tuning")
    }

    fun end(): Unit = synchronized(lock) {
        if (!set) return
        set = false
        runCatching { audio.clearCommunicationDevice() }
        if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "talk microphone: back to normal")
    }

    private fun headset(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
        it.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }

    private companion object {
        const val TAG = "TalkMic"
    }
}
