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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohen.ptt.R
import com.chencohen.ptt.talk.AndroidTalkVideoOut
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType

/**
 * Push-to-video's switch on a Talk screen (2026-11-22, hen; off by default): "Video" sends the camera with the voice.
 * Turning it on asks for the camera first.
 */
@Composable
internal fun PushVideoSwitch(on: Boolean, set: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) set(true) }
    Text(
        stringResource(if (on) R.string.talk_video_on else R.string.talk_video_off),
        color = if (on) Color.White else LibraryColors.OnBackground, style = LibraryType.Small,
        modifier = modifier.background(if (on) LibraryColors.Inverse else LibraryColors.Raised, CircleShape)
            .clickable(role = Role.Switch) {
                if (on) {
                    set(false)
                } else if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    set(true)
                } else {
                    ask.launch(Manifest.permission.CAMERA)
                }
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** While shown with push-to-video on, the camera stays open for [chat] (a press sends at once); closed after. */
@Composable
internal fun WarmCamera(chat: String, on: Boolean, warm: (String?) -> Unit) {
    DisposableEffect(chat, on) {
        warm(if (on) chat else null)
        onDispose { warm(null) }
    }
}

/** This phone's camera as it sends it, small, mirrored like a mirror. */
@Composable
internal fun PushVideoPreview(modifier: Modifier = Modifier) {
    val capture = com.chencohen.ptt.talkVideoCapture ?: return
    val rotation = capture.previewRotation()
    Box(modifier.size(96.dp, 128.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        val turned = rotation % 180 != 0
        // The camera's 4:3 frames, turned upright, covering the 3:4 box.
        val (w, h) = if (turned) 128.dp to 96.dp else 96.dp to 128.dp
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
                            capture.setPreview(null)
                            surface?.release()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.requiredSize(w, h).graphicsLayer { rotationZ = rotation.toFloat(); scaleX = -1f },
        )
    }
}

/**
 * A burst's video, as its sender's camera took it, turned upright and fitted in the space given; drawn by
 * AndroidTalkVideoOut in step with the voice.
 */
@Composable
internal fun TalkVideoView(modifier: Modifier = Modifier) {
    val shown by AndroidTalkVideoOut.shown.collectAsStateWithLifecycle()
    val s = shown
    BoxWithConstraints(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val rotation = s?.rotation ?: 0
        val frameAspect = if (s != null && s.width > 0 && s.height > 0) s.width.toFloat() / s.height else 4f / 3f
        val turned = rotation % 180 != 0
        val shownAspect = if (turned) 1f / frameAspect else frameAspect
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val (bw, bh) = if (w / h > shownAspect) (h * shownAspect) to h else w to (w / shownAspect)
        val (vw, vh) = if (turned) bh to bw else bw to bh
        with(density) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(texture).also { AndroidTalkVideoOut.attach(it) }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                surface?.let { AndroidTalkVideoOut.detach(it); it.release() }
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.requiredSize(vw.toDp(), vh.toDp()).graphicsLayer { rotationZ = rotation.toFloat() },
            )
        }
    }
}
