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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.runtime.key
import androidx.compose.ui.layout.onSizeChanged
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
    /** How this phone shows a user's video: cropped to fill ([fill]) or whole, in a tile [aspect] wide for 1 high. */
    val setView: (user: String, fill: Boolean, aspect: Float) -> Unit = { _, _, _ -> },
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
fun CallScreen(
    call: CallUi,
    title: String,
    nameOf: (String) -> String,
    transport: LiveKitCallTransport?,
    actions: CallActions,
    compact: Boolean = false,
    canSave: Boolean = false,
    /** Call settings › Advanced › Show video stats: a Stats button here shows each video's fps, bit rate and size. */
    statsAllowed: Boolean = false,
) {
    if (compact) {
        CompactCall(call, title, transport)
        return
    }
    val room by (transport?.room ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    val changes by (transport?.changes ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color(0xFF0F1413))) {
        val r = room
        val remote = if (r != null && call.phase == CallPhase.ACTIVE) remoteVideo(r, changes) else emptyList()
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("call_view", android.content.Context.MODE_PRIVATE) }
        // Two layouts (2026-11-23, hen, from the approved mockup), kept for next time. Maximized: their video covers the
        // whole screen, cropped, under the cutout too; this phone's in a small corner window. Normal: their whole
        // picture as wide as the screen from the very top (side by side on its side), this phone's in the rest.
        var maximized by remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("fill", true)) }
        fun toggleLayout() {
            maximized = !maximized
            prefs.edit().putBoolean("fill", maximized).apply()
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
        var statsShown by remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("stats", false)) }
        val stats = statsAllowed && statsShown
        // Follow my face (2026-11-22, hen): framed on this phone before sending; kept for next time.
        var follow by remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("follow_face", false)) }
        LaunchedEffect(transport, follow) { transport?.followFace = follow }
        val own = r?.let { ownVideo(it, changes) }?.takeIf { call.camera }
        val ownShape = call.ownAspect.takeIf { it > 0f } ?: (if (landscape) 4f / 3f else 3f / 4f)
        // Their pictures' shapes, from their frames: Normal sizes their video by it.
        val shapes = remember { androidx.compose.runtime.mutableStateMapOf<String, Float>() }
        // A tap anywhere on the video hides or shows every control; nothing to hide before the video comes.
        val videoOn = call.phase == CallPhase.ACTIVE && (remote.isNotEmpty() || own != null)
        var controls by remember { androidx.compose.runtime.mutableStateOf(true) }
        val shown = controls || !videoOn
        val normal = !maximized && remote.isNotEmpty()

        // Each tile's shape and whether it is cropped go to its sender (their preview outlines the crop).
        fun Modifier.told(user: String) = onSizeChanged { size ->
            if (size.height > 0) actions.setView(user, maximized, size.width.toFloat() / size.height)
        }

        @Composable
        fun theirs(modifier: Modifier) {
            if (landscape) {
                Row(modifier) {
                    remote.forEach { (user, track) ->
                        key(user, track.sid ?: track.name, maximized) {
                            VideoView(r!!, track, Modifier.weight(1f).fillMaxSize().told(user), fill = maximized, stats = stats, onShape = { shapes[user] = it })
                        }
                    }
                }
            } else {
                Column(modifier) {
                    remote.forEach { (user, track) ->
                        key(user, track.sid ?: track.name, maximized) {
                            VideoView(r!!, track, Modifier.weight(1f).fillMaxWidth().told(user), fill = maximized, stats = stats, onShape = { shapes[user] = it })
                        }
                    }
                }
            }
        }

        @Composable
        fun mine(modifier: Modifier) {
            val o = own ?: return
            Box(modifier) {
                key(o.sid ?: o.name) { VideoView(r!!, o, Modifier.fillMaxSize(), mirror = true, fill = false, stats = stats) }
                CropOutlines(ownShape, call.viewers.filterValues { it.fill }, nameOf, showNames = call.viewers.size > 1)
            }
        }

        Box(
            Modifier.fillMaxSize().pointerInput(videoOn) { detectTapGestures(onTap = { if (videoOn) controls = !controls }) },
        ) {
            when {
                normal && landscape -> Row(Modifier.fillMaxSize()) {
                    // Theirs at full height and its own shape from the start edge; this phone's in the rest.
                    val shape = remote.firstOrNull()?.first?.let { shapes[it] } ?: (4f / 3f)
                    theirs(Modifier.fillMaxHeight().aspectRatio(shape * remote.size.coerceAtLeast(1), matchHeightConstraintsFirst = true))
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        mine(Modifier.fillMaxSize().padding(8.dp))
                    }
                }
                normal -> Column(Modifier.fillMaxSize()) {
                    // Theirs as wide as the screen at its own shape, from the very top; this phone's in the rest.
                    val shape = remote.firstOrNull()?.first?.let { shapes[it] } ?: (3f / 4f)
                    theirs(Modifier.fillMaxWidth().aspectRatio(shape / remote.size.coerceAtLeast(1)))
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        mine(Modifier.fillMaxSize().padding(8.dp))
                    }
                }
                else -> {
                    if (remote.isNotEmpty()) theirs(Modifier.fillMaxSize())
                    if (own != null) {
                        // A small window in the corner, above the buttons while they show.
                        val lift by androidx.compose.animation.core.animateDpAsState(if (shown) 150.dp else 16.dp, label = "own window")
                        val window = if (ownShape >= 1f) Modifier.size(width = 160.dp, height = (160 / ownShape).dp) else Modifier.size(width = (160 * ownShape).dp, height = 160.dp)
                        mine(
                            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = lift).then(window)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            shown,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                val scrim = Color.Black.copy(alpha = 0.55f)
                // The top: the pills, the name and how the call is going, over a soft shade.
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(if (videoOn) scrim else Color.Transparent, Color.Transparent)))
                        .statusBarsPadding().displayCutoutPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (call.phase == CallPhase.ACTIVE && call.video) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            Pill(
                                stringResource(
                                    when (orientation) {
                                        1 -> R.string.call_orientation_portrait
                                        2 -> R.string.call_orientation_landscape
                                        else -> R.string.call_orientation_auto
                                    },
                                ),
                            ) {
                                orientation = (orientation + 1) % 3
                                prefs.edit().putInt("orientation", orientation).apply()
                            }
                            if (call.camera) {
                                Pill(stringResource(R.string.call_follow_face), on = follow) {
                                    follow = !follow
                                    prefs.edit().putBoolean("follow_face", follow).apply()
                                }
                            }
                            if (statsAllowed) {
                                Pill(stringResource(R.string.call_stats), on = statsShown) {
                                    statsShown = !statsShown
                                    prefs.edit().putBoolean("stats", statsShown).apply()
                                }
                            }
                            // The layout a tap switches to.
                            if (remote.isNotEmpty()) Pill(stringResource(if (maximized) R.string.call_layout_normal else R.string.call_layout_maximized)) { toggleLayout() }
                        }
                    }
                    if (remote.isEmpty()) {
                        Spacer(Modifier.height(48.dp))
                        Avatar(title, size = 96.dp)
                    }
                    Spacer(Modifier.height(12.dp))
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
                            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f), com.chencohen.ptt.ui.style.LibraryShapes.Card).padding(16.dp),
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
                }
                // The bottom: Save or Stop, and the call's buttons, over a soft shade.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, if (videoOn) scrim else Color.Transparent)))
                        .navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
                }
            }
        }
    }
}

