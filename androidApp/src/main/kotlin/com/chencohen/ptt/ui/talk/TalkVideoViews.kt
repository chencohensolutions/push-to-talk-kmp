package com.chencohen.ptt.ui.talk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohen.ptt.R
import com.chencohen.ptt.talk.AndroidTalkVideoOut
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType

/**
 * Voice | Video above the big button (2026-11-22, hen; it was a camera among the call buttons, beside the video call):
 * Video sends the camera with the voice (push-to-video, asking for the camera the first time).
 */
@Composable
internal fun VoiceVideoSwitch(video: Boolean, set: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) set(true) }
    fun choose(on: Boolean) {
        when {
            on == video -> {}
            !on -> set(false)
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> set(true)
            else -> ask.launch(Manifest.permission.CAMERA)
        }
    }
    androidx.compose.foundation.layout.Row(
        modifier.background(LibraryColors.Raised, CircleShape).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple(false, com.chencohen.ptt.ui.style.LibraryIcons.Mic, R.string.talk_mode_voice),
            Triple(true, com.chencohen.ptt.ui.style.LibraryIcons.Video, R.string.talk_mode_video),
        ).forEach { (on, icon, words) ->
            val chosen = on == video
            androidx.compose.foundation.layout.Row(
                Modifier.background(if (chosen) LibraryColors.Inverse else Color.Transparent, CircleShape)
                    .clickable(role = Role.Tab) { choose(on) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (chosen) LibraryColors.OnInverse else LibraryColors.OnBackground
                androidx.compose.material3.Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
                androidx.compose.material3.Text(
                    stringResource(words), color = ink, style = com.chencohen.ptt.ui.style.LibraryType.Small,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** While shown with push-to-video on, the camera stays open for [chat] (a press sends at once); closed after. */
@Composable
internal fun WarmCamera(chat: String, on: Boolean, warm: (String?) -> Unit) {
    // Only while the app is in front: going to the background lets the camera go, coming back opens it again.
    androidx.lifecycle.compose.LifecycleStartEffect(chat, on) {
        warm(if (on) chat else null)
        onStopOrDispose { warm(null) }
    }
}

/**
 * This phone's camera, upright and mirrored like a mirror, in a box of its own shape centred in the space given (no
 * bars). The camera already turns a preview by its sensor's angle for the phone held upright; only the screen's own
 * turn is undone here. What is sent keeps its frames as captured, with their rotation alongside.
 */
@Composable
internal fun PushVideoPreview(modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    val capture = com.chencohen.ptt.talkVideoCapture ?: return
    val display = androidx.compose.ui.platform.LocalView.current.display?.rotation ?: 0
    val screenTurn = display * 90
    // Upright: 3:4 with the phone held upright, 4:3 on its side.
    val shape = if (screenTurn % 180 == 0) {
        com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
    } else {
        com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH.toFloat() / com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT
    }
    FittedBox(shape, modifier, alignment) { w, h ->
        // The camera draws its preview upright for the phone held upright; on its side the view is laid out upright
        // (its sides swapped) and turned back by the screen's turn, so it fills the box with no bars or stretch.
        val sideways = screenTurn % 180 != 0
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            texture.setDefaultBufferSize(com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH, com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT)
                            surface = Surface(texture).also { capture.setPreview(it) }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            // Let go only once the camera stopped drawing into it, and only if it is still the
                            // preview: on a rotation the new view's surface can come before the old one goes.
                            val gone = surface
                            surface = null
                            if (gone == null) return true
                            capture.clearPreview(gone) {
                                gone.release()
                                texture.release()
                            }
                            return false
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                    }
                }
            },
            // The camera mirrors a front camera's preview itself (like a mirror).
            modifier = Modifier.requiredSize(if (sideways) h else w, if (sideways) w else h).graphicsLayer { rotationZ = -screenTurn.toFloat() },
        )
    }
}

