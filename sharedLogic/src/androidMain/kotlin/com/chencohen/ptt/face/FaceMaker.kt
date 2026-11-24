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
enum class FaceStyle(val file: String, val bytes: Long, val sha256: String, val ink: Boolean = false) {
    SOFT("face_soft.tflite", 3_250_464, "79c982b8c256d5232b74da2e7271b1226e729a62fca4b1bcbff96da790442576"),
    PAINTED("face_painted.tflite", 3_250_464, "8b422088cd2848b78467739ceab1237fd84a17552c5dafb1086807a940ba69d3"),
    HAND_DRAWN("face_handdrawn.tflite", 3_250_464, "71d1124c4cba2e8ae50c3a2e600052165426e0e89ea7eb1c746a678a48506fa5"),
    INK("face_ink.tflite", 8_602_592, "161125875aab07c0d0517c6331be136a00ce2d39a1ba61827c7a12ad29ccc6d8", ink = true),
}

/** Where a style's model is: [done] of [total] bytes here, ready, or failed (no network: try again). */
data class ModelProgress(val done: Long = 0, val total: Long = 0, val ready: Boolean = false, val failed: Boolean = false)

/**
 * Makes a person's picture on the phone only (2026-11-24, hen): their face found in a photo (ML Kit), head cut out on a clear background (MediaPipe selfie segmenter, Apache-2.0, bundled), drawn in a [FaceStyle],
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
                // The model is only used when it is exactly the one this app was built with.
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                part.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                val sum = digest.digest().joinToString("") { "%02x".format(it) }
                if (sum != style.sha256) error("${style.file}: SHA-256 $sum, expected ${style.sha256}")
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

    /** A photo made ready: the head, square, [SIZE] px, and the person's outline (0–1 each pixel). */
    class Prepared internal constructor(val crop: Bitmap, internal val mask: FloatArray, internal val landmarks: Landmarks?)

    /**
     * Finds the face in [photo] (upright) and cuts out the head only (2026-11-24, hen: "the face illustration should be
     * only face"): face and hair, centred, nothing below the jaw line; null when there's no face.
     */
    suspend fun prepare(context: Context, photo: Bitmap): Prepared? = withContext(Dispatchers.Default) {
        initialize(context)
        val found = findFace(photo) ?: return@withContext null
        val face = found.box
        // The head: about 1.9 face sizes, its middle a little above the face's (the hair).
        val side = (max(face.width(), face.height()) * 1.9f).roundToInt()
        val cx = face.centerX().toFloat()
        val cy = face.centerY() - face.height() * 0.12f
        val left = cx - side / 2f
        val top = cy - side / 2f
        val scale = SIZE.toFloat() / side
        val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(crop).apply {
            drawColor(Color.WHITE)
            val m = Matrix().apply { postTranslate(-left, -top); postScale(scale, scale) }
            drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        // The jaw line in the crop: below it (the neck, the shoulders) goes.
        val jaw = (found.jaw.takeIf { it.size >= 5 } ?: listOf(
            android.graphics.PointF(face.left.toFloat(), face.centerY().toFloat()),
            android.graphics.PointF(face.centerX().toFloat(), face.bottom.toFloat()),
            android.graphics.PointF(face.right.toFloat(), face.centerY().toFloat()),
        )).map { android.graphics.PointF((it.x - left) * scale, (it.y - top) * scale) }
        val landmarks = found.landmarks?.map { p: Pair<Float, Float> -> (p.first - left) * scale to (p.second - top) * scale }
        Prepared(crop, headOnly(segment(context, crop), jaw), landmarks)
    }

    /** Whether [photo]'s face shows [expression], as far as the detector tells (smiling, eyes open); true when it can't tell. */
    suspend fun looks(photo: Bitmap, expression: Expression): Boolean {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).build(),
        )
        return try {
            suspendCancellableCoroutine { c ->
                detector.process(InputImage.fromBitmap(photo, 0))
                    .addOnSuccessListener { faces ->
                        val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        val smile = f?.smilingProbability
                        val left = f?.leftEyeOpenProbability
                        val right = f?.rightEyeOpenProbability
                        c.resume(
                            when {
                                f == null -> true
                                expression == Expression.SMILE || expression == Expression.LAUGH -> smile == null || smile > 0.5f
                                expression == Expression.WINK -> left == null || right == null || minOf(left, right) < 0.4f
                                expression == Expression.SAD || expression == Expression.ANGRY -> smile == null || smile < 0.5f
                                else -> true
                            },
                        )
                    }
                    .addOnFailureListener { c.resume(true) }
            }
        } finally {
            detector.close()
        }
    }

    private class Found(val box: Rect, val jaw: List<android.graphics.PointF>, val landmarks: Landmarks?)

    private suspend fun findFace(photo: Bitmap): Found? {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL).build(),
        )
        return try {
            suspendCancellableCoroutine { c ->
                detector.process(InputImage.fromBitmap(photo, 0))
                    .addOnSuccessListener { faces ->
                        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        // The face's outline, its lower half: the jaw from ear to ear.
                        val outline = face?.getContour(com.google.mlkit.vision.face.FaceContour.FACE)?.points.orEmpty()
                        val middle = face?.boundingBox?.centerY()?.toFloat() ?: 0f
                        c.resume(face?.let { Found(it.boundingBox, outline.filter { p -> p.y >= middle }.sortedBy { p -> p.x }, Landmarks.of(it)) })
                    }
                    .addOnFailureListener { c.resume(null) }
            }
        } finally {
            detector.close()
        }
    }

    /**
     * [mask] kept only above the jaw: under the jaw line (between its ends) it fades out over a few pixels, so the chin
     * stays and the neck goes; beside the face the hair is kept down to the chin's height.
     */
    private fun headOnly(mask: FloatArray, jaw: List<android.graphics.PointF>): FloatArray {
        val chin = jaw.maxOf { it.y }
        val fade = SIZE * 0.025f
        for (x in 0 until SIZE) {
            val line = when {
                x <= jaw.first().x || x >= jaw.last().x -> chin
                else -> {
                    val i = jaw.indexOfFirst { it.x >= x }.coerceAtLeast(1)
                    val a = jaw[i - 1]; val b = jaw[i]
                    val t = if (b.x == a.x) 0f else (x - a.x) / (b.x - a.x)
                    a.y + (b.y - a.y) * t
                }
            }
            for (y in 0 until SIZE) {
                val keep = ((line + fade - y) / fade).coerceIn(0f, 1f)
                mask[y * SIZE + x] *= keep
            }
        }
        // Only what joins the head: pieces left beside it (a collar, a hand, someone behind) go.
        val start = (jaw.sumOf { it.x.toDouble() } / jaw.size).toInt().coerceIn(0, SIZE - 1) +
            ((chin - SIZE * 0.15f).toInt().coerceIn(0, SIZE - 1)) * SIZE
        val joined = BooleanArray(SIZE * SIZE)
        val stack = IntArray(SIZE * SIZE)
        var top = 0
        if (mask[start] > 0.3f) { joined[start] = true; stack[top++] = start }
        while (top > 0) {
            val i = stack[--top]
            val x = i % SIZE; val y = i / SIZE
            for ((dx, dy) in NEIGHBOURS) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until SIZE || ny !in 0 until SIZE) continue
                val n = ny * SIZE + nx
                if (!joined[n] && mask[n] > 0.3f) { joined[n] = true; stack[top++] = n }
            }
        }
        if (top == 0 && joined.any { it }) {
            // Keep the soft edge: what is next to the head stays as it was.
            for (i in mask.indices) if (!joined[i] && !nearJoined(joined, i)) mask[i] = 0f
        }
        return mask
    }

    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private fun nearJoined(joined: BooleanArray, i: Int): Boolean {
        val x = i % SIZE; val y = i / SIZE
        for (dy in -3..3) for (dx in -3..3) {
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until SIZE && ny in 0 until SIZE && joined[ny * SIZE + nx]) return true
        }
        return false
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
        // The sides fade out a little, in case long hair reaches the square's edge.
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            soft[y * SIZE + x] *= (minOf(x, SIZE - 1 - x) / (SIZE * 0.04f)).coerceIn(0f, 1f)
        }
        return soft
    }

    /** [prepared] drawn in [style]: the 256 px picture (alpha outside the person). */
    suspend fun draw(context: Context, prepared: Prepared, style: FaceStyle): Bitmap? =
        drawFull(context, prepared, style)?.let { Bitmap.createScaledBitmap(it, OUT, OUT, true) }

    /**
     * [prepared] drawn in [style] with each [Expression] (2026-11-24, hen: the faces as emoticons): the style once, then
     * the face bent into each expression on the phone (Expressions); 256 px each, alpha outside the head.
     */
    suspend fun drawSet(context: Context, prepared: Prepared, style: FaceStyle): Map<Expression, Bitmap>? = withContext(Dispatchers.Default) {
        val full = drawFull(context, prepared, style) ?: return@withContext null
        val marks = prepared.landmarks
        Expression.entries.associateWith { e ->
            val bent = if (e == Expression.NEUTRAL || marks == null) full else Expressions.make(full, marks, e, style.ink)
            Bitmap.createScaledBitmap(bent, OUT, OUT, true)
        }
    }

    private suspend fun drawFull(context: Context, prepared: Prepared, style: FaceStyle): Bitmap? = withContext(Dispatchers.Default) {
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
        Bitmap.createBitmap(out, SIZE, SIZE, Bitmap.Config.ARGB_8888)
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
