package com.chencohen.ptt.ui.talk

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.ClipKind
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.talk.NowPlaying
import com.chencohen.ptt.talk.OwnTalk
import com.chencohen.ptt.talk.TalkPhase
import com.chencohen.ptt.talk.TalkUiState
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryShapes
import com.chencohen.ptt.ui.style.LibraryType
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/** "0:04", "1:20". */
internal fun clipLength(ms: Long): String {
    val s = (ms + 500) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** A debug build shows the catch-up readout (how far behind live a burst plays). */
@Composable
internal fun isDebugBuild(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
}

/**
 * Asks for the microphone the first time; [then] runs once it is allowed. Returns what to call before recording.
 */
@Composable
internal fun rememberMicrophone(onDenied: () -> Unit): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    val denied by rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) denied() }
    return remember(context) {
        { then: () -> Unit ->
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                then()
            } else {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/**
 * Pressed and released, for hold-to-talk: [onPress] when a finger goes down, [onRelease] with how far it moved
 * sideways when it comes up (or the gesture is taken away).
 */
internal fun Modifier.holdToTalk(onPress: () -> Unit, onRelease: (dx: Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onPress()
        var dx = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            dx = change.position.x - down.position.x
            change.consume()
            if (!change.pressed) break
        }
        onRelease(dx)
    }
}

/** What the Talk button says under it, from the floor and this phone's own talking. */
@Composable
internal fun talkLine(chat: String, talk: TalkUiState, nameOf: (String) -> String, me: String): String {
    val own = talk.own?.takeIf { it.chat == chat }
    val talker = talk.talkers[chat]
    return when {
        own != null -> when (own.phase) {
            TalkPhase.REQUESTING -> stringResource(R.string.talk_requesting)
            TalkPhase.TALKING -> stringResource(R.string.talk_live)
            TalkPhase.BUSY -> stringResource(R.string.talk_busy, nameOf(own.busyWith.orEmpty()))
            TalkPhase.REFUSED -> if (own.refusal == OwnTalk.MIC) stringResource(R.string.talk_no_mic) else stringResource(R.string.talk_refused)
            TalkPhase.OFFLINE -> stringResource(R.string.talk_offline)
            TalkPhase.VOICE -> stringResource(R.string.voice_recording)
        }
        talker != null && talker.user == me -> stringResource(R.string.talk_other_phone)
        talker != null -> stringResource(R.string.talk_someone, nameOf(talker.user))
        else -> stringResource(R.string.talk_hold)
    }
}

/**
 * Talk mode (docs/screens/talk.md): the large hold-to-talk circle (ink, with a teal ring while live), who is speaking,
 * Busy when someone else holds the floor, and the last bursts below. Swipe down (or Back) returns to the chat.
 */
@Composable
internal fun TalkModeScreen(
    chat: String,
    title: String,
    me: String,
    talk: TalkUiState,
    /** The live path (phase 5b): in this chat's room, and the last press-to-hear, for the debug readout. */
    liveRoom: com.chencohen.ptt.talk.LiveState,
    bursts: List<TextMessage>,
    nameOf: (String) -> String,
    press: () -> Unit,
    release: () -> Unit,
    play: (String) -> Unit,
    close: () -> Unit,
    micDenied: () -> Unit,
) {
    val microphone = rememberMicrophone(micDenied)
    val own = talk.own?.takeIf { it.chat == chat }
    val live = own?.phase == TalkPhase.TALKING
    val someone = talk.talkers[chat]?.takeIf { it.user != me }
    var pulled by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    Column(
        Modifier.fillMaxSize().background(LibraryColors.Background).statusBarsPadding().navigationBarsPadding()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (pulled > with(density) { 120.dp.toPx() }) close()
                        pulled = 0f
                    },
                ) { _, dy -> pulled = (pulled + dy).coerceAtLeast(0f) }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = close), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.ChevronDown, stringResource(R.string.talk_close), tint = LibraryColors.OnBackground)
            }
            Text(title, color = LibraryColors.OnBackground, style = LibraryType.PageTitle, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            talkLine(chat, talk, nameOf, me),
            color = if (live || someone != null) LibraryColors.Accent else LibraryColors.Muted,
            style = LibraryType.RowTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        val playing = talk.playing?.takeIf { it.chat == chat && it.live }
        if (playing != null && isDebugBuild()) {
            Text(stringResource(R.string.talk_debug_behind, playing.behindMs), color = LibraryColors.Muted, style = LibraryType.Tiny)
        }
        if (isDebugBuild() && liveRoom.room == chat) {
            val latency = liveRoom.lastLatencyMs
            Text(
                if (latency != null) stringResource(R.string.talk_debug_live, latency) else stringResource(R.string.talk_debug_live_room),
                color = LibraryColors.Muted, style = LibraryType.Tiny,
            )
        }
        Spacer(Modifier.weight(1f))
        val label = stringResource(R.string.talk_button)
        Box(
            Modifier
                .size(220.dp)
                .border(if (live || someone != null) 8.dp else 0.dp, if (live) LibraryColors.Accent else LibraryColors.Accent.copy(alpha = 0.5f), CircleShape)
                .padding(if (live || someone != null) 12.dp else 0.dp)
                .background(if (someone != null && !live) LibraryColors.Muted.copy(alpha = 0.4f) else LibraryColors.Inverse, CircleShape)
                .semantics { contentDescription = label }
                .holdToTalk(onPress = { microphone(press) }, onRelease = { release() }),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(LibraryIcons.Mic, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(56.dp))
                Text(label, color = LibraryColors.OnInverse, style = LibraryType.RowTitle)
            }
        }
        Spacer(Modifier.weight(1f))
        if (bursts.isNotEmpty()) {
            Text(stringResource(R.string.talk_last), color = LibraryColors.Muted, style = LibraryType.SectionLabel, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            LazyColumn(
                Modifier.fillMaxWidth().height(200.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(bursts.takeLast(10).asReversed(), key = { it.id }) { m ->
                    val who = if (m.outgoing) stringResource(R.string.talk_you) else nameOf(m.author ?: m.user)
                    ClipRow(m, who, talk.playing, play)
                }
            }
        }
    }
}

