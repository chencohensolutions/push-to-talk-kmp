package com.chencohen.ptt.ui.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.talk.clipLength
import kotlinx.coroutines.delay

// Push-to-video out of the chat's flow (2026-11-24, hen): theirs drops down under the header, mine rises above the
// buttons; the chat stays between them, still usable. docs/screens/conversation.md, "Live panels".

private const val PANEL_MS = 250

/** m:ss since [since], ticking. */
@Composable
private fun elapsedSince(since: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    return clipLength((now - since).coerceAtLeast(0L))
}

/** A red dot and words over the video, on a dark pill. */
@Composable
private fun LiveTag(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(LibraryColors.Error, CircleShape))
        Text(text, color = Color.White, style = LibraryType.Small)
    }
}

/**
 * Their push-to-video (or, in a group, whoever holds the floor: [who] named), under the header: slides down in 250 ms
 * while it plays, back up when it ends. [since]: when it started.
 */
@Composable
internal fun TheirLivePanel(shown: Boolean, who: String?, since: Long, height: Dp = 300.dp) {
    AnimatedVisibility(
        shown,
        enter = expandVertically(tween(PANEL_MS), expandFrom = Alignment.Top) + slideInVertically(tween(PANEL_MS)) { -it },
        exit = shrinkVertically(tween(PANEL_MS), shrinkTowards = Alignment.Top) + slideOutVertically(tween(PANEL_MS)) { -it },
    ) {
        val shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
        Box(Modifier.fillMaxWidth().height(height).shadow(8.dp, shape).clip(shape).background(Color.Black)) {
            // Whole, as sent (2026-11-24, hen: filling the panel zoomed a small picture far too close); Follow face's
            // pill is at its top, so the live tag goes at the bottom.
            com.chencohen.ptt.ui.talk.TalkVideoView(Modifier.fillMaxSize(), cover = false)
            val words = listOfNotNull(who, stringResource(R.string.talk_panel_live), elapsedSince(since)).joinToString(" · ")
            LiveTag(words, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}

/** Mine, while I send video: rises above the buttons; [waitingFor] someone else's floor first. */
@Composable
internal fun MyLivePanel(shown: Boolean, since: Long, waitingFor: String?, handsFree: Boolean, height: Dp = 240.dp) {
    AnimatedVisibility(
        shown,
        enter = expandVertically(tween(PANEL_MS), expandFrom = Alignment.Bottom) + slideInVertically(tween(PANEL_MS)) { it },
        exit = shrinkVertically(tween(PANEL_MS), shrinkTowards = Alignment.Bottom) + slideOutVertically(tween(PANEL_MS)) { it },
    ) {
        val shape = RoundedCornerShape(24.dp)
        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().height(height).shadow(8.dp, shape).clip(shape).background(Color.Black)) {
            com.chencohen.ptt.ui.talk.PushVideoPreview(Modifier.fillMaxSize())
            val me = stringResource(R.string.talk_panel_you)
            val words = if (waitingFor != null) stringResource(R.string.talk_waiting_for, waitingFor)
            else listOfNotNull(me, stringResource(if (handsFree) R.string.talk_panel_hands_free else R.string.talk_panel_live), elapsedSince(since)).joinToString(" · ")
            LiveTag(words, Modifier.align(Alignment.TopStart).padding(12.dp))
        }
    }
}

/** "You're both live · Make it a call" (2026-11-24, hen), over the bottom of the chat. */
@Composable
internal fun BothLiveChip(shown: Boolean, makeCall: () -> Unit) {
    AnimatedVisibility(shown, enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.shadow(6.dp, CircleShape).background(LibraryColors.Inverse, CircleShape)
                    .clickable(role = Role.Button, onClick = makeCall)
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.talk_both_live), color = LibraryColors.OnInverse, style = LibraryType.Small)
                Text(
                    stringResource(R.string.talk_make_call), color = Color.White, style = LibraryType.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 10.dp).background(com.chencohen.ptt.ui.style.Pink, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * A push-to-talk burst the chat doesn't keep (2026-11-24, hen: its "Keep voice / videos in chat" off): one muted,
 * centred line once it ends, "Sharon sent live video · 0:07"; nothing to play.
 */
@Composable
internal fun TalkTrace(message: TextMessage, author: String, count: Int = 1, totalMs: Long = message.clip?.durationMs ?: 0L) {
    val clip = message.clip ?: return
    val length = totalMs.takeIf { it > 0 }?.let { clipLength(it) }
    val words = when {
        message.outgoing && clip.video -> stringResource(R.string.talk_trace_my_video)
        message.outgoing -> stringResource(R.string.talk_trace_my_voice)
        clip.video -> com.chencohen.ptt.genderedString(R.string.talk_trace_video, author, author)
        else -> com.chencohen.ptt.genderedString(R.string.talk_trace_voice, author, author)
    }
    Text(
        listOfNotNull(if (count > 1) "$words ×$count" else words, length).joinToString(" · "),
        color = LibraryColors.Muted, style = LibraryType.Tiny, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Locked, hands-free: the big button's place shows Stop. */
@Composable
internal fun StopLive(size: Dp, stop: () -> Unit) {
    val label = stringResource(R.string.talk_stop)
    Box(
        Modifier.size(size).shadow(10.dp, CircleShape).background(com.chencohen.ptt.ui.style.Pink, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = stop),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(size * 0.26f).background(Color.White, RoundedCornerShape(4.dp)))
            Text(label, color = Color.White, style = LibraryType.Tiny, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
