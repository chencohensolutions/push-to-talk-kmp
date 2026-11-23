package com.chencohen.ptt.calls

import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import livekit.org.webrtc.VideoFrame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The biggest face in a stream of video frames, looked for about four times a second on a 240-wide copy with ML Kit's
 * on-phone detector (bundled, offline). [found] gets it as a share of the UPRIGHT picture (the frame turned by its
 * rotation), or null when there is none; on a thread of its own. Used by Frame my face (FaceFramer, the camera before
 * sending) and Follow face (the call screen, on what is received).
 */
class FaceFinder(private val tag: String, private val found: (RectF?) -> Unit) {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
                .build(),
        )
    }
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "face-finder").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    @Volatile private var lastLookAt = 0L
    @Volatile private var closed = false

    /** Looks at [frame] when it is time to (on the thread that delivered it, which can read its texture). */
    fun offer(frame: VideoFrame) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLookAt < EVERY_MS || !busy.compareAndSet(false, true)) return
        lastLookAt = now
        val buffer = frame.buffer
        val sw = WIDTH
        val sh = (WIDTH * buffer.height / buffer.width.coerceAtLeast(1)) and 1.inv()
        val nv21 = try {
            val small = buffer.cropAndScale(0, 0, buffer.width, buffer.height, sw, sh)
            val i420 = small.toI420()
            small.release()
            val bytes = i420?.let { toNv21(it, sw, sh) }
            i420?.release()
            bytes
        } catch (e: Throwable) {
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
                val uw = (if (rotation % 180 == 0) sw else sh).toFloat()
                val uh = (if (rotation % 180 == 0) sh else sw).toFloat()
                val box = face?.boundingBox?.let { RectF(it.left / uw, it.top / uh, it.right / uw, it.bottom / uh) }
                Log.i(tag, "${faces.size} faces in ${sw}x$sh r$rotation${box?.let { " at %.2f,%.2f %.2fx%.2f".format(it.centerX(), it.centerY(), it.width(), it.height()) } ?: ""}")
                found(box)
            } catch (e: Throwable) {
                // Also a missing detector library (a release build on an x86_64 emulator): nothing found, nothing else.
                Log.w(tag, "face detection", e)
            } finally {
                busy.set(false)
            }
        }
    }

    fun close() {
        closed = true
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
        const val EVERY_MS = 250L
        const val WIDTH = 240
    }
}
