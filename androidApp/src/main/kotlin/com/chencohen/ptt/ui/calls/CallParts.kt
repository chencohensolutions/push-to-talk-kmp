package com.chencohen.ptt.ui.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohen.ptt.R
import com.chencohen.ptt.calls.CallPhase
import com.chencohen.ptt.calls.CallUi
import com.chencohen.ptt.calls.LiveKitCallTransport
import com.chencohen.ptt.messaging.CallOutcome
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.talk.clipLength
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** What the call screen can do. */
class CallActions(
    val accept: () -> Unit = {},
    val decline: () -> Unit = {},
    val hangUp: () -> Unit = {},
    val setMicrophone: (Boolean) -> Unit = {},
    val setCamera: (Boolean) -> Unit = {},
    val switchCamera: () -> Unit = {},
    /** Save calls: saving starts or stops; "Save this call?" Not now. */
    val setSaving: (Boolean) -> Unit = {},
    val dismissAsk: () -> Unit = {},
)

/** Shares a saved call's file (the app's calls folder, through its FileProvider). */
fun shareCall(context: android.content.Context, name: String) {
    val file = java.io.File(java.io.File(context.filesDir, "calls"), name)
    if (!file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".calls", file)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(if (name.endsWith(".mkv")) "video/x-matroska" else "video/mp4")
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, context.getString(R.string.call_share)))
}

/**
 * Asks for the microphone (and the camera for a video call) before [then]; [onDenied] when the microphone isn't
 * allowed. Without the camera a video call goes on as a voice call.
 */
@Composable
fun rememberCallPermissions(onDenied: () -> Unit): (video: Boolean, then: (video: Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val denied by rememberUpdatedState(onDenied)
    var pending by remember { androidx.compose.runtime.mutableStateOf<Pair<Boolean, (Boolean) -> Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val (video, then) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (granted[Manifest.permission.RECORD_AUDIO] != true && !allowed(context, Manifest.permission.RECORD_AUDIO)) denied()
        else then(video && (granted[Manifest.permission.CAMERA] == true || allowed(context, Manifest.permission.CAMERA)))
    }
    return { video, then ->
        val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { video }).filter { !allowed(context, it) }
        if (needed.isEmpty()) then(video) else {
            pending = video to then
            launcher.launch(needed.toTypedArray())
        }
    }
}

private fun allowed(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** "Missed voice call", "Video call · 3:12", for the chat and the Calls tab. */
@Composable
fun callLine(message: TextMessage): String {
    val call = message.call ?: return message.body
    val kind = stringResource(if (call.video) R.string.call_kind_video else R.string.call_kind_voice)
    return when (call.outcome) {
        CallOutcome.ANSWERED -> if (call.durationMs > 0) stringResource(R.string.call_line_length, kind, clipLength(call.durationMs)) else kind
        CallOutcome.MISSED -> stringResource(R.string.call_line_missed, kind)
        CallOutcome.DECLINED -> stringResource(R.string.call_line_declined, kind)
        CallOutcome.NO_ANSWER -> stringResource(R.string.call_line_no_answer, kind)
        CallOutcome.BUSY -> stringResource(R.string.call_line_busy, kind)
        CallOutcome.CANCELLED -> stringResource(R.string.call_line_cancelled, kind)
        CallOutcome.ELSEWHERE -> stringResource(R.string.call_line_elsewhere, kind)
        CallOutcome.FAILED -> stringResource(R.string.call_line_failed, kind)
    }
}

/** A call's entry in the chat: its icon and line; missed in red. */
@Composable
fun CallEntryContent(message: TextMessage, onInverse: Boolean, share: (String) -> Unit = {}, playKept: (TextMessage) -> Unit = {}, callBack: () -> Unit) {
    val call = message.call ?: return
    val missed = call.outcome == CallOutcome.MISSED
    Row(Modifier.clickable(role = Role.Button, onClick = callBack).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (call.video) LibraryIcons.Video else LibraryIcons.Call, null,
            tint = if (missed) LibraryColors.Error else if (onInverse) LibraryColors.OnInverse else LibraryColors.OnBackground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            callLine(message),
            color = if (missed) LibraryColors.Error else if (onInverse) LibraryColors.OnInverse else LibraryColors.OnBackground,
            style = LibraryType.Body,
        )
    }
    call.savedAs?.let { file ->
        Text(
            stringResource(R.string.call_share_saved),
            color = if (onInverse) LibraryColors.OnInverse else LibraryColors.Accent,
            style = LibraryType.Caption,
            modifier = Modifier.clickable(role = Role.Button) { share(file) }.padding(vertical = 4.dp),
        )
    }
    if (call.keptKey != null) {
        Text(
            stringResource(R.string.kept_open),
            color = if (onInverse) LibraryColors.OnInverse else LibraryColors.Accent,
            style = LibraryType.Caption,
            modifier = Modifier.clickable(role = Role.Button) { playKept(message) }.padding(vertical = 4.dp),
        )
    }
}