/** A clip in a list: play, who, how long, when. */
@Composable
private fun ClipRow(message: TextMessage, who: String, playing: NowPlaying?, play: (String) -> Unit) {
    val clip = message.clip ?: return
    val now = playing?.clip == message.id
    Row(
        Modifier.fillMaxWidth().background(LibraryColors.Raised, LibraryShapes.Tile).clickable(role = Role.Button) { play(message.id) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (now) LibraryIcons.Pause else LibraryIcons.Play, stringResource(R.string.clip_play), tint = LibraryColors.Accent, modifier = Modifier.size(20.dp))
        Text(who, color = LibraryColors.OnBackground, style = LibraryType.Body, modifier = Modifier.padding(start = 10.dp).weight(1f))
        Text(
            listOfNotNull(clip.durationMs.takeIf { it > 0 }?.let(::clipLength), DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at))).joinToString(" · "),
            color = LibraryColors.Muted, style = LibraryType.Small,
        )
    }
}

/** A clip inside a chat bubble: Play or Pause, a bar while it plays, its length, and "Talk" for a burst. */
@Composable
internal fun ClipContent(message: TextMessage, playing: NowPlaying?, live: Boolean, play: (String) -> Unit, stop: () -> Unit, onInverse: Boolean) {
    val clip = message.clip ?: return
    val now = playing?.clip == message.id
    val ink = if (onInverse) LibraryColors.OnInverse else LibraryColors.OnBackground
    val muted = if (onInverse) LibraryColors.OnInverseMuted else LibraryColors.Muted
    Row(
        Modifier.clickable(role = Role.Button) { if (now) stop() else play(message.id) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(if (onInverse) LibraryColors.OnInverse.copy(alpha = 0.15f) else LibraryColors.Accent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (now) LibraryIcons.Pause else LibraryIcons.Play, stringResource(if (now) R.string.clip_stop else R.string.clip_play), tint = if (onInverse) ink else LibraryColors.Accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.padding(start = 10.dp).width(150.dp)) {
            val total = clip.durationMs.takeIf { it > 0 }
            val fraction = if (now && total != null) (playing!!.playedMs.toFloat() / total).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(4.dp).background(muted.copy(alpha = 0.3f), LibraryShapes.Pill)) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(if (onInverse) ink else LibraryColors.Accent, LibraryShapes.Pill))
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (clip.kind == ClipKind.BURST) {
                    Icon(LibraryIcons.Talk, null, tint = muted, modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.clip_burst), color = muted, style = LibraryType.Tiny, modifier = Modifier.padding(start = 3.dp, end = 6.dp))
                }
                // "Live" only while it is being said; a burst whose length this phone doesn't know yet (not played,
                // not whole here) shows none.
                val length = when {
                    now -> clipLength(playing!!.playedMs)
                    total != null -> clipLength(total)
                    live -> stringResource(R.string.clip_live)
                    else -> null
                }
                if (length != null) Text(length, color = muted, style = LibraryType.Tiny)
                if (!message.outgoing && !clip.heard && !now) {
                    Box(Modifier.padding(start = 6.dp).size(6.dp).background(LibraryColors.Accent, CircleShape))
                }
            }
        }
    }
}

