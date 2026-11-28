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
import com.chencohen.ptt.messaging.Content
import com.chencohen.ptt.messaging.MediaPart
import com.chencohen.ptt.messaging.MediaReplyContent
import com.chencohen.ptt.messaging.MediaReplyStatus
import com.chencohen.ptt.messaging.MediaRequestContent
import com.chencohen.ptt.relay.PushKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

    /** The file at [source] opened again, to send its untouched original; null when it is gone or can't be read. */
    suspend fun open(source: String): OriginalReader? = null
}

/** A shared file read again from where it is on this phone, with its SHA-256 worked out as it is read. */
interface OriginalReader {
    /** Its size now (-1: not known). */
    val size: Long

    /** Up to [max] next bytes; null at its end. */
    suspend fun read(max: Int): ByteArray?

    /** The SHA-256 of what was read, once it has all been read. */
    fun sha256(): ByteArray

    fun close()
}

/** Where opening an original stands, for the viewer (2026-11-28, hen: a small progress pill and Cancel). */
sealed interface OriginalState {
    /** Asked the phone that shared it; waiting for its answer. */
    data object Asking : OriginalState

    /** Coming: [done] of [total] bytes here. */
    data class Coming(val done: Long, val total: Long) : OriginalState

    /** Here: [path] is a file on this phone, or (on the phone that shared it) where it is ([local], a content URI). */
    data class Ready(val path: String, val local: Boolean = false) : OriginalState

    /** The phone that shared it no longer has it. */
    data object Gone : OriginalState

    /** The file there was changed since it was shared. */
    data object Changed : OriginalState

    /** It didn't come (no answer, or the network): Try again asks again. */
    data object Failed : OriginalState
}

/**
 * Sharing pictures, videos and files in a chat (2026-11-28, hen; docs/design.md, "Sharing media"). A shared message is
 * a text whose [TextMessage.media] lists its items, each with its own key (MediaCrypto). The sender's phone uploads a
 * preview per receiving screen width, sealed, and keeps no copy of the original: it reads it again from where it is
 * ([MessagingStore.mediaSource]) when someone opens it. Each phone keeps the sealed previews it sent or got
 * ([MediaFiles]); one that is gone (a cleared cache) is fetched again from the server, quietly.
 */
/**
 * Where an item of a shared message is kept, for the viewer's Info (2026-11-28, hen: "what other cached my device saved
 * about that image and which peer holds what quality of the image").
 */
data class MediaKeeping(
    /** Each preview (and a video's poster) made for it, and whether this phone keeps it; [KeptPart.shown]: the one drawn here. */
    val parts: List<KeptPart>,
    /** The original's file on this phone: where the user shared it from (mine), or the copy fetched; null: not here. */
    val originalPath: String?,
    /** Mine: the shared file itself (this phone reads it again when someone asks). */
    val originalIsSource: Boolean,
    /** The original's size, its picture's size and type, as shared. */
    val originalBytes: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalMime: String,
    /** When the original went up to the server for someone who asked (it stays there about a month); 0: never. */
    val originalUploadedAt: Long,
    /** Who else holds what, as this phone knows it. */
    val peers: List<PeerHolds>,
)

data class KeptPart(val poster: Boolean, val width: Int, val height: Int, val bytes: Long, val mime: String, val here: Boolean, val shown: Boolean)

/**
 * [user]'s phones: [original] the one who shared it (the file is on their phone); else the preview made for their
 * screen ([previewWidth]), and [has] whether it reached them (true, false, or null: not known here).
 */
