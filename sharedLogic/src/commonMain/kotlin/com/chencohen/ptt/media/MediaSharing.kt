package com.chencohen.ptt.media

import com.chencohen.ptt.messaging.AttachmentContent
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.MediaContent
import com.chencohen.ptt.messaging.MediaKind
import com.chencohen.ptt.messaging.MessageStatus
import com.chencohen.ptt.messaging.MessagingStore
import com.chencohen.ptt.messaging.PartContent
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.talk.BlobApi
import com.chencohen.ptt.talk.BlobException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** One picture, video or file read on this phone, ready to share (the platform makes it: [MediaPreparer]). */
class PreparedItem(
    val kind: Int,
    val mime: String,
    val name: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val takenAt: Long = 0,
    val takenOffsetMin: Int = 0,
    val latE7: Int = 0,
    val lonE7: Int = 0,
    val hasPlace: Boolean = false,
    val sha256: ByteArray = ByteArray(0),
    val thumb: ByteArray = ByteArray(0),
    /** A picture's previews, one per width asked for (JPEG XL). */
    val previews: List<PreparedPreview> = emptyList(),
    /** Where it is on this phone (a content URI), to read the original when someone asks for it. */
    val source: String = "",
)

class PreparedPreview(val width: Int, val height: Int, val bytes: ByteArray, val mime: String = "image/jxl")

/** Reads what the user shares (Android: MediaStore, EXIF, the JPEG XL encoder). */
interface MediaPreparer {
    /** [source] read and its previews made, one per width [widths] gives for its own width; null when it can't be read. */
    suspend fun prepare(source: String, widths: (originalWidth: Int) -> List<Int>): PreparedItem?
}

/**
 * Sharing pictures, videos and files in a chat (2026-11-28, hen; docs/design.md, "Sharing media"). A shared message is
 * a text whose [TextMessage.media] lists its items, each with its own key (MediaCrypto). The sender's phone uploads a
 * preview per receiving screen width, sealed, and keeps no copy of the original: it reads it again from where it is
 * ([MessagingStore.mediaSource]) when someone opens it. Each phone keeps the sealed previews it sent or got
 * ([MediaFiles]); one that is gone (a cleared cache) is fetched again from the server, quietly.
 */
