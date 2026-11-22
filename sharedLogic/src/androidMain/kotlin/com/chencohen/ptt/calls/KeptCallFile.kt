package com.chencohen.ptt.calls

import com.chencohen.ptt.messaging.KeptTrack
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.time.OffsetDateTime
import java.util.PriorityQueue

/**
 * A call the server kept, made into one Matroska file on this phone (docs/screens/calls.md, "Playing a kept call"):
 * each track blob is downloaded, its frames decrypted with the call's key (LiveKitFrames) and written as they were
 * encoded, without re-encoding: a video and an audio track per person, lined up by when each keeping began.
 */
class KeptCallFile(private val read: suspend (blob: String, onBytes: suspend (ByteArray) -> Unit) -> Unit, private val scratch: File) {
    /** One person in the file: their name, and their Matroska track numbers. */
    data class Person(val identity: String, val name: String, val video: Int?, val audio: Int?)

    /**
     * The file made: its people, length and frames; and from the call's event logs, each device's camera rotation
     * ([rotations]: ms → clockwise degrees to turn it upright) and how each viewer showed each video ([views]).
     */
    data class Made(
        val people: List<Person>,
        val durationMs: Long,
        val frames: Int,
        val unreadable: Int,
        val rotations: Map<String, List<Pair<Long, Int>>> = emptyMap(),
        val views: List<ViewAt> = emptyList(),
    )

    /** At [ms], [viewer] (user.device) showed [of]'s video (a user) cropped to fill ([fill]) or whole, in a tile [aspect]. */
    data class ViewAt(val ms: Long, val viewer: String, val of: String, val fill: Boolean, val aspect: Float)

    private class EventAt(val us: Long, val identity: String, val event: CallEvent)

    private class Group(val identity: String, val video: Boolean, val codec: String, val file: File) {
        var frames = 0
        var width = 0
        var height = 0
        var avcC: ByteArray? = null
        var firstUs = Long.MAX_VALUE
        var lastUs = Long.MIN_VALUE
        var seenKey = false
        var unreadable = 0
    }

