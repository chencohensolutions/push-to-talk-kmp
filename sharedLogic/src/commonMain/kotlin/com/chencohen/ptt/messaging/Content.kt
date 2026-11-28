@file:OptIn(ExperimentalSerializationApi::class)

package com.chencohen.ptt.messaging

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

// What travels inside the ciphertext of an envelope (docs/design.md, "Inside an envelope"): one protobuf message,
// padded to a multiple of 160 bytes before it is encrypted, so the server can't tell a pair request from a text by
// its size. Only the phones read it; the server's ptt.proto doesn't know it. Version 1: new kinds get new field numbers
// and an old phone skips what it doesn't know.

/** One message between phones. Exactly one of the kinds is set; [destination] only on copies to the sender's own devices. */
@Serializable
internal data class Content(
    @ProtoNumber(1) val pairRequest: PairRequestContent? = null,
    @ProtoNumber(2) val text: TextContent? = null,
    /** To the sender's own other phones only: what that phone now knows of a contact. */
    @ProtoNumber(3) val contact: ContactSyncContent? = null,
    /** Delivered or read: the ids of texts this phone got. */
    @ProtoNumber(4) val receipt: ReceiptContent? = null,
    /** The sender is typing (or stopped). Sent with PUSH_NONE: never wakes a phone. */
    @ProtoNumber(5) val typing: TypingContent? = null,
    /** From the group's owner's devices: the group as it is now, signed by the owner's identity key. */
    @ProtoNumber(6) val groupChange: GroupChangeContent? = null,
    /** A device's sender key for a group, to members the owner's devices named only. */
    @ProtoNumber(7) val senderKey: SenderKeyContent? = null,
    /** Inside a group message (a SENDER_KEY_MESSAGE): the group it is for, which must be the envelope's. */
    @ProtoNumber(8) val group: String = "",
    /** The "clip start" message of a talk burst, or a voice message: the clip's id and key (phase 5a). */
    @ProtoNumber(9) val clip: ClipContent? = null,
    /** The sender device's key for its live audio in the conversation's talk room (phase 5b). */
    @ProtoNumber(10) val talkKey: TalkKeyContent? = null,
    /** A video call's signalling: invite, answer, hang-up (phase 6). */
    @ProtoNumber(11) val call: CallContent? = null,
    /** On a copy for the sender's own other devices: the user the message went to. */
    @ProtoNumber(15) val destination: String = "",
    /** What the sending phone can play (ContentCodec.CAPS_*), on every message, end to end; 0: not said. */
    @ProtoNumber(16) val caps: Int = 0,
    /** "Here": the sender has this chat's Talk screen or reply bubble open now (true) or closed it (false). */
    @ProtoNumber(17) val here: HereContent? = null,
    /** The sender's own profile for a paired contact (their email, which they chose to share). */
    @ProtoNumber(18) val profile: ProfileContent? = null,
    /** A sent text edited (2026-11-24, hen), or being edited live. Older apps skip the field. */
    @ProtoNumber(19) val edit: EditContent? = null,
    /** The author deleted their message for everyone (2026-11-24, hen). Older apps skip the field. */
    @ProtoNumber(20) val delete: DeleteContent? = null,
    /** The sender has this chat open and in view, or left it ([ViewingContent]); live only. Older apps skip it. */
    @ProtoNumber(21) val viewing: ViewingContent? = null,
    /** A reaction with one of the sender's faces to a message (2026-11-24, hen). Older apps skip it. */
    @ProtoNumber(22) val reaction: ReactionContent? = null,
    /**
     * The sender wants the floor of this chat while someone holds it (2026-11-24, hen: "both live"): they are live,
     * waiting their turn; false when they stopped. Live only. Older apps skip it.
     */
    @ProtoNumber(23) val liveWaiting: ViewingContent? = null,
    /**
     * The sender opened this chat ([ViewingContent.open]; again every few minutes while it stays open) or left it
     * (2026-11-24, hen): the phone joins the chat's talk room quietly, so a push that follows starts at once. Pushed
     * as PUSH_WARM when open. Older apps skip it.
     */
    @ProtoNumber(24) val warm: ViewingContent? = null,
    /**
     * Both live became a call in the talk room itself (2026-11-25, hen: "instant"): both microphones (and cameras, if
     * live) stay open there until either ends it ([ViewingContent.open] false). Older apps skip it.
     */
    @ProtoNumber(25) val roomCall: ViewingContent? = null,
    /**
     * What became of the talker's push here (2026-11-25, hen: "who can hear you"): heard, or saved and why
     * (HeardStatus). To the talker only, live only. Older apps skip it.
     */
    @ProtoNumber(26) val talkHeard: TalkHeardContent? = null,
    /** The sender's phone's app version (2026-11-27, hen: "see what version the contact uses"). Older apps send none. */
    @ProtoNumber(27) val about: AboutContent? = null,
    /**
     * The chat colours the sender chose for our one-to-one chat (2026-11-27, hen: "when choosing colors on contact
     * screen sync it with other person phone so same person will be as the same color on both phone"), in the
     * sender's words: its [ColorsContent.mine] is the sender's bubbles, so it is "their messages" here. Older apps skip it.
     */
    @ProtoNumber(28) val colors: ColorsContent? = null,
    /**
     * Shared pictures, videos or files (2026-11-28, hen; docs/design.md, "Sharing media"): each with its own key, its
     * previews on the server, and its original kept on the sending phone until someone asks. Sent with a [text]
     * fallback ("Photo") that older apps show; newer apps show [media] and ignore that text.
     */
    @ProtoNumber(29) val media: MediaContent? = null,
    /** Asks the phone that shared an item for a part of it (its original, or a preview at this phone's width). */
    @ProtoNumber(30) val mediaRequest: MediaRequestContent? = null,
    /** The sharing phone's answer to [mediaRequest], to the whole chat: where the part is now, or why it can't come. */
    @ProtoNumber(31) val mediaReply: MediaReplyContent? = null,
    /**
     * A new install asks a contact's phone for their chat's history (2026-11-28, hen: "make it resilient by requesting
     * history from the peer"). Older apps skip it.
     */
    @ProtoNumber(32) val historyRequest: HistoryRequestContent? = null,
    /** One part of a chat's history, sent back for a [historyRequest]. */
    @ProtoNumber(33) val history: HistoryContent? = null,
    /**
     * What the sending phone holds of shared items (2026-11-28, hen: "monitor who have what in a smart way, so the app
     * could clear its cache but make sure all the original images exist somewhere"), to the chat; or asks for that.
     * Older apps skip it.
     */
    @ProtoNumber(34) val holdings: HoldingsContent? = null,
)