internal class MediaSharing(
    private val store: MessagingStore,
    private val files: MediaFiles,
    private val blobs: BlobApi,
    private val random: Random,
    private val now: () -> Long,
    /** The screen widths of the phones that will see a message in [chat] (this phone's too). */
    private val screens: (chat: String) -> List<Int>,
    /** This phone's screen width (the short side, physical pixels); 0: not known. */
    private val myScreen: () -> Int,
    private val publish: () -> Unit,
    private val newId: () -> String,
) {
    var preparer: MediaPreparer? = null

    private val fetching = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ByteArray?>>()

    /**
     * Reads [sources] and keeps them as messages to [chat] (pending; [deliver] uploads and sends them), at most
     * [MAX_ITEMS] items each, [caption] on the first. Answers the messages; empty when nothing could be read.
     */
    suspend fun prepare(chat: String, sources: List<String>, caption: String): List<TextMessage> {
        val preparer = preparer ?: return emptyList()
        val widths = screens(chat)
        val items = sources.mapNotNull { source ->
            try {
                preparer.prepare(source) { PreviewSpec.widths(widths, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val out = mutableListOf<TextMessage>()
        items.chunked(MAX_ITEMS).forEachIndexed { i, chunk ->
            val id = newId()
            val at = now()
            val text = if (i == 0) caption.trim().take(MAX_CAPTION) else ""
            val attachments = chunk.map { item -> seal(id, item) }
            val media = MediaContent(id, at, text, attachments)
            val message = TextMessage(id, chat, outgoing = true, body = fallback(media), at = at, status = MessageStatus.PENDING, media = media)
            store.putMessage(message)
            out += message
        }
        publish()
        return out
    }

    /** [item] with its own key; its previews sealed and kept (this phone shows them, and uploads them). */
    private fun seal(message: String, item: PreparedItem): AttachmentContent {
        val key = random.nextBytes(MediaCrypto.KEY_BYTES)
        val itemId = newId()
        val previews = item.previews.map { p ->
            val blob = blobId()
            val sealed = MediaCrypto.sealAll(MediaCrypto.partKey(key, blob, MediaCrypto.Part.PREVIEW), blob, p.bytes)
            files.write(blob, sealed, sent = true)
            PartContent(blob = blob, size = p.bytes.size.toLong(), width = p.width, height = p.height, mime = p.mime)
        }
        if (item.source.isNotEmpty()) store.putMediaSource(message, itemId, item.source)
        return AttachmentContent(
            id = itemId, kind = item.kind, key = key, sha256 = item.sha256, size = item.size, mime = item.mime,
            name = item.name.take(MAX_NAME), width = item.width, height = item.height, durationMs = item.durationMs,
            takenAt = item.takenAt, takenOffsetMin = item.takenOffsetMin, latE7 = item.latE7, lonE7 = item.lonE7,
            hasPlace = item.hasPlace, previews = previews, thumb = item.thumb,
        )
    }

    /**
     * Uploads the previews of [message] (this phone's, not sent yet) to the server, for its chat; again after a broken
     * upload, from where it left off. Throws [BlobException] when the server refuses one (403: no longer allowed),
     * [MissingPreviewException] when a preview to upload is gone from this phone.
     */
    suspend fun upload(message: TextMessage) {
        val media = message.media ?: return
        val group = Group.idOf(message.user)
        for (item in media.items) {
            for (part in item.previews) {
                val sealed = files.read(part.blob) ?: throw MissingPreviewException()
                blobs.put(part.blob, if (group == null) message.user else null, group, sealed)
            }
        }
    }

    /** What came in a message, checked: at most [MAX_ITEMS] items, each with a key and good blob ids; null: none. */
    fun accept(media: MediaContent?, originDevice: Int): MediaContent? {
        media ?: return null
        val items = media.items.take(MAX_ITEMS).filter { item ->
            item.key.size == MediaCrypto.KEY_BYTES && item.id.isNotEmpty() && item.previews.size <= PreviewSpec.MAX_PREVIEWS + 1 &&
                (item.previews + listOfNotNull(item.poster)).all { validBlob(it.blob) && it.size >= 0 }
        }.map { it.copy(name = it.name.take(MAX_NAME), originDevice = if (it.originDevice == 0) originDevice else it.originDevice) }
        if (items.isEmpty()) return null
        return media.copy(caption = media.caption.take(MAX_CAPTION), items = items)
    }

    /**
     * The preview of item [itemId] of message [messageId] that fits this phone's screen, opened; from what this phone
     * keeps, else fetched from the server and kept. Null when it can't be had now (no network, gone from the server,
     * changed bytes).
     */
    suspend fun preview(messageId: String, itemId: String): ByteArray? {
        val message = store.message(messageId) ?: return null
        val item = message.media?.items?.firstOrNull { it.id == itemId } ?: return null
        val part = PreviewSpec.pick(item.previews, myScreen().takeIf { it > 0 } ?: PreviewSpec.DEFAULT_WIDTH) ?: return null
        return part(item, part, MediaCrypto.Part.PREVIEW, sent = message.outgoing)
    }

    /** Whether the preview this phone would show of [itemId] is kept here (drawn at once, no spinner). */
    fun hasPreview(messageId: String, itemId: String): Boolean {
        val item = store.message(messageId)?.media?.items?.firstOrNull { it.id == itemId } ?: return false
        val part = PreviewSpec.pick(item.previews, myScreen().takeIf { it > 0 } ?: PreviewSpec.DEFAULT_WIDTH) ?: return false
        return files.read(part.blob) != null
    }

    private suspend fun part(item: AttachmentContent, part: PartContent, kind: MediaCrypto.Part, sent: Boolean): ByteArray? {
        val key = MediaCrypto.partKey(item.key, part.blob, kind)
        files.read(part.blob)?.let { sealed ->
            MediaCrypto.openAll(key, part.blob, sealed)?.let { return it }
            files.delete(part.blob) // Changed on this phone's storage: fetched again.
        }
        val (waiter, fetch) = fetching.withLock {
            inFlight[part.blob]?.let { it to false } ?: (CompletableDeferred<ByteArray?>().also { inFlight[part.blob] = it } to true)
        }
        if (!fetch) return waiter.await()
        val plain = try {
            val sealed = download(part.blob, MediaCrypto.sealedSize(part.size))
            sealed?.let { MediaCrypto.openAll(key, part.blob, it) }?.also { files.write(part.blob, sealed, sent) }
        } catch (e: CancellationException) {
            waiter.complete(null)
            throw e
        } catch (_: Exception) {
            null
        }
        waiter.complete(plain)
        fetching.withLock { inFlight.remove(part.blob) }
        return plain
    }

    /** Blob [id] read whole; null when it isn't [expected] bytes (cut short, or not this part). */
    private suspend fun download(id: String, expected: Long): ByteArray? {
        if (expected > MAX_PREVIEW_BYTES) return null
        val chunks = mutableListOf<ByteArray>()
        var size = 0L
        blobs.read(id) { bytes ->
            size += bytes.size
            if (size > expected) throw BlobException(0, "More than the preview's size")
            chunks += bytes
        }
        if (size != expected) return null
        val out = ByteArray(size.toInt())
        var at = 0
        for (c in chunks) {
            c.copyInto(out, at)
            at += c.size
        }
        return out
    }

    private fun blobId(): String = random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** The bytes this phone keeps of media it sent ([sent]) or got. */
    fun kept(sent: Boolean): Long = files.size(sent)

    /** Clears what this phone keeps of media it sent ([sent]: Settings › Storage) or got; what's still wanted comes again. */
    fun clear(sent: Boolean) = files.clear(sent)

    companion object {
        const val MAX_ITEMS = 30
        const val MAX_CAPTION = 4000
        const val MAX_NAME = 200

        /** A preview is fetched whole into memory; one this big isn't a preview. */
        const val MAX_PREVIEW_BYTES = 64L shl 20

        fun validBlob(id: String): Boolean = id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }

        /** The text older apps (and notifications) show for [media]: its caption, or what it is. */
        fun fallback(media: MediaContent): String {
            media.caption.takeIf { it.isNotBlank() }?.let { return it }
            val kinds = media.items.map { it.kind }.distinct()
            val sign = when (kinds.singleOrNull()) {
                MediaKind.IMAGE -> "📷"
                MediaKind.VIDEO -> "🎬"
                else -> "📎"
            }
            return if (media.items.size > 1) "$sign ×${media.items.size}" else sign
        }
    }
}

/** A preview this phone made to send is gone from its storage before it was uploaded. */
class MissingPreviewException : Exception("The preview to upload is gone from this phone")
