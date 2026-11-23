package com.chencohen.ptt.talk

import com.chencohen.ptt.calls.Matroska
import java.io.File
import java.io.OutputStream

/**
 * A burst (or voice message) as an ordinary file (Save as file; 2026-11-23, hen): its Opus voice and, for a
 * push-to-video burst, its video as sent (VP8, H.264 or H.265, not re-encoded), in Matroska (.mkv), as saved calls are;
 * the picture turned upright by its rotation when played.
 */
object BurstFile {
    fun make(contents: ClipContents, scratch: File, out: OutputStream) {
        val frames = contents.frames
        val first = frames.firstOrNull { it.key } ?: frames.firstOrNull()
        val codec = first?.codec ?: TalkVideo.VP8
        var width = AndroidTalkVideoCapture.WIDTH
        var height = AndroidTalkVideoCapture.HEIGHT
        var codecPrivate: ByteArray? = null
        if (first != null) {
            when (codec) {
                TalkVideo.VP8 -> Matroska.vp8Size(first.data)?.let { (w, h) -> width = w; height = h }
                TalkVideo.H265 -> {
                    codecPrivate = Matroska.hvcC(first.data)
                    Matroska.hevcSize(first.data)?.let { (w, h) -> width = w; height = h }
                }
                else -> codecPrivate = Matroska.avcC(first.data)
            }
        }
        val tracks = buildList {
            add(Matroska.Track(1, false, Matroska.CODEC_OPUS, "Voice", codecPrivate = Matroska.opusHead(), default = true))
            if (frames.isNotEmpty()) {
                add(
                    Matroska.Track(
                        2, true, if (codec == TalkVideo.VP8) Matroska.CODEC_VP8 else Matroska.CODEC_AVC, "Video",
                        width = width, height = height, codecPrivate = codecPrivate, default = true,
                        rotation = first?.rotation ?: 0,
                        codecIdOverride = if (codec == TalkVideo.H265) Matroska.CODEC_HEVC else null,
                    ),
                )
            }
        }
        val mkv = Matroska(tracks)
        mkv.begin(scratch)
        // In time order across the two: the voice's 20 ms packets, and the pictures by their time from the press.
        var v = 0
        var last = 0L
        contents.audio.forEachIndexed { i, packet ->
            val at = i * TalkAudioFormat.FRAME_MS.toLong()
            while (v < frames.size && frames[v].ptsMs <= at) {
                val f = frames[v++]
                mkv.frame(2, f.ptsMs, f.key, if (f.codec == TalkVideo.VP8) f.data else Matroska.annexBToLengths(f.data))
            }
            mkv.frame(1, at, true, packet)
            last = at
        }
        while (v < frames.size) {
            val f = frames[v++]
            mkv.frame(2, f.ptsMs, f.key, if (f.codec == TalkVideo.VP8) f.data else Matroska.annexBToLengths(f.data))
            last = maxOf(last, f.ptsMs)
        }
        mkv.finish(out, last + TalkAudioFormat.FRAME_MS)
    }
}
