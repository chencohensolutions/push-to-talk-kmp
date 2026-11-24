package com.chencohen.ptt.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tflite.java.TfLite
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.InterpreterApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A style the face can be drawn in (2026-11-24, hen: "the user will choose the styles"). Each is one small LiteRT model,
 * downloaded the first time it is shown (docs/design.md, "Pictures"):
 * - DCT-Net (menyifang/DCT-Net, Apache-2.0): Soft (3d), Painted (sd-design), Hand-drawn (handdrawn); BGR 0–255 in,
 *   BGR 0–255 out, made by tools/face/convert.py from the authors' frozen graphs.
 * - Informative Drawings (carolineec/informative-drawings, MIT): Ink (contour); RGB 0–1 in, lines 0–1 out.
 */
enum class FaceStyle(val file: String, val bytes: Long, val ink: Boolean = false) {
    SOFT("face_soft.tflite", 3_250_464),
    PAINTED("face_painted.tflite", 3_250_464),
    HAND_DRAWN("face_handdrawn.tflite", 3_250_464),
    INK("face_ink.tflite", 8_602_592, ink = true),
}

/** Where a style's model is: [done] of [total] bytes here, ready, or failed (no network: try again). */
data class ModelProgress(val done: Long = 0, val total: Long = 0, val ready: Boolean = false, val failed: Boolean = false)

/**
 * Makes a person's picture on the phone only (2026-11-24, hen): their face found in a photo (ML Kit), head and
 * shoulders cut out on a clear background (MediaPipe selfie segmenter, Apache-2.0, bundled), drawn in a [FaceStyle],
 * 256 px WebP with alpha, about 15 KB. The photo is never kept or sent.
 */
object FaceMaker {
    /** The styles' models are published here (a release of the app's repository). */
    const val MODELS_URL = "https://github.com/chencohensolutions/push-to-talk-kmp/releases/download/face-models-1/"
    private const val SEGMENTER = "selfie_segmenter.tflite"
    private const val SIZE = 384
    private const val OUT = 256

    private val progress = FaceStyle.entries.associateWith { MutableStateFlow(ModelProgress()) }
    private val downloads = Mutex()
    private val interpreters = HashMap<String, InterpreterApi>()
    @Volatile private var ready = false

    fun progressOf(style: FaceStyle): StateFlow<ModelProgress> = progress.getValue(style)

    private fun dir(context: Context) = File(context.filesDir, "face-models").apply { mkdirs() }

    /** Marks the models already here (or in this build's assets) as ready. */
    fun refresh(context: Context) {
        for (style in FaceStyle.entries) {
            if (File(dir(context), style.file).length() == style.bytes || inAssets(context, style.file)) {
                progress.getValue(style).value = ModelProgress(style.bytes, style.bytes, ready = true)
            }
        }
    }

    private fun inAssets(context: Context, file: String) = runCatching { context.assets.open("face-models/$file").close(); true }.getOrDefault(false)

