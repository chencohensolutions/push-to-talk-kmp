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
 * - `--es where chat --es theirs voice|video|none`: the other side live in the chat on screen (the app's, or the
 *   floating chat's), not in the floating window;
 * - `--es where window`: the floating chat opens on [chat], as for a message from it;
 * - `--es pill stop|push`: the bottom pill as if locked hands-free (Stop) or not, nothing sent;
 * - `--es members testbot2:video,testbot3:voice,...` (empty: none): group members live at once, as if each pushed (several
 *   talkers; 2026-11-25), in the open group chat and in the floating window's row; each keeps its start while listed
 *   again, so a list one longer is one more joining. With `--es where chat --es mine voice|video|none`: my side too.
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
        if (intent.hasExtra("members")) {
            val before = LivePretend.members.value.associateBy { it.user }
            val now = System.currentTimeMillis()
            LivePretend.members.value = intent.getStringExtra("members").orEmpty().split(',').filter { it.isNotBlank() }.mapIndexed { i, m ->
                val user = m.substringBefore(':')
                LivePretend.Member(user, m.substringAfter(':', "voice") == "video", before[user]?.since ?: (now + i))
            }
            // In the open chat only (where chat), else in the floating window's row.
            if (intent.getStringExtra("where") == "chat") {
                if (intent.hasExtra("mine")) LivePretend.mine.value = kind("mine")
            } else TalkBubble.pretendMembers(app, chat)
            return
        }
        if (intent.getStringExtra("where") == "chat" && intent.hasExtra("mine")) {
            LivePretend.mine.value = kind("mine")
            return
        }
        if (intent.hasExtra("pill")) {
            TalkBubble.pretendLocked(intent.getStringExtra("pill") == "stop")
            return
        }
        if (intent.getStringExtra("where") == "window") {
            QuickReplyWindow.arrived(app, chat)
            return
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
