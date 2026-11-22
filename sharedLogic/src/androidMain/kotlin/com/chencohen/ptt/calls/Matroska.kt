package com.chencohen.ptt.calls

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * The smallest Matroska writer a kept call needs: tracks of Opus, VP8 or H.264 frames as they were encoded, in
 * SimpleBlocks, clusters about a second long, and cues at each cluster so a player can seek. Times are milliseconds.
 */
class Matroska(private val tracks: List<Track>) {
    class Track(
        val number: Int,
        val video: Boolean,
        /** V_VP8, V_MPEG4/ISO/AVC or A_OPUS. */
        val codecId: String,
        val name: String,
        val width: Int = 0,
        val height: Int = 0,
        val codecPrivate: ByteArray? = null,
        val default: Boolean = false,
    )

    private class Cue(val timeMs: Long, val clusterAt: Long)

    private val cues = mutableListOf<Cue>()
    private var clusterStart = -1L
    private var cluster = ByteArrayOutputStream()
    private var clusterBlocks = 0
    private var lastMs = 0L
    private val cueTrack = tracks.firstOrNull { it.video }?.number ?: tracks.firstOrNull()?.number ?: 1

    /** The clusters go here first; [finish] puts the header in front. */
    private lateinit var body: OutputStream
    private var bodySize = 0L
    private lateinit var bodyFile: File

    fun begin(scratch: File) {
        bodyFile = scratch
        body = scratch.outputStream().buffered(1 shl 16)
        bodySize = 0
    }

    /** Frames in time order (across tracks). */
    fun frame(track: Int, timeMs: Long, keyFrame: Boolean, data: ByteArray) {
        val newCluster = clusterStart < 0 ||
            timeMs - clusterStart > 30_000 ||
            (timeMs - clusterStart >= 1_000 && (track == cueTrack && keyFrame || timeMs - clusterStart >= 5_000))
        if (newCluster) {
            flushCluster()
            clusterStart = timeMs
            if (track == cueTrack && keyFrame || cues.isEmpty()) cues += Cue(timeMs, bodySize)
            cluster.write(element(ID_TIMECODE, uint(timeMs)))
        }
        val block = ByteArrayOutputStream(data.size + 8)
        block.write(vint(track.toLong()))
        val rel = (timeMs - clusterStart).toInt()
        block.write(rel ushr 8 and 0xff)
        block.write(rel and 0xff)
        block.write(if (keyFrame) 0x80 else 0)
        block.write(data)
        cluster.write(element(ID_SIMPLE_BLOCK, block.toByteArray()))
        clusterBlocks++
        lastMs = maxOf(lastMs, timeMs)
    }

    private fun flushCluster() {
        if (clusterStart < 0) return
        val bytes = element(ID_CLUSTER, cluster.toByteArray())
        body.write(bytes)
        bodySize += bytes.size
        cluster = ByteArrayOutputStream()
        clusterBlocks = 0
    }

    /** Writes the whole file to [out] (and closes it), and deletes the scratch file. */
    fun finish(out: OutputStream, durationMs: Long = lastMs + 20) {
        flushCluster()
        body.close()
        val info = element(
            ID_INFO,
            element(ID_TIMECODE_SCALE, uint(1_000_000)) +
                element(ID_DURATION, float(durationMs.toDouble())) +
                element(ID_MUXING_APP, "push-to-talk".encodeToByteArray()) +
                element(ID_WRITING_APP, "push-to-talk".encodeToByteArray()),
        )
        val tracksElement = element(ID_TRACKS, tracks.map { trackEntry(it) }.fold(ByteArray(0)) { a, b -> a + b })
        // Fixed-width positions, so the sizes don't depend on them.
        fun seek(id: Int, at: Long) = element(ID_SEEK, element(ID_SEEK_ID, idBytes(id)) + element(ID_SEEK_POSITION, uint8(at)))
        val seekHeadSize = element(ID_SEEK_HEAD, seek(ID_INFO, 0) + seek(ID_TRACKS, 0) + seek(ID_CUES, 0)).size
        fun cuesElement(base: Long) = element(
            ID_CUES,
            cues.map { c ->
                element(
                    ID_CUE_POINT,
                    element(ID_CUE_TIME, uint(c.timeMs)) +
                        element(ID_CUE_TRACK_POSITIONS, element(ID_CUE_TRACK, uint(cueTrack.toLong())) + element(ID_CUE_CLUSTER_POSITION, uint8(base + c.clusterAt))),
                )
            }.fold(ByteArray(0)) { a, b -> a + b },
        )
        val cuesSize = cuesElement(0).size
        val clustersAt = (seekHeadSize + info.size + tracksElement.size + cuesSize).toLong()
        val cuesBytes = cuesElement(clustersAt)
        val seekHead = element(
            ID_SEEK_HEAD,
            seek(ID_INFO, seekHeadSize.toLong()) + seek(ID_TRACKS, (seekHeadSize + info.size).toLong()) +
                seek(ID_CUES, (seekHeadSize + info.size + tracksElement.size).toLong()),
        )
        val segmentSize = clustersAt + bodySize
        out.buffered(1 shl 16).use { o ->
            o.write(ebmlHeader())
            o.write(idBytes(ID_SEGMENT))
            o.write(size8(segmentSize))
            o.write(seekHead)
            o.write(info)
            o.write(tracksElement)
            o.write(cuesBytes)
            bodyFile.inputStream().use { it.copyTo(o) }
        }
        bodyFile.delete()
    }