/** One message of shared items: its [id] (the message's), when it was sent, a caption, and up to 30 items. */
@Serializable
data class MediaContent(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val sentAt: Long = 0,
    @ProtoNumber(3) val caption: String = "",
    @ProtoNumber(4) val items: List<AttachmentContent> = emptyList(),
    /**
     * Sent before its caption (2026-11-28, hen: "first send the pictures, then write the text"): it comes in without a
     * notification; the caption, an [EditContent] with `notify`, notifies. Older apps skip it.
     */
    @ProtoNumber(5) val quiet: Boolean = false,
    /**
     * How long the server keeps its previews and original: [FOREVER] (2026-11-28, hen: "keep forever everything"; the
     * server's 1 TB lets the least fetched go), or 0 from older apps: 30 days. A phone that got it lets its previews go
     * from the cache while the server has them.
     */
    @ProtoNumber(6) val keepDays: Int = 0,
) {
    companion object {
        const val FOREVER = -1
    }
}

/**
 * One shared item. [key] (32 random bytes) encrypts its parts (MediaCrypto); [sha256] is of the untouched original,
 * which the receiver checks, and is also the item's id on the sender's Journal server (the next phase). Sizes are the
 * upright picture's. The place and time taken come from the original's EXIF; each phone names the place itself, in
 * its own language, from [latE7]/[lonE7].
 */
