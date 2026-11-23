package com.chencohen.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only (adb): `am broadcast -n com.chencohen.ptt/.DebugTriggers -a quick_reply --es chat <user>` runs the
 * quick-reply window as an incoming message from <user> would; `-a wake --es chat <user>` the talk wake-up.
 */
class DebugTriggers : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra("chat") ?: return
        Log.i("DebugTriggers", "${intent.action} $chat")
        when (intent.action) {
            "quick_reply" -> QuickReplyWindow.arrived(context, chat)
            "wake" -> TalkPlaying.debugWake(context.applicationContext as TalkApp, chat)
        }
    }
}
