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
    /** On a copy for the sender's own other devices: the user the message went to. */
    @ProtoNumber(15) val destination: String = "",
)

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
) {
    override fun equals(other: Any?): Boolean = other is ContactSyncContent && user == other.user &&
        identityKey.contentEquals(other.identityKey) && verified == other.verified && displayName == other.displayName

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

@Serializable
internal data class TypingContent(@ProtoNumber(1) val typing: Boolean = false)

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

    /** [content] encoded and padded: a 0x80 byte after it, then zeros to a multiple of [BLOCK] (Signal's padding). */
    fun encode(content: Content): ByteArray {
        val bytes = ProtoBuf.encodeToByteArray(Content.serializer(), content)
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
