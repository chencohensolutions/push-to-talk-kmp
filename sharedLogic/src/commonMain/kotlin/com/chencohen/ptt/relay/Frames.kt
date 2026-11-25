@file:OptIn(ExperimentalSerializationApi::class)

package com.chencohen.ptt.relay

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoOneOf
import kotlinx.serialization.protobuf.ProtoType

// The frames of /ws, as in src/commonMain/proto/ptt.proto (copied from push-to-talk-server-go, where it is the source:
// ./gradlew :sharedLogic:syncServerSchema copies it with the GraphQL schema). One binary WebSocket message is one Frame.
//
// Written by hand with kotlinx-serialization-protobuf rather than generated (Wire): the build already has the
// serialization plugin, so this adds one runtime library and no code generator to a build that only CI can run for
// Android, and the eight messages are small. Field numbers follow the .proto file; FramesTest decodes frames the
// server's Go code encoded (and the other way round), so a change on one side that isn't made on the other fails it.
// Enum fields are plain Ints, so a newer server's new value is passed along instead of failing the whole frame.

/** One WebSocket message. Exactly one of the fields is set. */
@Serializable
internal data class Frame(@ProtoOneOf val kind: FrameKind? = null)

@Serializable
internal sealed interface FrameKind

@Serializable
internal data class EnvelopeFrame(@ProtoNumber(1) val envelope: Envelope) : FrameKind

@Serializable
internal data class AckFrame(@ProtoNumber(2) val ack: Ack) : FrameKind

@Serializable
internal data class SendFrame(@ProtoNumber(3) val send: Send) : FrameKind

@Serializable
internal data class SendResultFrame(@ProtoNumber(4) val sendResult: SendResult) : FrameKind

@Serializable
internal data class PingFrame(@ProtoNumber(5) val ping: Ping) : FrameKind

@Serializable
internal data class PongFrame(@ProtoNumber(6) val pong: Pong) : FrameKind

@Serializable
internal data class QueueEmptyFrame(@ProtoNumber(7) val queueEmpty: QueueEmpty) : FrameKind

@Serializable
internal data class PresenceFrame(@ProtoNumber(8) val presence: Presence) : FrameKind

@Serializable
internal data class TalkStartFrame(@ProtoNumber(9) val talkStart: TalkStart) : FrameKind

@Serializable
internal data class TalkStateFrame(@ProtoNumber(10) val talkState: TalkState) : FrameKind

@Serializable
internal data class ClipSegmentFrame(@ProtoNumber(11) val clipSegment: ClipSegment) : FrameKind

@Serializable
internal data class TalkEndFrame(@ProtoNumber(12) val talkEnd: TalkEnd) : FrameKind

@Serializable
internal data class TalkAckFrame(@ProtoNumber(13) val talkAck: TalkAck) : FrameKind

/** Takes the floor of a conversation for a burst (the server's docs/messaging.md, "Talk"): [peer] or [group]. */
@Serializable
internal data class TalkStart(
    @ProtoNumber(1) val burstId: String = "",
    @ProtoNumber(2) val peer: String = "",
    @ProtoNumber(3) val group: String = "",
)

@Serializable
internal data class TalkEnd(@ProtoNumber(1) val burstId: String = "")

@Serializable
internal data class TalkAck(@ProtoNumber(1) val burstId: String = "")

@Serializable
internal data class BufferedTalkFrame(@ProtoNumber(14) val bufferedTalk: BufferedTalk) : FrameKind

/**
 * A push kept for this user by the server's recorder because they weren't in the talk room (the server's
 * docs/messaging.md, "Talk buffer"): GET /blob/[blobId], track records still encrypted with the talker's room key.
 */
@Serializable
internal data class BufferedTalk(
    @ProtoNumber(1) val blobId: String = "",
    @ProtoNumber(2) val talker: String = "",
    @ProtoNumber(3) val peer: String = "",
    @ProtoNumber(4) val group: String = "",
    @ProtoNumber(5) val media: String = "",
    @ProtoNumber(6) val codec: String = "",
    @ProtoNumber(7) val startedAt: Long = 0,
    @ProtoNumber(8) val burstId: String = "",
)

