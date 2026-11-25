package com.chencohen.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Test build only: `adb shell am broadcast -a com.chencohen.ptt.TEST_PAIR -n com.chencohen.ptt/.TestPair --es link
 * '<pairing link>'` pairs with whoever the link (another phone's Add contact code) is, as scanning it would: the test
 * emulators pair without a camera (2026-11-25, hen: testbot1 to 3).
 */
class TestPair : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val link = intent.getStringExtra("link") ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Log.i("TestPair", "pairing: " + runCatching { testPairWith(app, link) }.fold({ if (it) "sent" else "not a code" }, { it.toString() }))
            } finally {
                pending.finish()
            }
        }
    }
}