    private fun trackEntry(t: Track): ByteArray {
        var b = element(ID_TRACK_NUMBER, uint(t.number.toLong())) +
            element(ID_TRACK_UID, uint(t.number.toLong())) +
            element(ID_TRACK_TYPE, uint(if (t.video) 1 else 2)) +
            element(ID_FLAG_LACING, uint(0)) +
            element(ID_FLAG_DEFAULT, uint(if (t.default) 1 else 0)) +
            element(ID_NAME, t.name.encodeToByteArray()) +
            element(ID_LANGUAGE, "und".encodeToByteArray()) +
            element(ID_CODEC_ID, t.codecId.encodeToByteArray())
        t.codecPrivate?.let { b += element(ID_CODEC_PRIVATE, it) }
        b += if (t.video) {
            element(ID_VIDEO, element(ID_PIXEL_WIDTH, uint(t.width.toLong())) + element(ID_PIXEL_HEIGHT, uint(t.height.toLong())))
        } else {
            element(ID_CODEC_DELAY, uint(0)) + element(ID_SEEK_PRE_ROLL, uint(80_000_000)) +
                element(ID_AUDIO, element(ID_SAMPLING_FREQUENCY, float(48_000.0)) + element(ID_CHANNELS, uint(1)))
        }
        return element(ID_TRACK_ENTRY, b)
    }

    private fun ebmlHeader() = element(
        ID_EBML,
        element(0x4286, uint(1)) + element(0x42F7, uint(1)) + element(0x42F2, uint(4)) + element(0x42F3, uint(8)) +
            element(0x4282, "webm".takeIf { tracks.all { it.codecId != CODEC_AVC } } ?.encodeToByteArray() ?: "matroska".encodeToByteArray()) +
            element(0x4287, uint(4)) + element(0x4285, uint(2)),
    )