/** The floor's news: the answer to a TalkStart, or a burst STARTED or ENDED in a conversation. Live only. */
@Serializable
internal data class TalkState(
    /** One of [TalkEvent]. */
    @ProtoNumber(1) val event: Int = 0,
    @ProtoNumber(2) val burstId: String = "",
    @ProtoNumber(3) val talkerUser: String = "",
    @ProtoNumber(4) val talkerDevice: Int = 0,
    @ProtoNumber(5) val peer: String = "",
    @ProtoNumber(6) val group: String = "",
    /** One of [TalkEndReason]. */
    @ProtoNumber(7) val endReason: Int = 0,
    /** With REFUSED: one of [SendStatus]. */
    @ProtoNumber(8) val refusal: Int = 0,
    @ProtoNumber(9) val detail: String = "",
    @ProtoNumber(10) @ProtoType(ProtoIntegerType.DEFAULT) val startedAt: Long = 0,
)

/** `TalkState.Event`. */
internal object TalkEvent {
    const val GRANTED = 1
    const val BUSY = 2
    const val REFUSED = 3
    const val STARTED = 4
    const val ENDED = 5
}

/** `TalkState.EndReason`. */
internal object TalkEndReason {
    const val RELEASED = 1
    const val TIMEOUT = 2
    const val DROPPED = 3
}

/**
 * One encrypted 200 ms piece of a clip ([com.chencohen.ptt.talk.ClipCrypto]). In a clip's file on the server (and
 * on the phone) it is kept as a record without its [burstId] ([com.chencohen.ptt.talk.ClipRecords]).
 */
@Serializable
internal data class ClipSegment(
    @ProtoNumber(1) val burstId: String = "",
    @ProtoNumber(2) val index: Int = 0,
    @ProtoNumber(3) val last: Boolean = false,
    @ProtoNumber(4) val ciphertext: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is ClipSegment && burstId == other.burstId && index == other.index &&
        last == other.last && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = burstId.hashCode() * 31 + index
}

/** A contact came online or went offline (the server's docs/messaging.md, "Presence"); live only. */
@Serializable
internal data class Presence(
    @ProtoNumber(1) val user: String = "",
    @ProtoNumber(2) val online: Boolean = false,
    /** When offline: last connected, milliseconds since 1970, rounded down to 5 minutes; 0 when unknown. */
    @ProtoNumber(3) @ProtoType(ProtoIntegerType.DEFAULT) val lastSeen: Long = 0,
)

@Serializable
internal data class Envelope(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val senderUser: String = "",
    @ProtoNumber(3) val senderDevice: Int = 0,
    @ProtoNumber(4) val recipientUser: String = "",
    @ProtoNumber(5) val recipientDevice: Int = 0,
    /** One of [EnvelopeType]. */
    @ProtoNumber(6) val type: Int = 0,
    @ProtoNumber(7) val ciphertext: ByteArray = ByteArray(0),
    @ProtoNumber(8) val serverTimestamp: Long = 0,
    @ProtoNumber(9) val routingToken: String = "",
    @ProtoNumber(10) val recipientRegistrationId: Int = 0,
    /** Set by the server: the group a SENDER_KEY_MESSAGE was sent to, or that a GROUP_CHANGED is about. */
    @ProtoNumber(11) val group: String = "",
) {
    override fun equals(other: Any?): Boolean = other is Envelope && id == other.id && senderUser == other.senderUser &&
        senderDevice == other.senderDevice && recipientUser == other.recipientUser && recipientDevice == other.recipientDevice &&
        type == other.type && ciphertext.contentEquals(other.ciphertext) && serverTimestamp == other.serverTimestamp &&
        routingToken == other.routingToken && recipientRegistrationId == other.recipientRegistrationId && group == other.group

    override fun hashCode(): Int = id.hashCode() * 31 + ciphertext.contentHashCode()
}

/** `Envelope.Type`. */
internal object EnvelopeType {
    const val PREKEY_MESSAGE = 1
    const val MESSAGE = 2
    const val SENDER_KEY_MESSAGE = 3
    const val PROVISIONING = 4
    const val IDENTITY_KEY_CHANGED = 5
    const val PAIRED = 6
    /** Made by the server: the group in `group` changed on the server, so the phone fetches `groups` again. */
    const val GROUP_CHANGED = 7
}