/** A box of [shape] (width for 1 high) as big as covers the space given, clipped to it; [content] gets its size. */
@Composable
internal fun CoverBox(shape: Float, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, content: @Composable (androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp) -> Unit) {
    BoxWithConstraints(modifier.clipToBounds().background(Color.Black), contentAlignment = alignment) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val (bw, bh) = if (w / h > shape) w to (w / shape) else (h * shape) to h
        with(density) {
            Box(Modifier.requiredSize(bw.toDp(), bh.toDp()), contentAlignment = Alignment.Center) { content(bw.toDp(), bh.toDp()) }
        }
    }
}

/** A rounded box of [shape] (width for 1 high), as big as fits, centred in the space given; [content] gets its size. */
@Composable
internal fun FittedBox(shape: Float, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, content: @Composable (androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp) -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val (bw, bh) = if (w / h > shape) (h * shape) to h else w to (w / shape)
        with(density) {
            Box(Modifier.size(bw.toDp(), bh.toDp()).clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                content(bw.toDp(), bh.toDp())
            }
        }
    }
}

/**
 * Above a Talk screen's button, centred: someone's video burst while it plays, and this phone's camera while Video is
 * on. When their video comes, this phone's slides and shrinks into the top end corner over it, and grows back when it
 * ends (2026-11-22, hen), in 280 ms; it is the same view throughout, so the camera isn't opened again.
 */
@Composable
internal fun TalkVideoBox(playingVideo: Boolean, pushVideo: Boolean, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    val small by androidx.compose.animation.core.animateFloatAsState(
        if (playingVideo && pushVideo) 1f else 0f,
        androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "own video",
    )
    BoxWithConstraints(modifier) {
        if (playingVideo) TalkVideoView(Modifier.fillMaxSize(), alignment)
        if (pushVideo) {
            androidx.compose.runtime.key("own camera") {
                // From where it sits alone (alignment, the whole box) to the top end corner at a third of the size.
                val from = alignment as? androidx.compose.ui.BiasAlignment ?: androidx.compose.ui.BiasAlignment(0f, 0f)
                val place = androidx.compose.ui.BiasAlignment(
                    from.horizontalBias + (1f - from.horizontalBias) * small,
                    from.verticalBias + (-1f - from.verticalBias) * small,
                )
                val share = 1f - (1f - SMALL_SHARE) * small
                Box(
                    Modifier.align(place).padding(all = (8 * small).dp).size(maxWidth * share, maxHeight * share),
                ) {
                    PushVideoPreview(Modifier.fillMaxSize(), if (small > 0.5f) Alignment.TopEnd else alignment)
                }
            }
        }
    }
}

/** This phone's video beside someone's: this much of the box's width and height. */
private const val SMALL_SHARE = 0.32f

/**
 * A burst's video, as its sender's camera took it, turned upright and fitted in the space given; drawn by
 * AndroidTalkVideoOut in step with the voice.
 */