    companion object {
        const val CODEC_VP8 = "V_VP8"
        const val CODEC_AVC = "V_MPEG4/ISO/AVC"
        const val CODEC_OPUS = "A_OPUS"

        const val ID_EBML = 0x1A45DFA3
        const val ID_SEGMENT = 0x18538067
        const val ID_SEEK_HEAD = 0x114D9B74
        const val ID_SEEK = 0x4DBB
        const val ID_SEEK_ID = 0x53AB
        const val ID_SEEK_POSITION = 0x53AC
        const val ID_INFO = 0x1549A966
        const val ID_TIMECODE_SCALE = 0x2AD7B1
        const val ID_DURATION = 0x4489
        const val ID_MUXING_APP = 0x4D80
        const val ID_WRITING_APP = 0x5741
        const val ID_TRACKS = 0x1654AE6B
        const val ID_TRACK_ENTRY = 0xAE
        const val ID_TRACK_NUMBER = 0xD7
        const val ID_TRACK_UID = 0x73C5
        const val ID_TRACK_TYPE = 0x83
        const val ID_FLAG_DEFAULT = 0x88
        const val ID_FLAG_LACING = 0x9C
        const val ID_NAME = 0x536E
        const val ID_LANGUAGE = 0x22B59C
        const val ID_CODEC_ID = 0x86
        const val ID_CODEC_PRIVATE = 0x63A2
        const val ID_CODEC_DELAY = 0x56AA
        const val ID_SEEK_PRE_ROLL = 0x56BB
        const val ID_VIDEO = 0xE0
        const val ID_PIXEL_WIDTH = 0xB0
        const val ID_PIXEL_HEIGHT = 0xBA
        const val ID_AUDIO = 0xE1
        const val ID_SAMPLING_FREQUENCY = 0xB5
        const val ID_CHANNELS = 0x9F
        const val ID_CUES = 0x1C53BB6B
        const val ID_CUE_POINT = 0xBB
        const val ID_CUE_TIME = 0xB3
        const val ID_CUE_TRACK_POSITIONS = 0xB7
        const val ID_CUE_TRACK = 0xF7
        const val ID_CUE_CLUSTER_POSITION = 0xF1
        const val ID_CLUSTER = 0x1F43B675
        const val ID_TIMECODE = 0xE7
        const val ID_SIMPLE_BLOCK = 0xA3

        fun idBytes(id: Int): ByteArray = when {
            id > 0xFFFFFF -> byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
            id > 0xFFFF -> byteArrayOf((id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
            id > 0xFF -> byteArrayOf((id ushr 8).toByte(), id.toByte())
            else -> byteArrayOf(id.toByte())
        }

        /** EBML's variable-length number, as short as it can be (sizes and track numbers). */
        fun vint(value: Long): ByteArray {
            var length = 1
            while (length < 8 && value >= (1L shl (7 * length)) - 1) length++
            val out = ByteArray(length)
            var v = value
            for (i in length - 1 downTo 0) {
                out[i] = v.toByte()
                v = v ushr 8
            }
            out[0] = (out[0].toInt() or (0x80 ushr (length - 1))).toByte()
            return out
        }

        fun size8(value: Long): ByteArray = ByteArray(8).also {
            var v = value
            for (i in 7 downTo 1) {
                it[i] = v.toByte()
                v = v ushr 8
            }
            it[0] = 0x01
        }

        fun element(id: Int, data: ByteArray): ByteArray = idBytes(id) + vint(data.size.toLong()) + data

        fun uint(value: Long): ByteArray {
            var length = 1
            while (length < 8 && (value ushr (8 * length)) != 0L) length++
            return ByteArray(length) { i -> (value ushr (8 * (length - 1 - i))).toByte() }
        }

        fun uint8(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (8 * (7 - i))).toByte() }

        fun float(value: Double): ByteArray = uint8(java.lang.Double.doubleToLongBits(value))

        /** The Opus header Matroska wants as CodecPrivate: mono, 48 kHz, no pre-skip. */
        fun opusHead(): ByteArray = "OpusHead".encodeToByteArray() + byteArrayOf(1, 1, 0, 0) +
            byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0, 0) + byteArrayOf(0, 0, 0)

        /** VP8 key frame size (its start code 9d 01 2a, then 14-bit width and height); null when it isn't one. */
        fun vp8Size(frame: ByteArray): Pair<Int, Int>? {
            if (frame.size < 10 || (frame[0].toInt() and 1) != 0) return null
            if ((frame[3].toInt() and 0xff) != 0x9d || (frame[4].toInt() and 0xff) != 0x01 || (frame[5].toInt() and 0xff) != 0x2a) return null
            val w = ((frame[7].toInt() and 0xff) shl 8 or (frame[6].toInt() and 0xff)) and 0x3fff
            val h = ((frame[9].toInt() and 0xff) shl 8 or (frame[8].toInt() and 0xff)) and 0x3fff
            return w to h
        }

        /** An H.264 access unit in Annex B as Matroska's AVC: each NAL unit with a 4-byte length. */
        fun annexBToLengths(frame: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(frame.size + 16)
            for (nal in nalUnits(frame)) {
                out.write(nal.size ushr 24 and 0xff)
                out.write(nal.size ushr 16 and 0xff)
                out.write(nal.size ushr 8 and 0xff)
                out.write(nal.size and 0xff)
                out.write(nal)
            }
            return out.toByteArray()
        }

        fun nalUnits(frame: ByteArray): List<ByteArray> {
            val starts = LiveKitFrames.naluStarts(frame)
            return starts.mapIndexed { i, s ->
                var end = if (i + 1 < starts.size) starts[i + 1] - 3 else frame.size
                // A 4-byte start code's leading zero belongs to neither unit.
                while (i + 1 < starts.size && end > s && frame[end - 1].toInt() == 0) end--
                frame.copyOfRange(s, end)
            }
        }

        /** avcC from the SPS and PPS of a key frame; null when they aren't there. */
        fun avcC(frame: ByteArray): ByteArray? {
            val units = nalUnits(frame)
            val sps = units.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7 } ?: return null
            val pps = units.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8 } ?: return null
            if (sps.size < 4) return null
            val out = ByteArrayOutputStream()
            out.write(1)
            out.write(sps[1].toInt() and 0xff)
            out.write(sps[2].toInt() and 0xff)
            out.write(sps[3].toInt() and 0xff)
            out.write(0xff)
            out.write(0xe1)
            out.write(sps.size ushr 8)
            out.write(sps.size and 0xff)
            out.write(sps)
            out.write(1)
            out.write(pps.size ushr 8)
            out.write(pps.size and 0xff)
            out.write(pps)
            return out.toByteArray()
        }

        /** Reads a file's EBML element at [at]: its id, data start and data size (for tests). */
        fun readElement(f: RandomAccessFile, at: Long): Triple<Int, Long, Long> {
            f.seek(at)
            val first = f.read()
            var id = first
            repeat(Integer.numberOfLeadingZeros(first) - 24) { id = id shl 8 or f.read() }
            val s0 = f.read()
            val sizeLength = Integer.numberOfLeadingZeros(s0) - 24
            var size = (s0 and (0xff ushr (sizeLength + 1))).toLong()
            repeat(sizeLength) { size = size shl 8 or f.read().toLong() }
            return Triple(id, f.filePointer, size)
        }
    }
}
