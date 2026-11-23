package com.chencohen.ptt.ui.talk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.LruCache
import com.chencohen.ptt.talk.TalkVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A video burst's picture for its bubble in the chat (2026-11-23, hen: "every video a person sends will be in the
 * chat"): its first key frame, decoded from the clip kept on the phone (still encrypted at rest; the picture lives in
 * memory only), turned upright.
 */
object ClipThumbs {
    private const val TAG = "ClipThumbs"
    private val cache = LruCache<String, Bitmap>(24)
    private val missing = HashSet<String>()

    fun cached(id: String): Bitmap? = cache.get(id)

    suspend fun load(context: Context, id: String): Bitmap? {
        cache.get(id)?.let { return it }
        if (id in missing) return null
        val contents = runCatching { com.chencohen.ptt.clipContents(context, id) }.getOrNull() ?: return null
        val key = contents.frames.firstOrNull { it.key } ?: return null
        val bitmap = withContext(Dispatchers.Default) { runCatching { decode(key) }.onFailure { Log.w(TAG, "no picture for $id", it) }.getOrNull() }
        if (bitmap == null) {
            synchronized(missing) { missing += id }
            return null
        }
        cache.put(id, bitmap)
        return bitmap
    }

    private fun decode(frame: TalkVideo.Frame): Bitmap? {
        val mime = when (frame.codec) {
            TalkVideo.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            TalkVideo.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> MediaFormat.MIMETYPE_VIDEO_VP8
        }
        val w = com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
        val h = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT
        val thread = HandlerThread("clip-thumb").also { it.start() }
        val reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
        val done = CountDownLatch(1)
        var out: Bitmap? = null
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (out == null) {
                    val iw = image.width
                    val ih = image.height
                    val nv21 = ByteArray(iw * ih * 3 / 2)
                    val y = image.planes[0]
                    for (row in 0 until ih) {
                        y.buffer.position(row * y.rowStride)
                        y.buffer.get(nv21, row * iw, iw)
                    }
                    val u = image.planes[1]
                    val v = image.planes[2]
                    var at = iw * ih
                    for (row in 0 until ih / 2) for (col in 0 until iw / 2) {
                        nv21[at++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
                        nv21[at++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
                    }
                    val jpeg = ByteArrayOutputStream()
                    YuvImage(nv21, ImageFormat.NV21, iw, ih, null).compressToJpeg(Rect(0, 0, iw, ih), 80, jpeg)
                    val raw = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size())
                    out = if (frame.rotation % 360 != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(frame.rotation.toFloat()) }, true) else raw
                    done.countDown()
                }
            } finally {
                image.close()
            }
        }, Handler(thread.looper))
        val dec = MediaCodec.createDecoderByType(mime)
        try {
            dec.configure(MediaFormat.createVideoFormat(mime, w, h), reader.surface, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var queued = false
            val until = System.currentTimeMillis() + 2_000
            while (out == null && System.currentTimeMillis() < until) {
                if (!queued) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        dec.getInputBuffer(i)!!.apply { clear(); put(frame.data) }
                        dec.queueInputBuffer(i, 0, frame.data.size, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        // The end, so the one picture comes out.
                        val j = dec.dequeueInputBuffer(10_000)
                        if (j >= 0) dec.queueInputBuffer(j, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) dec.releaseOutputBuffer(o, true)
                if (o >= 0 && out == null) done.await(300, TimeUnit.MILLISECONDS)
            }
        } finally {
            runCatching { dec.stop() }
            runCatching { dec.release() }
            reader.close()
            thread.quitSafely()
        }
        return out
    }
}
