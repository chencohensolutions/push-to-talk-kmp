package com.chencohen.ptt.ui.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.chencohen.ptt.media.OriginalStream
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * The player's door to an original video streamed from the server (OriginalState.Streaming, 2026-11-28, hen): each
 * open (the start, or a seek) reads it from that byte on, opened piece by piece in memory, nothing written to the phone.
 * ExoPlayer calls it on its loading thread, so waiting for a piece there is fine.
 */
@OptIn(UnstableApi::class)
internal class StreamDataSource(
    private val open: (position: Long) -> OriginalStream?,
) : BaseDataSource(true) {
    private var stream: OriginalStream? = null
    private var piece = ByteArray(0)
    private var at = 0
    private var left = 0L
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val opened = open(dataSpec.position) ?: throw IOException("The original can't be streamed")
        stream = opened
        uri = dataSpec.uri
        piece = ByteArray(0)
        at = 0
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, opened.length) else opened.length
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0) return C.RESULT_END_OF_INPUT
        if (at >= piece.size) {
            val next = try {
                runBlocking { stream?.read() }
            } catch (e: Exception) {
                throw IOException(e)
            } ?: return C.RESULT_END_OF_INPUT
            piece = next
            at = 0
        }
        val n = minOf(length.toLong(), (piece.size - at).toLong(), left).toInt()
        System.arraycopy(piece, at, buffer, offset, n)
        at += n
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        stream?.close()
        stream = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    class Factory(private val open: (position: Long) -> OriginalStream?) : DataSource.Factory {
        override fun createDataSource(): DataSource = StreamDataSource(open)
    }
}