@Serializable
data class AttachmentContent(
    @ProtoNumber(1) val id: String = "",
    /** One of [MediaKind]. */
    @ProtoNumber(2) val kind: Int = 0,
    @ProtoNumber(3) val key: ByteArray = ByteArray(0),
    @ProtoNumber(4) val sha256: ByteArray = ByteArray(0),
    @ProtoNumber(5) val size: Long = 0,
    @ProtoNumber(6) val mime: String = "",
    @ProtoNumber(7) val name: String = "",
    @ProtoNumber(8) val width: Int = 0,
    @ProtoNumber(9) val height: Int = 0,
    @ProtoNumber(10) val durationMs: Long = 0,
    /** When it was taken (ms since 1970, UTC) and that place's offset from UTC in minutes; 0: not known. */
    @ProtoNumber(11) val takenAt: Long = 0,
    @ProtoNumber(12) val takenOffsetMin: Int = 0,
    /** Where it was taken, in degrees times 10^7; [hasPlace] says whether they are set. */
    @ProtoNumber(13) val latE7: Int = 0,
    @ProtoNumber(14) val lonE7: Int = 0,
    @ProtoNumber(15) val hasPlace: Boolean = false,
    /** Its previews: an image's, one per receiving screen width; a video's, one 1080p HEVC. */
    @ProtoNumber(16) val previews: List<PartContent> = emptyList(),
    /** A video's first frame, a JPEG XL. */
    @ProtoNumber(17) val poster: PartContent? = null,
    /** The sender's device that holds the original: only it answers a [MediaRequestContent]. */
    @ProtoNumber(18) val originDevice: Int = 0,
    /** A ThumbHash, about 25 bytes, drawn while the preview downloads. */
    @ProtoNumber(19) val thumb: ByteArray = ByteArray(0),
    /** Words written above this picture in the message (2026-11-28, hen: "add text above any image"). */
    @ProtoNumber(20) val text: String = "",
    /**
     * A picture's composition (2026-11-28, hen: "pick the composition of an image"): its previews show only this part
     * of the original, which [width] and [height] still describe; opening it shows the whole original. Null: all of it.
     */
    @ProtoNumber(21) val crop: CropContent? = null,
) {
    /** The height of what the chat shows of it: the original's, less what [crop] trims. */
    val shownHeight: Int get() = crop?.let { (height.toLong() * it.kept / CropContent.WHOLE).toInt().coerceAtLeast(1) } ?: height

    override fun equals(other: Any?): Boolean = other is AttachmentContent && id == other.id && kind == other.kind &&
        key.contentEquals(other.key) && sha256.contentEquals(other.sha256) && size == other.size && mime == other.mime &&
        name == other.name && width == other.width && height == other.height && durationMs == other.durationMs &&
        takenAt == other.takenAt && takenOffsetMin == other.takenOffsetMin && latE7 == other.latE7 &&
        lonE7 == other.lonE7 && hasPlace == other.hasPlace && previews == other.previews && poster == other.poster &&
        originDevice == other.originDevice && thumb.contentEquals(other.thumb) && text == other.text &&
        crop == other.crop
    override fun hashCode(): Int = id.hashCode()
}

/**
 * The part of a picture its previews show, and its point of interest (2026-11-28, hen: "by default its the full width
 * of the image and the top and bottom border can be trimmed"), in 1/[WHOLE] of the original: [top] and [bottom] trimmed
 * off it, and the point at [focusX], [focusY] (of the whole original), set when [hasFocus]. Where a strip shows the
 * picture smaller than its crop (a grid, a reply), it is cut around that point.
 */
@Serializable
data class CropContent(
    @ProtoNumber(1) val top: Int = 0,
    @ProtoNumber(2) val bottom: Int = 0,
    @ProtoNumber(3) val focusX: Int = WHOLE / 2,
    @ProtoNumber(4) val focusY: Int = WHOLE / 2,
    @ProtoNumber(5) val hasFocus: Boolean = false,
) {
    /** How much of the height is kept, in 1/[WHOLE]. */
    val kept: Int get() = WHOLE - top - bottom

    /** Within bounds (at least [MIN_KEPT] kept, the point inside the picture); null when it changes nothing. */
    fun clean(): CropContent? {
        val t = top.coerceIn(0, WHOLE - MIN_KEPT)
        val b = bottom.coerceIn(0, WHOLE - MIN_KEPT - t)
        val c = CropContent(t, b, focusX.coerceIn(0, WHOLE), focusY.coerceIn(0, WHOLE), hasFocus)
        return c.takeIf { it.top > 0 || it.bottom > 0 || it.hasFocus }?.let { if (it.hasFocus) it else it.copy(focusX = WHOLE / 2, focusY = WHOLE / 2) }
    }

    /** The point of interest within the kept part, 0 to 1 across and down (the middle when none is set). */
    val focusInCrop: Pair<Float, Float>
        get() = if (!hasFocus) 0.5f to 0.5f
        else (focusX.toFloat() / WHOLE).coerceIn(0f, 1f) to ((focusY - top).toFloat() / kept.coerceAtLeast(1)).coerceIn(0f, 1f)

    companion object {
        const val WHOLE = 10_000
        /** A tenth of the height stays, at least. */
        const val MIN_KEPT = 1_000
    }
}

/** `AttachmentContent.kind`. */
object MediaKind {
    const val IMAGE = 1
    const val VIDEO = 2
    const val FILE = 3
}

/**
 * One uploaded part of an item: its blob on the server, its plaintext size, its picture's size, and for a video its
 * codec and HDR. [growing]: still being uploaded when the message went (a video's preview, read with follow).
 */
@Serializable
data class PartContent(
    @ProtoNumber(1) val blob: String = "",
    @ProtoNumber(2) val size: Long = 0,
    @ProtoNumber(3) val width: Int = 0,
    @ProtoNumber(4) val height: Int = 0,
    @ProtoNumber(5) val mime: String = "",
    /** One of [MediaCodec]; 0 for a picture. */
    @ProtoNumber(6) val codec: Int = 0,
    /** One of [MediaHdr]. */
    @ProtoNumber(7) val hdr: Int = 0,
    @ProtoNumber(8) val growing: Boolean = false,
)

/** `PartContent.codec`. */
object MediaCodec {
    const val HEVC = 1
    const val H264 = 2
}

