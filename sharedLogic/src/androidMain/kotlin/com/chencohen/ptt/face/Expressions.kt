package com.chencohen.ptt.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt


/** Where the face's parts are (in the crop's pixels): the mouth's outline, each eye's and each brow's. */
class Landmarks internal constructor(
    internal val upperLip: List<Pair<Float, Float>>,
    internal val lowerLip: List<Pair<Float, Float>>,
    /** The lips' inner edges: where the mouth opens. */
    internal val upperInner: List<Pair<Float, Float>>,
    internal val lowerInner: List<Pair<Float, Float>>,
    internal val leftEye: List<Pair<Float, Float>>,
    internal val rightEye: List<Pair<Float, Float>>,
    internal val leftBrow: List<Pair<Float, Float>>,
    internal val rightBrow: List<Pair<Float, Float>>,
) {
    internal fun map(f: (Pair<Float, Float>) -> Pair<Float, Float>) =
        Landmarks(upperLip.map(f), lowerLip.map(f), upperInner.map(f), lowerInner.map(f), leftEye.map(f), rightEye.map(f), leftBrow.map(f), rightBrow.map(f))

    companion object {
        internal fun of(face: Face): Landmarks? {
            fun c(type: Int) = face.getContour(type)?.points?.map { it.x to it.y }.orEmpty()
            val marks = Landmarks(
                c(FaceContour.UPPER_LIP_TOP), c(FaceContour.LOWER_LIP_BOTTOM),
                c(FaceContour.UPPER_LIP_BOTTOM), c(FaceContour.LOWER_LIP_TOP),
                c(FaceContour.LEFT_EYE), c(FaceContour.RIGHT_EYE),
                c(FaceContour.LEFT_EYEBROW_TOP), c(FaceContour.RIGHT_EYEBROW_TOP),
            )
            val ok = marks.upperLip.size >= 5 && marks.lowerLip.size >= 5 && marks.upperInner.size >= 3 && marks.lowerInner.size >= 3 && marks.leftEye.size >= 8 && marks.rightEye.size >= 8 &&
                marks.leftBrow.size >= 3 && marks.rightBrow.size >= 3
            return marks.takeIf { ok }
        }
    }
}

/**
 * Bends the drawn face into an expression, on the phone and with nothing learned: the image is moved around the
 * face's parts (each a small soft displacement: the mouth's corners up for a smile, down for sad; the brows; the
 * eyelids), and an open mouth is painted for laughing and surprise. The face stays the person's own drawing.
 */
internal object Expressions {
    private class Push(val x: Float, val y: Float, val dx: Float, val dy: Float, val r: Float)