/**
 * The composer's microphone: hold to record a voice message, let go to send it, slide toward the line to cancel.
 * [recording] while this phone records one.
 */
@Composable
internal fun VoiceButton(recording: OwnTalk?, start: () -> Unit, finish: (send: Boolean) -> Unit, micDenied: () -> Unit, modifier: Modifier = Modifier) {
    val microphone = rememberMicrophone(micDenied)
    val density = LocalDensity.current
    val cancelAt = with(density) { 100.dp.toPx() }
    var since by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var sliding by remember { mutableStateOf(false) }
    val active = recording?.phase == TalkPhase.VOICE
    LaunchedEffect(active) {
        if (active) {
            since = System.currentTimeMillis()
            while (true) {
                elapsed = System.currentTimeMillis() - since
                delay(200)
            }
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            Text(
                if (sliding) stringResource(R.string.voice_release_cancel) else stringResource(R.string.voice_slide_cancel, clipLength(elapsed)),
                color = if (sliding) LibraryColors.Error else LibraryColors.Muted,
                style = LibraryType.Caption,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        val label = stringResource(R.string.voice_hold)
        Box(
            Modifier.size(if (active) 56.dp else 44.dp)
                .background(if (active) LibraryColors.Accent else LibraryColors.Inverse, CircleShape)
                .semantics { contentDescription = label }
                .holdToTalk(
                    onPress = {
                        sliding = false
                        microphone(start)
                    },
                    onRelease = { dx ->
                        // Toward the line to write in: left, or right in a right-to-left layout.
                        val cancel = abs(dx) > cancelAt
                        sliding = false
                        finish(!cancel)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(LibraryIcons.Mic, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun Dot(color: androidx.compose.ui.graphics.Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** A chat's When I'm away (the plan's "Background delivery"): Play aloud (the default) or Notify only. */
@Composable
internal fun WhenAwayCard(notifyOnly: Boolean, set: (Boolean) -> Unit) {
    com.chencohen.ptt.ui.style.SectionTitle(stringResource(R.string.talk_away_title))
    com.chencohen.ptt.ui.style.SettingsCard {
        ChoiceRow(stringResource(R.string.talk_away_play), stringResource(R.string.talk_away_play_body), !notifyOnly) { set(false) }
        com.chencohen.ptt.ui.style.RowDivider()
        ChoiceRow(stringResource(R.string.talk_away_notify), stringResource(R.string.talk_away_notify_body), notifyOnly) { set(true) }
    }
}

/** One of several choices: a check on the chosen one. */
@Composable
internal fun ChoiceRow(title: String, body: String?, chosen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
            if (body != null) Text(body, color = LibraryColors.Muted, style = LibraryType.Caption)
        }
        if (chosen) Icon(LibraryIcons.Check, stringResource(R.string.talk_chosen), tint = LibraryColors.Accent, modifier = Modifier.padding(start = 12.dp).size(20.dp))
    }
}

/** On or off, with a switch. */
@Composable
internal fun SwitchRow(title: String, body: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!on) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
            if (body != null) Text(body, color = LibraryColors.Muted, style = LibraryType.Caption)
        }
        androidx.compose.material3.Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.padding(start = 12.dp))
    }
}
