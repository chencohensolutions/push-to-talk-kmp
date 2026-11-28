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
import com.chencohen.ptt.messaging.HeldOriginal
import com.chencohen.ptt.messaging.HoldingContent
import com.chencohen.ptt.messaging.HoldingsContent
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

    /** The size of the file at [source] without reading it (-1: not known). */
    suspend fun sizeOf(source: String): Long = -1
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

    /**
     * Mine, and this phone can no longer read where it was shared from (2026-11-28: the gallery's pass to a picked
     * picture ends when the app restarts, unless the app may read all photos).
     */
    data object Unreadable : OriginalState

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
    /** This phone's copy of the original is the only one known, so it is kept out of the cache (Clear leaves it). */
    val onlyCopy: Boolean = false,
    /** The original is known to be somewhere other than a copy on this phone (the sharer's file, a copy, a Journal). */
    val originalElsewhere: Boolean = false,
    /** Mine, and the file it was shared from can't be read here now (deleted, changed, or no access to it). */
    val sourceUnreadable: Boolean = false,
    /** Mine: every request for its original this phone got, oldest first. */
    val requests: List<OriginalRequest> = emptyList(),
)

/**
 * A request for an original this phone got (2026-11-28, hen: logged, refused ones too): when, in which [chat], from
 * [user]'s phone [device], for item [itemId] of message [messageId], and what came of it ([result], one of
 * [RequestResult]).
 */
data class OriginalRequest(val at: Long, val chat: String, val user: String, val device: Int, val messageId: String, val itemId: String, val result: Int)

/** `OriginalRequest.result`. */
object RequestResult {
    /** Uploaded for them. */
    const val SENT = 1
    /** Already on the server (sent before, within media_days): told where. */
    const val ON_SERVER = 2
    /** The file is gone from where it was shared, or can't be read here. */
    const val GONE = 3
    /** The file changed since it was shared. */
    const val CHANGED = 4
    /** The upload broke. */
    const val FAILED = 5
    /** Refused: no such message here (never, or deleted). */
    const val UNKNOWN = 6
    /** Refused: the message is in another chat. */
    const val OTHER_CHAT = 7
    /** Refused: the message isn't this phone's user's. */
    const val NOT_MINE = 8
    /** Refused: they aren't in the group any more. */
    const val NOT_MEMBER = 9

    fun refused(result: Int) = result >= UNKNOWN
}

/**
 * What this phone keeps of a chat's shared media (bytes and how many): the previews it sent ([sent]) and got
 * ([got]; [gotLasting] of those are no longer on the server, so a clear keeps them), copies of originals ([copies])
 * and those kept as the only copy known ([only]); [shared] of its own items are read from where they were shared, and
 * [lost] items' originals are known to be nowhere.
 */
data class ChatStorage(
    val sent: Long, val sentCount: Int,
    val got: Long, val gotCount: Int,
    val copies: Long, val copiesCount: Int,
    val only: Long, val onlyCount: Int,
    val gotLasting: Long,
    val shared: Int,
    val lost: Int,
)

data class KeptPart(val poster: Boolean, val width: Int, val height: Int, val bytes: Long, val mime: String, val here: Boolean, val shown: Boolean)

/**
 * [user]'s phones: [original] the one who shared it (the file is on their phone); else the preview made for their
 * screen ([previewWidth]), and [has] whether it reached them (true, false, or null: not known here).
 */