/** A small pill over the video: [on] in the inverse colour. */
@Composable
private fun Pill(text: String, on: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (on) LibraryColors.OnInverse else Color.White, style = LibraryType.Small,
        modifier = Modifier.background(if (on) LibraryColors.Inverse else LibraryColors.PillScrim, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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
/** The others' cameras, with whose each is (a user). */
private fun remoteVideo(room: Room, changes: Int): List<Pair<String, VideoTrack>> =
    room.remoteParticipants.values.mapNotNull { p ->
        val track = p.getTrackPublication(Track.Source.CAMERA)?.takeIf { !it.muted }?.track as? VideoTrack ?: return@mapNotNull null
        (p.identity?.value?.substringBeforeLast('.') ?: "") to track
    }

@Suppress("UNUSED_PARAMETER")
private fun ownVideo(room: Room, changes: Int): VideoTrack? =
    room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.takeIf { !it.muted }?.track as? VideoTrack

/**
 * One video track drawn into a TextureViewRenderer, given back when it leaves the screen. [fill] crops the picture to
 * the space given; otherwise it is shown whole, as big as fits, with bars. The renderer always crops to its own view's
 * shape (its scaling type only sizes the view, which Compose fixes), so Fit shapes the view to the picture
 * (2026-11-22, hen: "fit and crop is not working": both cropped).
 */
@Composable
private fun VideoView(room: Room, track: VideoTrack, modifier: Modifier, mirror: Boolean = false, fill: Boolean = true, stats: Boolean = false, onShape: (Float) -> Unit = {}) {
    val context = LocalContext.current
    val renderer = remember(track) { TextureViewRenderer(context).also { room.initVideoRenderer(it); it.setMirror(mirror) } }
    // The picture's shape as shown (its rotation applied), from the frames themselves.
    var aspect by remember(track) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(renderer, fill) {
        renderer.setScalingType(if (fill) livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL else livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    }
    DisposableEffect(track) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val sizes = object : livekit.org.webrtc.VideoSink {
            private var last = ""
            override fun onFrame(frame: livekit.org.webrtc.VideoFrame) {
                val w = frame.rotatedWidth
                val h = frame.rotatedHeight
                val now = "${frame.buffer.width}x${frame.buffer.height} r${frame.rotation}"
                if (h <= 0 || now == last) return
                last = now
                android.util.Log.i("CallVideo", "${if (mirror) "own" else "received"} video $now, shown ${w}x$h")
                main.post {
                    aspect = w.toFloat() / h
                    onShape(w.toFloat() / h)
                }
            }
        }
        track.addRenderer(renderer)
        track.addRenderer(sizes)
        onDispose {
            track.removeRenderer(sizes)
            track.removeRenderer(renderer)
            renderer.release()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val shaped = if (!fill && aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize()
        AndroidView(factory = { renderer }, modifier = shaped)
        val line = videoStats(track, stats)
        if (line != null) {
            Text(
                line, color = Color.White, style = LibraryType.Tiny,
                modifier = Modifier.align(Alignment.TopStart).then(if (fill) Modifier.statusBarsPadding() else Modifier).padding(6.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Show video stats (Call settings › Advanced; 2026-11-22, hen): [track]'s frame rate, bit rate, size and codec from
 * WebRTC's stats, read once a second while [on]. This phone's camera sums its simulcast layers' bytes and shows the
 * biggest layer's size and rate.
 */
@Composable
private fun videoStats(track: VideoTrack, on: Boolean): String? {
    var line by remember(track) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    LaunchedEffect(track, on) {
        if (!on) {
            line = null
            return@LaunchedEffect
        }
        var lastBytes = -1L
        var lastAt = 0L
        while (true) {
            val report = runCatching { track.getRTCStats() }.getOrNull()
            if (report != null) {
                val all = report.statsMap.values
                val rtp = all.filter { (it.type == "inbound-rtp" || it.type == "outbound-rtp") && it.members["kind"] == "video" }
                fun num(s: livekit.org.webrtc.RTCStats, k: String) = (s.members[k] as? Number)?.toDouble()
                val bytes = rtp.sumOf { (num(it, "bytesReceived") ?: num(it, "bytesSent") ?: 0.0).toLong() }
                val top = rtp.maxByOrNull { num(it, "frameWidth") ?: 0.0 }
                val now = android.os.SystemClock.elapsedRealtime()
                val kbps = if (lastBytes >= 0 && now > lastAt) (bytes - lastBytes) * 8 / (now - lastAt) else null
                lastBytes = bytes
                lastAt = now
                if (top != null) {
                    val codec = (all.firstOrNull { it.id == top.members["codecId"] }?.members?.get("mimeType") as? String)?.substringAfter('/')
                    val w = num(top, "frameWidth")?.toInt()
                    val h = num(top, "frameHeight")?.toInt()
                    line = listOfNotNull(
                        num(top, "framesPerSecond")?.let { "%.0f fps".format(it) },
                        kbps?.let { "$it kbit/s" },
                        if (w != null && h != null) "${w}×$h" else null,
                        codec,
                    ).joinToString(" · ")
                }
            }
            delay(1_000)
        }
    }
    return line
}


/**
 * On this phone's own preview (its whole picture, [shape] wide for 1 high): the part each viewer sees when they crop
 * to fill their tile, a thin outline each, with their name when there are several.
 */
@Composable
private fun CropOutlines(shape: Float, viewers: Map<String, com.chencohen.ptt.calls.CallView>, nameOf: (String) -> String, showNames: Boolean) {
    if (viewers.isEmpty()) return
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        viewers.forEach { (user, view) ->
            // Fill shows the middle of the picture in the tile's shape.
            val (fw, fh) = if (shape > view.aspect) (view.aspect / shape) to 1f else 1f to (shape / view.aspect)
            Box(
                Modifier.align(Alignment.Center).size(w * fw, h * fh)
                    .border(1.5.dp, Color.White.copy(alpha = 0.9f)),
            ) {
                if (showNames) {
                    Text(
                        nameOf(user), color = Color.White, style = LibraryType.Tiny,
                        modifier = Modifier.align(Alignment.TopStart).background(LibraryColors.PillScrim).padding(horizontal = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * The call in the small floating window (picture-in-picture): the other's video filling it, this phone's own in a
 * corner; Android's window gives Hang up and closing.
 */
@Composable
private fun CompactCall(call: CallUi, title: String, transport: LiveKitCallTransport?) {
    val room by (transport?.room ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    val changes by (transport?.changes ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val r = room
        val remote = if (r != null) remoteVideo(r, changes) else emptyList()
        if (r != null && remote.isNotEmpty()) {
            Column(Modifier.fillMaxSize()) {
                remote.forEach { (_, track) -> VideoView(r, track, Modifier.weight(1f).fillMaxWidth(), fill = true) }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(title, size = 56.dp) }
        }
        val own = r?.let { ownVideo(it, changes) }
        if (r != null && own != null && call.camera) {
            VideoView(r, own, Modifier.align(Alignment.BottomEnd).padding(6.dp).size(width = 40.dp, height = 54.dp), mirror = true, fill = true)
        }
    }
}