data class PeerHolds(val user: String, val original: Boolean, val previewWidth: Int, val has: Boolean?)

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
    private val scope: CoroutineScope? = null,
    /** Sends [content] in [chat]; [pushUser]: only that user's phones are woken ([pushKind]). */
    private val send: suspend (chat: String, content: Content, pushKind: Int, pushUser: String) -> Unit = { _, _, _, _ -> },
) {
    var preparer: MediaPreparer? = null

    private val _originals = MutableStateFlow<Map<String, OriginalState>>(emptyMap())

    /** The originals asked for on this phone, by "message/item". */
    val originals: StateFlow<Map<String, OriginalState>> = _originals.asStateFlow()

    private val opening = mutableMapOf<String, Job>()
    private val answers = mutableMapOf<String, CompletableDeferred<MediaReplyContent>>()

    /** The originals this phone is uploading now, by "message/item" (a wake-up keeps the connection for them). */
    private val uploading = mutableMapOf<String, Job>()

    /** Whether this phone is sending an original someone asked for. */
    val sending: Boolean get() = uploading.values.any { it.isActive }

    private val fetching = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ByteArray?>>()

    /**
     * Reads [sources] and keeps them as messages to [chat] (pending; [deliver] uploads and sends them), at most
     * [MAX_ITEMS] items each, [caption] on the first. Answers the messages; empty when nothing could be read.
     */
    suspend fun prepare(chat: String, sources: List<String>, caption: String, keep: Boolean = true): List<TextMessage> {
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
            if (keep) store.putMessage(message) else drafts[id] = message
            out += message
        }
        // A draft's previews go up while the caption is written (2026-11-28, hen: "start sending the images to server
        // before writing"); the message that names them goes only on Send, and a dropped draft's are deleted.
        if (!keep) scope?.let { scope -> for (m in out) draftUploads[m.id] = scope.launch { runCatching { putPreviews(m) } } }
        if (keep) publish()
        return out
    }

    /**
     * Messages [prepare]d without keeping them (a draft the user writes a caption on first, 2026-11-28, hen), now kept
     * to be sent: [caption] on the first, their time now.
     */
    fun keep(drafts: List<TextMessage>, caption: String, removed: Set<String> = emptySet(), quiet: Boolean = false, texts: Map<String, String> = emptyMap()): List<TextMessage> {
        val at = now()
        // Only what was taken out is dropped: the upload of the rest goes on, and Send waits for it.
        for (m in drafts) {
            val gone = m.media?.items.orEmpty().filter { it.id in removed }
            for (item in gone) for (p in item.previews) forget(p.blob)
        }
        val out = drafts.mapNotNull { m ->
            this.drafts.remove(m.id)
            // Words above a picture go with it (2026-11-28, hen).
            val items = m.media!!.items.filter { it.id !in removed }.map { item -> texts[item.id]?.trim()?.take(MAX_CAPTION)?.let { item.copy(text = it) } ?: item }
            if (items.isEmpty()) null else m to items
        }.mapIndexed { i, (m, items) ->
            val media = m.media!!.copy(sentAt = at, caption = if (i == 0) caption.trim().take(MAX_CAPTION) else "", items = items, quiet = quiet && i == 0)
            m.copy(at = at, media = media, body = fallback(media)).also { store.putMessage(it) }
        }
        publish()
        return out
    }

    /** A draft dropped: its previews are deleted. */
    fun drop(drafts: List<TextMessage>) {
        for (m in drafts) {
            this.drafts.remove(m.id)
            val job = draftUploads.remove(m.id)
            val parts = m.media?.items.orEmpty().flatMap { it.previews }.map { it.blob }
            job?.cancel()
            for (blob in parts) files.delete(blob)
            // What already went up is deleted from the server, once the upload has stopped.
            scope?.launch {
                job?.join()
                for (blob in parts) if (uploaded.remove(blob)) runCatching { blobs.delete(blob) }
            }
        }
    }

    /** A preview taken out of a draft: gone from this phone, and from the server if it went up. */
    private fun forget(blob: String) {
        files.delete(blob)
        scope?.launch {
            for (job in draftUploads.values.toList()) job.join()
            if (uploaded.remove(blob)) runCatching { blobs.delete(blob) }
        }
    }

    /** The drafts' preview uploads, started on [prepare], by message id. */
    private val draftUploads = mutableMapOf<String, Job>()

    /** The previews this phone has put on the server (an upload after a draft's skips them). */
    private val uploaded = mutableSetOf<String>()

    /** Drafts not sent yet, by message id (their previews show while the caption is written). */
    private val drafts = mutableMapOf<String, TextMessage>()

    private val _uploads = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** How far each message of this phone's being sent got uploading its previews (0 to 1), by message id. */
    val uploads: StateFlow<Map<String, Float>> = _uploads.asStateFlow()

    private val _transfers = MutableStateFlow<Map<String, Float>>(emptyMap())

    /**
     * Each item's preview going up (mine) or coming down (theirs) from the server now, 0 to 1, by item id (2026-11-28,
     * hen: "i want to see upload progress and download progress from server"). Gone once done.
     */
    val transfers: StateFlow<Map<String, Float>> = _transfers.asStateFlow()

    private fun transfer(item: String, done: Long, total: Long) {
        _transfers.update { it + (item to (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)) }
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
        draftUploads.remove(message.id)?.join()
        putPreviews(message)
    }

    private suspend fun putPreviews(message: TextMessage) {
        val media = message.media ?: return
        val group = Group.idOf(message.user)
        val total = media.items.sumOf { item -> item.previews.sumOf { MediaCrypto.sealedSize(it.size) } }.coerceAtLeast(1)
        var done = 0L
        _uploads.update { it + (message.id to 0f) }
        try {
            for (item in media.items) {
                val itemTotal = item.previews.sumOf { MediaCrypto.sealedSize(it.size) }
                var itemDone = 0L
                for (part in item.previews) {
                    if (part.blob !in uploaded) transfer(item.id, itemDone, itemTotal)
                    if (part.blob in uploaded) {
                        itemDone += MediaCrypto.sealedSize(part.size)
                        done += MediaCrypto.sealedSize(part.size)
                        _uploads.update { it + (message.id to (done.toFloat() / total).coerceAtMost(1f)) }
                        continue
                    }
                    val sealed = files.read(part.blob) ?: throw MissingPreviewException()
                    val peer = if (group == null) message.user else null
                    // In pieces, so the progress moves smoothly; whole when the server can't take pieces.
                    var offset = 0L
                    var stuck = 0
                    try {
                        while (offset < sealed.size) {
                            val end = minOf(sealed.size.toLong(), offset + PREVIEW_PIECE).toInt()
                            val got = blobs.putPiece(part.blob, peer, group, false, sealed.size.toLong(), offset, sealed.copyOfRange(offset.toInt(), end))
                            stuck = if (got <= offset) stuck + 1 else 0
                            if (stuck > 2) throw BlobException(409, "The server keeps another part")
                            offset = got
                            transfer(item.id, itemDone + offset, itemTotal)
                            _uploads.update { it + (message.id to ((done + offset).toFloat() / total).coerceAtMost(1f)) }
                        }
                    } catch (e: BlobException) {
                        if (e.status != 0) throw e
                        blobs.put(part.blob, peer, group, sealed)
                    }
                    uploaded += part.blob
                    itemDone += sealed.size
                    transfer(item.id, itemDone, itemTotal)
                    done += sealed.size
                    _uploads.update { it + (message.id to (done.toFloat() / total).coerceAtMost(1f)) }
                }
            }
        } finally {
            _uploads.update { it - message.id }
            _transfers.update { it - media.items.map { item -> item.id }.toSet() }
        }
    }

    /** What came in a message, checked: at most [MAX_ITEMS] items, each with a key and good blob ids; null: none. */
    fun accept(media: MediaContent?, originDevice: Int): MediaContent? {
        media ?: return null
        val items = media.items.take(MAX_ITEMS).filter { item ->
            item.key.size == MediaCrypto.KEY_BYTES && item.id.isNotEmpty() && item.previews.size <= PreviewSpec.MAX_PREVIEWS + 1 &&
                (item.previews + listOfNotNull(item.poster)).all { validBlob(it.blob) && it.size >= 0 }
        }.map { it.copy(name = it.name.take(MAX_NAME), text = it.text.take(MAX_CAPTION), originDevice = if (it.originDevice == 0) originDevice else it.originDevice) }
        if (items.isEmpty()) return null
        return media.copy(caption = media.caption.take(MAX_CAPTION), items = items)
    }

    /**
     * The preview of item [itemId] of message [messageId] that fits this phone's screen, opened; from what this phone
     * keeps, else fetched from the server and kept. Null when it can't be had now (no network, gone from the server,
     * changed bytes).
     */
    suspend fun preview(messageId: String, itemId: String): ByteArray? {
        val message = store.message(messageId) ?: drafts[messageId] ?: return null
        val item = message.media?.items?.firstOrNull { it.id == itemId } ?: return null
        val part = PreviewSpec.pick(item.previews, myScreen().takeIf { it > 0 } ?: PreviewSpec.DEFAULT_WIDTH) ?: return null
        return part(item, part, MediaCrypto.Part.PREVIEW, sent = message.outgoing).also { if (it != null) checkAll(messageId) }
    }

    /** Fetches message [messageId]'s pictures' previews now (its videos' too when [videos]); those that fail come later. */
    suspend fun fetchPreviews(messageId: String, videos: Boolean) {
        val items = store.message(messageId)?.media?.items.orEmpty()
        for (item in items) {
            if (item.kind == com.chencohen.ptt.messaging.MediaKind.VIDEO && !videos) continue
            if (item.previews.isEmpty()) continue
            preview(messageId, item.id)
        }
        checkAll(messageId)
    }

    private val waitingAll = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Returns once every item of message [messageId] that has a preview has it on this phone (the receipt waits for it). */
    suspend fun allPreviews(messageId: String) {
        val wait = fetching.withLock { waitingAll.getOrPut(messageId) { CompletableDeferred() } }
        checkAll(messageId)
        wait.await()
    }

    private suspend fun checkAll(messageId: String) {
        val items = store.message(messageId)?.media?.items ?: return
        if (!items.all { it.previews.isEmpty() || hasPreview(messageId, it.id) }) return
        fetching.withLock { waitingAll.remove(messageId) }?.complete(Unit)
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
            val sealed = download(part.blob, MediaCrypto.sealedSize(part.size)) { done, total -> transfer(item.id, done, total) }
            sealed?.let { MediaCrypto.openAll(key, part.blob, it) }?.also { files.write(part.blob, sealed, sent) }
        } catch (e: CancellationException) {
            waiter.complete(null)
            throw e
        } catch (_: Exception) {
            null
        } finally {
            _transfers.update { it - item.id }
        }
        waiter.complete(plain)
        fetching.withLock { inFlight.remove(part.blob) }
        return plain
    }

    /** Blob [id] read whole; null when it isn't [expected] bytes (cut short, or not this part). */
    private suspend fun download(id: String, expected: Long, progress: (done: Long, total: Long) -> Unit = { _, _ -> }): ByteArray? {
        if (expected > MAX_PREVIEW_BYTES) return null
        val chunks = mutableListOf<ByteArray>()
        var size = 0L
        progress(0, expected)
        blobs.read(id) { bytes ->
            size += bytes.size
            if (size > expected) throw BlobException(0, "More than the preview's size")
            chunks += bytes
            progress(size, expected)
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

    // Originals (2026-11-28, hen): opening one quietly fetches the untouched file from the phone that shared it.

    /**
     * Opens the original of item [itemId] of message [messageId]: at once on the phone that shared it (where it is) or
     * when it is here already, else asked of the phone that shared it and fetched as it uploads ([originals] says how
     * it goes). A broken fetch goes on from where it stopped.
     */
    fun openOriginal(messageId: String, itemId: String) {
        val scope = scope ?: return
        val key = "$messageId/$itemId"
        if (opening[key]?.isActive == true) return
        val message = store.message(messageId) ?: return
        val item = message.media?.items?.firstOrNull { it.id == itemId } ?: return
        store.mediaSource(messageId, itemId)?.let { return setOriginal(key, OriginalState.Ready(it, local = true)) }
        store.mediaSource(messageId, "$itemId$ORIGINAL_BLOB")?.let { blob -> files.originalPath(blob)?.let { return setOriginal(key, OriginalState.Ready(it)) } }
        opening[key] = scope.launch {
            try {
                fetchOriginal(message, item, key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setOriginal(key, OriginalState.Failed)
            }
        }
    }

    /** What this phone keeps of item [itemId] of message [messageId] (its previews, its original); [MediaKeeping.peers] empty. */
    fun keeping(messageId: String, itemId: String): MediaKeeping? {
        val message = store.message(messageId) ?: return null
        val item = message.media?.items?.firstOrNull { it.id == itemId } ?: return null
        val shown = PreviewSpec.pick(item.previews, myScreen().takeIf { it > 0 } ?: PreviewSpec.DEFAULT_WIDTH)
        val parts = item.previews.map { KeptPart(false, it.width, it.height, it.size, it.mime, files.read(it.blob) != null, it == shown) } +
            listOfNotNull(item.poster).map { KeptPart(true, it.width, it.height, it.size, it.mime, files.read(it.blob) != null, false) }
        val source = store.mediaSource(messageId, itemId)
        val fetched = store.mediaSource(messageId, "$itemId$ORIGINAL_BLOB")?.let { files.originalPath(it) }
        return MediaKeeping(
            parts = parts, originalPath = source ?: fetched, originalIsSource = source != null,
            originalBytes = item.size, originalWidth = item.width, originalHeight = item.height, originalMime = item.mime,
            originalUploadedAt = store.mediaSource(messageId, "$itemId$ORIGINAL_UPLOADED")?.toLongOrNull()?.takeIf { it > 0 } ?: 0,
            peers = emptyList(),
        )
    }

    /** Message [id] as kept (the viewer's). */
    fun message(id: String): TextMessage? = store.message(id)

    /** Stops fetching an original (Cancel); what came is kept, and opening it again goes on from there. */
    fun cancelOriginal(messageId: String, itemId: String) {
        val key = "$messageId/$itemId"
        opening.remove(key)?.cancel()
        _originals.update { it - key }
    }

    private suspend fun fetchOriginal(message: TextMessage, item: AttachmentContent, key: String) {
        var blob = store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")
        if (blob == null || !uploadedRecently(message.id, item.id)) {
            setOriginal(key, OriginalState.Asking)
            val answer = CompletableDeferred<MediaReplyContent>()
            answers[key] = answer
            val owner = if (message.outgoing) "" else message.author ?: message.user
            send(message.user, Content(mediaRequest = MediaRequestContent(message.id, item.id, MediaPart.ORIGINAL)), PushKind.MEDIA, owner)
            val reply = withTimeoutOrNull(ANSWER_WAIT) { answer.await() }
            answers.remove(key)
            when (reply?.status) {
                MediaReplyStatus.READY -> blob = reply.upload?.blob
                MediaReplyStatus.GONE -> return setOriginal(key, OriginalState.Gone)
                MediaReplyStatus.CHANGED -> return setOriginal(key, OriginalState.Changed)
                else -> return setOriginal(key, OriginalState.Failed)
            }
        }
        val id = blob?.takeIf { validBlob(it) } ?: return setOriginal(key, OriginalState.Failed)
        download(id, item, key)
    }

    /** The original [blob] of [item] fetched as it grows, opened chunk by chunk into this phone's cache. */
    private suspend fun download(blob: String, item: AttachmentContent, key: String) {
        val partKey = MediaCrypto.partKey(item.key, blob, MediaCrypto.Part.ORIGINAL)
        val chunks = MediaCrypto.chunkCount(item.size)
        val lastSize = (MediaCrypto.sealedSize(item.size) - (chunks - 1) * MediaCrypto.SEALED).toInt()
        // A broken fetch goes on from its last whole chunk.
        var done = files.partialOriginal(blob) / MediaCrypto.CHUNK
        if (files.partialOriginal(blob) != done * MediaCrypto.CHUNK || done >= chunks) {
            files.deleteOriginal(blob)
            done = 0
        }
        setOriginal(key, OriginalState.Coming(done * MediaCrypto.CHUNK, item.size))
        var idle = 0
        while (done < chunks) {
            val before = done
            var pending = ByteArray(0)
            try {
                blobs.readFrom(blob, done * MediaCrypto.SEALED, follow = true) { bytes ->
                    pending += bytes
                    while (done < chunks) {
                        val last = done == chunks - 1
                        val need = if (last) lastSize else MediaCrypto.SEALED
                        if (pending.size < need) break
                        val plain = MediaCrypto.openChunk(partKey, blob, done, last, pending.copyOfRange(0, need))
                            ?: throw BlobException(0, "A piece of the original didn't open")
                        files.appendOriginal(blob, plain)
                        pending = pending.copyOfRange(need, pending.size)
                        done++
                        setOriginal(key, OriginalState.Coming(minOf(item.size, done * MediaCrypto.CHUNK), item.size))
                    }
                }
            } catch (e: BlobException) {
                if (e.status == 404) {
                    files.deleteOriginal(blob)
                    store.putMediaSource(key.substringBefore('/'), "${item.id}$ORIGINAL_UPLOADED", "0")
                    return setOriginal(key, OriginalState.Failed)
                }
                if (e.status == 0) {
                    files.deleteOriginal(blob)
                    return setOriginal(key, OriginalState.Failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The network: again below.
            }
            // The upload paused (the server follows a minute of quiet) or the network broke: again, a few times.
            idle = if (done == before) idle + 1 else 0
            if (done < chunks) {
                if (idle >= FETCH_TRIES) return setOriginal(key, OriginalState.Failed)
                delay(RETRY_WAIT * idle)
            }
        }
        files.finishOriginal(blob)
        val path = files.originalPath(blob) ?: return setOriginal(key, OriginalState.Failed)
        setOriginal(key, OriginalState.Ready(path))
    }

    private fun setOriginal(key: String, state: OriginalState) {
        _originals.update { it + (key to state) }
    }

    /** Whether the original was uploaded for this item within the server's media_days (so it is still there). */
    private fun uploadedRecently(message: String, item: String): Boolean {
        val at = store.mediaSource(message, "$item$ORIGINAL_UPLOADED")?.toLongOrNull() ?: return false
        return at > 0 && now() - at < ORIGINAL_KEPT
    }

    /**
     * A message about a shared original in [chat], from [author]'s device [device]: a request this phone answers when it
     * shared the item (it keeps where the file is), or an answer to this phone's request.
     */
    fun took(chat: String, author: String, device: Int, content: Content) {
        content.mediaReply?.let { reply ->
            val message = store.message(reply.messageId)?.takeIf { it.user == chat } ?: return@let
            val item = message.media?.items?.firstOrNull { it.id == reply.attachmentId } ?: return@let
            val owner = if (message.outgoing) null else message.author ?: message.user
            if (owner != null && (owner != author || (item.originDevice != 0 && item.originDevice != device))) return@let
            if (reply.status == MediaReplyStatus.READY) {
                val blob = reply.upload?.blob?.takeIf { validBlob(it) } ?: return@let
                store.putMediaSource(message.id, "${item.id}$ORIGINAL_BLOB", blob)
                store.putMediaSource(message.id, "${item.id}$ORIGINAL_UPLOADED", now().toString())
            }
            answers["${message.id}/${item.id}"]?.complete(reply)
        }
        content.mediaRequest?.let { request -> answer(chat, request) }
    }

    /** Answers a request for an original this phone shared: uploads it (once in media_days), or says why it can't. */
    private fun answer(chat: String, request: MediaRequestContent) {
        val scope = scope ?: return
        if (request.part != MediaPart.ORIGINAL) return
        val message = store.message(request.messageId)?.takeIf { it.user == chat && it.outgoing } ?: return
        val item = message.media?.items?.firstOrNull { it.id == request.attachmentId } ?: return
        val source = store.mediaSource(message.id, item.id) ?: return
        val key = "${message.id}/${item.id}"
        val blob = store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")
        val reply = { status: Int, part: PartContent? -> MediaReplyContent(message.id, item.id, MediaPart.ORIGINAL, status, part) }
        if (blob != null && (uploading[key]?.isActive == true || uploadedRecently(message.id, item.id))) {
            scope.launch { runCatching { send(chat, Content(mediaReply = reply(MediaReplyStatus.READY, part(blob, item, growing = uploading[key]?.isActive == true))), PushKind.NONE, "") } }
            return
        }
        uploading[key] = scope.launch {
            val id = blobId()
            val result = try {
                uploadOriginal(chat, message, item, source, id) { send(chat, Content(mediaReply = reply(MediaReplyStatus.READY, part(id, item, growing = true))), PushKind.NONE, "") }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                blobs.delete(id)
                null
            }
            when (result) {
                MediaReplyStatus.READY -> {
                    store.putMediaSource(message.id, "${item.id}$ORIGINAL_BLOB", id)
                    store.putMediaSource(message.id, "${item.id}$ORIGINAL_UPLOADED", now().toString())
                }
                MediaReplyStatus.GONE, MediaReplyStatus.CHANGED -> runCatching { send(chat, Content(mediaReply = reply(result, null)), PushKind.NONE, "") }
                else -> Unit
            }
        }
    }

    private fun part(blob: String, item: AttachmentContent, growing: Boolean) =
        PartContent(blob = blob, size = item.size, width = item.width, height = item.height, mime = item.mime, growing = growing)

    /**
     * Reads the file at [source] again and uploads it sealed as blob [id], telling the chat ([started]) once its first
     * piece is on the server, so the asker fetches it as it grows. Its last chunk goes only once the whole file is
     * checked against the SHA-256 that was shared: a file changed since is deleted from the server. Answers a
     * [MediaReplyStatus].
     */
    private suspend fun uploadOriginal(chat: String, message: TextMessage, item: AttachmentContent, source: String, id: String, started: suspend () -> Unit): Int {
        val reader = preparer?.open(source) ?: return MediaReplyStatus.GONE
        try {
            if (reader.size >= 0 && reader.size != item.size) return MediaReplyStatus.CHANGED
            val partKey = MediaCrypto.partKey(item.key, id, MediaCrypto.Part.ORIGINAL)
            val group = Group.idOf(chat)
            val peer = if (group == null) chat else null
            val total = MediaCrypto.sealedSize(item.size)
            val chunks = MediaCrypto.chunkCount(item.size)
            var offset = 0L
            var told = false
            var index = 0L
            var piece = ByteArray(0)
            var plain = ByteArray(0)
            suspend fun flush() {
                if (piece.isEmpty()) return
                var at = 0
                var tries = 0
                while (at < piece.size) {
                    val bytes = piece.copyOfRange(at, piece.size)
                    val server = try {
                        blobs.putPiece(id, peer, group, keepMedia = true, total = total, offset = offset + at, bytes = bytes)
                    } catch (e: BlobException) {
                        if (e.status in 400..499 && e.status != 408 && e.status != 429 || ++tries >= FETCH_TRIES) throw e
                        delay(RETRY_WAIT * tries)
                        continue
                    }
                    at = (server - offset).toInt().coerceIn(0, piece.size)
                }
                offset += piece.size
                piece = ByteArray(0)
                if (!told) {
                    told = true
                    started()
                }
            }
            while (true) {
                // A full last chunk is held until the file ends: one more byte means it grew.
                val bytes = reader.read(if (plain.size == MediaCrypto.CHUNK) 1 else MediaCrypto.CHUNK - plain.size) ?: break
                plain += bytes
                if (plain.size == MediaCrypto.CHUNK && index < chunks - 1) {
                    piece += MediaCrypto.sealChunk(partKey, id, index++, false, plain)
                    plain = ByteArray(0)
                    if (piece.size >= PIECE) flush()
                }
                if (index * MediaCrypto.CHUNK + plain.size > item.size) return MediaReplyStatus.CHANGED
            }
            if (index != chunks - 1 || !reader.sha256().contentEquals(item.sha256)) {
                blobs.delete(id)
                return MediaReplyStatus.CHANGED
            }
            piece += MediaCrypto.sealChunk(partKey, id, index, true, plain)
            flush()
            return MediaReplyStatus.READY
        } finally {
            reader.close()
        }
    }

    private fun blobId(): String = random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** The bytes this phone keeps of media it sent ([sent]) or got. */
    fun kept(sent: Boolean): Long = files.size(sent)

    /** Clears what this phone keeps of media it sent ([sent]: Settings › Storage) or got; what's still wanted comes again. */
    fun clear(sent: Boolean) = files.clear(sent)

    companion object {
        const val MAX_ITEMS = 30
        const val MAX_CAPTION = 4000

        /** A preview goes up in pieces this big (its progress shown as it goes). */
        const val PREVIEW_PIECE = 64L * 1024
        const val MAX_NAME = 200

        /** How long a request for an original waits for the sharing phone's answer (it may be asleep: a push wakes it). */
        const val ANSWER_WAIT = 90_000L

        /** Tries in a row with nothing new before a fetch or an upload gives up; the wait grows with each. */
        const val FETCH_TRIES = 4
        const val RETRY_WAIT = 2_000L

        /** An original is uploaded in pieces about this big (each one PUT). */
        const val PIECE = 1 shl 20

        /** The server keeps an original 30 days (media_days); asked again a day before that. */
        const val ORIGINAL_KEPT = 29L * 24 * 60 * 60 * 1000

        /** Where an item's original blob id and its upload time are kept beside its source (MessagingStore.mediaSource). */
        private const val ORIGINAL_BLOB = "#original"
        private const val ORIGINAL_UPLOADED = "#uploaded"

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
