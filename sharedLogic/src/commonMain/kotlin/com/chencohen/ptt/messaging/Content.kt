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
) {
    override fun equals(other: Any?): Boolean = other is ClipContent && id == other.id && key.contentEquals(other.key) &&
        kind == other.kind && sentAt == other.sentAt && durationMs == other.durationMs && video == other.video

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

    /** This phone's caps, sent with everything it sends (the platform sets them). */
    var myCaps = CAPS_SAID

    /** The caps each user's phones last said; a user's several phones: the last one heard. */
    private val heard = mutableMapOf<String, Int>()

    fun note(user: String, content: Content) {
        if (content.caps and CAPS_SAID != 0) heard[user] = content.caps
    }

    /** Whether [user]'s phone said it decodes H.265 (unknown: no). */
    fun decodesH265(user: String): Boolean = (heard[user] ?: 0) and CAPS_H265 != 0

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
