package com.chencohen.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Test build only: `adb shell am broadcast -a com.chencohen.ptt.TEST_TYPE -n com.chencohen.ptt/.TestTyping
 * --es chat <user> --es b64 <UTF-8 text, base64> [--ei every 200] [--ei hold 20000]` live-types the text to the chat
 * one letter at a time, holds it, then clears it (never sends).
 */
class TestTyping : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra("chat") ?: return
        val text = intent.getStringExtra("b64")?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) } ?: return
        val every = intent.getIntExtra("every", 200).toLong()
        val hold = intent.getIntExtra("hold", 20_000).toLong()
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                for (i in 1..text.length) {
                    sendDraft(app, chat, text.take(i), i, i)
                    delay(every)
                }
                delay(hold)
                sendDraft(app, chat, "")
            } finally {
                pending.finish()
            }
        }
    }
}
