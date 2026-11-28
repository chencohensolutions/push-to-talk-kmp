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
import com.chencohen.ptt.messaging.CropContent
import com.chencohen.ptt.messaging.EditContent
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
import kotlinx.coroutines.channels.Channel
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

/** A picture's previews made again for a new [CropContent], and its ThumbHash of that part. */
class PreparedCrop(val previews: List<PreparedPreview>, val thumb: ByteArray = ByteArray(0))

/** Reads what the user shares (Android: MediaStore, EXIF, the JPEG XL encoder). */
interface MediaPreparer {
    /** [source] read and its previews made, one per width [widths] gives for its own width; null when it can't be read. */
    suspend fun prepare(source: String, widths: (originalWidth: Int) -> List<Int>): PreparedItem?

    /** The file at [source] opened again, to send its untouched original; null when it is gone or can't be read. */
    suspend fun open(source: String): OriginalReader? = null

    /** The size of the file at [source] without reading it (-1: not known). */
    suspend fun sizeOf(source: String): Long = -1

    /**
     * New previews of the picture at [source], made from the original's part [crop] keeps (its whole width), one per
     * width [widths] gives for that part's width; null when it can't be read.
     */
    suspend fun crop(source: String, crop: CropContent, widths: (croppedWidth: Int) -> List<Int>): PreparedCrop? = null

    /**
     * The clip of the video at [source] ([width] x [height] upright, [durationMs] long): the whole video in 1080p HEVC,
     * an MP4, telling [progress] (0 to 1) as it is made; null when it is too long for one or can't be made.
     */
    suspend fun clip(source: String, width: Int, height: Int, durationMs: Long, progress: (Float) -> Unit): PreparedPreview? = null
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

    /**
     * A video played from the server as it is read, never kept here ([MediaSharing.streamOriginal]): Stream original
     * videos is on, or it is bigger than the cache (2026-11-28, hen).
     */
    data class Streaming(val messageId: String, val itemId: String, val size: Long) : OriginalState

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
    /** Settings › Storage › Cache limit, in bytes (2026-11-28, hen: 10 GB by default, 1 GB at least). */
    private val cacheLimit: () -> Long = { DEFAULT_CACHE },
    /** Settings › Storage › Clear the cache when the phone has less than 2 GB free. */
    private val clearWhenLow: () -> Boolean = { true },
    /** Settings › Storage › Stream original videos: none is kept (one bigger than the cache limit never is). */
    private val streamVideos: () -> Boolean = { false },
    /** Whether the phone is on Wi-Fi (or another network that isn't metered): originals go up at once then. */
    private val unmetered: () -> Boolean = { true },
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

