package com.chencohen.ptt.calls

import android.graphics.RectF
import android.os.SystemClock
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink

/**
 * Frame my face (2026-11-22, hen): on the sending phone, between the camera and the encoder, the picture is cropped
 * around the biggest face, head and shoulders, and scaled back to the camera's size, so every phone in the call (and
 * this one's preview) sees it framed. [FaceFinder] looks about four times a second; the crop eases toward it, ignores
 * small moves, zooms at most [MAX_ZOOM], and goes back to the whole picture when no face is seen for [LOST_MS]. Off,
 * frames pass through untouched. Nothing leaves the phone but the picture.
 *
 * 2026-11-23 (hen: "didn't really do anything"): the crop was 3x the face's height, and a selfie's face is already a
 * third of the picture, so it asked for no zoom at all; now the face is [FACE_SHARE] of the height.
 */
class FaceFramer : VideoProcessor {
    @Volatile var enabled = false

    private var sink: VideoSink? = null
    private val finder = FaceFinder(TAG) { box -> box?.let(::aim) }

    // Where the crop goes (from the finder) and where it is (eased each frame): centre and size, as shares of the
    // camera's picture in its own (unrotated) coordinates.
    @Volatile private var targetX = 0.5f
    @Volatile private var targetY = 0.5f
    @Volatile private var targetSize = 1f
    @Volatile private var seenAt = 0L
    @Volatile private var rotation = 0
    private var x = 0.5f
    private var y = 0.5f
    private var size = 1f

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) {}

    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        if (!enabled && size >= 0.999f) {
            out.onFrame(frame)
            return
        }
        rotation = frame.rotation
        if (enabled) finder.offer(frame)
        val now = SystemClock.elapsedRealtime()
        if (!enabled || now - seenAt > LOST_MS) {
            targetX = 0.5f
            targetY = 0.5f
            targetSize = 1f
        }
        // Eased toward the target: about half a second to settle at 30 fps; the zoom back out is slower.
        val ease = if (targetSize > size) 0.06f else 0.14f
        x += (targetX - x) * 0.14f
        y += (targetY - y) * 0.14f
        size += (targetSize - size) * ease
        val buffer = frame.buffer
        val w = buffer.width
        val h = buffer.height
        val cw = (w * size).toInt().coerceIn(2, w) and 1.inv()
        val ch = (h * size).toInt().coerceIn(2, h) and 1.inv()
        val cx = ((w * x).toInt() - cw / 2).coerceIn(0, w - cw) and 1.inv()
        val cy = ((h * y).toInt() - ch / 2).coerceIn(0, h - ch) and 1.inv()
        val cropped = buffer.cropAndScale(cx, cy, cw, ch, w, h)
        val framed = VideoFrame(cropped, frame.rotation, frame.timestampNs)
        out.onFrame(framed)
        framed.release()
    }

    /** The crop for a face at [box] (shares of the upright picture). */
    private fun aim(box: RectF) {
        // The face's centre, a little low (head and shoulders: the face in the upper middle), and its height.
        val u = box.centerX()
        val v = box.centerY() + box.height() * 0.35f
        val (bx, by) = when (rotation) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        val want = (box.height() / FACE_SHARE).coerceIn(1f / MAX_ZOOM, 1f)
        // A dead zone: small moves and size changes are left alone.
        if (kotlin.math.abs(bx - targetX) > 0.04f || kotlin.math.abs(by - targetY) > 0.04f || kotlin.math.abs(want - targetSize) > 0.08f) {
            targetX = bx
            targetY = by
            targetSize = want
        }
        seenAt = SystemClock.elapsedRealtime()
    }

    fun release() = finder.close()

    private companion object {
        const val TAG = "FaceFramer"
        const val LOST_MS = 1_500L
        const val MAX_ZOOM = 2f

        /** The face's height as a share of the framed picture's: head and shoulders fill it. */
        const val FACE_SHARE = 0.45f
    }
}