    /** Gets [style]'s model if it isn't here yet (one download at a time, in the order asked); true when ready. */
    suspend fun download(context: Context, style: FaceStyle): Boolean = withContext(Dispatchers.IO) {
        refresh(context)
        val state = progress.getValue(style)
        if (state.value.ready) return@withContext true
        downloads.withLock {
            if (state.value.ready) return@withLock true
            val target = File(dir(context), style.file)
            val part = File(dir(context), style.file + ".part")
            try {
                state.value = ModelProgress(0, style.bytes)
                val connection = URL(MODELS_URL + style.file).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            state.value = ModelProgress(done, style.bytes)
                        }
                    }
                }
                if (part.length() != style.bytes) error("got ${part.length()} bytes of ${style.bytes}")
                part.renameTo(target)
                state.value = ModelProgress(style.bytes, style.bytes, ready = true)
                true
            } catch (e: Exception) {
                Log.w("FaceMaker", "${style.file} didn't download", e)
                part.delete()
                state.value = ModelProgress(failed = true)
                false
            }
        }
    }

    private suspend fun initialize(context: Context) {
        if (ready) return
        suspendCancellableCoroutine { c ->
            TfLite.initialize(context.applicationContext).addOnCompleteListener { c.resume(Unit) }
        }
        ready = true
    }

    private fun interpreter(context: Context, file: String, bundled: Boolean): InterpreterApi = synchronized(interpreters) {
        interpreters.getOrPut(file) {
            val bytes = when {
                bundled -> context.assets.open(file).use { it.readBytes() }
                inAssets(context, file) -> context.assets.open("face-models/$file").use { it.readBytes() }
                else -> File(dir(context), file).readBytes()
            }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
            val options = InterpreterApi.Options().setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY).setNumThreads(4)
            InterpreterApi.create(model, options)
        }
    }

    /** A photo made ready: the head and shoulders, square, [SIZE] px, and the person's outline (0–1 each pixel). */
    class Prepared internal constructor(val crop: Bitmap, internal val mask: FloatArray)

    /** Finds the face in [photo] (upright) and cuts out the head and shoulders; null when there's no face. */
    suspend fun prepare(context: Context, photo: Bitmap): Prepared? = withContext(Dispatchers.Default) {
        initialize(context)
        val face = findFace(photo) ?: return@withContext null
        // Head and shoulders: about 2.4 face widths, the face a little above the middle.
        val side = (max(face.width(), face.height()) * 2.4f).roundToInt()
        val cx = face.centerX()
        val cy = face.centerY() + face.height() * 0.25f
        val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(crop).apply {
            drawColor(Color.WHITE)
            val scale = SIZE.toFloat() / side
            val m = Matrix().apply { postTranslate(-(cx - side / 2f), -(cy - side / 2f)); postScale(scale, scale) }
            drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        Prepared(crop, segment(context, crop))
    }

    private suspend fun findFace(photo: Bitmap): Rect? {
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
        return try {
            suspendCancellableCoroutine { c ->
                detector.process(InputImage.fromBitmap(photo, 0))
                    .addOnSuccessListener { faces -> c.resume(faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox) }
                    .addOnFailureListener { c.resume(null) }
            }
        } finally {
            detector.close()
        }
    }

    /** The person's outline in [crop]: 0 background, 1 person, for each of its [SIZE]² pixels, the edge a little soft. */
    private fun segment(context: Context, crop: Bitmap): FloatArray {
        val n = 256
        val small = Bitmap.createScaledBitmap(crop, n, n, true)
        val pixels = IntArray(n * n).also { small.getPixels(it, 0, n, 0, 0, n, n) }
        val input = ByteBuffer.allocateDirect(n * n * 3 * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            input.putFloat(Color.red(p) / 255f); input.putFloat(Color.green(p) / 255f); input.putFloat(Color.blue(p) / 255f)
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        interpreter(context, SEGMENTER, bundled = true).run(input, output)
        output.rewind()
        val raw = FloatArray(n * n) { output.float }
        // Up to SIZE, firmed up in the middle, then softened a little at the edge.
        val mask = FloatArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val v = bilinear(raw, n, x * (n - 1f) / (SIZE - 1), y * (n - 1f) / (SIZE - 1))
            mask[y * SIZE + x] = ((v - 0.35f) / 0.3f).coerceIn(0f, 1f)
        }
        val soft = blur(mask, SIZE, 2)
        // The bottom fades out (the shoulders end softly instead of at the photo's edge), and so do the sides.
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val bottom = ((SIZE - 1 - y) / (SIZE * 0.18f)).coerceIn(0f, 1f)
            val side = (minOf(x, SIZE - 1 - x) / (SIZE * 0.04f)).coerceIn(0f, 1f)
            soft[y * SIZE + x] *= bottom * side
        }
        return soft
    }

    /** [prepared] drawn in [style]: the 256 px picture (alpha outside the person). */
    suspend fun draw(context: Context, prepared: Prepared, style: FaceStyle): Bitmap? = withContext(Dispatchers.Default) {
        if (!download(context, style)) return@withContext null
        initialize(context)
        val pixels = IntArray(SIZE * SIZE).also { prepared.crop.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
        val interpreter = interpreter(context, style.file, bundled = false)
        val out = IntArray(SIZE * SIZE)
        if (style.ink) {
            val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
            for (p in pixels) { input.putFloat(Color.red(p) / 255f); input.putFloat(Color.green(p) / 255f); input.putFloat(Color.blue(p) / 255f) }
            input.rewind()
            val output = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
            synchronized(interpreter) { interpreter.run(input, output) }
            output.rewind()
            for (i in out.indices) {
                val v = (output.float.coerceIn(0f, 1f) * 255).roundToInt()
                out[i] = withAlpha(Color.rgb(v, v, v), prepared.mask[i])
            }
        } else {
            val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
            for (p in pixels) { input.putFloat(Color.blue(p).toFloat()); input.putFloat(Color.green(p).toFloat()); input.putFloat(Color.red(p).toFloat()) }
            input.rewind()
            val output = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
            synchronized(interpreter) { interpreter.run(input, output) }
            output.rewind()
            for (i in out.indices) {
                val b = output.get().toInt() and 0xff
                val g = output.get().toInt() and 0xff
                val r = output.get().toInt() and 0xff
                out[i] = withAlpha(Color.rgb(r, g, b), prepared.mask[i])
            }
        }
        val full = Bitmap.createBitmap(out, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Bitmap.createScaledBitmap(full, OUT, OUT, true)
    }

    /** The picture as sent: WebP with alpha, about 15 KB. */
    fun encode(picture: Bitmap): ByteArray = ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }.toByteArray()

    private fun withAlpha(color: Int, a: Float): Int = (color and 0x00ffffff) or ((a * 255).roundToInt().coerceIn(0, 255) shl 24)

    private fun bilinear(v: FloatArray, n: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, n - 1); val y0 = y.toInt().coerceIn(0, n - 1)
        val x1 = (x0 + 1).coerceAtMost(n - 1); val y1 = (y0 + 1).coerceAtMost(n - 1)
        val fx = x - x0; val fy = y - y0
        val top = v[y0 * n + x0] * (1 - fx) + v[y0 * n + x1] * fx
        val bottom = v[y1 * n + x0] * (1 - fx) + v[y1 * n + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    /** A small box blur, twice (close to a gaussian of about [r] px). */
    private fun blur(src: FloatArray, n: Int, r: Int): FloatArray {
        var a = src
        repeat(2) {
            val h = FloatArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                var s = 0f; var c = 0
                for (k in -r..r) { val xx = x + k; if (xx in 0 until n) { s += a[y * n + xx]; c++ } }
                h[y * n + x] = s / c
            }
            val v = FloatArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                var s = 0f; var c = 0
                for (k in -r..r) { val yy = y + k; if (yy in 0 until n) { s += h[yy * n + x]; c++ } }
                v[y * n + x] = s / c
            }
            a = v
        }
        return a
    }
}
