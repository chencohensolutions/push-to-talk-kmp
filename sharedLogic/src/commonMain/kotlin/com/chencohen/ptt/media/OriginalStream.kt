package com.chencohen.ptt.media

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * An original being read from the server for the player ([MediaSharing.streamOriginal]): [length] bytes from where it
 * was opened, handed out in pieces as they come and open. Nothing of it is written to the phone.
 */
class OriginalStream internal constructor(val length: Long, private val pieces: Channel<ByteArray>, private val job: Job) {
    /** The next piece; null at the end. Throws when it stopped coming (the network, or the server no longer has it). */
    suspend fun read(): ByteArray? = try {
        pieces.receive()
    } catch (_: ClosedReceiveChannelException) {
        null
    }

    fun close() {
        job.cancel()
        pieces.cancel()
    }
}