/** The Calls tab: every call, newest first; a row calls back the same way. */
@Composable
fun CallsList(
    calls: List<TextMessage>,
    nameOf: (String) -> String,
    callBack: (chat: String, video: Boolean) -> Unit,
    open: (String) -> Unit,
    share: (String) -> Unit = {},
    playKept: (TextMessage) -> Unit = {},
    saveKept: (TextMessage) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        calls.forEach { message ->
            val call = message.call ?: return@forEach
            val missed = call.outcome == CallOutcome.MISSED
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { open(message.user) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(nameOf(message.user))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(nameOf(message.user), color = if (missed) LibraryColors.Error else LibraryColors.OnBackground, style = LibraryType.RowTitle)
                    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.at))
                    Text(
                        "${stringResource(if (message.outgoing) R.string.call_outgoing else R.string.call_incoming)} · ${callLine(message)} · $time",
                        color = LibraryColors.Muted, style = LibraryType.Caption,
                    )
                }
                if (call.keptKey != null) {
                    val saveLabel = stringResource(R.string.kept_save)
                    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = saveLabel) { saveKept(message) }, contentAlignment = Alignment.Center) {
                        Icon(LibraryIcons.Download, saveLabel, tint = LibraryColors.OnBackground)
                    }
                    val playLabel = stringResource(R.string.kept_open)
                    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = playLabel) { playKept(message) }, contentAlignment = Alignment.Center) {
                        Icon(LibraryIcons.Play, playLabel, tint = LibraryColors.OnBackground)
                    }
                }
                call.savedAs?.let { file ->
                    val shareLabel = stringResource(R.string.call_share)
                    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = shareLabel) { share(file) }, contentAlignment = Alignment.Center) {
                        Icon(LibraryIcons.Share, shareLabel, tint = LibraryColors.OnBackground)
                    }
                }
                val label = stringResource(if (call.video) R.string.call_video else R.string.call_voice)
                Box(
                    Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label) { callBack(message.user, call.video) },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (call.video) LibraryIcons.Video else LibraryIcons.Call, label, tint = LibraryColors.OnBackground) }
            }
        }
    }
}

/**
 * The call screen (docs/screens/calls.md), over everything while a call is on: ringing (Decline in red, Answer in
 * teal), calling, then the others' video (or their names for a voice call) with this phone's own picture in a corner,
 * the Recording badge, and the microphone, camera, flip and Hang up buttons.
 */
