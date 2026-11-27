package com.chencohen.ptt.ui.talk

import android.Manifest
import androidx.compose.ui.graphics.luminance
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
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

/**
 * The hold-to-talk circle (docs/screens/talk.md): ink (the main color), 220 dp, a microphone and "Hold to talk"; a
 * teal ring while live, a faded ring and a muted circle while someone else talks.
 */
@Composable
internal fun TalkCircle(live: Boolean, someoneTalking: Boolean, press: () -> Unit, release: () -> Unit, modifier: Modifier = Modifier, diameter: Dp = 220.dp, video: Boolean = false) {
    val label = stringResource(R.string.talk_button)
    val level = rememberVoiceLevel(live || someoneTalking)
    val glow = if (live) LibraryColors.Accent else LibraryColors.Inverse
    Box(
        modifier
            .size(diameter)
            // Grows (up to 1.25) and glows with the voice: the one playing, or the microphone's while talking.
            .graphicsLayer {
                val scale = 1f + 0.25f * level.value
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                val l = level.value
                if (l > 0.01f) {
                    drawCircle(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(glow.copy(alpha = 0.45f * l), androidx.compose.ui.graphics.Color.Transparent),
                            center = center,
                            radius = size.minDimension / 2 * (1f + 0.35f * l),
                        ),
                        radius = size.minDimension / 2 * (1f + 0.35f * l),
                    )
                }
            }
            .border(if (live || someoneTalking) 8.dp else 0.dp, if (live) LibraryColors.Accent else LibraryColors.Accent.copy(alpha = 0.5f), CircleShape)
            .padding(if (live || someoneTalking) 12.dp else 0.dp)
            .background(if (someoneTalking && !live) LibraryColors.Muted.copy(alpha = 0.4f) else LibraryColors.Inverse, CircleShape)
            .semantics { contentDescription = label }
            .holdToTalk(onPress = { press() }, onRelease = { release() }),
        contentAlignment = Alignment.Center,
    ) {
        // The icon only (2026-11-22, hen); the label is for TalkBack.
        // A camera when the press sends video too (Voice | Video above it).
        com.chencohen.ptt.ui.style.IconsOnAccent { Icon(if (video) LibraryIcons.Video else LibraryIcons.Mic, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(64.dp)) }
    }
}

/**
 * While this screen is started (on screen, the app in front), the microphone stays open for an instant press
 * (TalkCapture.warm); it closes when the screen stops (left, screen off, app in the background). Only with the
 * microphone allowed: it is asked for on the first press.
 */
@Composable
internal fun WarmMicrophone(warm: (Boolean) -> Unit) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val current by rememberUpdatedState(warm)
    androidx.compose.runtime.DisposableEffect(owner) {
        fun allowed() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (allowed()) current(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> current(false)
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && allowed()) current(true)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            current(false)
        }
    }
}

/**
 * The voice level for the talk button, smoothed every frame (VoiceLevel: 60 ms up, 200 ms down) from the player's
 * and the microphone's (TalkAudio), while [active]; 0 otherwise. Reads two volatile floats a frame, allocates nothing.
 */
@Composable
internal fun rememberVoiceLevel(active: Boolean): androidx.compose.runtime.State<Float> {
    val level = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 16f else (now - last) / 1_000_000f
                last = now
                val target = if (active) maxOf(com.chencohen.ptt.talk.TalkAudio.playLevel, com.chencohen.ptt.talk.TalkAudio.micLevel) else 0f
                level.floatValue = com.chencohen.ptt.talk.VoiceLevel.smooth(level.floatValue, target, dt)
            }
            if (!active && level.floatValue < 0.001f) {
                level.floatValue = 0f
                break
            }
        }
    }
    return level
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
    // In the quick-reply window there's no activity to ask from: not allowed yet goes to [onDenied] (it opens the chat).
    if (androidx.activity.compose.LocalActivityResultRegistryOwner.current == null) {
        return remember(context) {
            { then: () -> Unit ->
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) then() else denied()
            }
        }
    }
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

/**
 * Hold to talk, or slide up to lock it hands-free (2026-11-24, hen): [onLift] with how far up the finger is, and
 * [onRelease] with whether it went past [lockAt] (locked: the burst goes on).
 */