/** `PartContent.hdr`. */
object MediaHdr {
    const val SDR = 0
    const val HLG = 1
    const val HDR10 = 2
}

/** Which part of an item ([MediaRequestContent.part], [MediaReplyContent.part]). */
object MediaPart {
    const val PREVIEW = 1
    const val ORIGINAL = 2
    const val POSTER = 3
}

/** A request for [part] of item [attachmentId] of message [messageId]; [width] for a preview, [now] from Get it now. */
@Serializable
internal data class MediaRequestContent(
    @ProtoNumber(1) val messageId: String = "",
    @ProtoNumber(2) val attachmentId: String = "",
    @ProtoNumber(3) val part: Int = 0,
    @ProtoNumber(4) val width: Int = 0,
    @ProtoNumber(5) val now: Boolean = false,
)

/** The answer: [status] (one of [MediaReplyStatus]) and, when ready or uploading, the part. */
@Serializable
internal data class MediaReplyContent(
    @ProtoNumber(1) val messageId: String = "",
    @ProtoNumber(2) val attachmentId: String = "",
    @ProtoNumber(3) val part: Int = 0,
    @ProtoNumber(4) val status: Int = 0,
    @ProtoNumber(5) val upload: PartContent? = null,
)

/**
 * What a phone holds of some shared items ([items]), sent to the chat when it changes; [ask]: the others answer with
 * what they hold of the same items (all their items in the chat when [items] is empty).
 */
@Serializable
internal data class HoldingsContent(
    @ProtoNumber(1) val items: List<HoldingContent> = emptyList(),
    @ProtoNumber(2) val ask: Boolean = false,
)

/** What the sending phone holds of item [attachmentId] of message [messageId]. */
@Serializable
internal data class HoldingContent(
    @ProtoNumber(1) val messageId: String = "",
    @ProtoNumber(2) val attachmentId: String = "",
    /** One of [HeldOriginal]. */
    @ProtoNumber(3) val original: Int = 0,
    /** The width of the preview it keeps (0: none kept). */
    @ProtoNumber(4) val previewWidth: Int = 0,
    /** Its Journal server has the original (the last phase; nothing sets it yet). */
    @ProtoNumber(5) val journal: Boolean = false,
)

/** `HoldingContent.original`. */
object HeldOriginal {
    const val NONE = 0
    /** The shared file itself, where it was shared from (the sharing phone). */
    const val SOURCE = 1
    /** A copy fetched when it was opened. */
    const val COPY = 2
    /** A copy kept because it is the only one known: it stays when the cache is cleared. */
    const val KEPT = 3
}

/** `MediaReplyContent.status`. */
object MediaReplyStatus {
    /** Uploaded, or uploading (the part's growing). */
    const val READY = 1
    /** Over 50 MB and the sender's phone waits for Wi-Fi (its setting); Get it now asks again with now. */
    const val WAITING_WIFI = 2
    /** The sender's phone no longer has the file. */
    const val GONE = 3
    /** The file there isn't the one that was shared any more (edited). */
    const val CHANGED = 4
}

/** Chat colours as the sender chose them: swatch indexes, -1 "like everyone", and when (ms) they chose. */
@Serializable
internal data class ColorsContent(
    @ProtoNumber(1) val mine: Int = -1,
    @ProtoNumber(2) val theirs: Int = -1,
    @ProtoNumber(3) val at: Long = 0,
)

/**
 * About the sending phone: its app's version ("1.4.28"), and its screen in physical pixels, short side first, so a
 * phone sharing a picture makes a preview exactly as wide as this screen (2026-11-28, hen). 0: not said (older apps).
 */
@Serializable
internal data class AboutContent(
    @ProtoNumber(1) val version: String = "",
    @ProtoNumber(2) val screenWidthPx: Int = 0,
    @ProtoNumber(3) val screenHeightPx: Int = 0,
)

/** A push's fate at one listener: [burstId] and one of HeardStatus. */
@Serializable
internal data class TalkHeardContent(
    @ProtoNumber(1) val burstId: String = "",
    @ProtoNumber(2) val status: Int = 0,
)

/** One expression of the sender's face ([code]: Expression code), a small WebP with alpha. */
@Serializable
internal data class FaceImage(@ProtoNumber(1) val code: Int = 0, @ProtoNumber(2) val image: ByteArray = ByteArray(0)) {
    override fun equals(other: Any?): Boolean = other is FaceImage && code == other.code && image.contentEquals(other.image)
    override fun hashCode(): Int = code
}

/** The sender reacted to message [id] with their face [face] (Expression code + 1); 0 takes the reaction back. */
@Serializable
internal data class ReactionContent(@ProtoNumber(1) val id: String = "", @ProtoNumber(2) val face: Int = 0)