    /**
     * Writes [out] from [tracks] of a call whose key is [callKey] (the base64 kept on its entry). [progress] goes
     * from 0 to 1 by bytes downloaded. Throws when nothing could be read.
     */
    suspend fun make(callKey: String, tracks: List<KeptTrack>, nameOf: (String) -> String, out: java.io.OutputStream, progress: (Float) -> Unit = {}): Made {
        val key = LiveKitFrames.key(callKey.encodeToByteArray())
        val rawKey = runCatching { kotlin.io.encoding.Base64.decode(callKey) }.getOrNull()
        scratch.mkdirs()
        // The devices' event logs: camera rotation, and how each showed the others (CallEvent).
        val events = mutableListOf<EventAt>()
        for (t in tracks.filter { it.kind == "events" }) {
            if (rawKey == null) break
            val startUs = epochMicros(t.recordingStartedAt)
            val bytes = java.io.ByteArrayOutputStream()
            runCatching { read(t.blob) { bytes.write(it) } }
            val input = DataInputStream(bytes.toByteArray().inputStream())
            while (input.available() >= 13) {
                val length = input.readInt()
                if (length < 9 || length > MAX_RECORD || input.available() < length) break
                val us = input.readLong()
                input.readUnsignedByte()
                val body = ByteArray(length - 9).also { input.readFully(it) }
                val event = CallEvent.open(rawKey, body)
                    ?: runCatching { CallEvent.open(rawKey, kotlin.io.encoding.Base64.decode(body.decodeToString().trim())) }.getOrNull()
                if (event != null) events += EventAt(startUs + us, t.identity, event)
            }
        }
        events.sortBy { it.us }
        val usable = tracks.filter { t -> listOf(LiveKitFrames.OPUS, LiveKitFrames.RED, LiveKitFrames.VP8, LiveKitFrames.H264, LiveKitFrames.H265).any { t.codec.equals(it, true) } }
        // RED is Opus once unwrapped: one audio track per person either way.
        fun codecOf(t: KeptTrack) = if (t.codec.equals(LiveKitFrames.RED, true)) LiveKitFrames.OPUS.lowercase() else t.codec.lowercase()
        val groups = linkedMapOf<Triple<String, String, String>, Group>()
        // People in the order they first appear; video before audio for each.
        val ordered = usable.sortedWith(compareBy({ it.identity }, { if (it.kind == "video") 0 else 1 }, { it.recordingStartedAt }))
        for (t in ordered) {
            val k = Triple(t.identity, t.kind, codecOf(t))
            groups.getOrPut(k) { Group(t.identity, t.kind == "video", codecOf(t), File.createTempFile("kept-", ".frames", scratch)) }
        }
        val total = usable.sumOf { it.size.toLong() }.coerceAtLeast(1)
        var done = 0L
        var unreadable = 0
        val writers = groups.values.associateWith { DataOutputStream(it.file.outputStream().buffered(1 shl 16)) }
        try {
            for (t in usable.sortedBy { it.recordingStartedAt }) {
                val g = groups.getValue(Triple(t.identity, t.kind, codecOf(t)))
                val red = t.codec.equals(LiveKitFrames.RED, true)
                val w = writers.getValue(g)
                val startUs = epochMicros(t.recordingStartedAt)
                val raw = File.createTempFile("kept-", ".blob", scratch)
                try {
                    raw.outputStream().buffered(1 shl 16).use { o ->
                        read(t.blob) { bytes ->
                            o.write(bytes)
                            done += bytes.size
                            progress((done.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                    DataInputStream(raw.inputStream().buffered(1 shl 16)).use { input ->
                        while (true) {
                            val length = try { input.readInt() } catch (_: EOFException) { break }
                            if (length < 9 || length > MAX_RECORD) break
                            val us = input.readLong()
                            val flags = input.readUnsignedByte()
                            val frame = ByteArray(length - 9).also { input.readFully(it) }
                            val plain = LiveKitFrames.decrypt(key, frame, t.codec)?.let { if (red) LiveKitFrames.redPrimary(it) else it }
                            if (plain == null) {
                                unreadable++
                                g.unreadable++
                                continue
                            }
                            var keyFrame = !g.video || (flags and 1) != 0
                            var data = plain
                            if (g.video && g.codec.equals(LiveKitFrames.VP8, true)) {
                                keyFrame = LiveKitFrames.isVp8KeyFrame(plain)
                                if (keyFrame && g.width == 0) Matroska.vp8Size(plain)?.let { (wd, h) -> g.width = wd; g.height = h }
                            } else if (g.video) {
                                if (keyFrame && g.avcC == null) {
                                    g.avcC = if (g.codec.equals(LiveKitFrames.H265, true)) Matroska.hvcC(plain) else Matroska.avcC(plain)
                                }
                                data = Matroska.annexBToLengths(plain)
                            }
                            // A picture can't start without a key frame.
                            if (g.video && !g.seenKey && !keyFrame) continue
                            if (keyFrame) g.seenKey = true
                            val at = startUs + us
                            w.writeLong(at)
                            w.writeBoolean(keyFrame)
                            w.writeInt(data.size)
                            w.write(data)
                            g.frames++
                            g.firstUs = minOf(g.firstUs, at)
                            g.lastUs = maxOf(g.lastUs, at)
                        }
                    }
                } finally {
                    raw.delete()
                }
            }
        } finally {
            writers.values.forEach { runCatching { it.close() } }
        }
        groups.values.forEach { g ->
            runCatching { android.util.Log.i("KeptCall", "${g.identity} ${g.codec}: ${g.frames} frames, ${g.unreadable} didn't open") }
        }
        val kept = groups.values.filter { it.frames > 0 && (!it.video || it.codec.equals(LiveKitFrames.VP8, true) || it.avcC != null) }
        try {
            if (kept.isEmpty()) throw IllegalStateException("Nothing in this call could be read")
            val base = kept.minOf { it.firstUs }
            val numbers = kept.mapIndexed { i, g -> g to i + 1 }.toMap()
            val people = kept.map { it.identity }.distinct().map { id ->
                Person(
                    id, nameOf(id.substringBeforeLast('.')),
                    kept.firstOrNull { it.identity == id && it.video }?.let { numbers.getValue(it) },
                    kept.firstOrNull { it.identity == id && !it.video }?.let { numbers.getValue(it) },
                )
            }
            fun msOf(us: Long) = maxOf((us - base) / 1000, 0)
            val rotations = events.filter { it.event.t == CallEvent.CAMERA && it.event.rot != null }
                .groupBy { it.identity }
                .mapValues { (_, list) -> list.map { msOf(it.us) to it.event.rot!! } }
            val views = events.filter { it.event.t == CallEvent.VIEW && it.event.of != null && it.event.aspect != null }
                .map { ViewAt(msOf(it.us), it.identity, it.event.of!!, it.event.fill == true, it.event.aspect!!) }
            val textNumber = kept.size + 1
            val texts = events.map { e ->
                msOf(e.us) to ("{\"by\":\"${e.identity}\"," + CallEvent.toJson(e.event).removePrefix("{")).encodeToByteArray()
            }
            val firstVideo = kept.firstOrNull { it.video }
            val firstAudio = kept.firstOrNull { !it.video }
            val mkv = Matroska(
                kept.map { g ->
                    val name = nameOf(g.identity.substringBeforeLast('.'))
                    if (g.video) {
                        val vp8 = g.codec.equals(LiveKitFrames.VP8, true)
                        Matroska.Track(
                            numbers.getValue(g), true, if (vp8) Matroska.CODEC_VP8 else Matroska.CODEC_AVC, name,
                            width = g.width.takeIf { it > 0 } ?: 640, height = g.height.takeIf { it > 0 } ?: 480,
                            codecPrivate = if (vp8) null else g.avcC, default = g === firstVideo,
                            codecIdOverride = if (g.codec.equals(LiveKitFrames.H265, true)) Matroska.CODEC_HEVC else null,
                            // The sender's camera rotation as it began (players turn the picture by it).
                            rotation = rotations[g.identity]?.firstOrNull()?.second ?: 0,
                        )
                    } else {
                        Matroska.Track(numbers.getValue(g), false, Matroska.CODEC_OPUS, name, codecPrivate = Matroska.opusHead(), default = g === firstAudio)
                    }
                } + listOfNotNull(
                    // The event logs, one JSON line each, as a text track ("Call events").
                    Matroska.Track(textNumber, false, Matroska.CODEC_TEXT, "Call events", text = true).takeIf { texts.isNotEmpty() },
                ),
            )
            mkv.begin(File.createTempFile("kept-", ".clusters", scratch))
            class Head(val g: Group, val input: DataInputStream, var at: Long = 0, var isKey: Boolean = false, var data: ByteArray = ByteArray(0))
            fun Head.next(): Boolean = try {
                at = input.readLong()
                isKey = input.readBoolean()
                data = ByteArray(input.readInt()).also { input.readFully(it) }
                true
            } catch (_: EOFException) {
                false
            }
            val heads = PriorityQueue<Head>(compareBy { it.at })
            val opened = kept.map { Head(it, DataInputStream(it.file.inputStream().buffered(1 shl 16))) }
            var frames = 0
            try {
                opened.filter { it.next() }.forEach { heads += it }
                var lastMs = -1L
                var nextText = 0
                while (heads.isNotEmpty()) {
                    val h = heads.poll()!!
                    val ms = maxOf((h.at - base) / 1000, 0)
                    while (nextText < texts.size && texts[nextText].first <= ms) {
                        mkv.frame(textNumber, texts[nextText].first, true, texts[nextText].second)
                        nextText++
                    }
                    lastMs = maxOf(lastMs, ms)
                    mkv.frame(numbers.getValue(h.g), maxOf(ms, 0), h.isKey, h.data)
                    frames++
                    if (h.next()) heads += h
                }
                while (nextText < texts.size) {
                    mkv.frame(textNumber, maxOf(texts[nextText].first, lastMs), true, texts[nextText].second)
                    nextText++
                }
                mkv.finish(out, lastMs + 20)
                return Made(people, lastMs + 20, frames, unreadable, rotations, views)
            } finally {
                opened.forEach { runCatching { it.input.close() } }
            }
        } finally {
            groups.values.forEach { it.file.delete() }
        }
    }

    companion object {
        const val MAX_RECORD = 4 * 1024 * 1024

        fun epochMicros(rfc3339: String): Long {
            val t = OffsetDateTime.parse(rfc3339).toInstant()
            return t.epochSecond * 1_000_000 + t.nano / 1000
        }
    }
}