internal fun Modifier.holdOrLock(
    lockAt: Float,
    onPress: () -> Unit,
    onLift: (Float) -> Unit,
    onRelease: (locked: Boolean) -> Unit,
    /**
     * How far left of where it went down the finger is, on the screen in either language (the hands-free target's
     * side; 2026-11-26, hen: "on the other side: most people are right-handed and the thumb hides the icon").
     */
    onSide: (Float) -> Unit = {},
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onPress()
        var up = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            up = (down.position.y - change.position.y).coerceAtLeast(0f)
            // Sideways first, so the lift knows which way the thumb is going (towards the side target, or up to the lock).
            onSide((down.position.x - change.position.x).coerceAtLeast(0f))
            onLift(up)
            change.consume()
            if (!change.pressed) break
        }
        onRelease(up >= lockAt)
    }
}

/** What the Talk button says under it, from the floor and this phone's own talking. */
@Composable
internal fun talkLine(chat: String, talk: TalkUiState, nameOf: (String) -> String, me: String): String {
    val own = talk.own?.takeIf { it.chat == chat }
    val talker = talk.talkers[chat]
    return when {
        own != null -> when (own.phase) {
            // Waiting for the chat's room: shown without words (2026-11-27, hen), where the push itself shows.
            TalkPhase.REQUESTING -> if (own.connecting) "" else stringResource(R.string.talk_requesting)
            TalkPhase.TALKING -> stringResource(R.string.talk_live)
            TalkPhase.BUSY -> com.chencohen.ptt.genderedString(R.string.talk_busy, own.busyWith, nameOf(own.busyWith.orEmpty()))
            // The room couldn't be reached, or was lost: no words either, one short buzz (TalkApp).
            TalkPhase.REFUSED -> when (own.refusal) {
                OwnTalk.MIC -> stringResource(R.string.talk_no_mic)
                OwnTalk.ROOM -> ""
                else -> stringResource(R.string.talk_refused)
            }
            TalkPhase.OFFLINE -> stringResource(R.string.talk_offline)
            TalkPhase.VOICE -> stringResource(R.string.voice_recording)
        }
        talker != null && talker.user == me -> stringResource(R.string.talk_other_phone)
        talker != null -> com.chencohen.ptt.genderedString(R.string.talk_someone, talker.user, nameOf(talker.user))
        // Nothing to say while nobody talks (2026-11-22, hen: no "Hold the button" text).
        else -> ""
    }
}



