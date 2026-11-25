package com.chencohen.ptt.talk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/**
 * Push-to-video through the talk room (2026-11-24, hen: "do it"; docs/design.md, "Push-to-video through LiveKit"):
 * what the screens need of the room's video. A burst's video arrives here while it is said; the clip path's
 * pictures are kept for phones that sleep, the "Keep videos in chat" copy, and when the room can't be reached, and a
 * screen shows only one of the two: the room's while its frames keep coming.
 */
object LiveTalkVideo {
    /** A talker's video arriving through the room now: [aspect] as shown (width for 1 high), [firstFrameAt] its first. */
    data class Live(val identity: String, val track: VideoTrack, val firstFrameAt: Long, val aspect: Float)

    private val _arriving = MutableStateFlow<Map<String, Live>>(emptyMap())

    /** By "user.device": whose video comes through the room now (a frame in the last second). */
    val arriving: StateFlow<Map<String, Live>> = _arriving.asStateFlow()

    @Volatile internal var room: Room? = null

    /**
     * This phone's own camera as the talk room's track reads it (while it does: warm for a press, or live): the own box
     * shows it, since the camera then feeds only the clip's encoder and the room (no separate preview).
     */
    val local = MutableStateFlow<VideoTrack?>(null)

    /** Sets a renderer up for the room's video (its EGL context). False when there is no room. */
    fun initRenderer(renderer: io.livekit.android.renderer.TextureViewRenderer): Boolean {
        val r = room ?: return false
        r.initVideoRenderer(renderer)
        return true
    }

    private val watchers = HashMap<String, Watcher>()

    /** [identity]'s video track came (subscribed): watched for frames. */
    internal fun subscribed(identity: String, track: VideoTrack) {
        synchronized(watchers) {
            watchers.remove(identity)?.let { it.track.removeRenderer(it) }
            val w = Watcher(identity, track)
            watchers[identity] = w
            track.addRenderer(w)
        }
    }

    /** [identity]'s video went (unsubscribed, unpublished, left), or every one ([identity] null: the room went). */
    internal fun gone(identity: String?) {
        synchronized(watchers) {
            val those = if (identity == null) watchers.keys.toList() else listOf(identity)
            those.forEach { k -> watchers.remove(k)?.let { it.track.removeRenderer(it); it.stop() } }
        }
        _arriving.update { if (identity == null) emptyMap() else it - identity }
    }

    /** Counts frames: the first makes the video "arriving", a second without frames ends it (the clips show again). */
    private class Watcher(val identity: String, val track: VideoTrack) : VideoSink {
        @Volatile private var last = 0L
        @Volatile private var shape = ""
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private val stale = object : Runnable {
            override fun run() {
                if (SystemClock.elapsedRealtime() - last > STALE_MS) {
                    _arriving.update { it - identity }
                } else {
                    main.postDelayed(this, 250)
                }
            }
        }

        override fun onFrame(frame: VideoFrame) {
            val now = SystemClock.elapsedRealtime()
            val wasStale = now - last > STALE_MS
            last = now
            val w = frame.rotatedWidth
            val h = frame.rotatedHeight
            if (h <= 0) return
            val s = "${w}x$h"
            if (wasStale || s != shape) {
                shape = s
                val first = System.currentTimeMillis()
                _arriving.update { m ->
                    val old = m[identity]
                    m + (identity to Live(identity, track, if (wasStale || old == null) first else old.firstFrameAt, w.toFloat() / h))
                }
                if (wasStale) {
                    Log.i(TAG, "$identity: video through the room (${w}x$h)")
                    main.removeCallbacks(stale)
                    main.postDelayed(stale, 250)
                }
            }
        }

        fun stop() = main.removeCallbacks(stale)
    }

    private const val STALE_MS = 1_000L
    private const val TAG = "LiveTalkVideo"
}

/**
 * The push-to-video camera's frames into the talk room: a surface LiveKit reads ([SurfaceTextureHelper]) given to
 * AndroidTalkVideoCapture as one more output of its session, so the one camera feeds the clip's encoder, the preview
 * and the room. The frames go as captured, with the rotation to show them upright (as the clip's).
 */
internal class TalkCameraCapturer(private val camera: () -> AndroidTalkVideoCapture?) : VideoCapturer {
    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var surface: Surface? = null

    override fun initialize(helper: SurfaceTextureHelper?, context: Context?, observer: CapturerObserver?) {
        this.helper = helper
        this.observer = observer
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val h = helper ?: return
        val cam = camera() ?: return
        h.setTextureSize(AndroidTalkVideoCapture.WIDTH, AndroidTalkVideoCapture.HEIGHT)
        val s = Surface(h.surfaceTexture)
        surface = s
        // The camera's output to a SurfaceTexture already comes turned for the phone held upright (its transform
        // matrix, which the renderers and the encoder apply), so the frame's own rotation is only the screen's turn.
        // (Said as the sensor's angle too, the picture showed a quarter turn off; 2026-11-25, hen: "the video is
        // rotated".)
        val sensor = cam.sensorOrientation()
        h.setFrameRotation(((cam.previewRotation() - sensor) % 360 + 360) % 360)
        h.startListening { frame -> observer?.onFrameCaptured(frame) }
        observer?.onCapturerStarted(true)
        cam.setLiveTarget(s)
    }

    override fun stopCapture() {
        val s = surface ?: return
        surface = null
        val done = java.util.concurrent.CountDownLatch(1)
        val cam = camera()
        if (cam != null) cam.setLiveTarget(null) { done.countDown() } else done.countDown()
        done.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        helper?.stopListening()
        s.release()
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}

    override fun dispose() {
        stopCapture()
    }

    override fun isScreencast(): Boolean = false
}
