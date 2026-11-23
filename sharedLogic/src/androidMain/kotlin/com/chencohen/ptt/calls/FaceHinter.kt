package com.chencohen.ptt.calls

import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink

/**
 * The sending side of Follow face (2026-11-23, hen: "the sender sends the face position", then "don't crop the face
 * before sending"): between the camera and the encoder, every frame goes on untouched, and [FaceFinder] looks for the
 * biggest face twice a second while the camera runs; each one found goes to [onFace] as a hint (FaceHint,
 * shares of the whole upright picture as sent) for the receivers to frame it their own way.
 */
class FaceHinter : VideoProcessor {
    /** Each face found, as a hint for the receivers; null: none looked for. */
    @Volatile var onFace: ((FaceHint) -> Unit)? = null

    private var sink: VideoSink? = null
    @Volatile private var frameAtMs = 0L
    // Twice a second (2026-11-23, hen: "too aggressive, make it more mellow: 2 key frames a second"): receivers glide
    // between them.
    private val finder = FaceFinder(TAG, everyMs = 500) { box ->
        if (box != null) onFace?.invoke(FaceHint(box.centerX(), box.centerY(), box.height(), frameAtMs))
    }

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) {}

    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        if (onFace != null) {
            frameAtMs = frame.timestampNs / 1_000_000
            finder.offer(frame)
        }
        out.onFrame(frame)
    }

    fun release() = finder.close()

    private companion object {
        const val TAG = "FaceHints"
    }
}
