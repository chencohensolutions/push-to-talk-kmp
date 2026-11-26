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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.animation.core.VectorConverter
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
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
            tint = if (missed) LibraryColors.Error else if (onInverse) LibraryColors.OnMine else LibraryColors.OnBackground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            callLine(message),
            color = if (missed) LibraryColors.Error else if (onInverse) LibraryColors.OnMine else LibraryColors.OnBackground,
            style = LibraryType.Body,
        )
    }
    call.savedAs?.let { file ->
        Text(
            stringResource(R.string.call_share_saved),
            color = if (onInverse) LibraryColors.OnMine else LibraryColors.Accent,
            style = LibraryType.Caption,
            modifier = Modifier.clickable(role = Role.Button) { share(file) }.padding(vertical = 4.dp),
        )
    }
    if (call.keptKey != null) {
        Text(
            stringResource(R.string.kept_open),
            color = if (onInverse) LibraryColors.OnMine else LibraryColors.Accent,
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
    downloaded: Set<String> = emptySet(),
) {
    Column(Modifier.fillMaxWidth()) {
        calls.forEach { message ->
            val call = message.call ?: return@forEach
            val missed = call.outcome == CallOutcome.MISSED
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { open(message.user) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(message.user, name = nameOf(message.user))
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
                    // Once the call has been downloaded from the server, its Download button is gone (2026-11-24, hen).
                    if (message.id !in downloaded) {
                        val saveLabel = stringResource(R.string.kept_save)
                        Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = saveLabel) { saveKept(message) }, contentAlignment = Alignment.Center) {
                            Icon(LibraryIcons.Download, saveLabel, tint = LibraryColors.OnBackground)
                        }
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
 * and the microphone, camera, flip and Hang up buttons.
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
        // On its side, the camera's edge: left when turned left (90), right when turned right (270); followed live,
        // a turn of 180 degrees changes no configuration.
        val cameraLeft = rememberDisplayRotation() != android.view.Surface.ROTATION_270
        var statsShown by remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("stats", false)) }
        val stats = statsAllowed && statsShown
        // Follow face (2026-11-23, hen): on what is received, this phone's view only; kept for next time.
        var followThem by remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("follow_remote", false)) }
        // Maximized 1:1 (2026-11-23, hen): where their face sits (a share of the height; one finger up or down on their
        // video), where this phone's window rests (one of six places), and the part of the screen it covers.
        var faceAt by remember { androidx.compose.runtime.mutableFloatStateOf(prefs.getFloat("face_at", 0.33f)) }
        var ownSlot by remember { androidx.compose.runtime.mutableIntStateOf(prefs.getInt("own_slot", 5)) }
        var ownArea by remember { androidx.compose.runtime.mutableStateOf<com.chencohen.ptt.calls.CallGrid.Area?>(null) }
        var screenHeight by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val dragFace: (Float) -> Unit = { dy ->
            if (screenHeight > 0f) {
                faceAt = (faceAt + dy / screenHeight).coerceIn(0.15f, 0.85f)
                prefs.edit().putFloat("face_at", faceAt).apply()
            }
        }
        val own = r?.let { ownVideo(it, changes) }?.takeIf { call.camera }
        val ownShape = call.ownAspect.takeIf { it > 0f } ?: (if (landscape) 4f / 3f else 3f / 4f)
        // Their pictures' shapes, from their frames: Normal sizes their video by it.
        val shapes = remember { androidx.compose.runtime.mutableStateMapOf<String, Float>() }
        // A tap anywhere on the video hides or shows every control; nothing to hide before the video comes.
        val videoOn = call.phase == CallPhase.ACTIVE && (remote.isNotEmpty() || own != null)
        var controls by remember { androidx.compose.runtime.mutableStateOf(true) }
        val shown = controls || !videoOn
        // Three or more in the call: everyone, this phone too, in equal tiles (2026-11-23, hen).
        val group = call.phase == CallPhase.ACTIVE && remote.size >= 2
        val normal = !maximized && remote.isNotEmpty() && !group
        // Their one video over the whole screen: the face height and this phone's window places apply.
        val solo = maximized && remote.size == 1 && !group

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
                            VideoView(r!!, track, Modifier.weight(1f).fillMaxSize().told(user), fill = maximized, stats = stats, onShape = { shapes[user] = it }, follow = followThem && user == remote.first().first, hint = { transport?.faceHint(user) }, pinchKey = if (remote.size == 1) (if (maximized) "zoom_maximized" else "zoom_normal") else null, faceAt = if (solo) faceAt else 0.4f, dragFace = if (solo) dragFace else null, avoid = if (solo) ownArea else null, pan = solo)
                        }
                    }
                }
            } else {
                Column(modifier) {
                    remote.forEach { (user, track) ->
                        key(user, track.sid ?: track.name, maximized) {
                            VideoView(r!!, track, Modifier.weight(1f).fillMaxWidth().told(user), fill = maximized, stats = stats, onShape = { shapes[user] = it }, follow = followThem && user == remote.first().first, hint = { transport?.faceHint(user) }, pinchKey = if (remote.size == 1) (if (maximized) "zoom_maximized" else "zoom_normal") else null, faceAt = if (solo) faceAt else 0.4f, dragFace = if (solo) dragFace else null, avoid = if (solo) ownArea else null, pan = solo)
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

        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        // Sides on its side are the camera's, not the language's: laid out left to right, text kept as it reads.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides if (landscape) androidx.compose.ui.unit.LayoutDirection.Ltr else direction,
        ) {
        Box(
            Modifier.fillMaxSize().pointerInput(videoOn) { detectTapGestures(onTap = { if (videoOn) controls = !controls }) },
        ) {
            when {
                group -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Equal tiles, each cropped to fill; with Follow face each person's face in the middle of their
                    // tile at the same head-and-shoulders size, so the grid looks even on any phone's shape.
                    val tiles = remote + listOfNotNull(own?.let { "" to it })
                    val grid = com.chencohen.ptt.calls.CallGrid.shape(tiles.size, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    // About three looks a second in all, shared out among the tiles.
                    val every = 500L * tiles.size
                    Column(Modifier.fillMaxSize()) {
                        for (row in 0 until grid.rows) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                for (column in 0 until grid.columns) {
                                    val at = row * grid.columns + column
                                    if (at >= tiles.size) {
                                        Spacer(Modifier.weight(1f).fillMaxHeight())
                                        continue
                                    }
                                    val (user, track) = tiles[at]
                                    key(user, track.sid ?: track.name) {
                                        VideoView(
                                            r!!, track,
                                            Modifier.weight(1f).fillMaxHeight().padding(1.dp).then(if (user.isNotEmpty()) Modifier.told(user) else Modifier),
                                            mirror = user.isEmpty(), fill = true, stats = stats,
                                            follow = followThem, maxZoom = 1.5f, faceAt = 0.45f, everyMs = every,
                                            hint = { if (user.isNotEmpty()) transport?.faceHint(user) else null },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                normal -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Theirs whole at its own shape: full height on the camera's side on its side (whichever way the
                    // phone is turned), full width from the very top upright. This phone's as big as fits in the rest
                    // (2026-11-23, hen: "there is more room for the video"): the buttons float over it. CallGrid.normal.
                    val n = remote.size.coerceAtLeast(1)
                    val shape = remote.firstOrNull()?.first?.let { shapes[it] } ?: (if (landscape) 4f / 3f else 3f / 4f)
                    val (them, me) = com.chencohen.ptt.calls.CallGrid.normal(
                        constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
                        if (landscape) shape * n else shape / n, ownShape, landscape,
                    )
                    val d = androidx.compose.ui.platform.LocalDensity.current
                    fun sized(sz: com.chencohen.ptt.calls.CallGrid.Size) = with(d) { Modifier.size(sz.width.toDp(), sz.height.toDp()) }
                    if (landscape) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            @Composable
                            fun theirSide() = Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { theirs(sized(them)) }
                            @Composable
                            fun mySide() = Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { mine(sized(me)) }
                            if (cameraLeft) {
                                theirSide()
                                mySide()
                            } else {
                                mySide()
                                theirSide()
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            theirs(sized(them))
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { mine(sized(me)) }
                        }
                    }
                }
                else -> {
                    if (remote.isNotEmpty()) theirs(Modifier.fillMaxSize())
                    if (own != null && !group) {
                        OwnWindow(ownShape, shown, landscape, cameraLeft, ownSlot, { slot ->
                            ownSlot = slot
                            prefs.edit().putInt("own_slot", slot).apply()
                        }, { area, height ->
                            ownArea = area
                            screenHeight = height
                        }) { m -> mine(m) }
                    } else {
                        androidx.compose.runtime.SideEffect { ownArea = null }
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
                            if (remote.isNotEmpty()) {
                                Pill(stringResource(R.string.call_follow_them), on = followThem) {
                                    followThem = !followThem
                                    prefs.edit().putBoolean("follow_remote", followThem).apply()
                                }
                            }
                            if (statsAllowed) {
                                Pill(stringResource(R.string.call_stats), on = statsShown) {
                                    statsShown = !statsShown
                                    prefs.edit().putBoolean("stats", statsShown).apply()
                                }
                            }
                            // The layout a tap switches to.
                            if (remote.isNotEmpty() && !group) Pill(stringResource(if (maximized) R.string.call_layout_normal else R.string.call_layout_maximized)) { toggleLayout() }
                        }
                    }
                    if (remote.isEmpty()) {
                        Spacer(Modifier.height(48.dp))
                        Avatar(call.chat, size = 96.dp, name = title)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(title, color = LibraryColors.PhotoText, style = LibraryType.PageTitle, textAlign = TextAlign.Center)
                    Text(statusLine(call, nameOf), color = LibraryColors.PhotoTextMuted, style = LibraryType.Body, textAlign = TextAlign.Center)
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
                // Save or Stop, and the call's buttons, over a soft shade: along the bottom upright; on its side, a
                // column along the edge away from the camera (2026-11-23, hen).
                val side = landscape && call.phase != CallPhase.ENDED
                Column(
                    if (side) {
                        Modifier.align(if (cameraLeft) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight()
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    if (cameraLeft) listOf(Color.Transparent, if (videoOn) scrim else Color.Transparent) else listOf(if (videoOn) scrim else Color.Transparent, Color.Transparent),
                                ),
                            )
                            .padding(horizontal = 12.dp, vertical = 16.dp)
                    } else {
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, if (videoOn) scrim else Color.Transparent)))
                            .navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (side) Arrangement.Center else Arrangement.Top,
                ) {
                    // Save starts keeping the call; Stop (red) stops it. Only the phone that is saving sees Stop.
                    if ((canSave || call.saving) && call.phase == CallPhase.ACTIVE) {
                        Text(
                            stringResource(if (call.saving) R.string.call_save_stop else R.string.call_save),
                            color = Color.White, style = LibraryType.Small,
                            modifier = Modifier
                                .background(if (call.saving) LibraryColors.Error else Color.White.copy(alpha = 0.18f), CircleShape)
                                .clickable(role = Role.Button) { actions.setSaving(!call.saving) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    when (call.phase) {
                        CallPhase.RINGING -> ButtonLine(side) {
                            RoundButton(LibraryIcons.Close, stringResource(R.string.call_decline), LibraryColors.Error, actions.decline)
                            RoundButton(if (call.video) LibraryIcons.Video else LibraryIcons.Call, stringResource(R.string.call_answer), LibraryColors.Accent, actions.accept)
                        }
                        CallPhase.ENDED -> {}
                        else -> ButtonLine(side) {
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
}

/** The call's buttons: in a row, or a column on the edge away from the camera. */
@Composable
private fun ButtonLine(column: Boolean, content: @Composable () -> Unit) {
    if (column) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { content() }
    }
}

/** The display's rotation (Surface.ROTATION_*), followed as it turns, a half turn included. */
@Composable
private fun rememberDisplayRotation(): Int {
    val view = androidx.compose.ui.platform.LocalView.current
    var rotation by remember { androidx.compose.runtime.mutableIntStateOf(view.display?.rotation ?: 0) }
    DisposableEffect(view) {
        val manager = view.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                view.display?.rotation?.let { rotation = it }
            }
        }
        manager.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return rotation
}

/** This phone's window over their video: 160 dp on its long side, at the camera's shape. */
internal fun ownWindowDp(shape: Float): Pair<Float, Float> = if (shape >= 1f) 160f to 160f / shape else 160f * shape to 160f

/**
 * This phone's window over their video (Maximized; 2026-11-23, hen): rests in one of six places (top or bottom, left,
 * centre or right; [slot] 0 to 5), clear of the bars and cutout, of the top lines and the buttons while they show, and
 * on its side of the buttons' column. A drag moves it with the finger; let go, it slides to the nearest place, kept for
 * next time. [area] gets the part of the screen it covers when resting (shares), and the screen's height.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.OwnWindow(
    shape: Float,
    shown: Boolean,
    landscape: Boolean,
    cameraLeft: Boolean,
    slot: Int,
    setSlot: (Int) -> Unit,
    area: (com.chencohen.ptt.calls.CallGrid.Area, Float) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        val d = androidx.compose.ui.platform.LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val (winW, winH) = ownWindowDp(shape)
        val ww = with(d) { winW.dp.toPx() }
        val wh = with(d) { winH.dp.toPx() }
        val safe = androidx.compose.foundation.layout.WindowInsets.safeDrawing
        val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
        val m = with(d) { 16.dp.toPx() }
        // While the controls show: below the top lines, above the buttons; on its side, beside the buttons' column.
        val topLift by androidx.compose.animation.core.animateDpAsState(if (shown) (if (landscape) 0.dp else 120.dp) else 0.dp, label = "own top")
        val bottomLift by androidx.compose.animation.core.animateDpAsState(if (shown && !landscape) 134.dp else 0.dp, label = "own bottom")
        val aside by androidx.compose.animation.core.animateDpAsState(if (shown && landscape) 88.dp else 0.dp, label = "own aside")
        val slots = com.chencohen.ptt.calls.CallGrid.windowSlots(
            w, h, ww, wh,
            safe.getLeft(d, dir).toFloat(), safe.getTop(d).toFloat(), safe.getRight(d, dir).toFloat(), safe.getBottom(d).toFloat(), m,
            topLift = with(d) { topLift.toPx() }, bottomLift = with(d) { bottomLift.toPx() },
            asideLeft = if (landscape && !cameraLeft) with(d) { aside.toPx() } else 0f,
            asideRight = if (landscape && cameraLeft) with(d) { aside.toPx() } else 0f,
        )
        val rest = slots[slot.coerceIn(0, 5)]
        val at = remember { androidx.compose.animation.core.Animatable(androidx.compose.ui.geometry.Offset(rest.first, rest.second), androidx.compose.ui.geometry.Offset.VectorConverter) }
        var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
        LaunchedEffect(rest, dragging) {
            if (!dragging) at.animateTo(androidx.compose.ui.geometry.Offset(rest.first, rest.second), androidx.compose.animation.core.tween(250))
        }
        androidx.compose.runtime.SideEffect { area(com.chencohen.ptt.calls.CallGrid.Area(rest.first / w, rest.second / h, (rest.first + ww) / w, (rest.second + wh) / h), h) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        content(
            Modifier.absoluteOffset { androidx.compose.ui.unit.IntOffset(at.value.x.toInt(), at.value.y.toInt()) }
                .size(winW.dp, winH.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            setSlot(com.chencohen.ptt.calls.CallGrid.nearest(slots, at.value.x, at.value.y))
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { at.snapTo(at.value + amount) }
                    }
                }
                .clip(RoundedCornerShape(12.dp)),
        )
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
private fun VideoView(
    room: Room,
    track: VideoTrack,
    modifier: Modifier,
    mirror: Boolean = false,
    fill: Boolean = true,
    stats: Boolean = false,
    onShape: (Float) -> Unit = {},
    /** Follow face (on what is received): pan and zoom the view to keep the face framed, up to [maxZoom]. */
    follow: Boolean = false,
    maxZoom: Float = 1.8f,
    faceAt: Float = 0.4f,
    everyMs: Long = 500L,
    /** The sender's newest face hint (FaceHint), when it sends them: used instead of looking here. */
    hint: () -> com.chencohen.ptt.calls.FaceHint? = { null },
    /**
     * Pinch to zoom (2026-11-23, hen), kept under this name: from the whole picture fitted (1.0) up to Follow face's
     * most; Follow face keeps the face centred at the zoom pinched. Null: no pinching here.
     */
    pinchKey: String? = null,
    /** One finger up or down on it moves where the face sits (Maximized 1:1), in pixels. */
    dragFace: ((Float) -> Unit)? = null,
    /** This phone's window over it (shares of it): the face is kept clear of it. */
    avoid: com.chencohen.ptt.calls.CallGrid.Area? = null,
    /** Follow face off: the face height pans the picture instead. */
    pan: Boolean = false,
) {
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
    var boxSize by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // The whole picture: as big as covers the box (Maximized, the box clips it) or fits in it (Normal). Drawn whole,
    // so Follow face can slide over all of it.
    val bw = boxSize.width.toFloat()
    val bh = boxSize.height.toFloat()
    // Pinching: the picture laid out whole (fitted), and its zoom from 1.0 up: Maximized starts at the zoom that fills.
    val pinching = pinchKey != null && aspect > 0f && bw > 0f && bh > 0f
    val content = if (aspect > 0f && bw > 0f && bh > 0f) com.chencohen.ptt.calls.CallGrid.content(bw, bh, aspect, fill && !pinching) else null
    val coverRatio = if (pinching) { val fit = com.chencohen.ptt.calls.CallGrid.content(bw, bh, aspect, false); maxOf(bw / fit.first, bh / fit.second) } else 1f
    val zoomMost = if (fill) coverRatio * 1.5f else maxZoom
    val pinch = rememberPinch(pinchKey, pinching, if (fill) coverRatio else 1f, zoomMost, dragFace)
    val framing = followFace(track, follow, fill, boxSize, content, maxZoom, faceAt, everyMs, hint, pinch.zoom, avoid, pan)
    Box(modifier.clipToBounds().onSizeChanged { boxSize = it }.then(pinch.modifier), contentAlignment = Alignment.Center) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val shaped = if (content != null) {
            with(density) { Modifier.requiredSize(content.first.toDp(), content.second.toDp()) }
        } else {
            Modifier.fillMaxSize()
        }
        AndroidView(factory = { renderer }, modifier = shaped.then(framing))
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
 * Follow face (2026-11-23, hen; the call screen's pill, on the received video only): the same face finder as Frame my
 * face looks at the frames as they come, and the view pans and zooms (at most [maxZoom]) to keep the face near the upper
 * third, head and shoulders about 45% of the height, easing in half a second, small moves left alone, back to the plain
 * picture 1.5 s after the face is lost. Display only. Maximized already crops: the crop slides to the face and zooms at
 * most 1.5x; Normal pans and zooms inside the tile.
 */
@Composable
private fun followFace(
    track: VideoTrack,
    on: Boolean,
    fill: Boolean,
    boxSize: androidx.compose.ui.unit.IntSize,
    content: Pair<Float, Float>?,
    maxZoom: Float,
    /** Where the face goes down the box (a share of its height); how often to look (shared out in a group). */
    faceAt: Float = 0.4f,
    everyMs: Long = com.chencohen.ptt.calls.FaceFinder.EVERY_MS,
    hint: () -> com.chencohen.ptt.calls.FaceHint? = { null },
    /** The zoom pinched (null: no pinching here; Follow face picks it). */
    zoom: Float? = null,
    avoid: com.chencohen.ptt.calls.CallGrid.Area? = null,
    pan: Boolean = false,
): Modifier {
    var box by remember(track) { androidx.compose.runtime.mutableStateOf<android.graphics.RectF?>(null) }
    var seenAt by remember(track) { androidx.compose.runtime.mutableLongStateOf(0L) }
    DisposableEffect(track, on) {
        if (!on) {
            box = null
            return@DisposableEffect onDispose {}
        }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val finder = com.chencohen.ptt.calls.FaceFinder("FollowFace", everyMs) { found ->
            main.post {
                if (found != null) {
                    box = found
                    seenAt = android.os.SystemClock.elapsedRealtime()
                }
            }
        }
        // Looked for here only while the sender sends no hints (an older app).
        val sink = livekit.org.webrtc.VideoSink { frame -> if (hint() == null) finder.offer(frame) }
        track.addRenderer(sink)
        onDispose {
            track.removeRenderer(sink)
            finder.close()
        }
    }
    // The face's real size (a share of the picture's height), kept now and then for Settings' picture of the layout.
    val context = LocalContext.current
    LaunchedEffect(track, on) {
        val prefs = context.getSharedPreferences("call_view", android.content.Context.MODE_PRIVATE)
        var sum = 0f
        var n = 0
        while (on) {
            delay(1_000)
            box?.let { b ->
                sum += b.height()
                n++
                if (n >= 5) {
                    prefs.edit().putFloat("face_h", (sum / n).coerceIn(0.08f, 0.8f)).apply()
                    sum = 0f
                    n = 0
                }
            }
        }
    }
    // The sender's newest hint (twice a second); lost for 1.5 s: back to the plain picture.
    LaunchedEffect(track, on) {
        while (on) {
            hint()?.let { h ->
                box = faceBox(h.x, h.y, h.h)
                seenAt = android.os.SystemClock.elapsedRealtime()
            }
            if (box != null && android.os.SystemClock.elapsedRealtime() - seenAt > 1_500) box = null
            delay(100)
        }
    }
    return faceFraming(track, on, fill, boxSize, content, maxZoom, faceAt, box, zoom, avoid, pan)
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(call.chat, size = 56.dp, name = title) }
        }
        val own = r?.let { ownVideo(it, changes) }
        if (r != null && own != null && call.camera) {
            VideoView(r, own, Modifier.align(Alignment.BottomEnd).padding(6.dp).size(width = 40.dp, height = 54.dp), mirror = true, fill = true)
        }
    }
}

/**
 * A video call inside the chat (2026-11-24, hen: "both live becomes a video call in the chat"), under the header in the
 * push-to-video panel's place and the same size: boxes 23.6% of the screen high, 3:4, side by side and centred, theirs
 * from the start side and mine at the end; in a group two of theirs at most, then "+N".
 */
@Composable
fun ChatCallTop(call: CallUi, title: String, transport: LiveKitCallTransport?) {
    val room by (transport?.room ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    val changes by (transport?.changes ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsStateWithLifecycle()
    val r = room
    val remote = if (r != null) remoteVideo(r, changes) else emptyList()
    val own = r?.let { ownVideo(it, changes) }?.takeIf { call.camera }
    val shown = remote.take(2)
    val count = shown.size.coerceAtLeast(1) + (if (own != null) 1 else 0)
    com.chencohen.ptt.ui.contacts.LiveBoxes(List(count) { 3f / 4f }) { i, box ->
        Box(box) {
            when {
                i < shown.size && r != null -> {
                    VideoView(r, shown[i].second, Modifier.fillMaxSize(), fill = true)
                    if (i == shown.lastIndex && remote.size > shown.size) {
                        Text(
                            "+${remote.size - shown.size}", color = Color.White, style = LibraryType.Small,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                i == 0 && shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(call.chat, size = 56.dp, name = title) }
                r != null && own != null -> VideoView(r, own, Modifier.fillMaxSize(), mirror = true, fill = true)
            }
        }
    }
}

/** The call inside the chat, at the bottom: a teal bar with its time and End, then mute, camera, flip, full screen. */
@Composable
fun ChatCallBottom(call: CallUi, actions: CallActions, fullScreen: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val since = call.connectedAt.takeIf { it > 0 } ?: call.startedAt
        val length = ((now - since) / 1000).coerceAtLeast(0)
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().background(LibraryColors.Accent, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.call_in_chat, "%d:%02d".format(length / 60, length % 60)),
                color = Color.White, style = LibraryType.RowTitle, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.call_end), color = Color.White, style = LibraryType.RowTitle,
                modifier = Modifier.background(LibraryColors.Error, CircleShape).clickable(role = Role.Button, onClick = actions.hangUp).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SmallCallButton(if (call.microphone) LibraryIcons.Mic else LibraryIcons.MicOff, stringResource(if (call.microphone) R.string.call_mute else R.string.call_unmute)) { actions.setMicrophone(!call.microphone) }
            SmallCallButton(if (call.camera) LibraryIcons.Video else LibraryIcons.VideoOff, stringResource(if (call.camera) R.string.call_camera_off else R.string.call_camera_on)) { actions.setCamera(!call.camera) }
            if (call.camera) SmallCallButton(LibraryIcons.Flip, stringResource(R.string.call_flip), actions.switchCamera)
            SmallCallButton(LibraryIcons.Expand, stringResource(R.string.call_full_screen), fullScreen)
        }
    }
}

@Composable
private fun SmallCallButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).background(LibraryColors.Raised, CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = LibraryColors.OnBackground, modifier = Modifier.size(22.dp)) }
}

/**
 * A push turned into a call (2026-11-26, hen: "turn push to transmit into call ... should navigate to the call
 * screen"): the talk room's call, full screen like any call, over everything, the same room (no ringing). Their
 * camera fills the middle (whole, as big as fits), or their face while they show none; mine in a small rounded
 * window at the top end while it's on; the chat's name and the time on top; Mute, Camera and Hang up at the bottom.
 */
@Composable
internal fun RoomCallScreen(
    call: com.chencohen.ptt.messaging.RoomCall,
    title: String,
    me: String,
    end: () -> Unit,
    media: (microphone: Boolean, camera: Boolean) -> Unit,
) {
    val arriving by com.chencohen.ptt.talk.LiveTalkVideo.arriving.collectAsStateWithLifecycle()
    val theirs = arriving.values.firstOrNull { it.identity.substringBeforeLast('.') != me }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0F1413)).pointerInput(Unit) { detectTapGestures { } }) {
        if (theirs != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.chencohen.ptt.ui.talk.RoomVideoView(theirs, Modifier.fillMaxWidth().aspectRatio(1f / theirs.aspect.coerceIn(0.3f, 3f)))
            }
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (theirs == null) {
                Spacer(Modifier.height(48.dp))
                Avatar(call.chat, size = 96.dp, name = title)
                Spacer(Modifier.height(12.dp))
            }
            Text(title, color = LibraryColors.PhotoText, style = LibraryType.PageTitle, textAlign = TextAlign.Center)
            Text(clipLength((now - call.since).coerceAtLeast(0)), color = LibraryColors.PhotoTextMuted, style = LibraryType.Body, textAlign = TextAlign.Center)
        }
        if (call.camera) {
            val shape = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
            val (w, h) = ownWindowDp(shape)
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 120.dp, end = 16.dp)
                    .size(w.dp, h.dp).clip(RoundedCornerShape(12.dp)),
            ) { com.chencohen.ptt.ui.talk.PushVideoPreview(Modifier.fillMaxSize(), cover = true) }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))))
                .navigationBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundButton(
                if (call.microphone) LibraryIcons.Mic else LibraryIcons.MicOff,
                stringResource(if (call.microphone) R.string.call_mute else R.string.call_unmute),
                Color.White.copy(alpha = 0.18f),
            ) { media(!call.microphone, call.camera) }
            RoundButton(
                if (call.camera) LibraryIcons.Video else LibraryIcons.VideoOff,
                stringResource(if (call.camera) R.string.call_camera_off else R.string.call_camera_on),
                Color.White.copy(alpha = 0.18f),
            ) { media(call.microphone, !call.camera) }
            RoundButton(LibraryIcons.Call, stringResource(R.string.call_hang_up), LibraryColors.Error, end)
        }
    }
}
