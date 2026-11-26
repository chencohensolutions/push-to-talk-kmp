package com.chencohen.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only (adb): `am broadcast -n com.chencohen.ptt/.DebugTriggers -a quick_reply --es chat <user>` runs the
 * quick-reply window as an incoming message from <user> would; `-a wake --es chat <user>` the talk wake-up; `-a bubble
 * --es chat <user>` the floating window's pill.
 */
class DebugTriggers : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra("chat") ?: return
        Log.i("DebugTriggers", "${intent.action} $chat")
        when (intent.action) {
            "quick_reply" -> QuickReplyWindow.arrived(context, chat)
            // The same, keeping what a dismissal left: nothing shows until the chat was quiet for 5 minutes.
            "quick_reply_more" -> QuickReplyWindow.arrived(context, chat, fresh = false)
            "wake" -> TalkPlaying.debugWake(context.applicationContext as TalkApp, chat)
            // The floating window's pill, as after a push from <user> (`--ez live true`: while it goes on).
            "bubble" -> TalkBubble.show(context.applicationContext, chat, chat, live = intent.getBooleanExtra("live", false), who = chat)
        }
    }
}
