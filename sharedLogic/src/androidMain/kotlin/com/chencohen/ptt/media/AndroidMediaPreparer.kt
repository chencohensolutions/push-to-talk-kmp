package com.chencohen.ptt.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.JxlEffort
import com.chencohen.ptt.messaging.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Reads what the user shares (2026-11-28, hen): its name, size and type, its SHA-256, when and where it was taken
 * (EXIF, or a video's own metadata), and for a picture or a video one JPEG XL preview per width asked for, upright, at
 * quality 95 (hen: "max", as Journal's previews). The file itself isn't copied: its URI is kept.
 */
class AndroidMediaPreparer(context: Context) : MediaPreparer {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver

    override suspend fun prepare(source: String, widths: (originalWidth: Int) -> List<Int>): PreparedItem? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val (name, size) = nameAndSize(uri)
        val sha = sha256(uri) ?: return@withContext null
        val kind = when {
            mime.startsWith("image/") -> MediaKind.IMAGE
            mime.startsWith("video/") -> MediaKind.VIDEO
            else -> MediaKind.FILE
        }
        val started = System.nanoTime()
        val item = when (kind) {
            MediaKind.IMAGE -> image(uri, mime, name, size, sha, source, widths)
            MediaKind.VIDEO -> video(uri, mime, name, size, sha, source, widths)
            else -> null
        } ?: PreparedItem(kind = MediaKind.FILE, mime = mime, name = name, size = size, sha256 = sha, source = source)
        Log.i(TAG, "Prepared $mime ${item.width}x${item.height}, ${size / 1024} KB, previews " +
            item.previews.joinToString { "${it.width}px ${it.bytes.size / 1024} KB" } + " in ${(System.nanoTime() - started) / 1_000_000} ms")
        item
    }

    override suspend fun open(source: String): OriginalReader? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        val input = runCatching { resolver.openInputStream(uri) }.getOrNull() ?: return@withContext null
        val size = nameAndSize(uri).second.takeIf { it > 0 } ?: -1L
        object : OriginalReader {
            private val digest = MessageDigest.getInstance("SHA-256")
            private val buffer = ByteArray(MediaCrypto.CHUNK)
            override val size: Long = size

            override suspend fun read(max: Int): ByteArray? = withContext(Dispatchers.IO) {
                val n = input.read(buffer, 0, minOf(max, buffer.size))
                if (n < 0) null else buffer.copyOf(n).also { digest.update(it) }
            }

            override fun sha256(): ByteArray = digest.digest()

            override fun close() = runCatching { input.close() }.let { }
        }
    }

    /**
     * New previews of the picture at [source] from the part of the original [crop] keeps (2026-11-28, hen: "make the
     * cropped preview from the original image"): decoded upright again, just sharp enough for the widest preview.
     */
    override suspend fun crop(source: String, crop: com.chencohen.ptt.messaging.CropContent, widths: (croppedWidth: Int) -> List<Int>): PreparedCrop? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        var scale = 1.0
        val full = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val most = widths(info.size.width).maxOrNull() ?: info.size.width
                scale = fit(info.size.width, most)
                if (scale < 1.0) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
        }.getOrNull() ?: return@withContext null
        val top = (full.height.toLong() * crop.top / com.chencohen.ptt.messaging.CropContent.WHOLE).toInt()
        val kept = (full.height.toLong() * crop.kept / com.chencohen.ptt.messaging.CropContent.WHOLE).toInt().coerceIn(1, full.height - top)
        val part = if (top == 0 && kept == full.height) full else Bitmap.createBitmap(full, 0, top, full.width, kept)
        val started = System.nanoTime()
        val previews = previews(part, widths((full.width / scale).roundToInt()))
        if (part !== full) part.recycle()
        full.recycle()
        Log.i(TAG, "Cropped ${crop.top}/${crop.bottom}: previews " + previews.joinToString { "${it.width}px ${it.bytes.size / 1024} KB" } + " in ${(System.nanoTime() - started) / 1_000_000} ms")
        PreparedCrop(previews)
    }

    override suspend fun sizeOf(source: String): Long = withContext(Dispatchers.IO) {
        runCatching { nameAndSize(Uri.parse(source)).second }.getOrDefault(-1L)
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment.orEmpty()
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }?.let { name = it }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        if (size < 0) size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: 0
        return name to size
    }

    private fun sha256(uri: Uri): ByteArray? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        } ?: return null
        digest.digest()
    }.getOrNull()

    /** The original's bytes with its location (MediaStore hides it without setRequireOriginal and ACCESS_MEDIA_LOCATION). */
    private fun original(uri: Uri): Uri =
        if (uri.authority == MediaStore.AUTHORITY) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri

    private fun image(uri: Uri, mime: String, name: String, size: Long, sha: ByteArray, source: String, widths: (Int) -> List<Int>): PreparedItem? {
        val exif = runCatching { openExif(original(uri)) ?: openExif(uri) }.getOrNull()
        // Upright: ImageDecoder turns it by its EXIF orientation.
        // Decoded just wide enough for the widest preview, at an exact size: a sample size may be rounded by the decoder
        // (a HEIC asked for 1/2 came at 1/3, 2026-11-28, hen: "why preview image width is 1000 shouldn't it be 1080?").
        var scale = 1.0
        val full = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // The size here is already upright (ImageDecoder turns it by its EXIF orientation, and says so).
                val most = widths(info.size.width).maxOrNull() ?: info.size.width
                scale = fit(info.size.width, most)
                if (scale < 1.0) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
        }.getOrNull() ?: return null
        val width = (full.width / scale).roundToInt()
        val height = (full.height / scale).roundToInt()
        val previews = previews(full, widths(width))
        full.recycle()
        val taken = exif?.let(::takenAt) ?: storeTakenAt(uri)?.let { at -> at to java.util.TimeZone.getDefault().getOffset(at) / 60_000 }
        val place = exif?.let { e -> FloatArray(2).takeIf { @Suppress("DEPRECATION") e.getLatLong(it) }?.let { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) } }
        android.util.Log.i("TalkMedia", "picked ${uri.authority}: exif ${exif != null}, taken ${taken != null}, place ${place != null}")
        return PreparedItem(
            kind = MediaKind.IMAGE, mime = mime, name = name, size = size, width = width, height = height,
            takenAt = taken?.first ?: 0, takenOffsetMin = taken?.second ?: 0,
            latE7 = place?.let { (it[0] * 1e7).roundToInt() } ?: 0, lonE7 = place?.let { (it[1] * 1e7).roundToInt() } ?: 0,
            hasPlace = place != null && !(place[0] == 0.0 && place[1] == 0.0),
            sha256 = sha, previews = previews, source = source,
        )
    }

    // By file descriptor first: the framework reads HEIF (what newer cameras save) only from a seekable file, not a stream.
    private fun openExif(uri: Uri): ExifInterface? =
        runCatching { resolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) } }.getOrNull()
            ?: resolver.openInputStream(uri)?.use { ExifInterface(it) }

    /** When the picture was taken, as the gallery has it (a picture whose EXIF has no date, or one ExifInterface can't read). */
    private fun storeTakenAt(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()

    /** When it was taken (ms, UTC) and its place's offset in minutes, from the EXIF's original date and offset. */
    private fun takenAt(exif: ExifInterface): Pair<Long, Int>? {
        val text = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        val local = runCatching { LocalDateTime.parse(text.trim(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")) }.getOrNull() ?: return null
        val offset = (exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME))
            ?.let { runCatching { ZoneOffset.of(it.trim()) }.getOrNull() }
        val zone = offset ?: java.time.ZoneId.systemDefault().rules.getOffset(local)
        return local.toInstant(zone).toEpochMilli() to zone.totalSeconds / 60
    }

    private suspend fun video(uri: Uri, mime: String, name: String, size: Long, sha: ByteArray, source: String, widths: (Int) -> List<Int>): PreparedItem? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(app, original(uri))
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (width, height) = if (rotation == 90 || rotation == 270) h to w else w to h
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val place = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::iso6709)
            val taken = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { d ->
                runCatching { java.time.ZonedDateTime.parse(d, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSX")).toInstant().toEpochMilli() }.getOrNull()
            }
            // Its first picture stands for it in the chat until its clip plays, and where there is none.
            val wanted = widths(width).maxOrNull() ?: width
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, wanted, PreviewSpec.heightAt(width, height, wanted).coerceAtLeast(1))
            val previews = frame?.let { previews(it, widths(width)).also { _ -> it.recycle() } }.orEmpty()
            PreparedItem(
                kind = MediaKind.VIDEO, mime = mime, name = name, size = size, width = width, height = height, durationMs = duration,
                takenAt = taken ?: 0, latE7 = place?.let { (it.first * 1e7).roundToInt() } ?: 0,
                lonE7 = place?.let { (it.second * 1e7).roundToInt() } ?: 0, hasPlace = place != null,
                sha256 = sha, previews = previews, source = source, clip = clip(uri, width, height, duration),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the video", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * The video's clip (2026-11-28, hen): the whole video in HEVC, its short side 1080 (never larger than it is), at
     * 3 Mbit/s, its own frame rate, HDR kept where it has it, its sound as it is; an MP4. Null when it is too long to
     * fit [MAX_CLIP_BYTES] at that rate, or can't be made here.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun clip(uri: Uri, width: Int, height: Int, durationMs: Long): PreparedPreview? {
        if (durationMs <= 0 || durationMs * (CLIP_BITRATE + CLIP_AUDIO) / 8 / 1000 > MAX_CLIP_BYTES) return null
        val out = java.io.File(app.cacheDir, "clip-${System.nanoTime()}.mp4")
        val started = System.nanoTime()
        try {
            val made = withContext(Dispatchers.Main) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { done ->
                    val encoders = androidx.media3.transformer.DefaultEncoderFactory.Builder(app)
                        .setRequestedVideoEncoderSettings(androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(CLIP_BITRATE).build())
                        .build()
                    val transformer = androidx.media3.transformer.Transformer.Builder(app)
                        .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H265)
                        .setEncoderFactory(encoders)
                        .addListener(object : androidx.media3.transformer.Transformer.Listener {
                            override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) {
                                if (done.isActive) done.resumeWith(Result.success(true))
                            }

                            override fun onError(
                                composition: androidx.media3.transformer.Composition,
                                exportResult: androidx.media3.transformer.ExportResult,
                                exportException: androidx.media3.transformer.ExportException,
                            ) {
                                Log.w(TAG, "Couldn't make the video's clip", exportException)
                                if (done.isActive) done.resumeWith(Result.success(false))
                            }
                        })
                        .build()
                    val effects = if (minOf(width, height) > CLIP_SHORT_SIDE) {
                        androidx.media3.transformer.Effects(emptyList(), listOf(androidx.media3.effect.Presentation.createForShortSide(CLIP_SHORT_SIDE)))
                    } else androidx.media3.transformer.Effects.EMPTY
                    val edited = androidx.media3.transformer.EditedMediaItem.Builder(androidx.media3.common.MediaItem.fromUri(original(uri))).setEffects(effects).build()
                    transformer.start(edited, out.absolutePath)
                    done.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() } }
                }
            }
            if (!made || !out.exists() || out.length() == 0L || out.length() > MAX_CLIP_BYTES) return null
            val (w, h) = uprightSize(out) ?: (width to height)
            val bytes = out.readBytes()
            Log.i(TAG, "Clip ${w}x$h, ${bytes.size / 1024} KB for ${durationMs / 1000} s in ${(System.nanoTime() - started) / 1_000_000} ms")
            return PreparedPreview(w, h, bytes, "video/mp4")
        } finally {
            out.delete()
        }
    }

    /** A video file's size as it is shown (turned by its rotation). */
    private fun uprightSize(file: java.io.File): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) h to w else w to h
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** "+32.0853+034.7818/" → degrees. */
    private fun iso6709(text: String): Pair<Double, Double>? {
        val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(text) ?: return null
        return (m.groupValues[1].toDoubleOrNull() ?: return null) to (m.groupValues[2].toDoubleOrNull() ?: return null)
    }

    /** The scale (at most 1) that makes a picture [width] pixels wide (upright) [most] wide. */
    private fun fit(width: Int, most: Int): Double = minOf(1.0, most.toDouble() / width.coerceAtLeast(1))

    /** [picture] (upright) as one JPEG XL per width, none wider than it. */
    private fun previews(picture: Bitmap, wanted: List<Int>): List<PreparedPreview> = wanted.mapNotNull { w ->
        val scaled = if (w >= picture.width) picture else Bitmap.createScaledBitmap(picture, w, PreviewSpec.heightAt(picture.width, picture.height, w), true)
        val bytes = runCatching {
            JxlCoder.encode(scaled, JxlChannelsConfiguration.RGB, JxlCompressionOption.LOSSY, JxlEffort.SQUIRREL, quality = QUALITY)
        }.onFailure { Log.w(TAG, "JPEG XL encoding failed", it) }.getOrNull()
        val preview = bytes?.let { PreparedPreview(scaled.width, scaled.height, it) }
        if (scaled !== picture) scaled.recycle()
        preview
    }

    private companion object {
        const val TAG = "TalkMedia"

        /** EXIF orientations that swap width and height. */
        val TURNED = setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)

        /** Journal's "max" preview quality (2026-11-28, hen: "so use max"). */
        const val QUALITY = 95
        /** A video's clip (2026-11-28, hen): 1080p HEVC at 3 Mbit/s, flat. */
        const val CLIP_SHORT_SIDE = 1080
        const val CLIP_BITRATE = 3_000_000
        /** About what its sound adds, to tell whether a clip will fit before making it. */
        const val CLIP_AUDIO = 256_000
        /** Under what a phone takes as one preview (MediaSharing.MAX_PREVIEW_BYTES, 64 MB): about two and a half minutes. */
        const val MAX_CLIP_BYTES = 60L shl 20
    }
}
