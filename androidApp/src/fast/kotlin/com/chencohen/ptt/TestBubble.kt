package com.chencohen.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chencohen.ptt.ui.contacts.LiveKind
import com.chencohen.ptt.ui.contacts.LiveNow
import com.chencohen.ptt.ui.contacts.LivePretend

/**
 * Test build only: `adb shell am broadcast -a com.chencohen.ptt.TEST_BUBBLE -n com.chencohen.ptt/.TestBubble
 * --es chat <user> [--ez live true] [--ez video false]` shows the floating window as a push from [chat] would (its
 * layout checked without one); `--ez live false` ends the "push". More, to check the live row's moves without a
 * second phone:
 * - `--ef shape 0.75`: their picture's shape as if they had said it (a video then grows out of their pill);
 * - `--es mine voice|video|none`: my side in the floating window's row as if I pushed (no camera, no sound);
 * - `--es where chat --es theirs voice|video|none`: the other side live in the chat open in the app, not in the
 *   floating window.
 */
class TestBubble : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra("chat") ?: return
        val app = context.applicationContext
        fun kind(extra: String) = when (intent.getStringExtra(extra)) {
            "voice" -> LiveKind.Voice
            "video" -> LiveKind.Video
            else -> LiveKind.None
        }
        if (intent.hasExtra("shape")) {
            val shape = intent.getFloatExtra("shape", 0.75f)
            TalkBubble.pretendShape = shape
            com.chencohen.ptt.talk.LiveTalkVideo.shapes.value += (LivePretend.IDENTITY to (shape * 1000).toInt())
        }
        if (intent.getStringExtra("where") == "chat") {
            LivePretend.theirs.value = kind("theirs")
            return
        }
        if (intent.hasExtra("mine")) {
            TalkBubble.liveRow(app, chat, LiveNow(mine = kind("mine"), mySince = System.currentTimeMillis()))
            return
        }
        TalkBubble.show(
            app, chat, chat,
            live = intent.getBooleanExtra("live", true), video = intent.getBooleanExtra("video", false), who = chat,
        )
    }
}
