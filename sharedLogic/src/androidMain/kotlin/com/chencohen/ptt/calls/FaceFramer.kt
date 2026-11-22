package com.chencohen.ptt.calls

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Follow my face (2026-11-22, hen): on the sending phone, between the camera and the encoder, the picture is cropped
 * around the biggest face, head and shoulders, and scaled back to the camera's size, so every phone in the call (and
 * this one's preview) sees it framed. ML Kit's on-phone face detector looks at a small copy (240 wide) about four times
 * a second; the crop eases toward it, ignores small moves, zooms at most [MAX_ZOOM], and goes back to the whole picture
 * when no face is seen for [LOST_MS]. Off, frames pass through untouched. Nothing leaves the phone but the picture.
 */
class FaceFramer : VideoProcessor {
    @Volatile var enabled = false

    private var sink: VideoSink? = null
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
                .build(),
        )
    }
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "face-framer").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private var lastLookAt = 0L

    // Where the crop goes (from the detector) and where it is (eased each frame): centre and size, as fractions of the
    // camera's picture in its own (unrotated) coordinates.
    @Volatile private var targetX = 0.5f
    @Volatile private var targetY = 0.5f
    @Volatile private var targetSize = 1f
    @Volatile private var seenAt = 0L
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
        val now = SystemClock.elapsedRealtime()
        if (enabled && now - lastLookAt >= LOOK_EVERY_MS && busy.compareAndSet(false, true)) {
            lastLookAt = now
            look(frame)
        }
        if (!enabled || now - seenAt > LOST_MS) {
            targetX = 0.5f
            targetY = 0.5f
            targetSize = 1f
        }
        // Eased toward the target: about half a second to settle; the zoom back out is slower.
        val ease = if (targetSize > size) 0.04f else 0.08f
        x += (targetX - x) * 0.08f
        y += (targetY - y) * 0.08f
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

    /** A small copy of [frame] to the detector (on this thread, which can read the camera's texture), then off it. */
    private fun look(frame: VideoFrame) {
        val buffer = frame.buffer
        val sw = LOOK_WIDTH
        val sh = (LOOK_WIDTH * buffer.height / buffer.width) and 1.inv()
        val nv21 = try {
            val small = buffer.cropAndScale(0, 0, buffer.width, buffer.height, sw, sh)
            val i420 = small.toI420()
            small.release()
            val bytes = i420?.let { toNv21(it, sw, sh) }
            i420?.release()
            bytes
        } catch (e: Exception) {
            null
        }
        if (nv21 == null) {
            busy.set(false)
            return
        }
        val rotation = frame.rotation
        work.execute {
            try {
                val faces = com.google.android.gms.tasks.Tasks.await(detector.process(InputImage.fromByteArray(nv21, sw, sh, rotation, InputImage.IMAGE_FORMAT_NV21)))
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null) aim(face.boundingBox, sw, sh, rotation)
            } catch (e: Throwable) {
                // Also a missing detector library (a release build on an x86_64 emulator): no framing, the call goes on.
                Log.w(TAG, "face detection", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /** The crop for a face [box] in the upright picture of a [w]×[h] camera image turned by [rotation]. */
    private fun aim(box: android.graphics.Rect, w: Int, h: Int, rotation: Int) {
        val uw = if (rotation % 180 == 0) w else h
        val uh = if (rotation % 180 == 0) h else w
        // The face's centre, a little low (head and shoulders: the face in the upper middle), and its height.
        val u = box.exactCenterX() / uw
        val v = (box.exactCenterY() + box.height() * 0.35f) / uh
        val faceShare = box.height().toFloat() / uh
        val (bx, by) = when (rotation) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        val want = (faceShare * HEADROOM).coerceIn(1f / MAX_ZOOM, 1f)
        // A dead zone: small moves and size changes are left alone.
        if (kotlin.math.abs(bx - targetX) > 0.04f || kotlin.math.abs(by - targetY) > 0.04f || kotlin.math.abs(want - targetSize) > 0.08f) {
            targetX = bx
            targetY = by
            targetSize = want
        }
        seenAt = SystemClock.elapsedRealtime()
    }

    fun release() {
        runCatching { detector.close() }
        work.shutdown()
    }

    private fun toNv21(i420: VideoFrame.I420Buffer, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3 / 2)
        val y = i420.dataY
        for (row in 0 until h) {
            y.position(row * i420.strideY)
            y.get(out, row * w, w)
        }
        val u = i420.dataU
        val v = i420.dataV
        var at = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[at++] = v.get(row * i420.strideV + col)
                out[at++] = u.get(row * i420.strideU + col)
            }
        }
        return out
    }

    private companion object {
        const val TAG = "FaceFramer"
        const val LOOK_EVERY_MS = 250L
        const val LOOK_WIDTH = 240
        const val LOST_MS = 1_500L
        const val MAX_ZOOM = 2f

        /** The crop's height for a face this tall: head and shoulders. */
        const val HEADROOM = 3f
    }
}
