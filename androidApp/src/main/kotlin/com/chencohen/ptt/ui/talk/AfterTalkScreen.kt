package com.chencohen.ptt.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.ClipKind
import com.chencohen.ptt.messagingState
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.style.LibraryType
import java.text.DateFormat
import java.util.Date

/**
 * After a burst that woke the phone (2026-11-23, hen: "instead of a black screen, show a dimmed full screen that
 * notifies there was a message from the contact, until the screen goes off"): near black, the screen at its dimmest,
 * in dim grey the letter circle, the name, "Talked to you · 20:04" (or "Sent you a video") and the length. A tap opens
 * the chat after unlocking; the screen going off closes it.
 */
@Composable
fun AfterTalkScreen(chat: String, open: () -> Unit) {
    val context = LocalContext.current
    val state by messagingState(context).collectAsStateWithLifecycle()
    val last = state.conversations[chat].orEmpty().lastOrNull { it.clip?.kind == ClipKind.BURST && !it.outgoing }
    val name = state.contacts.firstOrNull { it.user == chat }?.name ?: chat
    val grey = Color(0xFF5A5F5E)
    Box(
        Modifier.fillMaxSize().background(Color(0xFF050606)).clickable(role = Role.Button, onClickLabel = stringResource(R.string.after_talk_open), onClick = open),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.background(Color.Black)) { Avatar(name, size = 72.dp) }
            Text(name, color = grey, style = LibraryType.PageTitle, textAlign = TextAlign.Center)
            if (last != null) {
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last.at))
                Text(
                    stringResource(if (last.clip?.video == true) R.string.after_talk_video else R.string.after_talk_voice, time),
                    color = grey, style = LibraryType.Body, textAlign = TextAlign.Center,
                )
                last.clip?.durationMs?.takeIf { it > 0 }?.let { Text(clipLength(it), color = grey, style = LibraryType.Small) }
            }
            Text(stringResource(R.string.after_talk_open), color = grey.copy(alpha = 0.6f), style = LibraryType.Small, modifier = Modifier.padding(top = 24.dp))
        }
    }
}
