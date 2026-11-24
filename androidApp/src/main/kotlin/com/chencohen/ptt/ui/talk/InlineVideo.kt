package com.chencohen.ptt.ui.talk

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.chencohen.ptt.talk.TalkVideo

/**
 * A video burst playing in its bubble, muted and looping (2026-11-24, hen: the most visible one in the chat plays as
 * you scroll). Its frames come from the clip kept on the phone; one decoder on its own thread draws them into a
 * TextureView at their own times, upright, then starts again.
 */
@Composable
internal fun InlineVideo(id: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var frames by remember(id) { mutableStateOf(InlineVideoFrames.cached(id)) }
    // Its frames may not be on the phone yet (the burst still coming, or its upload not done): tried again until they are.
    LaunchedEffect(id) {
        var tries = 0
        while (frames.isNullOrEmpty() && tries++ < 60) {
            frames = InlineVideoFrames.load(context, id)
            if (frames.isNullOrEmpty()) kotlinx.coroutines.delay(1_500)
        }
    }
    val f = frames ?: return
    if (f.isEmpty()) return
    val loop = remember(id) { arrayOfNulls<VideoLoop>(1) }
    DisposableEffect(id) { onDispose { loop[0]?.stop(); loop[0] = null } }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        upright(this@apply, f.first().rotation)
                        loop[0]?.stop()
                        loop[0] = VideoLoop(f, Surface(st)).also { it.start() }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = upright(this@apply, f.first().rotation)

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        loop[0]?.stop()
                        loop[0] = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier,
    )
}

/** The picture turned by its rotation and filling the view (the view has the upright picture's shape). */
private fun upright(view: TextureView, rotation: Int) {
    val w = view.width.toFloat()
    val h = view.height.toFloat()
    if (w <= 0f || h <= 0f) return
    val m = Matrix()
    val r = ((rotation % 360) + 360) % 360
    if (r != 0) {
        m.postRotate(r.toFloat(), w / 2, h / 2)
        if (r == 90 || r == 270) m.postScale(w / h, h / w, w / 2, h / 2)
    }
    view.setTransform(m)
}

/** The bursts' frames, a few kept in memory. */
internal object InlineVideoFrames {
    private val cache = LruCache<String, List<TalkVideo.Frame>>(6)

    fun cached(id: String): List<TalkVideo.Frame>? = cache.get(id)

    suspend fun load(context: Context, id: String): List<TalkVideo.Frame> {
        cache.get(id)?.let { return it }
        val contents = runCatching { com.chencohen.ptt.clipContents(context, id) }.getOrNull() ?: return emptyList()
        // From the first key frame (a decoder starts there).
        val first = contents.frames.indexOfFirst { it.key }
        val frames = if (first < 0) emptyList() else contents.frames.drop(first)
        if (frames.isNotEmpty()) cache.put(id, frames)
        return frames
    }
}

/** Decodes [frames] into [surface] at their times, again and again, until [stop]. */
private class VideoLoop(private val frames: List<TalkVideo.Frame>, private val surface: Surface) {
    @Volatile private var running = true
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ run() }, "inline-video").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val first = frames.first()
        val mime = when (first.codec) {
            TalkVideo.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            TalkVideo.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> MediaFormat.MIMETYPE_VIDEO_VP8
        }
        val dec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(MediaFormat.createVideoFormat(mime, com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH, com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT), surface, null, 0)
                start()
            }
        } catch (e: Exception) {
            Log.w("InlineVideo", "no decoder", e)
            return
        }
        val info = MediaCodec.BufferInfo()
        val base = first.ptsMs
        try {
            while (running) {
                val startNs = System.nanoTime()
                var next = 0
                var ended = false
                while (running && !ended) {
                    if (next < frames.size) {
                        val i = dec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val f = frames[next++]
                            dec.getInputBuffer(i)!!.apply { clear(); put(f.data) }
                            dec.queueInputBuffer(i, 0, f.data.size, (f.ptsMs - base) * 1000, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        }
                    } else if (next == frames.size) {
                        val i = dec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            next++
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, 5_000)
                    if (o >= 0) {
                        // Shown at its own time.
                        val due = startNs + info.presentationTimeUs * 1000
                        val wait = (due - System.nanoTime()) / 1_000_000
                        if (wait > 0) Thread.sleep(wait.coerceAtMost(500))
                        dec.releaseOutputBuffer(o, info.size > 0 && running)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                    }
                }
                if (running) dec.flush()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w("InlineVideo", "loop", e)
        } finally {
            runCatching { dec.stop() }
            runCatching { dec.release() }
            runCatching { surface.release() }
        }
    }
}
