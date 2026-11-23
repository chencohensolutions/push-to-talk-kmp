package com.chencohen.ptt.ui.talk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.util.LruCache
import com.chencohen.ptt.talk.TalkVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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

    /**
     * The key frame decoded into the codec's own buffers (flexible YUV, read through [MediaCodec.getOutputImage]):
     * no surface, so nothing a hardware buffer could make unreadable (2026-11-23: an ImageReader surface's planes
     * aborted the app on the S25).
     */
    private fun decode(frame: TalkVideo.Frame): Bitmap? {
        val mime = when (frame.codec) {
            TalkVideo.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            TalkVideo.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> MediaFormat.MIMETYPE_VIDEO_VP8
        }
        val w = com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH
        val h = com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT
        val dec = MediaCodec.createDecoderByType(mime)
        try {
            val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            dec.configure(format, null, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var queued = false
            val until = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < until) {
                if (!queued) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        dec.getInputBuffer(i)!!.apply { clear(); put(frame.data) }
                        dec.queueInputBuffer(i, 0, frame.data.size, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        val j = dec.dequeueInputBuffer(10_000)
                        if (j >= 0) dec.queueInputBuffer(j, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o < 0) continue
                try {
                    if (info.size <= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                        continue
                    }
                    val image = dec.getOutputImage(o) ?: return null
                    image.use { img ->
                        if (img.format != ImageFormat.YUV_420_888 || img.planes.size < 3) return null
                        val crop = img.cropRect
                        val iw = crop.width() and 1.inv()
                        val ih = crop.height() and 1.inv()
                        val nv21 = ByteArray(iw * ih * 3 / 2)
                        val y = img.planes[0]
                        for (row in 0 until ih) for (col in 0 until iw) {
                            nv21[row * iw + col] = y.buffer.get((crop.top + row) * y.rowStride + (crop.left + col) * y.pixelStride)
                        }
                        val u = img.planes[1]
                        val v = img.planes[2]
                        var at = iw * ih
                        for (row in 0 until ih / 2) for (col in 0 until iw / 2) {
                            val r = crop.top / 2 + row
                            val c = crop.left / 2 + col
                            nv21[at++] = v.buffer.get(r * v.rowStride + c * v.pixelStride)
                            nv21[at++] = u.buffer.get(r * u.rowStride + c * u.pixelStride)
                        }
                        val jpeg = ByteArrayOutputStream()
                        YuvImage(nv21, ImageFormat.NV21, iw, ih, null).compressToJpeg(Rect(0, 0, iw, ih), 80, jpeg)
                        val raw = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size()) ?: return null
                        return if (frame.rotation % 360 != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(frame.rotation.toFloat()) }, true) else raw
                    }
                } finally {
                    runCatching { dec.releaseOutputBuffer(o, false) }
                }
            }
            return null
        } finally {
            runCatching { dec.stop() }
            runCatching { dec.release() }
        }
    }

}
