package com.chencohen.ptt.talk

import com.chencohen.ptt.server.ServerHttp
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

/** `/blob` on the server (its docs/messaging.md, "Blobs"): clips read while they grow, voice messages uploaded. */
internal interface BlobApi {
    /**
     * Reads blob [id] from its start, giving [onBytes] what arrives as it arrives; for a clip, until its last record
     * (the server follows a growing clip). Throws when the server refuses it (404: not allowed or gone).
     */
    suspend fun read(id: String, onBytes: suspend (ByteArray) -> Unit)

    /** Uploads [bytes] as blob [id] for the conversation with [peer] or in [group], going on from where a broken upload left off. */
    suspend fun put(id: String, peer: String?, group: String?, bytes: ByteArray)

    /**
     * Reads blob [id] from byte [offset], as it arrives; with [follow], a file still being uploaded is sent as it grows
     * (a shared original on its way). Throws [BlobException] (404: not allowed or gone; 409: not complete, no follow).
     */
    suspend fun readFrom(id: String, offset: Long, follow: Boolean, onBytes: suspend (ByteArray) -> Unit): Unit =
        throw BlobException(0, "Not supported")

    /**
     * Uploads [bytes] at [offset] of blob [id] ([total] bytes in all), for [peer] or [group]; [keepMedia]: a shared
     * original, kept media_days. Answers how much the server has after it, which is less than offset + size when it
     * had a different part (409): the caller goes on from there.
     */
    suspend fun putPiece(id: String, peer: String?, group: String?, keepMedia: Boolean, total: Long, offset: Long, bytes: ByteArray): Long =
        throw BlobException(0, "Not supported")

    /** Deletes blob [id] this phone made (an upload given up, or one that turned out not to be the shared file). */
    suspend fun delete(id: String): Unit = Unit

    /**
     * Makes blob [id] this phone made readable in the conversation with [peer] or in [group] too (a picture shared
     * again, 2026-11-28, hen: "without doubling the storage"). Answers false when it isn't on the server (gone: upload
     * it again) or the server can't share (older).
     */
    suspend fun share(id: String, peer: String?, group: String?): Boolean = false

    /** Takes blob [id] this phone made out of one conversation; the server deletes it when none is left. */
    suspend fun unshare(id: String, peer: String?, group: String?): Unit = delete(id)
}

/** The server refused a blob request. */
class BlobException(val status: Int, message: String) : Exception(message)

internal class KtorBlobApi(private val http: ServerHttp) : BlobApi {
    override suspend fun read(id: String, onBytes: suspend (ByteArray) -> Unit) {
        val client = http.client() ?: throw IllegalStateException("This phone isn't registered")
        client.prepareGet(http.url("/blob/$id")).execute { response ->
            if (!response.status.isSuccess()) throw BlobException(response.status.value, "The clip isn't on the server (${response.status.value})")
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = channel.readAvailable(buffer)
                if (n < 0) break
                if (n > 0) onBytes(buffer.copyOf(n))
            }
        }
    }

    override suspend fun put(id: String, peer: String?, group: String?, bytes: ByteArray) {
        val client = http.client() ?: throw IllegalStateException("This phone isn't registered")
        val query = if (group != null) "group=${group.encodeURLParameter()}" else "peer=${peer.orEmpty().encodeURLParameter()}"
        var offset = 0L
        repeat(3) {
            val response = client.put(http.url("/blob/$id", query)) {
                header(HEADER_TOTAL, bytes.size.toString())
                header(HEADER_OFFSET, offset.toString())
                setBody(bytes.copyOfRange(offset.toInt(), bytes.size))
            }
            when {
                response.status.isSuccess() -> return
                // The server has another part already (a broken upload): go on from there.
                response.status.value == 409 -> offset = response.headers[HEADER_OFFSET]?.toLongOrNull()?.takeIf { it in 0..bytes.size }
                    ?: throw BlobException(409, "The upload didn't fit what the server has")
                else -> throw BlobException(response.status.value, "The server didn't take the voice message (${response.status.value})")
            }
            if (offset == bytes.size.toLong()) return
        }
        throw BlobException(409, "The upload didn't fit what the server has")
    }

    override suspend fun readFrom(id: String, offset: Long, follow: Boolean, onBytes: suspend (ByteArray) -> Unit) {
        val client = http.client() ?: throw IllegalStateException("This phone isn't registered")
        client.prepareGet(http.url("/blob/$id", if (follow) "follow=1" else null)) {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.execute { response ->
            if (!response.status.isSuccess()) throw BlobException(response.status.value, "The file isn't on the server (${response.status.value})")
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = channel.readAvailable(buffer)
                if (n < 0) break
                if (n > 0) onBytes(buffer.copyOf(n))
            }
        }
    }

    override suspend fun putPiece(id: String, peer: String?, group: String?, keepMedia: Boolean, total: Long, offset: Long, bytes: ByteArray): Long {
        val client = http.client() ?: throw IllegalStateException("This phone isn't registered")
        val query = (if (group != null) "group=${group.encodeURLParameter()}" else "peer=${peer.orEmpty().encodeURLParameter()}") +
            if (keepMedia) "&keep=media" else ""
        val response = client.put(http.url("/blob/$id", query)) {
            header(HEADER_TOTAL, total.toString())
            header(HEADER_OFFSET, offset.toString())
            setBody(bytes)
        }
        return when {
            response.status.isSuccess() -> offset + bytes.size
            response.status.value == 409 -> response.headers[HEADER_OFFSET]?.toLongOrNull()?.takeIf { it in 0..total }
                ?: throw BlobException(409, "The upload didn't fit what the server has")
            else -> throw BlobException(response.status.value, "The server didn't take the file (${response.status.value})")
        }
    }

    override suspend fun delete(id: String) {
        val client = http.client() ?: return
        runCatching { client.delete(http.url("/blob/$id")) }
    }

    private fun conversation(peer: String?, group: String?) =
        if (group != null) "group=${group.encodeURLParameter()}" else "peer=${peer.orEmpty().encodeURLParameter()}"

    override suspend fun share(id: String, peer: String?, group: String?): Boolean {
        val client = http.client() ?: return false
        return runCatching { client.post(http.url("/blob/$id", conversation(peer, group))).status.isSuccess() }.getOrDefault(false)
    }

    override suspend fun unshare(id: String, peer: String?, group: String?) {
        val client = http.client() ?: return
        runCatching { client.delete(http.url("/blob/$id", conversation(peer, group))) }
    }

    private companion object {
        const val HEADER_TOTAL = "Blob-Total"
        const val HEADER_OFFSET = "Blob-Offset"
    }
}

/**
 * The clips this phone keeps, as record files ([ClipRecords]): ciphertext only, so a file is no use without its key
 * in the encrypted store. On Android, files in the app's own storage.
 */
interface ClipFiles {
    fun write(id: String, bytes: ByteArray)

    fun read(id: String): ByteArray?

    fun delete(id: String)
}

class MemoryClipFiles : ClipFiles {
    private val files = mutableMapOf<String, ByteArray>()

    override fun write(id: String, bytes: ByteArray) {
        files[id] = bytes.copyOf()
    }

    override fun read(id: String): ByteArray? = files[id]?.copyOf()

    override fun delete(id: String) {
        files.remove(id)
    }
}