@Composable
fun CallScreen(call: CallUi, title: String, nameOf: (String) -> String, transport: LiveKitCallTransport?, actions: CallActions, canSave: Boolean = false) {
    val room by (transport?.room ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    val changes by (transport?.changes ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color(0xFF0F1413))) {
        val r = room
        val remote = if (r != null && call.phase == CallPhase.ACTIVE) remoteVideo(r, changes) else emptyList()
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("call_view", android.content.Context.MODE_PRIVATE) }
        // The others' picture comes whole (the camera's own 4:3); this phone fills the screen with it (cropped) or fits
        // it whole. A tap on the pill, or a double tap on the video, switches; kept for next time (2026-11-22, hen).
        var fill by remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("fill", true)) }
        fun toggleFill() {
            fill = !fill
            prefs.edit().putBoolean("fill", fill).apply()
        }
        // The screen's orientation during a call: Auto (the phone's sensor), Portrait or Landscape; kept for next time.
        var orientation by remember { androidx.compose.runtime.mutableIntStateOf(prefs.getInt("orientation", 0)) }
        val activity = context as? android.app.Activity
        androidx.compose.runtime.DisposableEffect(orientation) {
            activity?.requestedOrientation = when (orientation) {
                1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
            onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (remote.isNotEmpty()) {
            val tile = Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleFill() }) }
            // Side by side in landscape, one above the other in portrait.
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    remote.forEach { track -> VideoView(r!!, track, tile.weight(1f).fillMaxSize(), fill = fill) }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    remote.forEach { track -> VideoView(r!!, track, tile.weight(1f).fillMaxWidth(), fill = fill) }
                }
            }
        }
        if (call.phase == CallPhase.ACTIVE && call.video) {
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        when (orientation) {
                            1 -> R.string.call_orientation_portrait
                            2 -> R.string.call_orientation_landscape
                            else -> R.string.call_orientation_auto
                        },
                    ),
                    color = Color.White, style = LibraryType.Small,
                    modifier = Modifier.background(LibraryColors.PillScrim, CircleShape)
                        .clickable(role = Role.Button) {
                            orientation = (orientation + 1) % 3
                            prefs.edit().putInt("orientation", orientation).apply()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (remote.isNotEmpty()) {
                    Text(
                        stringResource(if (fill) R.string.call_video_fit else R.string.call_video_fill),
                        color = Color.White, style = LibraryType.Small,
                        modifier = Modifier.background(LibraryColors.PillScrim, CircleShape)
                            .clickable(role = Role.Button) { toggleFill() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (remote.isEmpty()) Spacer(Modifier.height(48.dp))
            if (remote.isEmpty()) Avatar(title, size = 96.dp)
            Spacer(Modifier.height(16.dp))
            Text(title, color = LibraryColors.PhotoText, style = LibraryType.PageTitle, textAlign = TextAlign.Center)
            Text(statusLine(call, nameOf), color = LibraryColors.PhotoTextMuted, style = LibraryType.Body, textAlign = TextAlign.Center)
            val recorders = call.recordingBy + listOfNotNull(call.serverRecordingBy)
            if (recorders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.call_recording, recorders.joinToString { nameOf(it) }),
                    color = Color.White, style = LibraryType.Small,
                    modifier = Modifier.background(LibraryColors.Error, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (call.askToSave && call.phase == CallPhase.ACTIVE) {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.12f), com.chencohen.ptt.ui.style.LibraryShapes.Card).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.call_save_ask), color = LibraryColors.PhotoText, style = LibraryType.RowTitle)
                    Text(stringResource(R.string.call_save_ask_body), color = LibraryColors.PhotoTextMuted, style = LibraryType.Caption, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(
                            stringResource(R.string.call_save_not_now), color = LibraryColors.PhotoTextMuted, style = LibraryType.RowTitle,
                            modifier = Modifier.clickable(role = Role.Button, onClick = actions.dismissAsk).padding(8.dp),
                        )
                        Text(
                            stringResource(R.string.call_save), color = LibraryColors.PhotoText, style = LibraryType.RowTitle,
                            modifier = Modifier.clickable(role = Role.Button) { actions.setSaving(true) }.padding(8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Stop shows for anyone while the server keeps the call; Record only where it can be saved.
            val stoppable = call.saving || call.serverRecordingBy != null
            if ((canSave || stoppable) && call.phase == CallPhase.ACTIVE) {
                Text(
                    stringResource(if (stoppable) R.string.call_save_stop else R.string.call_save),
                    color = Color.White, style = LibraryType.Small,
                    modifier = Modifier
                        .background(if (stoppable) LibraryColors.Error else Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable(role = Role.Button) { actions.setSaving(!stoppable) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
            val own = r?.let { ownVideo(it, changes) }
            if (own != null && call.camera) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    VideoView(r, own, if (landscape) Modifier.size(width = 160.dp, height = 108.dp) else Modifier.size(width = 108.dp, height = 160.dp), mirror = true, fill = false)
                }
                Spacer(Modifier.height(16.dp))
            }
            when (call.phase) {
                CallPhase.RINGING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(LibraryIcons.Close, stringResource(R.string.call_decline), LibraryColors.Error, actions.decline)
                    RoundButton(if (call.video) LibraryIcons.Video else LibraryIcons.Call, stringResource(R.string.call_answer), LibraryColors.Accent, actions.accept)
                }
                CallPhase.ENDED -> {}
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(
                        if (call.microphone) LibraryIcons.Mic else LibraryIcons.MicOff,
                        stringResource(if (call.microphone) R.string.call_mute else R.string.call_unmute),
                        Color.White.copy(alpha = 0.18f),
                    ) { actions.setMicrophone(!call.microphone) }
                    RoundButton(
                        if (call.camera) LibraryIcons.Video else LibraryIcons.VideoOff,
                        stringResource(if (call.camera) R.string.call_camera_off else R.string.call_camera_on),
                        Color.White.copy(alpha = 0.18f),
                    ) { actions.setCamera(!call.camera) }
                    if (call.camera) RoundButton(LibraryIcons.Flip, stringResource(R.string.call_flip), Color.White.copy(alpha = 0.18f), actions.switchCamera)
                    RoundButton(LibraryIcons.Call, stringResource(R.string.call_hang_up), LibraryColors.Error, actions.hangUp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun statusLine(call: CallUi, nameOf: (String) -> String): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallPhase.ACTIVE) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return when (call.phase) {
        CallPhase.CALLING -> stringResource(R.string.call_calling)
        CallPhase.RINGING -> stringResource(if (call.video) R.string.call_ringing_video else R.string.call_ringing_voice)
        CallPhase.CONNECTING -> stringResource(R.string.call_connecting)
        CallPhase.ACTIVE -> {
            val length = clipLength((now - call.connectedAt).coerceAtLeast(0))
            if (call.others.size > 1) "${call.others.joinToString { nameOf(it) }} · $length" else length
        }
        CallPhase.ENDED -> stringResource(
            when (call.outcome) {
                CallOutcome.BUSY -> R.string.call_ended_busy
                CallOutcome.DECLINED -> R.string.call_ended_declined
                CallOutcome.NO_ANSWER -> R.string.call_ended_no_answer
                CallOutcome.FAILED -> R.string.call_ended_failed
                CallOutcome.ELSEWHERE -> R.string.call_ended_elsewhere
                else -> R.string.call_ended
            },
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).background(color, CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = LibraryColors.PhotoTextMuted, style = LibraryType.Small)
    }
}

/** The others' cameras now ([changes] only makes this read again when tracks change). */
@Suppress("UNUSED_PARAMETER")
private fun remoteVideo(room: Room, changes: Int): List<VideoTrack> =
    room.remoteParticipants.values.mapNotNull { p ->
        p.getTrackPublication(Track.Source.CAMERA)?.takeIf { !it.muted }?.track as? VideoTrack
    }

@Suppress("UNUSED_PARAMETER")
private fun ownVideo(room: Room, changes: Int): VideoTrack? =
    room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.takeIf { !it.muted }?.track as? VideoTrack

/** One video track drawn into a TextureViewRenderer, given back when it leaves the screen. */
@Composable
private fun VideoView(room: Room, track: VideoTrack, modifier: Modifier, mirror: Boolean = false, fill: Boolean = true) {
    val context = LocalContext.current
    val renderer = remember(track) { TextureViewRenderer(context).also { room.initVideoRenderer(it); it.setMirror(mirror) } }
    androidx.compose.runtime.LaunchedEffect(renderer, fill) {
        renderer.setScalingType(if (fill) livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL else livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    }
    DisposableEffect(track) {
        track.addRenderer(renderer)
        onDispose {
            track.removeRenderer(renderer)
            renderer.release()
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}
