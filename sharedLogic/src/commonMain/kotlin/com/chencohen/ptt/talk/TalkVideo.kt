package com.chencohen.ptt.talk

/**
 * Push-to-video (2026-11-22, hen; off unless chosen on a Talk screen): the camera goes in the same burst as the voice,
 * through the same encrypted clip path, so it plays live, from a woken phone, and again from the history.
 *
 * A clip with video ([com.chencohen.ptt.messaging.Clip.video], "clip start" ClipContent.video) tags every packet with
 * its first byte: [AUDIO] then the Opus packet as before, or [VIDEO] then a piece of an H.264 frame: its number (2
 * bytes), the piece's index and the count (1 byte each), its time from the press in milliseconds (4 bytes), flags (1
 * byte: 1 a key frame, carrying its parameter sets; the codec in bits 4-5: [VP8], [H265] or [H264]), the rotation in quarter turns clockwise to show it upright (1 byte),
 * then the bytes. A frame is cut into pieces of at most [PIECE] bytes, so a segment stays under the server's 16 KB.
 *
 * Follow face in push-to-video (2026-11-23, hen): [FACE] then where the sender's face is, twice a second: its time
 * from the press in milliseconds (4 bytes), then its centre and height as shares of the upright picture (2 bytes each,
 * 0 to 65535). It travels inside the encrypted clip, so a replay has it too. Apps before 1.4.14 skip any first byte
 * they don't know (their Splitter takes only [AUDIO] and [VIDEO]), so they play such a clip as before.
 */
object TalkVideo {
    const val AUDIO: Byte = 0
    const val VIDEO: Byte = 1
    const val FACE: Byte = 2
    private const val FACE_SIZE = 1 + 4 + 2 + 2 + 2
    const val PIECE = 3000
    private const val HEADER = 1 + 2 + 1 + 1 + 4 + 1 + 1

    const val VP8 = 0
    const val H265 = 1
    const val H264 = 2

    /** A whole encoded frame, in [codec] ([VP8], [H265] or [H264]). */
    class Frame(val ptsMs: Long, val key: Boolean, val rotation: Int, val data: ByteArray, val codec: Int = VP8)

    fun audio(opus: ByteArray): ByteArray = byteArrayOf(AUDIO) + opus

    /** Where the sender's face is at [ptsMs]: its centre ([x], [y]) and [h]eight, shares of the upright picture. */
    data class Face(val ptsMs: Long, val x: Float, val y: Float, val h: Float)