@Serializable
internal data class Ack(@ProtoNumber(1) val ids: List<String> = emptyList())

@Serializable
internal data class Send(
    @ProtoNumber(1) val clientId: String = "",
    @ProtoNumber(2) val envelopes: List<Envelope> = emptyList(),
    /** What a wake-up for it is ([PushKind]): the server can't read the ciphertext. */
    @ProtoNumber(3) val pushKind: Int = 0,
    /** A group Send: the group's id; [envelopes] is then one SENDER_KEY_MESSAGE with no recipient. */
    @ProtoNumber(4) val group: String = "",
    /** In a group Send: the devices it is for, by user (exactly the server's, or STALE_DEVICES). */
    @ProtoNumber(5) val groupDevices: List<UserDevices> = emptyList(),
    /** Only to the devices connected now: never queued, never pushed (live typing's drafts). Older servers ignore it. */
    @ProtoNumber(6) val ephemeral: Boolean = false,
)

/** ptt.proto's PushKind: which FCM push a recipient without an open /ws gets. */
internal object PushKind {
    const val SYNC = 1
    const val MESSAGE = 2
    const val NONE = 3
    const val TALK = 4
    const val CALL = 5
    /** Someone opened a chat with this user: join its talk room quietly (2026-11-24, hen; the server's PUSH_WARM). */
    const val WARM = 6
}

@Serializable
internal data class SendResult(
    @ProtoNumber(1) val clientId: String = "",
    /** One of [SendStatus]. */
    @ProtoNumber(2) val status: Int = 0,
    @ProtoNumber(3) val envelopeIds: List<String> = emptyList(),
    @ProtoNumber(4) val devices: List<UserDevices> = emptyList(),
    @ProtoNumber(5) val detail: String = "",
)

/** `SendResult.Status`. */
internal object SendStatus {
    const val ACCEPTED = 1
    const val NOT_PAIRED = 2
    const val STALE_DEVICES = 3
    const val UNKNOWN_USER = 4
    const val BLOCKED = 5
    const val INVALID = 6
    const val TOO_LARGE = 7
    const val QUEUE_FULL = 8
    /** A group Send to a group the sender isn't a member of, or that doesn't exist. */
    const val NOT_MEMBER = 9
}

@Serializable
internal data class UserDevices(
    @ProtoNumber(1) val user: String = "",
    @ProtoNumber(2) val devices: List<ActiveDevice> = emptyList(),
)

@Serializable
internal data class ActiveDevice(
    @ProtoNumber(1) val deviceNumber: Int = 0,
    @ProtoNumber(2) val registrationId: Int = 0,
)

@Serializable
internal data class Ping(@ProtoNumber(1) @ProtoType(ProtoIntegerType.DEFAULT) val id: Long = 0)

@Serializable
internal data class Pong(@ProtoNumber(1) @ProtoType(ProtoIntegerType.DEFAULT) val id: Long = 0)

@Serializable
internal class QueueEmpty {
    override fun equals(other: Any?): Boolean = other is QueueEmpty
    override fun hashCode(): Int = 0
}

/** Encodes and decodes [Frame]s; unknown fields (a newer server's) are skipped. */
internal object Frames {
    private val proto = ProtoBuf

    fun encodeSegment(segment: ClipSegment): ByteArray = proto.encodeToByteArray(ClipSegment.serializer(), segment)

    fun decodeSegment(bytes: ByteArray): ClipSegment? = try {
        proto.decodeFromByteArray(ClipSegment.serializer(), bytes)
    } catch (_: Exception) {
        null
    }

    fun encode(frame: Frame): ByteArray = proto.encodeToByteArray(Frame.serializer(), frame)

    /** Null when [bytes] isn't a frame. */
    fun decode(bytes: ByteArray): Frame? = try {
        proto.decodeFromByteArray(Frame.serializer(), bytes)
    } catch (_: Exception) {
        null
    }
}