/** A clip inside a chat bubble: Play or Pause, a bar while it plays, its length, and "Talk" for a burst. */
@Composable
internal fun ClipContent(message: TextMessage, playing: NowPlaying?, live: Boolean, play: (String) -> Unit, stop: () -> Unit, onInverse: Boolean, save: ((String) -> Unit)? = null) {
    val clip = message.clip ?: return
    val now = playing?.clip == message.id
    val ink = if (onInverse) LibraryColors.OnMine else LibraryColors.OnTheirs
    val muted = if (onInverse) LibraryColors.OnMineMuted else LibraryColors.Muted
    Row(
        // A long press saves it as a file (2026-11-23, hen).
        Modifier.combinedClickable(role = Role.Button, onLongClick = save?.let { s -> { s(message.id) } }) { if (now) stop() else play(message.id) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(if (onInverse) LibraryColors.OnMine.copy(alpha = 0.15f) else LibraryColors.Accent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
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
    // Talking in the chat (push-to-talk) or recording a voice message.
    val active = recording?.phase == TalkPhase.VOICE || recording?.phase == TalkPhase.REQUESTING || recording?.phase == TalkPhase.TALKING
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
            com.chencohen.ptt.ui.style.IconsOnAccent { Icon(LibraryIcons.Mic, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(22.dp)) }
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

/** A contact's or group's Live typing switch (2026-11-23, hen; off unless turned on here). */
@Composable
internal fun LiveTypingCard(on: Boolean, set: (Boolean) -> Unit) {
    com.chencohen.ptt.ui.style.SectionTitle(stringResource(R.string.settings_live_typing))
    com.chencohen.ptt.ui.style.SettingsCard {
        SwitchRow(stringResource(R.string.live_typing_contact), stringResource(R.string.settings_live_typing_body), on, set)
    }
}

/** A contact's or group's Chat direction (2026-11-24, hen): follow the phone (the default), right to left, left to right. */
@Composable
internal fun ChatDirectionCard(direction: Int, set: (Int) -> Unit) {
    com.chencohen.ptt.ui.style.SectionTitle(stringResource(R.string.chat_direction))
    com.chencohen.ptt.ui.style.SettingsCard {
        val d = com.chencohen.ptt.messaging.ChatDirection
        ChoiceRow(stringResource(R.string.chat_direction_follow), stringResource(R.string.chat_direction_follow_body), direction == d.FOLLOW) { set(d.FOLLOW) }
        ChoiceRow(stringResource(R.string.chat_direction_rtl), null, direction == d.RTL) { set(d.RTL) }
        ChoiceRow(stringResource(R.string.chat_direction_ltr), null, direction == d.LTR) { set(d.LTR) }
    }
    com.chencohen.ptt.ui.style.PageNote(stringResource(R.string.chat_direction_body), top = 6)
}

/**
 * In the chat (2026-11-24, hen): whether push-to-talk voice and video stay as messages, and whether both live becomes
 * a call by itself; all off by default.
 */
@Composable
internal fun KeepInChatCard(chat: String, talk: com.chencohen.ptt.messaging.TalkSettings, set: (com.chencohen.ptt.messaging.TalkSettings) -> Unit) {
    com.chencohen.ptt.ui.style.SectionTitle(stringResource(R.string.contact_keep_title))
    com.chencohen.ptt.ui.style.SettingsCard {
        SwitchRow(stringResource(R.string.contact_keep_voice), stringResource(R.string.contact_keep_voice_body), chat in talk.keepVoice) { on ->
            set(talk.copy(keepVoice = if (on) talk.keepVoice + chat else talk.keepVoice - chat))
        }
        SwitchRow(stringResource(R.string.contact_keep_video), stringResource(R.string.contact_keep_video_body), chat in talk.keepVideo) { on ->
            set(talk.copy(keepVideo = if (on) talk.keepVideo + chat else talk.keepVideo - chat))
        }
        SwitchRow(stringResource(R.string.contact_both_live_call), stringResource(R.string.contact_both_live_call_body), chat in talk.bothLiveCall) { on ->
            set(talk.copy(bothLiveCall = if (on) talk.bothLiveCall + chat else talk.bothLiveCall - chat))
        }
    }
}

/** The layout a chat with [direction] (ChatDirection) has, on a phone laid out [phone]. */
internal fun chatLayout(direction: Int, phone: androidx.compose.ui.unit.LayoutDirection): androidx.compose.ui.unit.LayoutDirection = when (direction) {
    com.chencohen.ptt.messaging.ChatDirection.RTL -> androidx.compose.ui.unit.LayoutDirection.Rtl
    com.chencohen.ptt.messaging.ChatDirection.LTR -> androidx.compose.ui.unit.LayoutDirection.Ltr
    else -> phone
}

/** A contact's or group's Quick talk switch: on, it is a page of the Talk tab (docs/screens/quick-talk.md). */
@Composable
internal fun QuickTalkCard(on: Boolean, set: (Boolean) -> Unit) {
    com.chencohen.ptt.ui.style.SectionTitle(stringResource(R.string.quick_talk_title))
    com.chencohen.ptt.ui.style.SettingsCard {
        SwitchRow(stringResource(R.string.quick_talk_switch), stringResource(R.string.quick_talk_switch_body), on, set)
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

/**
 * Under a Talk screen's button (2026-11-22, hen): push-to-video's Video switch, the chat, a voice call, a video call.
 */
@Composable
internal fun TalkChatActions(
    openChat: () -> Unit,
    call: (video: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pushVideo: Boolean = false,
    setPushVideo: (Boolean) -> Unit = {},
    /** The other user has their Talk screen or bubble for this chat open now ("here"). */
    peerHere: Boolean = false,
) {
    // Actions only, as on a contact's page; Voice | Video sits above the big button (2026-11-22, hen).
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TalkChatAction(LibraryIcons.Chat, stringResource(R.string.talk_action_chat), onClick = openChat)
        TalkChatAction(LibraryIcons.Call, stringResource(R.string.call_voice)) { call(false) }
        // Glows when they are "here" (their Talk screen or bubble open): a video call they'll see at once.
        TalkChatAction(LibraryIcons.Video, stringResource(R.string.call_video), glow = peerHere) { call(true) }
    }
}

@Composable
private fun TalkChatAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, glow: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).background(if (glow) androidx.compose.ui.graphics.Color(0xFF2BB3A3) else LibraryColors.Raised, androidx.compose.foundation.shape.CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = LibraryColors.OnBackground, modifier = Modifier.size(24.dp)) }
    }
}

/** While shown and the app is in front, the other user is told this chat's Talk screen is open ("here"). */
@Composable
internal fun Here(chat: String, setHere: (String?) -> Unit) {
    androidx.lifecycle.compose.LifecycleStartEffect(chat) {
        setHere(chat)
        onStopOrDispose { setHere(null) }
    }
}


/**
 * A chat's two colours (2026-11-27, hen: "customize the two colors of a chat screen messages, from the contact settings
 * screen"): my bubbles and theirs, each Default or one of ChatSwatches, with two bubbles showing them as the chat will.
 * Kept on this phone only. My bubble's outline stays the green line (sent, on the server), only its fill and theirs
 * change; their text is light or dark by the colour.
 */
@Composable
internal fun ChatColorsCard(chat: String, talk: com.chencohen.ptt.messaging.TalkSettings, set: (com.chencohen.ptt.messaging.TalkSettings) -> Unit) {
    // Its own choice in the rows (A: like everyone, or on Settings' card the default look); the preview as it will show.
    val global = chat == com.chencohen.ptt.messaging.GLOBAL_COLORS
    val (mine, theirs) = talk.ownColorsOf(chat)
    val (shownMine, shownTheirs) = talk.colorsOf(chat)
    // The bubbles' brightness, for this theme (2026-11-27, hen: "from white to black slider, the brightness, default at
    // center"; and "add the slider for brightness also in the contact settings screen"): Settings' for every chat, or
    // this chat's own over it ("like everyone" until moved). Kept on this phone only.
    val light = LibraryColors.Background.luminance() > 0.5f
    val side = if (light) 0 else 1
    val everyone = if (light) com.chencohen.ptt.ui.style.ChatShade.light else com.chencohen.ptt.ui.style.ChatShade.dark
    val ownBright = if (global) null else talk.brightOf(chat)
    val ownNow = ownBright?.getOrNull(side)?.takeIf { it >= 0 }
    var value by androidx.compose.runtime.remember(light, chat, if (global) talk.colorsFor[com.chencohen.ptt.messaging.brightKey(chat)] else ownBright) {
        androidx.compose.runtime.mutableFloatStateOf(ownNow?.let { it / 100f } ?: everyone)
    }
    // While its own slider moves, the preview follows it.
    var moving by androidx.compose.runtime.remember(chat) { androidx.compose.runtime.mutableStateOf(false) }
    fun withThisTheme(v: Int): List<Int> {
        val other = ownBright?.getOrNull(1 - side) ?: -1
        return if (light) listOf(v, other) else listOf(other, v)
    }
    val previewBright = if (global) null else if (moving) withThisTheme((value * 100).toInt()) else ownBright
    com.chencohen.ptt.ui.style.SectionTitle(stringResource(R.string.chat_colors))
    com.chencohen.ptt.ui.style.SettingsCard {
        // The preview: theirs at the start side, mine at the end, as in the chat.
        com.chencohen.ptt.ui.style.ChatColors(shownMine, shownTheirs, previewBright) {
            Column(Modifier.fillMaxWidth().background(LibraryColors.Background).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        stringResource(R.string.chat_colors_sample_theirs), color = LibraryColors.OnTheirs, style = LibraryType.Message,
                        modifier = Modifier.background(LibraryColors.Theirs, com.chencohen.ptt.ui.style.LibraryShapes.Card).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        stringResource(R.string.chat_colors_sample_mine), color = LibraryColors.OnMine, style = LibraryType.Message,
                        modifier = Modifier.background(LibraryColors.Mine, com.chencohen.ptt.ui.style.LibraryShapes.Card).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        SwatchRow(stringResource(R.string.chat_colors_mine), mine, global) { set(talk.withColors(chat, it, theirs)) }
        SwatchRow(stringResource(R.string.chat_colors_theirs), theirs, global) { set(talk.withColors(chat, mine, it)) }
        // Its track goes from white through the colour to black, the colour as it is in the middle.
        val hueNow = (if (shownMine >= 0) shownMine else 2)
        val base = (if (light) com.chencohen.ptt.ui.style.ChatHuesBright else com.chencohen.ptt.ui.style.ChatHuesDeep)[hueNow % 6]
        val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
        val stops = listOf(androidx.compose.ui.graphics.Color.White, base, androidx.compose.ui.graphics.Color.Black)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_colors_brightness), color = LibraryColors.OnBackground, style = LibraryType.RowTitle, modifier = Modifier.weight(1f))
                // On a chat's page: "Like everyone" until its own is set; a tap goes back to it.
                if (!global) {
                    val following = ownNow == null && !moving
                    Text(
                        stringResource(R.string.chat_colors_like_everyone),
                        color = if (following) LibraryColors.OnInverse else LibraryColors.OnBackground,
                        style = LibraryType.Caption,
                        modifier = Modifier
                            .background(if (following) LibraryColors.Inverse else LibraryColors.Raised, CircleShape)
                            .border(1.dp, if (following) androidx.compose.ui.graphics.Color.Transparent else LibraryColors.Muted.copy(alpha = 0.4f), CircleShape)
                            .clickable(role = Role.Button) {
                                moving = false
                                value = everyone
                                val other = ownBright?.getOrNull(1 - side) ?: -1
                                set(if (light) talk.withBright(chat, -1, other) else talk.withBright(chat, other, -1))
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                // White at the start (the right in Hebrew), black at the end.
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp)
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(if (rtl) stops.reversed() else stops), CircleShape)
                        .border(1.dp, LibraryColors.Muted.copy(alpha = 0.4f), CircleShape),
                )
                androidx.compose.material3.Slider(
                    value = value,
                    onValueChange = { v ->
                        value = v
                        // Seen at once in the preview above.
                        if (global) {
                            if (light) com.chencohen.ptt.ui.style.ChatShade.light = v else com.chencohen.ptt.ui.style.ChatShade.dark = v
                        } else {
                            moving = true
                        }
                    },
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = LibraryColors.OnBackground,
                        activeTrackColor = androidx.compose.ui.graphics.Color.Transparent,
                        inactiveTrackColor = androidx.compose.ui.graphics.Color.Transparent,
                        activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
                        inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    onValueChangeFinished = {
                        if (global) {
                            val now = listOf((com.chencohen.ptt.ui.style.ChatShade.light * 100).toInt(), (com.chencohen.ptt.ui.style.ChatShade.dark * 100).toInt())
                            set(talk.copy(colorsFor = talk.colorsFor + (com.chencohen.ptt.messaging.brightKey(chat) to now)))
                        } else {
                            val both = withThisTheme((value * 100).toInt())
                            set(talk.withBright(chat, both[0], both[1]))
                            moving = false
                        }
                    },
                )
            }
        }
        // A contact's card: whether the colours they synced show here (2026-11-27, hen: "add option to respect synced
        // colors, enabled by default", "i thought the respect colors is per contact setting").
        if (!global) {
            SwitchRow(stringResource(R.string.chat_colors_respect_synced), stringResource(R.string.chat_colors_respect_synced_body), chat !in talk.ignoreSyncedFrom) { on ->
                set(talk.copy(ignoreSyncedFrom = if (on) talk.ignoreSyncedFrom - chat else talk.ignoreSyncedFrom + chat))
            }
        }
    }
    // A contact's colours go to them, a group's to its members (synced); Settings' stay on this phone.
    val note = when {
        global -> R.string.chat_colors_global_body
        com.chencohen.ptt.messaging.Group.idOf(chat) == null -> R.string.chat_colors_body_contact
        else -> R.string.chat_colors_body_group
    }
    com.chencohen.ptt.ui.style.PageNote(stringResource(note), top = 6)
}