/**
 * What a user shares with their paired contacts about themselves: their email ("" none; Settings › Contacts), and
 * their picture (2026-11-24, hen). In a group, only the picture counts.
 */
@Serializable
internal data class ProfileContent(
    @ProtoNumber(1) val email: String = "",
    /** How the app should refer to the sender (Gender); older apps send none (not set). */
    @ProtoNumber(2) val gender: Int = 0,
    /** Which picture the sender has: "" not said (older apps), [NO_PICTURE] none, else its id. */
    @ProtoNumber(3) val pictureId: String = "",
    /** The picture itself (a small WebP with a clear background), only to whoever doesn't have [pictureId] yet; else empty. */
    @ProtoNumber(4) val picture: ByteArray = ByteArray(0),
    /**
     * The receiver was told of a picture it doesn't have (it was sent when this phone still had an app without
     * pictures): send it again. Only this flag counts in such a profile.
     */
    @ProtoNumber(5) val needPicture: Boolean = false,
    /** The face in each expression (2026-11-24, hen: the faces as emoticons), with [picture]'s bytes only. */
    @ProtoNumber(6) val faces: List<FaceImage> = emptyList(),
    /** How many expressions the sender's picture has (said with or without the bytes): a phone with fewer asks again. */
    @ProtoNumber(7) val faceCount: Int = 0,
) {
    override fun equals(other: Any?): Boolean = other is ProfileContent && email == other.email && gender == other.gender &&
        pictureId == other.pictureId && picture.contentEquals(other.picture) && needPicture == other.needPicture

    override fun hashCode(): Int = email.hashCode() * 31 + pictureId.hashCode()

    companion object {
        const val NO_PICTURE = "-"
    }
}

/** The sender's Talk screen or reply bubble for this chat is open ([open]); said again every 20 s while it is. */
/**
 * An edit of the author's own text [id] (2026-11-24, hen). [stage] FINAL: its text is [body] now, edited at [editedAt]
 * (sent like any text, kept and pushed silently). LIVE: being edited, [body] as it stands (empty and [hidden] when the
 * author's Live typing is off: only that it is being edited); live only, never kept. CANCEL: the live edit ended
 * unchanged.
 */
@Serializable
internal data class EditContent(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val body: String = "",
    @ProtoNumber(3) val editedAt: Long = 0,
    @ProtoNumber(4) val stage: Int = FINAL,
    @ProtoNumber(5) val hidden: Boolean = false,
    /** LIVE: where the editor's caret is in [body], and the selection's end (2026-11-24, hen); -1: not said. */
    @ProtoNumber(6) val selStart: Int = -1,
    @ProtoNumber(7) val selEnd: Int = -1,
    /** FINAL, on pictures sent [MediaContent.quiet]: their caption (empty: none), which notifies as a new message. */
    @ProtoNumber(8) val notify: Boolean = false,
    /**
     * FINAL: a picture of the message with a new composition after it was sent (2026-11-28, hen: "edit the composition
     * after message sent"): the same item (its id and key), its new [AttachmentContent.crop] and the previews made for it.
     */
    @ProtoNumber(9) val item: AttachmentContent? = null,
) {
    companion object {
        const val FINAL = 0
        const val LIVE = 1
        const val CANCEL = 2
    }
}

/**
 * The chat is open on the sender's phone and in view ([open]), so what comes in is read at once there (2026-11-24, hen:
 * the read circle). Said again every 20 s; false when they leave it. Only from those who show when they're online.
 */
@Serializable
internal data class ViewingContent(@ProtoNumber(1) val open: Boolean = false)

/** Message [id] deleted by its author, for everyone. */
@Serializable
internal data class DeleteContent(@ProtoNumber(1) val id: String = "")

@Serializable
internal data class HereContent(@ProtoNumber(1) val open: Boolean = false)


/**
 * The first message of a pairing (docs/design.md, "Pairing"): sent by the phone that scanned an Add contact code to
 * every device of the user who showed it. [secret] is the code's secret, which only reaches whoever saw the screen.
 */
@Serializable
internal data class PairRequestContent(
    @ProtoNumber(1) val secret: ByteArray = ByteArray(0),
    @ProtoNumber(2) val user: String = "",
    @ProtoNumber(3) val identityKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is PairRequestContent && secret.contentEquals(other.secret) &&
        user == other.user && identityKey.contentEquals(other.identityKey)

    override fun hashCode(): Int = user.hashCode()
}

/**
 * A contact as one phone of a user knows it, for the user's other phones (docs/design.md, "Linking a phone"): paired,
 * with [identityKey] trusted, and whether the safety number was compared.
 */
