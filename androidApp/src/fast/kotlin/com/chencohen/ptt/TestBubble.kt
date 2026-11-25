package com.chencohen.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Test build only: `adb shell am broadcast -a com.chencohen.ptt.TEST_BUBBLE -n com.chencohen.ptt/.TestBubble
 * --es chat <user> [--ez live true] [--ez video false]` shows the floating window as a push from [chat] would (its
 * layout checked without one); `--ez live false` ends the "push".
 */
class TestBubble : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra("chat") ?: return
        TalkBubble.show(
            context.applicationContext, chat, chat,
            live = intent.getBooleanExtra("live", true), video = intent.getBooleanExtra("video", false), who = chat,
        )
    }
}