@Composable
internal fun TalkVideoView(modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, cover: Boolean = false) {
    val shown by AndroidTalkVideoOut.shown.collectAsStateWithLifecycle()
    val s = shown
    val rotation = s?.rotation ?: 0
    val frameAspect = if (s != null && s.width > 0 && s.height > 0) s.width.toFloat() / s.height else 4f / 3f
    val turned = rotation % 180 != 0
    val upright = if (turned) 1f / frameAspect else frameAspect
    // Follow face (2026-11-23, hen: "in push-to-video too"): the same choice as in calls; the sender's face positions
    // (inside the clip), or a look here twice a second when it sends none (an older app).
    val context = LocalContext.current
    val prefs = androidx.compose.runtime.remember { context.getSharedPreferences("call_view", android.content.Context.MODE_PRIVATE) }
    var follow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("follow_remote", false)) }
    var texture by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<TextureView?>(null) }
    val face = talkFace(follow && s != null, s?.clip, texture, rotation)
    val key = s?.clip ?: ""
    @Composable
    fun picture(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, framing: Modifier) {
        Box(Modifier.requiredSize(w, h).then(framing), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c ->
                    TextureView(c).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            override fun onSurfaceTextureAvailable(t: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(t).also { AndroidTalkVideoOut.attach(it) }
                            }

                            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                                surface?.let { AndroidTalkVideoOut.detach(it); it.release() }
                                return true
                            }

                            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                        }
                    }.also { texture = it }
                },
                // The frames as captured, turned upright by the sender's rotation.
                modifier = Modifier.requiredSize(if (turned) h else w, if (turned) w else h).graphicsLayer { rotationZ = rotation.toFloat() },
            )
        }
    }
    @Composable
    fun followPill(m: Modifier) {
        Text(
            stringResource(R.string.call_follow_them),
            color = if (follow) com.chencohen.ptt.ui.style.LibraryColors.OnInverse else Color.White,
            style = com.chencohen.ptt.ui.style.LibraryType.Small,
            modifier = m.background(if (follow) com.chencohen.ptt.ui.style.LibraryColors.Inverse else com.chencohen.ptt.ui.style.LibraryColors.PillScrim, CircleShape)
                .clickable(role = Role.Switch) {
                    follow = !follow
                    prefs.edit().putBoolean("follow_remote", follow).apply()
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    val density = LocalDensity.current
    if (cover) {
        // The whole screen: the picture laid out whole and zoomed to fill it, from the whole picture (pinched out) to
        // half as close again.
        BoxWithConstraints(modifier.clipToBounds().background(Color.Black), contentAlignment = Alignment.Center) {
            val bw = constraints.maxWidth.toFloat()
            val bh = constraints.maxHeight.toFloat()
            val fit = com.chencohen.ptt.calls.CallGrid.content(bw, bh, upright, false)
            val coverRatio = maxOf(bw / fit.first, bh / fit.second)
            val pinch = com.chencohen.ptt.ui.calls.rememberPinch("zoom_talk_full", true, coverRatio, coverRatio * 1.5f)
            val framing = com.chencohen.ptt.ui.calls.faceFraming(
                key, follow, true, androidx.compose.ui.unit.IntSize(bw.toInt(), bh.toInt()), fit, coverRatio * 1.5f, 0.4f, face, pinch.zoom,
            )
            Box(Modifier.fillMaxSize().then(pinch.modifier), contentAlignment = Alignment.Center) {
                with(density) { picture(fit.first.toDp(), fit.second.toDp(), framing) }
            }
            followPill(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp))
        }
    } else {
        FittedBox(upright, modifier, alignment) { w, h ->
            val wp = with(density) { w.toPx() }
            val hp = with(density) { h.toPx() }
            val pinch = com.chencohen.ptt.ui.calls.rememberPinch("zoom_talk", true, 1f, 1.8f)
            val framing = com.chencohen.ptt.ui.calls.faceFraming(
                key, follow, false, androidx.compose.ui.unit.IntSize(wp.toInt(), hp.toInt()), wp to hp, 1.8f, 0.4f, face, pinch.zoom,
            )
            Box(Modifier.fillMaxSize().clipToBounds().then(pinch.modifier), contentAlignment = Alignment.Center) {
                picture(w, h, framing)
                followPill(Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }
    }
}

/**
 * Where the face is in the push-to-video burst showing ([clip]): the sender's positions in step with the voice, or,
 * from a sender that sends none, a look at [view]'s picture here twice a second. Gone 1.5 s after the last one.
 */
@Composable
private fun talkFace(on: Boolean, clip: String?, view: TextureView?, rotation: Int): android.graphics.RectF? {
    var box by androidx.compose.runtime.remember(clip) { androidx.compose.runtime.mutableStateOf<android.graphics.RectF?>(null) }
    var seenAt by androidx.compose.runtime.remember(clip) { androidx.compose.runtime.mutableLongStateOf(0L) }
    DisposableEffect(on, clip, view, rotation) {
        if (!on || clip == null) {
            box = null
            return@DisposableEffect onDispose {}
        }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val finder = com.chencohen.ptt.calls.FaceFinder("TalkFollow", 500) { found ->
            main.post {
                if (found != null) {
                    box = found
                    seenAt = android.os.SystemClock.elapsedRealtime()
                }
            }
        }
        val look = object : Runnable {
            override fun run() {
                val v = view
                if (!AndroidTalkVideoOut.hasFaces() && v != null && v.isAvailable && v.width > 0 && finder.due()) {
                    val w = 240
                    val h = (w * v.height / v.width).coerceAtLeast(2)
                    val bitmap = runCatching { v.getBitmap(w, h) }.getOrNull()
                    if (bitmap != null) finder.offerBitmap(bitmap, rotation) else finder.offerNothing()
                }
                main.postDelayed(this, 500)
            }
        }
        main.post(look)
        onDispose {
            main.removeCallbacks(look)
            finder.close()
        }
    }
    androidx.compose.runtime.LaunchedEffect(on, clip) {
        while (on) {
            AndroidTalkVideoOut.faceNow()?.let { f ->
                box = com.chencohen.ptt.ui.calls.faceBox(f.x, f.y, f.h)
                seenAt = android.os.SystemClock.elapsedRealtime()
            }
            if (box != null && android.os.SystemClock.elapsedRealtime() - seenAt > 1_500) box = null
            kotlinx.coroutines.delay(100)
        }
    }
    return box
}

/**
 * A Talk screen's middle: the video box, the big button, and the round buttons under it. Upright, the video is centred
 * above the button; on its side (2026-11-22, hen) the video sits on the side of the front camera, the button and its
 * buttons beside it.
 */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.TalkStage(
    playingVideo: Boolean,
    pushVideo: Boolean,
    circle: @Composable (androidx.compose.ui.unit.Dp) -> Unit,
    actions: @Composable () -> Unit,
    /** The chat's name (and what the floor is doing): on top upright, with the button on its side. */
    header: @Composable () -> Unit = {},
    /** Voice | Video, just above the big button. */
    switch: @Composable () -> Unit = {},
) {
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (!landscape) {
        header()
        TalkVideoBox(playingVideo, pushVideo, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp))
        switch()
        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        circle(220.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        actions()
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        return
    }
    // Turned left (the top to the left) the front camera is on the left; turned right, on the right.
    val cameraLeft = androidx.compose.ui.platform.LocalView.current.display?.rotation != Surface.ROTATION_270
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        androidx.compose.foundation.layout.Row(Modifier.weight(1f).fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun androidx.compose.foundation.layout.RowScope.video() =
                // Against the middle of the screen, beside the controls: no gap between them.
                TalkVideoBox(
                    playingVideo, pushVideo, Modifier.weight(1f).fillMaxSize().padding(vertical = 8.dp, horizontal = 4.dp),
                    alignment = if (cameraLeft) Alignment.CenterEnd else Alignment.CenterStart,
                )

            @Composable
            fun androidx.compose.foundation.layout.RowScope.controls() =
                BoxWithConstraints(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    // The round button as big as fits under the name and above its round buttons, at most 220 dp.
                    val size = minOf(220.dp, maxHeight - 76.dp - 72.dp - 48.dp, maxWidth * 0.8f).coerceAtLeast(88.dp)
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
                            header()
                            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                            switch()
                            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                            circle(size)
                            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                            actions()
                        }
                    }
                }
            when {
                // No video to show: the button and its buttons alone, in the middle.
                !playingVideo && !pushVideo -> controls()
                cameraLeft -> {
                    video()
                    controls()
                }
                else -> {
                    controls()
                    video()
                }
            }
        }
    }
}

/**
 * Whether [user] is "here" now (their Talk screen or bubble for this chat open), from [here] (until when), looked at
 * again every few seconds so it goes when it runs out.
 */
@Composable
internal fun rememberHere(here: Map<String, Long>, user: String): Boolean {
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(here, user) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(2_000)
        }
    }
    return (here[user] ?: 0L) > now
}

/** The green "here" dot by a name (2026-11-22, hen): the same on the Talk screens and in the bubble. */
@Composable
internal fun HereDot(shown: Boolean, modifier: Modifier = Modifier) {
    if (!shown) return
    val label = stringResource(R.string.talk_here)
    Box(
        modifier.padding(start = 8.dp).size(12.dp).background(Color(0xFF34C759), CircleShape)
            .semantics { contentDescription = label },
    )
}