@Serializable
internal data class ContactSyncContent(
    @ProtoNumber(1) val user: String = "",
    @ProtoNumber(2) val identityKey: ByteArray = ByteArray(0),
    @ProtoNumber(3) val verified: Boolean = false,
    @ProtoNumber(4) val displayName: String = "",
    /** This user's name for them ("" none); [nicknameSet]: this message says it (older phones don't). */
    @ProtoNumber(5) val nickname: String = "",
    @ProtoNumber(6) val nicknameSet: Boolean = false,
    /** Their email as this user typed it ("" none); [emailSet]: this message says it. */
    @ProtoNumber(7) val email: String = "",
    @ProtoNumber(8) val emailSet: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is ContactSyncContent && user == other.user &&
        identityKey.contentEquals(other.identityKey) && verified == other.verified && displayName == other.displayName &&
        nickname == other.nickname && nicknameSet == other.nicknameSet && email == other.email && emailSet == other.emailSet

    override fun hashCode(): Int = user.hashCode()
}

/** A text message. Phase 2 only sends one as a test of the transport; the conversation screen is phase 3. */
@Serializable
internal data class TextContent(
    @ProtoNumber(1) val body: String = "",
    /** When it was written, milliseconds since 1970 (UTC). */
    @ProtoNumber(2) val sentAt: Long = 0,
    /** The text's own id, the same on every phone: what receipts name. */
    @ProtoNumber(3) val id: String = "",
    /** One of the sender's own faces instead of words (2026-11-24, hen): its Expression code + 1; 0 none. [body] has an emoji for older apps. */
    @ProtoNumber(4) val face: Int = 0,
)

/**
 * [ids] of texts were delivered to a phone of the recipient, or read there ([read]). Sent back to the texts' sender;
 * the copy to the recipient's own other phones says they were read there too.
 */
@Serializable
internal data class ReceiptContent(
    @ProtoNumber(1) val read: Boolean = false,
    @ProtoNumber(2) val ids: List<String> = emptyList(),
)

/**
 * A clip's key (docs/design.md, "Phase 5a"): sent when a burst is granted (PUSH_TALK), or with a voice message once it
 * is uploaded (PUSH_MESSAGE). [id] is the blob's on the server, 32 hex digits, and the message's in the chat.
 */
@Serializable
internal data class ClipContent(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val key: ByteArray = ByteArray(0),
    /** 1 a talk burst, 2 a voice message. */
    @ProtoNumber(3) val kind: Int = 0,
    /** When it was said, milliseconds since 1970 (UTC). */
    @ProtoNumber(4) val sentAt: Long = 0,
    /** A voice message's length; 0 for a burst, which is still being said. */
    @ProtoNumber(5) val durationMs: Long = 0,
    /** Push-to-video: the clip's packets are tagged, audio and pieces of the camera's frames (TalkVideo). */
    @ProtoNumber(6) val video: Boolean = false,
    /** An urgent push (2026-11-25, hen): may play through Do Not Disturb where the listener allows it. */
    @ProtoNumber(7) val urgent: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is ClipContent && id == other.id && key.contentEquals(other.key) &&
        kind == other.kind && sentAt == other.sentAt && durationMs == other.durationMs && video == other.video &&
        urgent == other.urgent

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val BURST = 1
        const val VOICE = 2
    }
}

/**
 * The key a device encrypts its live audio with in a conversation's talk room (LiveKit's frame encryption, a key per
 * sender: docs/design.md, "Phase 5b"). [index] goes up with each new key of that device.
 */
@Serializable
internal data class TalkKeyContent(
    @ProtoNumber(1) val key: ByteArray = ByteArray(0),
    @ProtoNumber(2) val index: Int = 0,
    /**
     * The sender's camera picture as shown upright, width per 1000 of height (750: 3:4 portrait); 0 unknown or older
     * apps (2026-11-25, hen: the other phone grows its box straight to it when the video starts).
     */
    @ProtoNumber(3) val cameraShape: Int = 0,
) {
    override fun equals(other: Any?): Boolean = other is TalkKeyContent && key.contentEquals(other.key) && index == other.index && cameraShape == other.cameraShape

    override fun hashCode(): Int = index
}

/**
 * A call's signalling (docs/design.md, "Phase 6"): [kind] is one of the constants. An invite carries the call's id
 * (32 hex digits, its LiveKit room `c-<id>`) and its fresh media key; the others name the call.
 */