/** A row of colours to pick from: A, then the six hues in this theme's shade; the chosen one ringed. Scrolls sideways. */
@Composable
private fun SwatchRow(title: String, chosen: Int, global: Boolean, choose: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
        val light = LibraryColors.Background.luminance() > 0.5f
        // A stored pick from the twelve shows as its hue.
        val picked = if (chosen < 0) -1 else chosen % com.chencohen.ptt.ui.style.ChatHuesDeep.size
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A: on a chat's page "like everyone" (Settings' colours), on Settings' card the default look.
            val defaultLabel = stringResource(if (global) R.string.chat_colors_default else R.string.chat_colors_like_everyone)
            (listOf(-1) + com.chencohen.ptt.ui.style.ChatHuesDeep.indices).forEach { i ->
                val on = i == picked
                val ring by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, androidx.compose.animation.core.tween(200), label = "swatch")
                Box(
                    Modifier.size(44.dp)
                        .border(2.dp, LibraryColors.OnBackground.copy(alpha = ring), CircleShape)
                        .padding(4.dp)
                        .clickable(role = Role.RadioButton, onClickLabel = if (i < 0) defaultLabel else null) { choose(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (i < 0) {
                        Box(Modifier.fillMaxSize().border(1.5.dp, LibraryColors.Muted, CircleShape), contentAlignment = Alignment.Center) {
                            Text("A", color = LibraryColors.Muted, style = LibraryType.Tiny)
                        }
                    } else {
                        Box(Modifier.fillMaxSize().background(com.chencohen.ptt.ui.style.chatHue(i, light)!!, CircleShape))
                    }
                }
            }
        }
    }
}
