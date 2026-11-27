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
 * Bluetooth headset nothing changes: its own microphone is used. A phone call (any mode but normal at the press) is
 * left alone.
 *
 * Communication mode is also what makes incoming talk sound like a phone call (2026-11-27, hen: "sometimes i hear it
 * as low as a phone call"; TalkSound), so it is only for a press during which nothing is heard:
 * - not begun while someone is live in the room or a clip plays, and dropped at once when one starts ([yieldToTalk]);
 * - [end] always takes this app's mode request back. It used to do so only while the phone's mode still read
 *   "communication", but Android reads "normal" as soon as the app's microphone stops, with the request still on its
 *   list; the request then put the phone back into communication mode at every later press, with no speaker chosen,
 *   and the room played on the earpiece (the S25's audio log, 2026-11-27 03:09);
 * - [settle] takes it back whenever no press is on (the room joined or left), in case an end was missed.
 *
 * One state for the whole app: the room's microphone and the clip recorder each hold one of these.
 */
class SpeakerphoneMic(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    fun begin(): Unit = synchronized(lock) {
        if (this in holders || Build.VERSION.SDK_INT < 31) return
        if (set) {
            holders += this
            return
        }
        val allowed = TalkSound.micTuning(
            modeNormal = audio.mode == AudioManager.MODE_NORMAL,
            headset = headset(),
            hearing = TalkAudio.clipsPlaying.get() > 0 || runCatching { TalkAudio.roomHearing() }.getOrDefault(false),
            talkSession = TalkAudio.inCallSession,
        )
        if (!allowed) return
        val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = runCatching { audio.setCommunicationDevice(speaker) }.getOrDefault(false)
        if (!ok) {
            normal(audio)
            Log.w(TAG, "the speaker isn't a communication device here")
            return
        }
        set = true
        holders += this
        Log.i(TAG, "talk microphone: speakerphone tuning")
    }

    fun end(): Unit = synchronized(lock) {
        if (!holders.remove(this) || holders.isNotEmpty() || !set) return
        set = false
        normal(audio)
        Log.i(TAG, "talk microphone: back to normal")
    }

    private fun headset(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
        it.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }

    companion object {
        private const val TAG = "TalkMic"
        private val lock = Any()
        private var set = false
        private val holders = mutableSetOf<SpeakerphoneMic>()

        /** Whether a press holds the tuning (communication mode) now. */
        val on: Boolean get() = synchronized(lock) { set }

        /**
         * Incoming talk starts being heard (someone went live in the room, a clip starts): the tuning goes for the rest
         * of this press, so what is heard plays as media on the loudspeaker. Off the caller's thread: Android can take
         * most of a second over the route.
         */
        fun yieldToTalk(context: Context) {
            if (!on) return
            val audio = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
            Thread({
                synchronized(lock) {
                    if (!set) return@synchronized
                    set = false
                    holders.clear()
                    normal(audio)
                    Log.i(TAG, "talk microphone: back to normal (incoming talk is heard)")
                }
            }, "talk-mic-yield").start()
        }

        /** No press is on: any mode request of this app's left behind is taken back. */
        fun settle(context: Context) {
            val audio = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
            synchronized(lock) {
                if (set) return
                runCatching { audio.mode = AudioManager.MODE_NORMAL }
            }
        }

        /**
         * This app's communication device and mode request both go. The mode is set to normal whatever it reads now:
         * that only removes this app's own request (a phone call's, Telecom's, stays).
         */
        private fun normal(audio: AudioManager) {
            runCatching { audio.clearCommunicationDevice() }
            runCatching { audio.mode = AudioManager.MODE_NORMAL }
        }
    }
}