@Serializable
internal data class CallContent(
    @ProtoNumber(1) val kind: Int = 0,
    @ProtoNumber(2) val id: String = "",
    @ProtoNumber(3) val key: ByteArray = ByteArray(0),
    @ProtoNumber(4) val video: Boolean = false,
    /** When it was sent (an invite older than 30 s doesn't ring). */
    @ProtoNumber(5) val sentAt: Long = 0,
    /** With VIEW: how the sender shows [of]'s video (a user): cropped to fill its tile, or whole; and the tile's shape. */
    @ProtoNumber(7) val fill: Boolean = false,
    @ProtoNumber(8) val aspect: Float = 0f,
    @ProtoNumber(9) val of: String = "",
) {
    override fun equals(other: Any?): Boolean = other is CallContent && kind == other.kind && id == other.id &&
        key.contentEquals(other.key) && video == other.video && sentAt == other.sentAt &&
        fill == other.fill && aspect == other.aspect && of == other.of

    override fun hashCode(): Int = id.hashCode() * 31 + kind

    companion object {
        const val INVITE = 1
        const val ACCEPT = 2
        const val DECLINE = 3
        const val HANG_UP = 4
        /** Busy on another call: declined without ringing. */
        const val BUSY = 5
        /** Answered or declined on another phone of the callee: this one stops ringing. */
        const val ANSWERED_ELSEWHERE = 7
        /** How this phone shows someone's video (Fill or Fit, its tile's shape): their own preview outlines it. */
        const val VIEW = 8
    }
}

@Serializable
internal data class TypingContent(
    @ProtoNumber(1) val typing: Boolean = false,
    /**
     * Live typing (2026-11-23, hen; the sender's setting, off by default): what is in the sender's line right now, as
     * written, deletions included; "" when it was emptied. Older apps skip it and see only "typing".
     */
    @ProtoNumber(2) val draft: String? = null,
    /** Where the writer's caret is in [draft] (2026-11-24, hen), and the selection's end; -1: not said. */
    @ProtoNumber(3) val selStart: Int = -1,
    @ProtoNumber(4) val selEnd: Int = -1,
)

/**
 * A group as its owner's devices say it is (docs/design.md, "Groups"): [version] goes up with every change, and a
 * phone takes only a newer one. The server's list of members is only for routing: these are the members a phone gives
 * its sender key to, each with the identity key the owner trusts for them.
 */
@Serializable
internal data class GroupStateContent(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val owner: String = "",
    @ProtoNumber(3) val version: Long = 0,
    @ProtoNumber(4) val name: String = "",
    /** A small JPEG; empty for none. */
    @ProtoNumber(5) val picture: ByteArray = ByteArray(0),
    @ProtoNumber(6) val members: List<GroupMemberContent> = emptyList(),
    @ProtoNumber(7) val invited: List<GroupMemberContent> = emptyList(),
    /** The owner deleted the group. */
    @ProtoNumber(8) val deleted: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is GroupStateContent && id == other.id && owner == other.owner &&
        version == other.version && name == other.name && picture.contentEquals(other.picture) &&
        members == other.members && invited == other.invited && deleted == other.deleted

    override fun hashCode(): Int = id.hashCode() * 31 + version.hashCode()
}

@Serializable
internal data class GroupMemberContent(
    @ProtoNumber(1) val user: String = "",
    @ProtoNumber(2) val identityKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is GroupMemberContent && user == other.user && identityKey.contentEquals(other.identityKey)

    override fun hashCode(): Int = user.hashCode()
}

/** [state] is an encoded [GroupStateContent]; [signature] the owner's identity key's over those bytes. */
@Serializable
internal data class GroupChangeContent(
    @ProtoNumber(1) val state: ByteArray = ByteArray(0),
    @ProtoNumber(2) val signature: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is GroupChangeContent && state.contentEquals(other.state) && signature.contentEquals(other.signature)

    override fun hashCode(): Int = state.contentHashCode()
}

/** libsignal's sender key distribution message of the sending device, for [group]. */
@Serializable
internal data class SenderKeyContent(
    @ProtoNumber(1) val group: String = "",
    @ProtoNumber(2) val distribution: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is SenderKeyContent && group == other.group && distribution.contentEquals(other.distribution)

    override fun hashCode(): Int = group.hashCode()
}

internal object GroupStateCodec {
    fun encode(state: GroupStateContent): ByteArray = ProtoBuf.encodeToByteArray(GroupStateContent.serializer(), state)

    fun decode(bytes: ByteArray): GroupStateContent? = try {
        ProtoBuf.decodeFromByteArray(GroupStateContent.serializer(), bytes)
    } catch (_: Exception) {
        null
    }
}

internal object ContentCodec {
    const val BLOCK = 160

    /** Said: the caps below are this phone's (a phone that doesn't say is taken to play nothing extra). */
    const val CAPS_SAID = 0x80
    /** It decodes H.265, so push-to-video may send it H.265 (2026-11-22). */
    const val CAPS_H265 = 1
    /** It takes shared media (2026-11-28): a sender gives it previews and originals, not only a text fallback. */
    const val CAPS_MEDIA = 2
    /** It plays 10-bit HEVC (HDR video previews keep their HDR for it; otherwise it gets an SDR preview). */
    const val CAPS_HEVC10 = 4

