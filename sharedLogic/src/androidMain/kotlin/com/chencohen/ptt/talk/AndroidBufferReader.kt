package com.chencohen.ptt.talk

import android.util.Base64
import android.util.Log
import com.chencohen.ptt.calls.LiveKitFrames

/**
 * Reads a push the server's recorder kept for this phone (the server's docs/messaging.md, "Talk buffer"; 2026-11-25,
 * hen): follows /blob/<id> as it grows, takes each record (8 bytes of microseconds, a flags byte, the frame as the
 * talker's LiveKit encrypted it), opens the frame with the talker's room key (LiveKitFrames, as for a kept call), and
 * hands on the Opus packet (RED's primary block) or the picture.
 */
internal class AndroidBufferReader(private val blobs: BlobApi, private val live: LiveTalk) : Talk.BufferReader {
    override suspend fun read(blobId: String, talker: String, codec: String, onAudio: (ByteArray) -> Unit, onVideo: (TalkVideo.Frame) -> Unit) {
        val raw = live.keyOf(talker) ?: run {
            Log.w(TAG, "buffer $blobId: no room key of $talker")
            return
        }
        val key = LiveKitFrames.key(Base64.encodeToString(raw, Base64.NO_WRAP).encodeToByteArray())
        val audio = codec.startsWith("audio/", ignoreCase = true)
        val red = codec.equals("audio/red", ignoreCase = true)
        val videoCodec = when {
            codec.equals(LiveKitFrames.H265, ignoreCase = true) -> TalkVideo.H265
            codec.equals(LiveKitFrames.H264, ignoreCase = true) -> TalkVideo.H264
            else -> TalkVideo.VP8
        }
        // The camera's pictures come unturned (the turn rode in an RTP header the recorder drops): upright as the
        // talker's camera shape says (portrait: a front camera's usual 270).
        val shape = LiveTalkVideo.shapes.value[talker]
        val rotation = if (shape != null && shape < 1000) 270 else 0
        var pending = ByteArray(0)
        var frames = 0
        var failed = 0
        blobs.read(blobId) { bytes ->
            pending += bytes
            var at = 0
            while (pending.size - at >= 4) {
                val n = ((pending[at].toInt() and 0xff) shl 24) or ((pending[at + 1].toInt() and 0xff) shl 16) or
                    ((pending[at + 2].toInt() and 0xff) shl 8) or (pending[at + 3].toInt() and 0xff)
                if (pending.size - at - 4 < n) break
                val record = pending.copyOfRange(at + 4, at + 4 + n)
                at += 4 + n
                if (record.size < 10) continue
                var us = 0L
                for (i in 0 until 8) us = (us shl 8) or (record[i].toLong() and 0xff)
                val keyFrame = (record[8].toInt() and 1) == 1
                val frame = LiveKitFrames.decrypt(key, record.copyOfRange(9, record.size), codec)
                if (frame == null) {
                    failed++
                    continue
                }
                frames++
                if (audio) {
                    val opus = if (red) LiveKitFrames.redPrimary(frame) else frame
                    if (opus != null) onAudio(opus)
                } else {
                    onVideo(TalkVideo.Frame(us / 1000, keyFrame, rotation, frame, videoCodec))
                }
            }
            pending = pending.copyOfRange(at, pending.size)
        }
        Log.i(TAG, "buffer $blobId ($codec from $talker): $frames frames played, $failed didn't open")
    }

    private companion object {
        const val TAG = "TalkBuffer"
    }
}