    /** Previews the server answered 404 for (removed to make room, or never there), and when each was last asked for again. */
    private val gone = mutableSetOf<String>()
    private val askedAgain = mutableMapOf<String, Long>()

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
            val media = MediaContent(id, at, text, attachments, keepDays = MediaContent.FOREVER)
            val message = TextMessage(id, chat, outgoing = true, body = fallback(media), at = at, status = MessageStatus.PENDING, media = media)
            if (keep) store.putMessage(message) else drafts[id] = message
            out += message
        }
        // A draft's previews go up while the caption is written (2026-11-28, hen: "start sending the images to server
        // before writing"); the message that names them goes only on Send, and a dropped draft's are deleted.
        // Videos' clips are made meanwhile too (2026-11-28, hen: "the encoding should happen while writing a message with
        // progress indication"): the draft shows at once, and Send (or the outbox) waits for them.
        scope?.let { scope ->
            for (m in out) draftUploads[m.id] = scope.launch {
                if (keep) return@launch makeClips(m.id)
                val previews = launch { runCatching { putPreviews(m) } }
                makeClips(m.id)
                previews.join()
                (drafts[m.id] ?: store.message(m.id))?.let { runCatching { putPreviews(it) } }
            }
        }
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
        // As this phone holds them now (a picture's composition changed while the caption was written).
        val drafts = drafts.map { this.drafts[it.id] ?: it }
        for (m in drafts) {
            val gone = m.media?.items.orEmpty().filter { it.id in removed }
            for (item in gone) for (p in item.uploaded) forget(p.blob, m)
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
        for (d in drafts) {
            val m = this.drafts[d.id] ?: d
            this.drafts.remove(m.id)
            val job = draftUploads.remove(m.id)
            val parts = m.media?.items.orEmpty().flatMap { it.uploaded }.map { it.blob }
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
        val blobsGone = items.flatMap { item -> item.parts.map { it.blob } + listOfNotNull(store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")) }
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
            for (part in item.parts) if (!named(part.blob, message.id)) files.delete(part.blob)
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
        (mediaMessages() + drafts.values).any { m -> m.id != except && m.media?.items.orEmpty().any { item -> item.parts.any { it.blob == blob } } }

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
                item.previews.isNotEmpty() && item.parts.all { files.has(it.blob) }
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

    /** Where item [itemId] of my message [messageId] was shared from (a content URI); null: not mine, or not known. */
    fun sourceOf(messageId: String, itemId: String): String? = store.mediaSource(messageId, itemId)

    /** Message [id], a draft's as this phone holds it now. */
    fun draftOrMessage(id: String): TextMessage? = drafts[id] ?: store.message(id)

    /**
     * Picture [itemId] of my message [messageId] (a draft, or sent) given the composition [crop] (null: the whole
     * picture), 2026-11-28, hen: its previews made again from the original, sealed with the item's own key. A draft's
     * go up with the rest; a sent one's go up now, then the chat is told with a quiet edit and the old previews go.
     * Answers the item as it is now; null when it couldn't be done (not mine, the original can't be read, no network).
     */
    suspend fun recrop(messageId: String, itemId: String, crop: CropContent?): AttachmentContent? {
        val preparer = preparer ?: return null
        val draft = drafts[messageId]
        val message = draft ?: store.message(messageId)?.takeIf { it.outgoing } ?: return null
        val media = message.media ?: return null
        val item = media.items.firstOrNull { it.id == itemId && it.kind == com.chencohen.ptt.messaging.MediaKind.IMAGE } ?: return null
        val wanted = crop?.clean()
        if (wanted == item.crop) return item
        val source = store.mediaSource(messageId, itemId) ?: return null
        val widths = screens(message.user)
        val made = try {
            preparer.crop(source, wanted ?: CropContent()) { PreviewSpec.widths(widths, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        if (made.previews.isEmpty()) return null
        // Each new preview with its own key, told only to this chat (2026-11-28, hen: a key per file).
        val previews = made.previews.map { sealPreview(null, it) }
        val now = item.copy(previews = previews, thumb = made.thumb.takeIf { it.isNotEmpty() } ?: item.thumb, crop = wanted)
        val updated = message.copy(media = media.copy(items = media.items.map { if (it.id == itemId) now else it }))
        val old = item.previews.map { it.blob }
        if (draft != null) {
            // A draft: the new previews go up with the rest, the old ones are deleted here and on the server.
            drafts[messageId] = updated
            // A clip still being made goes on (it is put on the draft as it is then); the upload starts again after it.
            val before = draftUploads.remove(messageId)
            for (blob in old) forget(blob, updated)
            scope?.let { scope -> draftUploads[messageId] = scope.launch { before?.join(); (drafts[messageId] ?: store.message(messageId))?.let { runCatching { putPreviews(it) } } } }
            return now
        }
        try {
            putPreviews(updated)
            send(message.user, Content(edit = EditContent(messageId, editedAt = now(), item = now)), PushKind.SYNC, "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("TalkMedia: new crop of $messageId/$itemId not sent: $e")
            for (p in previews) if (!named(p.blob, messageId)) files.delete(p.blob)
            return null
        }
        store.putMessage(updated)
        publish()
        dropOld(updated, old)
        changed(updated, itemId)
        return now
    }

    /**
     * Picture [edit] of [message] (someone's, or mine from another phone) given a new composition by its sender: the
     * same item (its id, kind and key) with new previews; the old previews go. Answers whether it was taken.
     */
    internal fun takeRecrop(message: TextMessage, edit: AttachmentContent): Boolean {
        val media = message.media ?: return false
        val item = media.items.firstOrNull { it.id == edit.id } ?: return false
        // Only the message's own author edits it (takeEdit checks); the item's key stays the one it was sent with.
        if (item.kind != com.chencohen.ptt.messaging.MediaKind.IMAGE || edit.kind != item.kind) return false
        val checked = accept(MediaContent(items = listOf(edit.copy(key = item.key))), item.originDevice)?.items?.singleOrNull() ?: return false
        if (checked.previews.isEmpty()) return false
        // Only its look changes: what it is (the original's size, hash, place, words) stays as it was sent.
        val now = item.copy(previews = checked.previews, thumb = checked.thumb.takeIf { it.isNotEmpty() } ?: item.thumb, crop = checked.crop?.clean())
        val updated = message.copy(media = media.copy(items = media.items.map { if (it.id == item.id) now else it }))
        store.putMessage(updated)
        val old = item.previews.map { it.blob }.filter { blob -> now.previews.none { it.blob == blob } }
        for (blob in old) if (!named(blob, message.id)) files.delete(blob)
        publish()
        return true
    }

    /** The previews [blobs] my sent [message] no longer names: gone from this phone and from its chat on the server. */
    private fun dropOld(message: TextMessage, blobs: List<String>) {
        for (blob in blobs) if (!named(blob, message.id)) files.delete(blob)
        val (peer, group) = conversationOf(message.user)
        scope?.launch {
            for (blob in blobs) {
                uploaded.remove("$blob@${message.user}")
                runCatching { this@MediaSharing.blobs.unshare(blob, peer, group) }
            }
        }
    }

    /**
     * [preview] sealed under a new blob id, and kept: with a key derived from item key [key], or (null) a random key of
     * its own, carried in the part.
     */
    private fun sealPreview(key: ByteArray?, p: PreparedPreview, kind: MediaCrypto.Part = MediaCrypto.Part.PREVIEW): PartContent {
        val blob = blobId()
        val own = if (key == null) random.nextBytes(MediaCrypto.KEY_BYTES) else null
        val sealed = MediaCrypto.sealAll(own ?: MediaCrypto.partKey(key!!, blob, kind), blob, p.bytes)
        files.write(blob, sealed, sent = true)
        return PartContent(blob = blob, size = p.bytes.size.toLong(), width = p.width, height = p.height, mime = p.mime, key = own ?: ByteArray(0))
    }

    /**
     * [again]'s item in message [message]: the same previews and poster (the same blobs), its own id, and not the
     * item's key (2026-11-28, Fable's review: chat A's key mustn't open what is made later for chat B): each part
     * carries its own key, so this chat gets the keys of exactly those files. Its original gets its own key too.
     */
    private fun again(message: String, again: Again): AttachmentContent {
        val itemId = newId()
        store.putMediaSource(message, itemId, again.source)
        store.putMediaSource(message, "$itemId$FROM", "${again.message.id}/${again.item.id}")
        val item = again.item
        fun keyed(part: PartContent, kind: MediaCrypto.Part) = part.copy(key = keyOf(item, part, kind), growing = false)
        return item.copy(
            id = itemId, text = "", originDevice = 0, key = ByteArray(0),
            previews = item.previews.map { keyed(it, MediaCrypto.Part.PREVIEW) },
            poster = item.poster?.let { keyed(it, MediaCrypto.Part.POSTER) },
            clip = item.clip?.let { keyed(it, MediaCrypto.Part.CLIP) },
        )
    }

    /** The key of [part] of [item]: its own, else derived from the item's. */
    private fun keyOf(item: AttachmentContent, part: PartContent, kind: MediaCrypto.Part): ByteArray =
        part.key.takeIf { it.size == MediaCrypto.KEY_BYTES } ?: MediaCrypto.partKey(item.key, part.blob, kind)

    /**
     * The key of the original [blob] of [itemId] in [messageId]: its own (a re-shared item's, kept when it went up or
     * came in its reply), else derived from the item's key. Null: neither is known.
     */
    private fun originalKey(messageId: String, item: AttachmentContent, blob: String): ByteArray? {
        store.mediaSource(messageId, "${item.id}$ORIGINAL_KEY")?.split(':')?.takeIf { it.size == 2 && it[0] == blob }?.let { (_, hex) ->
            return hexBytes(hex)?.takeIf { it.size == MediaCrypto.KEY_BYTES }
        }
        return item.key.takeIf { it.size == MediaCrypto.KEY_BYTES }?.let { MediaCrypto.partKey(it, blob, MediaCrypto.Part.ORIGINAL) }
    }

    private fun keepOriginalKey(messageId: String, itemId: String, blob: String, key: ByteArray) {
        store.putMediaSource(messageId, "$itemId$ORIGINAL_KEY", "$blob:" + key.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    }

    private fun hexBytes(hex: String): ByteArray? =
        if (hex.length % 2 != 0) null else runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()

    /** [item] with its own key; its previews sealed and kept (this phone shows them, and uploads them). */
    private fun seal(message: String, item: PreparedItem): AttachmentContent {
        val key = random.nextBytes(MediaCrypto.KEY_BYTES)
        val itemId = newId()
        val previews = item.previews.map { sealPreview(key, it) }
        if (item.source.isNotEmpty()) store.putMediaSource(message, itemId, item.source)
        return AttachmentContent(
            id = itemId, kind = item.kind, key = key, sha256 = item.sha256, size = item.size, mime = item.mime,
            name = item.name.take(MAX_NAME), width = item.width, height = item.height, durationMs = item.durationMs,
            takenAt = item.takenAt, takenOffsetMin = item.takenOffsetMin, latE7 = item.latE7, lonE7 = item.lonE7,
            hasPlace = item.hasPlace, previews = previews, thumb = item.thumb,
        )
    }

    private val _encoding = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Each video of mine whose clip is being made now, 0 to 1, by item id; gone once done. */
    val encoding: StateFlow<Map<String, Float>> = _encoding.asStateFlow()

    /**
     * Makes the clips of message [messageId]'s videos (a draft's, or one kept to send) that have none, one at a time, and
     * puts each on its item (2026-11-28, hen). A video taken out meanwhile, or a message gone already, drops its clip.
     */
    private suspend fun makeClips(messageId: String) {
        val preparer = preparer ?: return
        val message = drafts[messageId] ?: store.message(messageId) ?: return
        for (item in message.media?.items.orEmpty()) {
            if (item.kind != MediaKind.VIDEO || item.clip != null || item.key.size != MediaCrypto.KEY_BYTES) continue
            val source = store.mediaSource(messageId, item.id) ?: continue
            _encoding.update { it + (item.id to 0f) }
            val made = try {
                preparer.clip(source, item.width, item.height, item.durationMs) { f -> _encoding.update { it + (item.id to f.coerceIn(0f, 1f)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                _encoding.update { it - item.id }
            }
            made?.let { putClip(messageId, item.id, sealPreview(item.key, it, MediaCrypto.Part.CLIP)) }
        }
    }

    private fun putClip(messageId: String, itemId: String, clip: PartContent) {
        fun with(m: TextMessage) = m.copy(media = m.media?.copy(items = m.media.items.map { if (it.id == itemId) it.copy(clip = clip) else it }))
        drafts[messageId]?.let { draft ->
            if (draft.media?.items?.any { it.id == itemId } == true) drafts[messageId] = with(draft) else files.delete(clip.blob)
            return
        }
        val kept = store.message(messageId)?.takeIf { m -> m.status == MessageStatus.PENDING && m.media?.items?.any { it.id == itemId } == true }
            ?: return files.delete(clip.blob)
        store.putMessage(with(kept))
        publish()
    }

    /**
     * Uploads the previews of [message] (this phone's, not sent yet) to the server, for its chat; again after a broken
     * upload, from where it left off. Throws [BlobException] when the server refuses one (403: no longer allowed),
     * [MissingPreviewException] when a preview to upload is gone from this phone.
     */
    suspend fun upload(sending: TextMessage) {
        draftUploads.remove(sending.id)?.join()
        // As kept now: a video's clip may have been put on it meanwhile.
        val message = store.message(sending.id) ?: sending
        putPreviews(message)
        message.media?.items?.forEach { changed(message, it.id) }
        uploadAhead(message.id)
    }

    /**
     * Uploads the originals of what this phone shared in [messageId] now, on Wi-Fi (2026-11-28, hen: "right away, on
     * Wi-Fi"), so they open for anyone at once, even with this phone off or the file gone from it, for as long as the
     * server keeps them. The chat is told as when someone asks (a READY reply). Off Wi-Fi: later ([uploadPending]).
     */
    fun uploadAhead(messageId: String) {
        val scope = scope ?: return
        if (!unmetered()) return
        val message = store.message(messageId)?.takeIf { it.outgoing && !it.deleted } ?: return
        for (item in message.media?.items.orEmpty()) {
            val key = "${message.id}/${item.id}"
            val source = store.mediaSource(message.id, item.id) ?: continue
            if (store.mediaSource(message.id, "${item.id}$SOURCE_GONE") != null) continue
            if (uploading[key]?.isActive == true || uploadedRecently(message.id, item.id)) continue
            // Shared again from an earlier message: its original is already there (shared to this chat on request).
            if (store.mediaSource(message.id, "${item.id}$FROM") != null) continue
            uploading[key] = scope.launch {
                val id = blobId()
                val result = try {
                    uploadOriginal(message.user, message, item, source, id) {}
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    runCatching { blobs.delete(id) }
                    null
                }
                when (result) {
                    MediaReplyStatus.READY -> {
                        store.putMediaSource(message.id, "${item.id}$ORIGINAL_BLOB", id)
                        store.putMediaSource(message.id, "${item.id}$ORIGINAL_UPLOADED", now().toString())
                        // Told once the message itself is on the server (the reply names it).
                        val waited = withTimeoutOrNull(AHEAD_WAIT) {
                            while (store.message(message.id)?.status == MessageStatus.PENDING) delay(RETRY_WAIT)
                        }
                        if (waited != null) {
                            val reply = MediaReplyContent(message.id, item.id, MediaPart.ORIGINAL, MediaReplyStatus.READY, part(message.id, id, item, growing = false))
                            runCatching { send(message.user, Content(mediaReply = reply), PushKind.NONE, "") }
                        }
                    }
                    MediaReplyStatus.GONE, MediaReplyStatus.CHANGED -> sourceGone(message, item.id)
                    else -> Unit
                }
            }
        }
    }

    /** What this phone shared and hasn't uploaded the originals of yet goes up now (on Wi-Fi; at start, and on Wi-Fi). */
    fun uploadPending() {
        if (!unmetered()) return
        for (message in mediaMessages().filter { it.outgoing && it.status != MessageStatus.PENDING && refetchable(it) }) uploadAhead(message.id)
    }

    private suspend fun putPreviews(message: TextMessage) {
        val media = message.media ?: return
        val group = Group.idOf(message.user)
        val total = media.items.sumOf { item -> item.uploaded.sumOf { MediaCrypto.sealedSize(it.size) } }.coerceAtLeast(1)
        var done = 0L
        _uploads.update { it + (message.id to 0f) }
        try {
            for (item in media.items) {
                val itemTotal = item.uploaded.sumOf { MediaCrypto.sealedSize(it.size) }
                var itemDone = 0L
                val reused = store.mediaSource(message.id, "${item.id}$FROM") != null
                for (part in item.uploaded) {
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
                    putSealed(part.blob, peer, group, media.keepDays, sealed) { offset ->
                        transfer(item.id, itemDone + offset, itemTotal)
                        _uploads.update { it + (message.id to ((done + offset).toFloat() / total).coerceAtMost(1f)) }
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
            val parts = item.parts
            // A key for the item, or one of its own for every part (shared again from another chat).
            (item.key.size == MediaCrypto.KEY_BYTES || parts.all { it.key.size == MediaCrypto.KEY_BYTES }) &&
                item.id.isNotEmpty() && item.previews.size <= PreviewSpec.MAX_PREVIEWS + 1 &&
                parts.all { validBlob(it.blob) && it.size >= 0 && (it.key.isEmpty() || it.key.size == MediaCrypto.KEY_BYTES) }
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
        val got = part(item, part, MediaCrypto.Part.PREVIEW, sent = message.outgoing)
            ?: if (part.blob in gone && !message.outgoing && message.id !in drafts && askAgain(message, item, part, MediaPart.PREVIEW)) {
                part(item, part, MediaCrypto.Part.PREVIEW, sent = false)
            } else null
        return got.also {
            if (it != null && !kept) {
                checkAll(messageId)
                if (message.id !in drafts) changed(message, item.id)
            }
        }
    }

    /**
     * The clip of video [itemId] of message [messageId] (its 1080p HEVC preview, an MP4), opened: from what this phone
     * keeps, else fetched from the server (asked of the sharer again when the server let it go) and kept. Null when
     * there is none or it can't be had now.
     */
    suspend fun clip(messageId: String, itemId: String): ByteArray? {
        val message = store.message(messageId) ?: drafts[messageId] ?: return null
        val item = message.media?.items?.firstOrNull { it.id == itemId } ?: return null
        val part = item.clip ?: return null
        return part(item, part, MediaCrypto.Part.CLIP, sent = message.outgoing)
            ?: if (part.blob in gone && !message.outgoing && message.id !in drafts && askAgain(message, item, part, MediaPart.CLIP)) {
                part(item, part, MediaCrypto.Part.CLIP, sent = false)
            } else null
    }

    /** Whether video [itemId]'s clip is kept here (it plays at once). */
    fun hasClip(messageId: String, itemId: String): Boolean {
        val item = store.message(messageId)?.media?.items?.firstOrNull { it.id == itemId } ?: return false
        return item.clip?.let { files.has(it.blob) } == true
    }

    /** Fetches message [messageId]'s pictures' previews now (its videos' too, and their clips, when [videos]); those that fail come later. */
    suspend fun fetchPreviews(messageId: String, videos: Boolean) {
        val items = store.message(messageId)?.media?.items.orEmpty()
        for (item in items) {
            if (item.kind == com.chencohen.ptt.messaging.MediaKind.VIDEO && !videos) continue
            if (item.previews.isNotEmpty()) preview(messageId, item.id)
            if (item.clip != null) clip(messageId, item.id)
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
        val key = keyOf(item, part, kind)
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
            sealed?.let { MediaCrypto.openAll(key, part.blob, it) }?.also {
                files.write(part.blob, sealed, sent)
                if (!sent) trimSoon()
            }
        } catch (e: CancellationException) {
            waiter.complete(null)
            throw e
        } catch (e: BlobException) {
            if (e.status == 404) gone += part.blob
            null
        } catch (_: Exception) {
            null
        } finally {
            _transfers.update { it - item.id }
        }
        waiter.complete(plain)
        fetching.withLock { inFlight.remove(part.blob) }
        return plain
    }

    /**
     * Asks the phone that shared [item] to put [part] on the server again (the server removed it to make room, oldest
     * fetched first, or lost it), and waits for its answer: true once it is there. At most once an hour for a part.
     */
    private suspend fun askAgain(message: TextMessage, item: AttachmentContent, part: PartContent, which: Int): Boolean {
        val at = now()
        val asked = fetching.withLock {
            val last = askedAgain[part.blob]
            (last == null || at - last >= ASK_AGAIN_EVERY).also { if (it) askedAgain[part.blob] = at }
        }
        if (!asked) return false
        val key = "${message.id}/${item.id}$PREVIEW_ANSWER$which"
        val answer = CompletableDeferred<MediaReplyContent>()
        answers[key] = answer
        val owner = message.author ?: message.user
        runCatching {
            send(message.user, Content(mediaRequest = MediaRequestContent(message.id, item.id, which, width = part.width)), PushKind.MEDIA, owner)
        }.onFailure { answers.remove(key); return false }
        val reply = withTimeoutOrNull(ANSWER_WAIT) { answer.await() }
        answers.remove(key)
        return reply?.status == MediaReplyStatus.READY && reply.upload?.blob == part.blob
    }

    /** Uploads [sealed] as blob [blob]: in pieces, so the progress moves smoothly; whole when the server can't take pieces. */
    private suspend fun putSealed(blob: String, peer: String?, group: String?, keepDays: Int, sealed: ByteArray, progress: (Long) -> Unit = {}) {
        var offset = 0L
        var stuck = 0
        try {
            while (offset < sealed.size) {
                val end = minOf(sealed.size.toLong(), offset + PREVIEW_PIECE).toInt()
                val got = blobs.putMediaPiece(blob, peer, group, keepDays, sealed.size.toLong(), offset, sealed.copyOfRange(offset.toInt(), end))
                stuck = if (got <= offset) stuck + 1 else 0
                if (stuck > 2) throw BlobException(409, "The server keeps another part")
                offset = got
                progress(offset)
            }
        } catch (e: BlobException) {
            if (e.status != 0) throw e
            blobs.put(blob, peer, group, sealed)
        }
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
        gone -= id
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
        store.mediaSource(messageId, "$itemId$ORIGINAL_BLOB")?.let { blob ->
            files.originalPath(blob)?.let {
                files.touchOriginal(blob)
                return setOriginal(key, OriginalState.Ready(it))
            }
        }
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
        if (streams(item)) {
            store.putMediaSource(message.id, "${item.id}$ORIGINAL_BLOB", id)
            files.deleteOriginal(id)
            return setOriginal(key, OriginalState.Streaming(message.id, item.id, item.size))
        }
        download(id, item, key)
    }

    /** Whether [item]'s original is played from the server instead of fetched here. */
    private fun streams(item: AttachmentContent): Boolean =
        item.kind == MediaKind.VIDEO && (streamVideos() || item.size > cacheLimit())

    /**
     * The original of [itemId] of [messageId] read from the server from byte [position], opened chunk by chunk as it
     * comes and never written here (the player's; a seek opens it again where it goes). A file still being uploaded
     * by the phone that shared it is read as it grows. Null: not known here.
     */
    fun streamOriginal(messageId: String, itemId: String, position: Long): OriginalStream? {
        val scope = scope ?: return null
        val item = store.message(messageId)?.media?.items?.firstOrNull { it.id == itemId } ?: return null
        val blob = store.mediaSource(messageId, "$itemId$ORIGINAL_BLOB")?.takeIf { validBlob(it) } ?: return null
        if (position < 0 || position > item.size) return null
        val partKey = originalKey(messageId, item, blob) ?: return null
        val chunks = MediaCrypto.chunkCount(item.size)
        val lastSize = (MediaCrypto.sealedSize(item.size) - (chunks - 1) * MediaCrypto.SEALED).toInt()
        val first = minOf(position / MediaCrypto.CHUNK, chunks - 1)
        val pieces = Channel<ByteArray>(STREAM_AHEAD)
        val job = scope.launch {
            var done = first
            var skip = (position - first * MediaCrypto.CHUNK).toInt()
            var idle = 0
            try {
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
                                pending = pending.copyOfRange(need, pending.size)
                                done++
                                val out = if (skip > 0) plain.copyOfRange(minOf(skip, plain.size), plain.size) else plain
                                skip = 0
                                if (out.isNotEmpty()) pieces.send(out)
                            }
                        }
                    } catch (e: BlobException) {
                        if (e.status == 404 || e.status == 0) throw e
                    }
                    idle = if (done == before) idle + 1 else 0
                    if (done < chunks) {
                        if (idle >= FETCH_TRIES) throw BlobException(0, "The original stopped coming")
                        delay(RETRY_WAIT * idle)
                    }
                }
                pieces.close()
            } catch (e: CancellationException) {
                pieces.close()
                throw e
            } catch (e: Exception) {
                pieces.close(e)
            }
        }
        return OriginalStream(item.size - position, pieces, job)
    }

    /** The original [blob] of [item] fetched as it grows, opened chunk by chunk into this phone's cache. */
    private suspend fun download(blob: String, item: AttachmentContent, key: String) {
        val partKey = originalKey(key.substringBefore('/'), item, blob) ?: return setOriginal(key, OriginalState.Failed)
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
        // The untouched original the sharer's message named, or nothing (each chunk is authenticated, but anyone in the
        // chat holds the item's key; 2026-11-28, Fable's review).
        if (item.sha256.isNotEmpty() && files.originalSha256(blob)?.contentEquals(item.sha256) == false) {
            files.deleteOriginal(blob)
            return setOriginal(key, OriginalState.Changed)
        }
        trimSoon()
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
        return at > 0 && now() - at < keptFor(store.message(message))
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
            if (reply.part == MediaPart.PREVIEW || reply.part == MediaPart.POSTER || reply.part == MediaPart.CLIP) {
                answers["${message.id}/${item.id}$PREVIEW_ANSWER${reply.part}"]?.complete(reply)
                return@let
            }
            if (reply.status == MediaReplyStatus.READY) {
                val blob = reply.upload?.blob?.takeIf { validBlob(it) } ?: return@let
                // The original's own key (an item shared again from another chat has no item key here).
                reply.upload.key.takeIf { it.size == MediaCrypto.KEY_BYTES }?.let { keepOriginalKey(message.id, item.id, blob, it) }
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
        if (request.part == MediaPart.PREVIEW || request.part == MediaPart.POSTER || request.part == MediaPart.CLIP) return answerPreview(chat, author, request)
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
                    val firstItem = store.message(first[0])?.media?.items?.firstOrNull { it.id == first[1] }
                    val theirKey = firstItem?.let { originalKey(first[0], it, theirs) }
                    if (theirKey != null && blobs.share(theirs, peer, grp)) {
                        // This chat gets the key of that one file, not the first chat's item key.
                        keepOriginalKey(message.id, item.id, theirs, theirKey)
                        store.putMediaSource(message.id, "${item.id}$ORIGINAL_BLOB", theirs)
                        store.putMediaSource(message.id, "${item.id}$ORIGINAL_UPLOADED", now().toString())
                        log(RequestResult.ON_SERVER)
                        runCatching { send(chat, Content(mediaReply = reply(MediaReplyStatus.READY, part(message.id, theirs, item, growing = false))), PushKind.NONE, "") }
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
            scope.launch { runCatching { send(chat, Content(mediaReply = reply(MediaReplyStatus.READY, part(message.id, blob, item, growing = uploading[key]?.isActive == true))), PushKind.NONE, "") } }
            return
        }
        uploading[key] = scope.launch {
            val id = blobId()
            val result = try {
                uploadOriginal(chat, message, item, source, id) { send(chat, Content(mediaReply = reply(MediaReplyStatus.READY, part(message.id, id, item, growing = true))), PushKind.NONE, "") }
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

    /**
     * Puts a preview (or poster) of an item this phone shared in [chat] on the server again, for [author] whose fetch
     * found it gone: the same sealed blob this phone keeps, so the key in the message still opens it. Only for a message
     * this phone sent in that very chat, not deleted, to someone still in it (as for originals; previews aren't logged).
     */
    private fun answerPreview(chat: String, author: String, request: MediaRequestContent) {
        val scope = scope ?: return
        val message = store.message(request.messageId)?.takeIf { it.user == chat && it.outgoing && !it.deleted } ?: return
        val group = Group.idOf(chat)
        if (group != null && store.group(group)?.isReader(author) != true) return
        val item = message.media?.items?.firstOrNull { it.id == request.attachmentId } ?: return
        val part = (if (request.part == MediaPart.POSTER) item.poster else if (request.part == MediaPart.CLIP) item.clip else item.previews.firstOrNull { it.width == request.width }
            ?: PreviewSpec.pick(item.previews, request.width.takeIf { it > 0 } ?: PreviewSpec.DEFAULT_WIDTH)) ?: return
        val reply = { status: Int, upload: PartContent? -> MediaReplyContent(message.id, item.id, request.part, status, upload) }
        if (uploading["${part.blob}$PREVIEW_ANSWER"]?.isActive == true) return
        uploading["${part.blob}$PREVIEW_ANSWER"] = scope.launch {
            val (peer, grp) = conversationOf(chat)
            val status = try {
                val sealed = files.read(part.blob)
                when {
                    sealed == null -> MediaReplyStatus.GONE
                    blobs.share(part.blob, peer, grp) -> MediaReplyStatus.READY // Still there for another chat.
                    else -> MediaReplyStatus.READY.also { putSealed(part.blob, peer, grp, message.media?.keepDays ?: 0, sealed) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch // Not now (offline, server full): the asker times out and asks again later.
            }
            runCatching { send(chat, Content(mediaReply = reply(status, part.takeIf { status == MediaReplyStatus.READY })), PushKind.NONE, "") }
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

    /** The original [blob] of [item] in [messageId], as a reply names it: with its own key when the item has none here. */
    private fun part(messageId: String, blob: String, item: AttachmentContent, growing: Boolean) =
        PartContent(
            blob = blob, size = item.size, width = item.width, height = item.height, mime = item.mime, growing = growing,
            key = if (item.key.isEmpty()) originalKey(messageId, item, blob) ?: ByteArray(0) else ByteArray(0),
        )

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
            // A re-shared item has no item key in this chat: its original gets a key of its own, kept and told.
            val partKey = if (item.key.size == MediaCrypto.KEY_BYTES) MediaCrypto.partKey(item.key, id, MediaCrypto.Part.ORIGINAL)
            else random.nextBytes(MediaCrypto.KEY_BYTES).also { keepOriginalKey(message.id, item.id, id, it) }
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
                        blobs.putMediaPiece(id, peer, group, message.media?.keepDays ?: 0, total = total, offset = offset + at, bytes = bytes)
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
                for (part in item.parts) {
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
    private fun refetchable(message: TextMessage): Boolean = now() - message.at < keptFor(message)

    /** How long the server keeps what [message] shared (forever, from 2026-11-28; else its days, a day less to be safe). */
    private fun keptFor(message: TextMessage?): Long {
        val days = message?.media?.keepDays?.takeIf { it != 0 } ?: return ORIGINAL_KEPT
        if (days < 0) return Long.MAX_VALUE
        return (days - 1L).coerceAtLeast(1) * DAY
    }

    /**
     * Clears what this phone keeps of [chat]'s shared media that can come again: the previews it got that are still on
     * the server, and its copies of originals that are somewhere else too. What it shared stays (the others may ask for
     * it), and so do the only copies and the previews that are no longer on the server.
     */
    fun clearChat(chat: String): ChatStorage {
        for (message in store.messagesWith(chat).filter { it.media != null && !it.deleted }) {
            var touched = false
            for (item in message.media?.items.orEmpty()) {
                if (!message.outgoing && refetchable(message)) for (part in item.parts) {
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

    /** What the cache takes now. */
    fun cacheBytes(): Long = files.cached().sumOf { it.bytes }

    private var trimming: Job? = null

    /** The cache grew: kept within its limit a moment later (once for many parts that come together). */
    private fun trimSoon() {
        val scope = scope ?: return
        if (trimming?.isActive == true) return
        trimming = scope.launch {
            delay(TRIM_WAIT)
            runCatching { trimCache() }
        }
    }

    /**
     * Keeps the cache within Settings › Storage's limit (2026-11-28, hen): what was read longest ago goes first. With
     * Clear when storage is low on and the phone under [LOW_FREE] free, all of it that can go goes. Only what can come
     * again goes (the previews a received message still has on the server, copies of originals that are elsewhere too,
     * parts no message names); nothing read in the last [IN_USE] (what is on screen or playing). Answers the bytes
     * removed.
     */
    fun trimCache(): Long {
        val parts = files.cached()
        var total = parts.sumOf { it.bytes }
        val free = files.freeBytes()
        val low = clearWhenLow() && free in 0 until LOW_FREE
        val limit = cacheLimit().coerceAtLeast(MIN_CACHE)
        if (!low && total <= limit) return 0
        val owners = mutableMapOf<String, Pair<TextMessage, String>>()
        val lasting = mutableSetOf<String>()
        for (message in mediaMessages()) for (item in message.media?.items.orEmpty()) {
            for (part in item.parts) {
                owners[part.blob] = message to item.id
                if (message.outgoing || !refetchable(message)) lasting += part.blob
            }
            store.mediaSource(message.id, "${item.id}$ORIGINAL_BLOB")?.let { owners["#$it"] = message to item.id }
        }
        val at = now()
        var removed = 0L
        for (part in parts.sortedBy { it.lastRead }) {
            if (!low && total <= limit) break
            if (at - part.lastRead < IN_USE) continue
            val owner = owners[if (part.original) "#${part.name}" else part.name]
            if (part.original) {
                files.deleteOriginal(part.name)
                owner?.let { (m, i) -> _originals.update { it - "${m.id}/$i" } }
            } else {
                if (part.name in lasting) continue
                files.delete(part.name)
            }
            total -= part.bytes
            removed += part.bytes
            owner?.let { (m, i) -> changed(m, i) }
        }
        return removed
    }

    companion object {
        const val MAX_ITEMS = 30
        const val MAX_CAPTION = 4000

        /** A preview goes up in pieces this big (its progress shown as it goes). */
        const val PREVIEW_PIECE = 64L * 1024
        const val MAX_NAME = 200

        /** How long a request for an original waits for the sharing phone's answer (it may be asleep: a push wakes it). */
        const val ANSWER_WAIT = 90_000L
        const val ASK_AGAIN_EVERY = 60 * 60_000L
        const val PREVIEW_ANSWER = "#preview"

        /** Tries in a row with nothing new before a fetch or an upload gives up; the wait grows with each. */
        const val FETCH_TRIES = 4
        const val RETRY_WAIT = 2_000L

        /** An original is uploaded in pieces about this big (each one PUT). */
        const val PIECE = 1 shl 20

        /** The server keeps an original 30 days (media_days); asked again a day before that. */
        const val ORIGINAL_KEPT = 29L * 24 * 60 * 60 * 1000
        const val DAY = 24L * 60 * 60 * 1000

        /** How long an original uploaded at once waits for its message to be on the server before telling the chat. */
        const val AHEAD_WAIT = 10L * 60 * 1000

        /** Where an item's original blob id and its upload time are kept beside its source (MessagingStore.mediaSource). */
        private const val ORIGINAL_BLOB = "#original"
        private const val ORIGINAL_UPLOADED = "#uploaded"

        /** An original's own key, "blob:hex" (a re-shared item's: its item key isn't in that chat). */
        private const val ORIGINAL_KEY = "#okey"

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

        /** Settings › Storage › Cache limit: 10 GB unless changed, 1 GB at least (2026-11-28, hen). */
        const val GB = 1L shl 30
        const val DEFAULT_CACHE = 10 * GB
        const val MIN_CACHE = GB

        /** Under this much free on the phone, Clear when storage is low clears the cache. */
        const val LOW_FREE = 2 * GB

        /** A part read this recently is in use (on screen or playing): the cache limit leaves it. */
        const val IN_USE = 10L * 60 * 1000

        /** The cache is trimmed this long after it grew. */
        const val TRIM_WAIT = 5_000L

        /** Pieces of a streamed original read ahead of the player. */
        const val STREAM_AHEAD = 32

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