    fun make(face: Bitmap, m: Landmarks, e: Expression, ink: Boolean): Bitmap {
        val mouthL = m.upperLip.minBy { it.first }
        val mouthR = m.upperLip.maxBy { it.first }
        val mw = mouthR.first - mouthL.first
        val mouthTop = m.upperLip.minBy { it.second }
        val mouthBottom = m.lowerLip.maxBy { it.second }
        val mcx = (mouthL.first + mouthR.first) / 2
        val mcy = (mouthTop.second + mouthBottom.second) / 2
        // The eye shown on the image's right is the one that winks.
        val eyes = listOf(m.leftEye, m.rightEye).sortedBy { e -> e.sumOf { it.first.toDouble() } }
        val brows = listOf(m.leftBrow, m.rightBrow).sortedBy { b -> b.sumOf { it.first.toDouble() } }
        val faceMid = (eyes[0].avgX() + eyes[1].avgX()) / 2
        val pushes = ArrayList<Push>()
        fun corners(up: Float, out: Float, r: Float = 0.45f) {
            pushes += Push(mouthL.first, mouthL.second, -out * mw, -up * mw, r * mw)
            pushes += Push(mouthR.first, mouthR.second, out * mw, -up * mw, r * mw)
        }
        fun lids(eye: List<Pair<Float, Float>>, top: Float, bottom: Float) {
            val h = eye.maxOf { it.second } - eye.minOf { it.second }
            val w = eye.maxOf { it.first } - eye.minOf { it.first }
            pushes += Push(eye.avgX(), eye.minOf { it.second }, 0f, top * h, w * 0.45f)
            pushes += Push(eye.avgX(), eye.maxOf { it.second }, 0f, bottom * h, w * 0.45f)
        }
        fun browInner(dy: Float, toward: Float) {
            for (b in brows) {
                val w = b.maxOf { it.first } - b.minOf { it.first }
                val inner = b.minBy { kotlin.math.abs(it.first - faceMid) }
                val outer = b.maxBy { kotlin.math.abs(it.first - faceMid) }
                val sign = if (inner.first < faceMid) 1f else -1f
                pushes += Push(inner.first, inner.second, sign * toward * w, dy * w, w * 0.4f)
                pushes += Push(outer.first, outer.second, 0f, -dy * 0.5f * w, w * 0.4f)
            }
        }
        fun browsUp(dy: Float) {
            for (b in brows) {
                val w = b.maxOf { it.first } - b.minOf { it.first }
                pushes += Push(b.avgX(), b.avgY(), 0f, -dy * w, w * 0.7f)
            }
        }
        // The mouth opening: the lower lip (and the chin under it) moved down by [open] mouth widths.
        var open = 0f
        fun openMouth(a: Float) {
            open = a
            pushes += Push(mcx, mouthBottom.second, 0f, a * mw, 0.55f * mw)
            pushes += Push(mcx, mouthBottom.second + 0.6f * mw, 0f, a * 0.6f * mw, 0.6f * mw)
        }
        // A smile in the photo flattened: the lower lip's middle up, so the corners going down read as a frown.
        fun flatten(a: Float) { pushes += Push(mcx, mouthBottom.second, 0f, -a * mw, 0.35f * mw) }
        when (e) {
            Expression.NEUTRAL -> {}
            Expression.SMILE -> { corners(0.2f, 0.08f); eyes.forEach { lids(it, 0f, -0.3f) } }
            Expression.LAUGH -> { corners(0.22f, 0.1f); openMouth(0.28f); eyes.forEach { lids(it, 0.3f, -0.4f) } }
            Expression.SAD -> { flatten(0.1f); corners(-0.32f, -0.06f, 0.55f); browInner(-0.26f, 0f); eyes.forEach { lids(it, 0.15f, 0f) } }
            Expression.SURPRISED -> { browsUp(0.3f); eyes.forEach { lids(it, -0.4f, 0.2f) }; corners(0f, -0.18f); openMouth(0.32f) }
            Expression.WINK -> { corners(0.16f, 0.06f); lids(eyes[1], 0.35f, -0.35f) }
            Expression.ANGRY -> { browInner(0.26f, 0.14f); eyes.forEach { lids(it, 0.28f, 0f) }; flatten(0.1f); corners(-0.3f, 0f, 0.55f) }
        }
        val out = warp(face, pushes)
        val canvas = Canvas(out)
        val dark = if (ink) Color.BLACK else darkest(face, mcx, mcy, mw)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dark; style = Paint.Style.FILL }
        if (open > 0f) {
            // The gap between the lips, where they are now: the upper lip's inner edge, and the lower one's moved down.
            fun moved(p: Pair<Float, Float>): Pair<Float, Float> {
                var x = p.first; var y = p.second
                for (q in pushes) {
                    val d = hypot(p.first - q.x, p.second - q.y)
                    val k = exp(-(d * d) / (2 * q.r * q.r))
                    x += q.dx * k; y += q.dy * k
                }
                return x to y
            }
            val top = m.upperInner.sortedBy { it.first }.map(::moved)
            val bottom = m.lowerInner.sortedBy { it.first }.map(::moved)
            val path = Path().apply {
                moveTo(top.first().first, top.first().second)
                top.drop(1).forEach { lineTo(it.first, it.second) }
                bottom.reversed().forEach { lineTo(it.first, it.second + 0.04f * mw) }
                close()
            }
            canvas.drawPath(path, fill)
            if (e == Expression.LAUGH && !ink) {
                // The top teeth.
                val teeth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 246, 240) }
                canvas.save()
                canvas.clipPath(path)
                val y0 = top.minOf { it.second }
                canvas.drawRect(top.first().first, y0, top.last().first, top.maxOf { it.second } + 0.09f * mw, teeth)
                canvas.restore()
            }
        }
        when (e) {
            Expression.WINK -> {
                // The closed eye: a soft arc where it was.
                val eye = eyes[1]
                val w = eye.maxOf { it.first } - eye.minOf { it.first }
                val h = eye.maxOf { it.second } - eye.minOf { it.second }
                // The open eye covered with the skin just under it, softly, then the closed lid drawn.
                if (!ink) {
                    val skin = average(face, eye.avgX(), eye.maxOf { it.second } + h * 1.2f, w * 0.25f)
                    val cover = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = skin
                        maskFilter = android.graphics.BlurMaskFilter(w * 0.08f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.drawOval(RectF(eye.avgX() - w * 0.62f, eye.avgY() - h * 1.0f, eye.avgX() + w * 0.62f, eye.avgY() + h * 0.9f), cover)
                } else {
                    val cover = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                    canvas.drawOval(RectF(eye.avgX() - w * 0.6f, eye.avgY() - h * 0.9f, eye.avgX() + w * 0.6f, eye.avgY() + h * 0.8f), cover)
                }
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (ink) Color.BLACK else darkest(face, eye.avgX(), eye.avgY(), w)
                    style = Paint.Style.STROKE; strokeWidth = (w * 0.1f).coerceAtLeast(2f); strokeCap = Paint.Cap.ROUND
                }
                val cy = eye.avgY()
                canvas.drawPath(Path().apply { moveTo(eye.avgX() - w * 0.55f, cy); quadTo(eye.avgX(), cy + w * 0.28f, eye.avgX() + w * 0.55f, cy) }, line)
            }
            else -> {}
        }
        return out
    }

    /** Each pixel taken from where the pushes moved it from (a soft gaussian around each part), alpha too. */
    private fun warp(src: Bitmap, pushes: List<Push>): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sx = x.toFloat()
            var sy = y.toFloat()
            for (p in pushes) {
                val d = hypot(x - (p.x + p.dx), y - (p.y + p.dy))
                if (d > p.r * 3) continue
                val k = exp(-(d * d) / (2 * p.r * p.r))
                sx -= p.dx * k
                sy -= p.dy * k
            }
            out[y * w + x] = sample(pixels, w, h, sx, sy)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
    }

    private fun sample(p: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, w - 1); val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = (x - x0).coerceIn(0f, 1f); val fy = (y - y0).coerceIn(0f, 1f)
        fun ch(c: Int, s: Int) = (c ushr s) and 0xff
        var r = 0
        for (s in intArrayOf(24, 16, 8, 0)) {
            val v = ch(p[y0 * w + x0], s) * (1 - fx) * (1 - fy) + ch(p[y0 * w + x1], s) * fx * (1 - fy) +
                ch(p[y1 * w + x0], s) * (1 - fx) * fy + ch(p[y1 * w + x1], s) * fx * fy
            r = r or (v.roundToInt().coerceIn(0, 255) shl s)
        }
        return r
    }

    /** The average colour around ([cx], [cy]) within [r]: the skin under an eye. */
    private fun average(b: Bitmap, cx: Float, cy: Float, r: Float): Int {
        var red = 0L; var green = 0L; var blue = 0L; var n = 0
        val ri = r.toInt().coerceAtLeast(1)
        for (dy in -ri..ri) for (dx in -ri..ri) {
            val x = (cx + dx).toInt(); val y = (cy + dy).toInt()
            if (x !in 0 until b.width || y !in 0 until b.height) continue
            val c = b.getPixel(x, y)
            if (Color.alpha(c) < 230) continue
            red += Color.red(c); green += Color.green(c); blue += Color.blue(c); n++
        }
        return if (n == 0) Color.rgb(230, 190, 170) else Color.rgb((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
    }

    /** The darkest colour around ([cx], [cy]) within half of [size], darkened: the inside of a mouth, a lash line. */
    private fun darkest(b: Bitmap, cx: Float, cy: Float, size: Float): Int {
        var best = Color.rgb(60, 20, 20)
        var lum = Float.MAX_VALUE
        val r = (size / 2).toInt().coerceAtLeast(2)
        for (dy in -r..r step 2) for (dx in -r..r step 2) {
            val x = (cx + dx).toInt(); val y = (cy + dy).toInt()
            if (x !in 0 until b.width || y !in 0 until b.height) continue
            val c = b.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            val l = 0.3f * Color.red(c) + 0.59f * Color.green(c) + 0.11f * Color.blue(c)
            if (l < lum) { lum = l; best = c }
        }
        return Color.rgb((Color.red(best) * 0.55f).toInt(), (Color.green(best) * 0.4f).toInt(), (Color.blue(best) * 0.4f).toInt())
    }

    private fun List<Pair<Float, Float>>.avgX() = sumOf { it.first.toDouble() }.toFloat() / size
    private fun List<Pair<Float, Float>>.avgY() = sumOf { it.second.toDouble() }.toFloat() / size
}