    fun face(face: Face): ByteArray {
        fun share(v: Float) = (v.coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
        val out = ByteArray(FACE_SIZE)
        out[0] = FACE
        val pts = face.ptsMs.toInt()
        out[1] = (pts ushr 24).toByte()
        out[2] = (pts ushr 16).toByte()
        out[3] = (pts ushr 8).toByte()
        out[4] = pts.toByte()
        listOf(face.x, face.y, face.h).forEachIndexed { i, v ->
            val n = share(v)
            out[5 + 2 * i] = (n ushr 8).toByte()
            out[6 + 2 * i] = n.toByte()
        }
        return out
    }

    private fun readFace(p: ByteArray): Face? {
        if (p.size < FACE_SIZE) return null
        fun u16(at: Int) = ((p[at].toInt() and 0xff) shl 8 or (p[at + 1].toInt() and 0xff)) / 65535f
        val pts = ((p[1].toLong() and 0xff) shl 24) or ((p[2].toLong() and 0xff) shl 16) or ((p[3].toLong() and 0xff) shl 8) or (p[4].toLong() and 0xff)
        return Face(pts, u16(5), u16(7), u16(9))
    }

    /** [frame] as packets of at most [PIECE] bytes of it each. */
    fun pieces(number: Int, frame: Frame): List<ByteArray> {
        val count = maxOf(1, (frame.data.size + PIECE - 1) / PIECE)
        require(count <= 255) { "frame too large" }
        return (0 until count).map { i ->
            val from = i * PIECE
            val to = minOf(frame.data.size, from + PIECE)
            val out = ByteArray(HEADER + (to - from))
            out[0] = VIDEO
            out[1] = (number ushr 8).toByte()
            out[2] = number.toByte()
            out[3] = i.toByte()
            out[4] = count.toByte()
            val pts = frame.ptsMs.toInt()
            out[5] = (pts ushr 24).toByte()
            out[6] = (pts ushr 16).toByte()
            out[7] = (pts ushr 8).toByte()
            out[8] = pts.toByte()
            out[9] = ((if (frame.key) 1 else 0) or ((frame.codec and 3) shl 4)).toByte()
            out[10] = ((frame.rotation / 90) and 3).toByte()
            frame.data.copyInto(out, HEADER, from, to)
            out
        }
    }

    /** A video clip's packets, split: the Opus packets to play, and the video pieces put back together. */
    class Splitter {
        private var number = -1
        private val pieces = HashMap<Int, ByteArray>()
        private var count = 0

        /** [faces] gets the sender's face positions (Follow face); anything else unknown is skipped. */
        fun split(packets: List<ByteArray>, frames: (Frame) -> Unit, faces: (Face) -> Unit = {}): List<ByteArray> {
            val audio = ArrayList<ByteArray>(packets.size)
            for (p in packets) {
                if (p.isEmpty()) continue
                when (p[0]) {
                    AUDIO -> audio += p.copyOfRange(1, p.size)
                    VIDEO -> piece(p)?.let(frames)
                    FACE -> readFace(p)?.let(faces)
                }
            }
            return audio
        }

        private fun piece(p: ByteArray): Frame? {
            if (p.size < HEADER) return null
            val n = ((p[1].toInt() and 0xff) shl 8) or (p[2].toInt() and 0xff)
            val index = p[3].toInt() and 0xff
            val total = p[4].toInt() and 0xff
            if (n != number) {
                number = n
                pieces.clear()
                count = total
            }
            pieces[index] = p.copyOfRange(HEADER, p.size)
            if (pieces.size < count) return null
            val data = (0 until count).map { pieces[it] ?: return null }.fold(ByteArray(0)) { a, b -> a + b }
            pieces.clear()
            val pts = ((p[5].toLong() and 0xff) shl 24) or ((p[6].toLong() and 0xff) shl 16) or ((p[7].toLong() and 0xff) shl 8) or (p[8].toLong() and 0xff)
            return Frame(pts, p[9].toInt() and 1 == 1, (p[10].toInt() and 3) * 90, data, (p[9].toInt() shr 4) and 3)
        }
    }
}

/** The camera for push-to-video (Android: Camera2 into a hardware H.264 encoder). */
interface TalkVideoCapture {
    /** The picture as shown upright, width per 1000 of height; 0 when unknown. */
    val uprightShape: Int get() = 0

    /** Opens the camera ahead of a press (a Talk screen with Video on shows), sending nothing. */
    fun warm() {}

    /** Closes it (the screen left, or Video turned off). */
    fun cool() {}

    /**
     * Sends from now on: [onFrame] gets each encoded frame (the first a key frame), its time counted from this call.
     * False when the camera can't be used.
     */
    fun start(onFrame: (TalkVideo.Frame) -> Unit): Boolean

    /** While sending: where the face is, twice a second (Follow face); null: stop looking. */
    fun faces(onFace: ((TalkVideo.Face) -> Unit)?) {}

    fun stop()
}

/** Where a burst's video is shown (Android: a decoder drawing into the Talk screen's or the reply bubble's view). */
interface TalkVideoOut {
    /** [clip]'s video begins (its first frames follow). */
    fun begin(clip: String)

    fun frame(clip: String, frame: TalkVideo.Frame)

    /** Where the sender's face is in [clip]'s pictures at a moment (Follow face). */
    fun face(clip: String, face: TalkVideo.Face) {}

    /** How far [clip]'s audio has played, so the pictures keep with the voice. */
    fun position(clip: String, playedMs: Long) {}

    /** [clip] ended or stopped. */
    fun end(clip: String)
}