data class PeerHolds(
    val user: String,
    val original: Boolean,
    val previewWidth: Int,
    val has: Boolean?,
    /** What [user]'s phone [device] itself said it holds, at [toldAt]: [held] one of HeldOriginal (-1: it hasn't said). */
    val device: Int = 0,
    val held: Int = -1,
    val keptWidth: Int = 0,
    val journal: Boolean = false,
    val toldAt: Long = 0,
)

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
        val mine = sharedHere()
        val items: List<Any> = sources.mapNotNull { source ->
            try {
                // Shared before (in this chat or another): sent again as the same item, nothing made or uploaded again
                // (2026-11-28, hen: "share images between groups without doubling the storage").
                earlier(source, mine)?.let { (m, item) -> Again(m, item, source) } ?: preparer.prepare(source) { PreviewSpec.widths(widths, it) }
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
            val attachments = chunk.map { item -> if (item is Again) again(id, item) else seal(id, item as PreparedItem) }
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
            for (item in gone) for (p in item.previews) forget(p.blob, m)
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
            // A preview another message names too stays (the same picture in another chat).
            for (blob in parts) if (!named(blob, m.id)) files.delete(blob)
            // What already went up is deleted from the server, once the upload has stopped.
            scope?.launch {
                job?.join()
                val (peer, group) = conversationOf(m.user)
                for (blob in parts) if (uploaded.remove("$blob@${m.user}")) runCatching { blobs.unshare(blob, peer, group) }
            }
        }
    }

    /**
     * [message] (this phone's) deleted for everyone (2026-11-28, hen: "if i delete image i sent from chat, the peer no
     * longer have access to that image"): its previews, poster and uploaded original go from the server and this
     * phone, and a later request for the original is refused (the message is gone).
     */
    fun revoke(message: TextMessage) {
        drop(listOf(message))
        val items = message.media?.items.orEmpty()
        val blobsGone = items.flatMap { item -> (item.previews + listOfNotNull(item.poster)).map { it.blob } + listOfNotNull(store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")) }
        for (item in items) {
            uploading.remove("${message.id}/${item.id}")?.cancel()
            store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")?.let { files.deleteOriginal(it) }
        }
        for (blob in blobsGone) if (!named(blob, message.id)) files.delete(blob)
        // Out of this chat only: the server keeps it while another chat it was shared to has it.
        val (peer, group) = conversationOf(message.user)
        scope?.launch { for (blob in blobsGone) runCatching { blobs.unshare(blob, peer, group) } }
        _originals.update { all -> all.filterKeys { !it.startsWith("${message.id}/") } }
    }

    /** [message] (someone else's) deleted for everyone by its sender: what this phone kept of it goes, the only copies too. */
    fun forgetTaken(message: TextMessage) {
        for (item in message.media?.items.orEmpty()) {
            opening.remove("${message.id}/${item.id}")?.cancel()
            for (part in item.previews + listOfNotNull(item.poster)) if (!named(part.blob, message.id)) files.delete(part.blob)
            store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")?.let { files.deleteOriginal(it) }
        }
        _originals.update { all -> all.filterKeys { !it.startsWith("${message.id}/") } }
    }

    /** A preview taken out of draft [message]: gone from this phone (unless another names it), and from the chat on the server. */
    private fun forget(blob: String, message: TextMessage) {
        if (!named(blob, message.id)) files.delete(blob)
        val (peer, group) = conversationOf(message.user)
        scope?.launch {
            for (job in draftUploads.values.toList()) job.join()
            if (uploaded.remove("$blob@${message.user}")) runCatching { blobs.unshare(blob, peer, group) }
        }
    }

    /** The drafts' preview uploads, started on [prepare], by message id. */
    private val draftUploads = mutableMapOf<String, Job>()

    /** The previews this phone has put on the server, as "blob@chat" (an upload after a draft's skips them). */
    private val uploaded = mutableSetOf<String>()

    private fun conversationOf(chat: String): Pair<String?, String?> = Group.idOf(chat).let { group -> (if (group == null) chat else null) to group }

    /** Whether a message other than [except] names [blob] (a picture shared to several chats: one sealed file). */
    private fun named(blob: String, except: String): Boolean =
        (mediaMessages() + drafts.values).any { m -> m.id != except && m.media?.items.orEmpty().any { item -> (item.previews + listOfNotNull(item.poster)).any { it.blob == blob } } }

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

    /** An item this phone shared before ([message]'s [item]), from [source], shared again. */
    private class Again(val message: TextMessage, val item: AttachmentContent, val source: String)

    /** The items this phone shared and still has: their file readable when last checked, their previews kept. */
    private fun sharedHere(): List<Pair<TextMessage, AttachmentContent>> =
        mediaMessages().filter { it.outgoing }.flatMap { m -> m.media?.items.orEmpty().map { m to it } }.filter { (m, item) ->
            store.mediaSource(m.id, item.id) != null && store.mediaSource(m.id, "${item.id}$SOURCE_GONE") == null &&
                item.previews.isNotEmpty() && (item.previews + listOfNotNull(item.poster)).all { files.has(it.blob) }
        }

    /** The item this phone shared before that is the file at [source]: the same source, else the same size and SHA-256. */
    private suspend fun earlier(source: String, mine: List<Pair<TextMessage, AttachmentContent>>): Pair<TextMessage, AttachmentContent>? {
        if (mine.isEmpty()) return null
        mine.lastOrNull { (m, item) -> store.mediaSource(m.id, item.id) == source }?.let { return it }
        val preparer = preparer ?: return null
        val size = preparer.sizeOf(source)
        val same = mine.filter { it.second.size == size && size > 0 }
        if (same.isEmpty()) return null
        val reader = preparer.open(source) ?: return null
        val sha = try {
            while (reader.read(MediaCrypto.CHUNK) != null) Unit
            reader.sha256()
        } finally {
            reader.close()
        }
        return same.lastOrNull { it.second.sha256.contentEquals(sha) }
    }

    /** [again]'s item in message [message]: the same key, previews and poster (the same blobs), its own id. */
    private fun again(message: String, again: Again): AttachmentContent {
        val itemId = newId()
        store.putMediaSource(message, itemId, again.source)
        store.putMediaSource(message, "$itemId$FROM", "${again.message.id}/${again.item.id}")
        return again.item.copy(id = itemId, text = "", originDevice = 0)
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
        message.media?.items?.forEach { changed(message, it.id) }
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
                val reused = store.mediaSource(message.id, "${item.id}$FROM") != null
                for (part in item.previews) {
                    val mark = "${part.blob}@${message.user}"
                    // Shared before: made readable in this chat too, nothing uploaded (gone from the server: uploaded).
                    if (reused && mark !in uploaded && blobs.share(part.blob, if (group == null) message.user else null, group)) uploaded += mark
                    if (mark !in uploaded) transfer(item.id, itemDone, itemTotal)
                    if (mark in uploaded) {
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
                    uploaded += mark
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
        // Only a preview that just came changes what this phone holds: one read from here as the chat scrolls tells nobody.
        val kept = files.has(part.blob)
        return part(item, part, MediaCrypto.Part.PREVIEW, sent = message.outgoing).also {
            if (it != null && !kept) {
                checkAll(messageId)
                if (message.id !in drafts) changed(message, item.id)
            }
        }
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
        store.mediaSource(messageId, itemId)?.let { source ->
            // Read where it was shared from only once it is known to be readable (a lost pass fails quietly otherwise).
            opening[key] = scope.launch {
                if (readable(source)) {
                    sourceBack(message, itemId)
                    setOriginal(key, OriginalState.Ready(source, local = true))
                } else {
                    sourceGone(message, itemId)
                    setOriginal(key, OriginalState.Unreadable)
                }
            }
            return
        }
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
        val shared = store.mediaSource(messageId, itemId)
        val unreadable = shared != null && store.mediaSource(messageId, "$itemId$SOURCE_GONE") != null
        val source = shared?.takeIf { !unreadable }
        val fetched = store.mediaSource(messageId, "$itemId$ORIGINAL_BLOB")?.let { files.originalPath(it) }
        return MediaKeeping(
            parts = parts, originalPath = source ?: fetched, originalIsSource = source != null,
            originalBytes = item.size, originalWidth = item.width, originalHeight = item.height, originalMime = item.mime,
            originalUploadedAt = store.mediaSource(messageId, "$itemId$ORIGINAL_UPLOADED")?.toLongOrNull()?.takeIf { it > 0 } ?: 0,
            peers = emptyList(),
            onlyCopy = fetched != null && store.mediaSource(messageId, "$itemId$ORIGINAL_BLOB")?.let { files.keptOriginal(it) } == true,
            originalElsewhere = originalElsewhere(message, item),
            sourceUnreadable = unreadable,
            requests = if (message.outgoing) originalRequests(message.user).filter { it.messageId == messageId && it.itemId == itemId } else emptyList(),
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
        reconsider(store.message(key.substringBefore('/')) ?: return setOriginal(key, OriginalState.Ready(path)), item)
        setOriginal(key, OriginalState.Ready(files.originalPath(blob) ?: path))
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
            // The sharer's answer says whether its file is there (older apps send no holdings).
            if (reply.part == MediaPart.ORIGINAL && reply.status in listOf(MediaReplyStatus.READY, MediaReplyStatus.GONE, MediaReplyStatus.CHANGED)) {
                val had = holdersOf(message.id, item.id).firstOrNull { it.user == author && it.device == device }
                val original = if (reply.status == MediaReplyStatus.READY) HeldOriginal.SOURCE else HeldOriginal.NONE
                putHolder(message.id, item.id, Holder(author, device, original, had?.previewWidth ?: 0, had?.journal ?: false, now()))
                reconsider(message, item)
            }
            answers["${message.id}/${item.id}"]?.complete(reply)
        }
        content.mediaRequest?.let { request -> answer(chat, author, device, request) }
        content.holdings?.let { holdings -> tookHoldings(chat, author, device, holdings) }
    }

    /**
     * Answers a request from [author]'s phone [device] for an original this phone shared: uploads it (once in
     * media_days), or says why it can't. Only an item of a message this phone sent in the very chat the request came
     * from, not deleted, to someone still in it; the file is the one kept when it was shared (nothing in a request names
     * a file). Every request is logged ([originalRequests]), refused ones too (2026-11-28, hen: "every original image
     * that requested will log somewhere; how can we be sure there isn't a way to download all my images").
     */
    private fun answer(chat: String, author: String, device: Int, request: MediaRequestContent) {
        val scope = scope ?: return
        if (request.part != MediaPart.ORIGINAL) return
        fun log(result: Int) = logRequest(OriginalRequest(now(), chat, author, device, request.messageId.take(64), request.attachmentId.take(64), result))
        val message = store.message(request.messageId) ?: return log(RequestResult.UNKNOWN)
        if (message.user != chat) return log(RequestResult.OTHER_CHAT)
        if (!message.outgoing) return log(RequestResult.NOT_MINE)
        if (message.deleted) return log(RequestResult.UNKNOWN)
        val group = Group.idOf(chat)
        if (group != null && store.group(group)?.isReader(author) != true) return log(RequestResult.NOT_MEMBER)
        val item = message.media?.items?.firstOrNull { it.id == request.attachmentId } ?: return log(RequestResult.UNKNOWN)
        val source = store.mediaSource(message.id, item.id) ?: return log(RequestResult.GONE)
        val key = "${message.id}/${item.id}"
        val blob = store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")
        val reply = { status: Int, part: PartContent? -> MediaReplyContent(message.id, item.id, MediaPart.ORIGINAL, status, part) }
        // Shared again: the original already on the server for the first sharing is made readable here too.
        val first = store.mediaSource(message.id, "${item.id}$FROM")?.split('/')
        if ((blob == null || !uploadedRecently(message.id, item.id)) && first?.size == 2 && uploadedRecently(first[0], first[1])) {
            val theirs = store.mediaSource(first[0], "${first[1]}$ORIGINAL_BLOB")
            if (theirs != null && uploading["${first[0]}/${first[1]}"]?.isActive != true) {
                scope.launch {
                    val (peer, grp) = conversationOf(chat)
                    if (blobs.share(theirs, peer, grp)) {
                        store.putMediaSource(message.id, "${item.id}$ORIGINAL_BLOB", theirs)
                        store.putMediaSource(message.id, "${item.id}$ORIGINAL_UPLOADED", now().toString())
                        log(RequestResult.ON_SERVER)
                        runCatching { send(chat, Content(mediaReply = reply(MediaReplyStatus.READY, part(theirs, item, growing = false))), PushKind.NONE, "") }
                    } else {
                        store.removeMediaSource(message.id, "${item.id}$FROM")
                        answer(chat, author, device, request)
                    }
                }
                return
            }
        }
        if (blob != null && (uploading[key]?.isActive == true || uploadedRecently(message.id, item.id))) {
            log(RequestResult.ON_SERVER)
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
            log(
                when (result) {
                    MediaReplyStatus.READY -> RequestResult.SENT
                    MediaReplyStatus.GONE -> RequestResult.GONE
                    MediaReplyStatus.CHANGED -> RequestResult.CHANGED
                    else -> RequestResult.FAILED
                },
            )
            when (result) {
                MediaReplyStatus.READY -> {
                    store.putMediaSource(message.id, "${item.id}$ORIGINAL_BLOB", id)
                    store.putMediaSource(message.id, "${item.id}$ORIGINAL_UPLOADED", now().toString())
                }
                MediaReplyStatus.GONE, MediaReplyStatus.CHANGED -> {
                    sourceGone(message, item.id)
                    runCatching { send(chat, Content(mediaReply = reply(result, null)), PushKind.NONE, "") }
                }
                else -> Unit
            }
        }
    }

    private fun logRequest(entry: OriginalRequest) {
        val list = (originalRequests() + entry).takeLast(MAX_REQUESTS)
        store.putMediaSource(REQUESTS, "", list.joinToString("\n") { r ->
            listOf(r.at, r.chat, r.user, r.device, r.messageId, r.itemId, r.result).joinToString("|") { it.toString().replace("|", "").replace("\n", "") }
        })
    }

    /** Every request for an original this phone got, oldest first ([chat]'s, or all), at most [MAX_REQUESTS]. */
    fun originalRequests(chat: String? = null): List<OriginalRequest> =
        store.mediaSource(REQUESTS, "")?.lineSequence()?.mapNotNull { line ->
            val f = line.split('|')
            if (f.size != 7) return@mapNotNull null
            OriginalRequest(f[0].toLongOrNull() ?: 0, f[1], f[2], f[3].toIntOrNull() ?: 0, f[4], f[5], f[6].toIntOrNull() ?: 0)
        }?.filter { chat == null || it.chat == chat }?.toList().orEmpty()

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
    fun clear(sent: Boolean) {
        val before = mediaMessages().flatMap { m -> m.media?.items.orEmpty().map { m to it } }.filter { (m, i) -> held(m, i).let { it.original != HeldOriginal.NONE || it.previewWidth > 0 } }
        files.clear(sent)
        // What this phone no longer holds goes to the chats (originals kept as the only copy stay).
        for ((m, i) in before) changed(m, i.id)
    }

    // Who holds what (2026-11-28, hen: "monitor who have what in a smart way, so the app could clear its cache but make
    // sure all the original images exist somewhere; if my image is in the journal server it counts as existed"). Each
    // phone tells the chat what it holds of each item when that changes (HoldingsContent, no push), and keeps what the
    // others told it. A copy of an original fetched here that is the only one known (the sharer's file is gone and no
    // one else has it, nor a Journal server) is moved out of the cache, so neither the system nor Clear deletes it; once
    // another holder is known it goes back to the cache.

    /** What [user]'s phone [device] said it holds of an item ([original] one of [HeldOriginal]), at [at]. */
    data class Holder(val user: String, val device: Int, val original: Int, val previewWidth: Int, val journal: Boolean, val at: Long)

    fun holdersOf(messageId: String, itemId: String): List<Holder> =
        store.mediaSource(messageId, "$itemId$HOLDERS")?.lineSequence()?.mapNotNull { line ->
            val f = line.split('|')
            if (f.size != 6) return@mapNotNull null
            Holder(f[0], f[1].toIntOrNull() ?: return@mapNotNull null, f[2].toIntOrNull() ?: 0, f[3].toIntOrNull() ?: 0, f[4] == "1", f[5].toLongOrNull() ?: 0)
        }?.toList().orEmpty()

    private fun putHolder(messageId: String, itemId: String, holder: Holder) {
        if ('|' in holder.user || '\n' in holder.user || holder.user.isEmpty()) return
        val list = holdersOf(messageId, itemId).filterNot { it.user == holder.user && it.device == holder.device } + holder
        store.putMediaSource(messageId, "$itemId$HOLDERS", list.takeLast(MAX_HOLDERS).joinToString("\n") {
            "${it.user}|${it.device}|${it.original}|${it.previewWidth}|${if (it.journal) 1 else 0}|${it.at}"
        })
    }

    /** What this phone holds of [item] of [message] now. */
    internal fun held(message: TextMessage, item: AttachmentContent): HoldingContent {
        val source = store.mediaSource(message.id, item.id)
        val gone = store.mediaSource(message.id, "${item.id}$SOURCE_GONE") != null
        val blob = store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")
        val original = when {
            source != null && !gone -> HeldOriginal.SOURCE
            blob != null && files.keptOriginal(blob) -> HeldOriginal.KEPT
            blob != null && files.originalPath(blob) != null -> HeldOriginal.COPY
            else -> HeldOriginal.NONE
        }
        val width = item.previews.filter { files.has(it.blob) }.maxOfOrNull { it.width } ?: 0
        return HoldingContent(message.id, item.id, original, width)
    }

    /** Whether the original of [item] is somewhere other than a copy on this phone, as far as this phone knows. */
    fun originalElsewhere(message: TextMessage, item: AttachmentContent): Boolean {
        val source = store.mediaSource(message.id, item.id)
        if (source != null && store.mediaSource(message.id, "${item.id}$SOURCE_GONE") == null) return true
        return holdersOf(message.id, item.id).any { it.original != HeldOriginal.NONE || it.journal }
    }

    /** Keeps this phone's copy of [item]'s original out of the cache while it is the only one known; else lets it go back. */
    private fun reconsider(message: TextMessage, item: AttachmentContent) {
        val blob = store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB") ?: return
        if (files.originalPath(blob) == null) return
        val keep = !originalElsewhere(message, item)
        if (files.keptOriginal(blob) != keep) {
            files.keepOriginal(blob, keep)
            changed(message, item.id)
        } else if (!files.keptOriginal(blob)) changed(message, item.id)
    }

    private suspend fun readable(source: String): Boolean {
        val reader = runCatching { preparer?.open(source) }.getOrNull() ?: return false
        runCatching { reader.close() }
        return true
    }

    /** The file [itemId] of [message] was shared from can be read again (access to all photos given): told. */
    private fun sourceBack(message: TextMessage, itemId: String) {
        if (store.mediaSource(message.id, "$itemId$SOURCE_GONE") == null) return
        store.removeMediaSource(message.id, "$itemId$SOURCE_GONE")
        changed(message, itemId)
    }

    /** The file [itemId] of [message] was shared from is gone (deleted, changed since, or no longer readable here). */
    private fun sourceGone(message: TextMessage, itemId: String) {
        if (store.mediaSource(message.id, itemId) == null || store.mediaSource(message.id, "$itemId$SOURCE_GONE") != null) return
        store.putMediaSource(message.id, "$itemId$SOURCE_GONE", now().toString())
        changed(message, itemId)
    }

    private val reporting = Mutex()
    private val pendingReports = mutableMapOf<String, MutableSet<Pair<String, String>>>()
    private var reportWaiting = false
    private val lastReported = mutableMapOf<Pair<String, String>, HoldingContent>()

    /** What this phone holds of [itemId] of [message] may have changed: told to the chat a moment later, with the rest. */
    private fun changed(message: TextMessage, itemId: String) {
        val scope = scope ?: return
        scope.launch {
            val start = reporting.withLock {
                pendingReports.getOrPut(message.user) { mutableSetOf() } += message.id to itemId
                (!reportWaiting).also { reportWaiting = true }
            }
            if (!start) return@launch
            delay(REPORT_WAIT)
            val batch = reporting.withLock {
                reportWaiting = false
                pendingReports.toMap().also { pendingReports.clear() }
            }
            for ((chat, items) in batch) tell(chat, items, ask = false, force = false)
        }
    }

    /** Tells [chat] what this phone holds of [items] (only what changed since it last said, unless [force]); [ask]: and asks the others. */
    private suspend fun tell(chat: String, items: Collection<Pair<String, String>>, ask: Boolean, force: Boolean) {
        val now = items.mapNotNull { (m, i) ->
            val message = store.message(m)?.takeIf { it.user == chat } ?: return@mapNotNull null
            val item = message.media?.items?.firstOrNull { it.id == i } ?: return@mapNotNull null
            held(message, item)
        }.filter { h -> force || reporting.withLock { lastReported[h.messageId to h.attachmentId] != h } }
        if (now.isEmpty() && !ask) return
        for (part in now.chunked(HOLDINGS_PART).ifEmpty { listOf(emptyList()) }) {
            runCatching { send(chat, Content(holdings = HoldingsContent(part, ask)), PushKind.NONE, "") }.onSuccess {
                reporting.withLock { part.forEach { lastReported[it.messageId to it.attachmentId] = it } }
            }
        }
    }

    /** Asks [chat]'s phones what they hold of [itemId] of [messageId] (the viewer's Info), telling them this phone's. */
    fun askHoldings(messageId: String, itemId: String) {
        val scope = scope ?: return
        val message = store.message(messageId) ?: return
        scope.launch { tell(message.user, listOf(messageId to itemId), ask = true, force = true) }
    }

    private fun tookHoldings(chat: String, author: String, device: Int, holdings: HoldingsContent) {
        for (h in holdings.items.take(HOLDINGS_PART)) {
            val message = store.message(h.messageId)?.takeIf { it.user == chat } ?: continue
            val item = message.media?.items?.firstOrNull { it.id == h.attachmentId } ?: continue
            val original = h.original.takeIf { it in HeldOriginal.NONE..HeldOriginal.KEPT } ?: HeldOriginal.NONE
            putHolder(message.id, item.id, Holder(author, device, original, h.previewWidth.coerceIn(0, 100_000), h.journal, now()))
            reconsider(message, item)
        }
        if (!holdings.ask) return
        val scope = scope ?: return
        val asked = holdings.items.map { it.messageId to it.attachmentId }.ifEmpty {
            store.messagesWith(chat).flatMap { m -> m.media?.items.orEmpty().map { m.id to it.id } }
        }
        scope.launch { tell(chat, asked, ask = false, force = true) }
    }

    /**
     * Checks that the files this phone shared are still where they were shared from (at start, and daily): one deleted
     * or changed is told to the chat, so a copy elsewhere is kept as the only one.
     */
    suspend fun checkSources() {
        val preparer = preparer ?: return
        for (message in mediaMessages().filter { it.outgoing }) {
            for (item in message.media?.items.orEmpty()) {
                val source = store.mediaSource(message.id, item.id) ?: continue
                val reader = runCatching { preparer.open(source) }.getOrNull()
                val there = reader != null && (reader.size < 0 || reader.size == item.size)
                runCatching { reader?.close() }
                if (there) sourceBack(message, item.id) else sourceGone(message, item.id)
            }
        }
    }

    /** What this phone keeps of [chat]'s shared media (its settings' Storage). */
    fun chatStorage(chat: String): ChatStorage {
        var mine = 0L; var mineCount = 0
        var theirs = 0L; var theirsCount = 0
        var copies = 0L; var copiesCount = 0
        var only = 0L; var onlyCount = 0
        var lasting = 0L
        var shared = 0; var lost = 0
        for (message in store.messagesWith(chat).filter { it.media != null && !it.deleted }) {
            for (item in message.media?.items.orEmpty()) {
                for (part in item.previews + listOfNotNull(item.poster)) {
                    val size = files.sizeOf(part.blob).takeIf { it > 0 } ?: continue
                    if (message.outgoing) { mine += size; mineCount++ } else {
                        theirs += size; theirsCount++
                        if (!refetchable(message)) lasting += size
                    }
                }
                if (store.mediaSource(message.id, item.id) != null && store.mediaSource(message.id, "${item.id}$SOURCE_GONE") == null) shared++
                val blob = store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")
                val size = blob?.let { files.originalSize(it) } ?: 0
                if (size > 0 && files.keptOriginal(blob!!)) { only += size; onlyCount++ } else if (size > 0) { copies += size; copiesCount++ }
                if (size == 0L && !originalElsewhere(message, item)) lost++
            }
        }
        return ChatStorage(mine, mineCount, theirs, theirsCount, copies, copiesCount, only, onlyCount, lasting, shared, lost)
    }

    /** A received message's previews are still on the server (fetched again after a clear). */
    private fun refetchable(message: TextMessage): Boolean = now() - message.at < ORIGINAL_KEPT

    /**
     * Clears what this phone keeps of [chat]'s shared media that can come again: the previews it got that are still on
     * the server, and its copies of originals that are somewhere else too. What it shared stays (the others may ask for
     * it), and so do the only copies and the previews that are no longer on the server.
     */
    fun clearChat(chat: String): ChatStorage {
        for (message in store.messagesWith(chat).filter { it.media != null && !it.deleted }) {
            var touched = false
            for (item in message.media?.items.orEmpty()) {
                if (!message.outgoing && refetchable(message)) for (part in item.previews + listOfNotNull(item.poster)) {
                    if (files.has(part.blob)) { files.delete(part.blob); touched = true }
                }
                val blob = store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")
                if (blob != null && files.originalPath(blob) != null && !files.keptOriginal(blob) && originalElsewhere(message, item)) {
                    files.deleteOriginal(blob)
                    _originals.update { it - "${message.id}/${item.id}" }
                    touched = true
                }
                if (touched) changed(message, item.id)
            }
        }
        return chatStorage(chat)
    }

    private fun mediaMessages(): List<TextMessage> = store.conversations().values.flatten().filter { it.media != null && !it.deleted }

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

        /** An item shared again: the "message/item" it was first shared as (its original can be shared from there). */
        private const val FROM = "#from"

        /** Where the log of requests for originals is kept (beside the sources, under a key no message has). */
        private const val REQUESTS = "#requests"
        const val MAX_REQUESTS = 2000

        /** Who told this phone what they hold of an item, and since when this phone's own shared file is gone. */
        private const val HOLDERS = "#holders"
        private const val SOURCE_GONE = "#gone"

        /** At most this many holders kept per item (a group's phones). */
        private const val MAX_HOLDERS = 64

        /** Holdings that change together go in one message this long after the first change. */
        const val REPORT_WAIT = 3_000L

        /** Items per holdings message (well inside an envelope). */
        const val HOLDINGS_PART = 200

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