    /** This phone's caps, sent with everything it sends (the platform sets them). */
    var myCaps = CAPS_SAID

    /** The caps each user's phones last said; a user's several phones: the last one heard. */
    private val heard = mutableMapOf<String, Int>()

    fun note(user: String, content: Content) {
        if (content.caps and CAPS_SAID != 0) heard[user] = content.caps
    }

    /**
     * This phone's H.265 decoder failed for real (2026-11-26, hen: "if one phone can't play H.265, fall back both
     * phones to VP8"): it no longer says it decodes H.265, so everyone sends it VP8 from their next push or call (the
     * caps go with everything it sends). [onH265Failed] keeps it so (the platform remembers it).
     */
    fun h265Failed() {
        if (myCaps and CAPS_H265 == 0) return
        myCaps = myCaps and CAPS_H265.inv()
        onH265Failed?.invoke()
    }

    var onH265Failed: (() -> Unit)? = null

    /** Whether [user]'s phone said it decodes H.265 (unknown: no). */
    fun decodesH265(user: String): Boolean = (heard[user] ?: 0) and CAPS_H265 != 0

    /** Whether [user]'s phone said it takes shared media (unknown: no). */
    fun takesMedia(user: String): Boolean = (heard[user] ?: 0) and CAPS_MEDIA != 0

    /** Whether [user]'s phone said it plays 10-bit HEVC (unknown: no). */
    fun playsHevc10(user: String): Boolean = (heard[user] ?: 0) and CAPS_HEVC10 != 0

    /** [content] encoded and padded: a 0x80 byte after it, then zeros to a multiple of [BLOCK] (Signal's padding). */
    fun encode(content: Content): ByteArray {
        val bytes = ProtoBuf.encodeToByteArray(Content.serializer(), content.copy(caps = myCaps))
        val padded = ByteArray(((bytes.size + 1 + BLOCK - 1) / BLOCK) * BLOCK)
        bytes.copyInto(padded)
        padded[bytes.size] = 0x80.toByte()
        return padded
    }

    /** The content of a decrypted message; null when it isn't padded content. */
    fun decode(padded: ByteArray): Content? {
        var end = padded.size - 1
        while (end >= 0 && padded[end] == 0.toByte()) end--
        if (end < 0 || padded[end] != 0x80.toByte()) return null
        return try {
            ProtoBuf.decodeFromByteArray(Content.serializer(), padded.copyOf(end))
        } catch (_: Exception) {
            null
        }
    }
}

/** "Send me our chat's history": this phone has none of it (a new install). */
@Serializable
internal data class HistoryRequestContent(
    /** Only messages before this time (0: all). */
    @ProtoNumber(1) val before: Long = 0,
)

/** Part of a chat's history, oldest first; [last] on the final part (an empty last part: nothing to send). */
@Serializable
internal data class HistoryContent(
    @ProtoNumber(1) val items: List<HistoryItem> = emptyList(),
    @ProtoNumber(2) val last: Boolean = false,
)

/** One message of a history, as its sender's phone keeps it. */
@Serializable
internal data class HistoryItem(
    @ProtoNumber(1) val id: String = "",
    /** Written by the phone sending the history (its user); else by the one asking. */
    @ProtoNumber(2) val fromSender: Boolean = false,
    @ProtoNumber(3) val body: String = "",
    @ProtoNumber(4) val at: Long = 0,
    @ProtoNumber(5) val face: Int = 0,
    @ProtoNumber(6) val media: MediaContent? = null,
    @ProtoNumber(7) val editedAt: Long = 0,
    @ProtoNumber(8) val deleted: Boolean = false,
)

/** The bytes of history items in one part: well under the server's 256 KiB envelope, after padding and encryption. */
internal const val HISTORY_PART_BYTES = 160 * 1024

/**
 * [messages] (a chat's, oldest first, as this phone keeps them) as history parts that each fit an envelope; one empty
 * part when there are none, so the asking phone knows it is done.
 */
internal fun historyParts(messages: List<TextMessage>): List<List<HistoryItem>> {
    val parts = mutableListOf<List<HistoryItem>>()
    var part = mutableListOf<HistoryItem>()
    var bytes = 0
    for (m in messages) {
        val item = HistoryItem(
            id = m.id, fromSender = m.outgoing, body = if (m.deleted) "" else m.body, at = m.at, face = m.face,
            media = if (m.deleted) null else m.media, editedAt = m.editedAt ?: 0, deleted = m.deleted,
        )
        val size = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(HistoryItem.serializer(), item).size + 8
        if (size > HISTORY_PART_BYTES) continue
        if (bytes + size > HISTORY_PART_BYTES && part.isNotEmpty()) {
            parts += part
            part = mutableListOf()
            bytes = 0
        }
        part += item
        bytes += size
    }
    parts += part
    return parts
}
