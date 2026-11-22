package com.chencohen.ptt.messaging

import com.chencohen.ptt.store.SecureStore
import com.chencohen.ptt.store.Spaces
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A paired user, or one this phone asked to pair with and is waiting for ([paired] false). [identityKey] (base64) is
 * the key this phone trusts for them: from their QR code, from their checked pair request, or (for a contact the
 * server lists that this phone didn't pair itself) from the server, until the safety number is compared.
 */
@Serializable
data class Contact(
    val user: String,
    val identityKey: String,
    val paired: Boolean,
    /** When the pair was made, as the server says (RFC 3339); null while waiting. */
    val since: String? = null,
    val blocked: Boolean = false,
    /** The safety number was compared (their code scanned, or marked by hand). */
    val verified: Boolean = false,
    /** A new identity key the server announced or listed for them, not accepted yet (base64). */
    val changedKey: String? = null,
    /** How many of their devices the server lists as active. */
    val devices: Int = 0,
    /** When this phone first saw them, milliseconds since 1970. */
    val addedAt: Long = 0,
    /** The name they gave for themselves when they joined by an invite (the server's `displayName`); null otherwise. */
    val displayName: String? = null,
)

/** Someone scanned this phone's Add contact code and asks to pair; waits for the user to confirm or decline. */
@Serializable
data class PairRequest(
    val user: String,
    val identityKey: String,
    val receivedAt: Long,
    /** It carried the secret of an invite link this phone made, which may have been forwarded: not verified. */
    val viaLink: Boolean = false,
)

/** Where a text this phone sent stands. */
enum class MessageStatus {
    /** Not on the server yet: in the outbox, sent when the phone is connected. */
    PENDING,

    /** The server took it. */
    SENT,

    /** A phone of theirs has it. */
    DELIVERED,

    /** They read it. */
    READ,

    /** The server refused it, or it can't be encrypted for them (a changed key, blocked): not tried again. */
    FAILED,
}

/**
 * A text in a conversation with [user]: [id] is the text's own, the same on every phone. [status] is for one this
 * phone's user sent ([outgoing]), from any of their phones; [read] for one they got.
 */
@Serializable
data class TextMessage(
    val id: String,
    val user: String,
    val outgoing: Boolean,
    val body: String,
    val at: Long,
    val status: MessageStatus = MessageStatus.SENT,
    val read: Boolean = false,
    /** In a group chat ([user] is then [Group.chatOf] its id): who wrote it. */
    val author: String? = null,
)

/** A member of a group as the owner's devices named them: the user and the identity key the owner trusts (base64). */
@Serializable
data class GroupMember(val user: String, val identityKey: String)

/**
 * A group this phone's user is in or invited to (docs/design.md, "Groups"). [name], [picture], [members] and [invited]
 * are what the owner's devices said, signed, at [version] (0: nothing from them yet); [serverMembers] and
 * [serverInvited] are the server's list, which only routes. A phone gives its sender key only to [readers].
 */
@Serializable
data class Group(
    val id: String,
    val owner: String,
    val name: String = "",
    /** A small JPEG, base64; null for none. */
    val picture: String? = null,
    val version: Long = 0,
    val members: List<GroupMember> = emptyList(),
    val invited: List<GroupMember> = emptyList(),
    val serverMembers: List<String> = emptyList(),
    val serverInvited: List<String> = emptyList(),
    /** Deleted, or this user left or was taken out: its chat stays, read only. */
    val gone: Boolean = false,
    val createdAt: Long = 0,
    /** The owner's last signed change, as it came (base64): what this phone checks a newer one against. */
    val signedState: String? = null,
    /** This device's current sender key's distribution id; null until the next message makes a new one. */
    val senderKeyId: String? = null,
    /** The devices ("user.device") that have that sender key, with the registration id they had then. */
    val keyHolders: Map<String, Int> = emptyMap(),
    /** The devices a group Send goes to, as the server last listed them ("user.device" to registration id). */
    val devices: Map<String, Int> = emptyMap(),
) {
    /**
     * Who reads the group: the members the owner's devices named, and those they invited who accepted on the server.
     * A user only on the server's list isn't one.
     */
    val readers: List<GroupMember> get() = members + invited.filter { it.user in serverMembers && members.none { m -> m.user == it.user } }

    fun isReader(user: String): Boolean = readers.any { it.user == user }

    fun member(user: String): GroupMember? = readers.firstOrNull { it.user == user } ?: invited.firstOrNull { it.user == user }

    /** This user is asked to join and hasn't answered. */
    fun isInvitation(me: String): Boolean = !gone && me in serverInvited && me !in serverMembers

    val chat: String get() = chatOf(id)

    companion object {
        /** The conversation key of a group's chat: user names have no '#'. */
        fun chatOf(id: String): String = "#$id"

        fun idOf(chat: String): String? = chat.takeIf { it.startsWith("#") }?.substring(1)
    }
}

/**
 * The secret of an Add contact code this phone showed, or of an invite link it made ([viaLink]; [token] is then the
 * invite's code), kept a week past its end so a late pair request is still recognised.
 */
@Serializable
internal data class Invite(
    val secret: String,
    val token: String,
    val expiresAt: Long,
    val madeAt: Long,
    val viaLink: Boolean = false,
) {
    /** Until when a pair request with its secret is taken: a week past whichever is later, made or expired. */
    fun keptUntil(): Long = maxOf(expiresAt, madeAt) + 7 * 24 * 3600_000L
}

/**
 * A Link a phone code this phone showed: its [secret] (base64url, the key it is kept by) and this phone's X25519 key
 * for that linking (base64). Used once; dropped an hour after it expires.
 */
@Serializable
internal data class LinkOffer(
    val secret: String,
    val privateKey: String,
    val publicKey: String,
    val expiresAt: Long,
)

/**
 * This phone scanned a Link a phone code and enrolled with it: it asks [user]'s device [device] for the identity key,
 * with its own X25519 key for that linking (base64), until the answer comes.
 */
@Serializable
internal data class LinkJoin(
    val user: String,
    val device: Int,
    val secret: String,
    val privateKey: String,
    val publicKey: String,
    val startedAt: Long,
)

/** This phone's counters and flags. */
@Serializable
internal data class Meta(
    /** Whose store this is: the server's fingerprint and the user. Another one wipes it. */
    val owner: String? = null,
    /** "user/device" the full key set was uploaded for; another one uploads it again. */
    val uploadedFor: String? = null,
    val nextPrekeyId: Int = 1,
    val nextKyberId: Int = 1,
    val nextSignedId: Int = 1,
    val signedId: Int = 0,
    val signedAt: Long = 0,
)

/** The shared code's part of the [SecureStore], as JSON values. */
internal class MessagingStore(val store: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> read(space: String, key: String, serializer: KSerializer<T>): T? =
        store.get(space, key)?.let { runCatching { json.decodeFromString(serializer, it.decodeToString()) }.getOrNull() }

    private fun <T> write(space: String, key: String, serializer: KSerializer<T>, value: T) =
        store.put(space, key, json.encodeToString(serializer, value).encodeToByteArray())

    private fun <T> all(space: String, serializer: KSerializer<T>): List<T> = store.keys(space).mapNotNull { read(space, it, serializer) }

    var meta: Meta
        get() = read(Spaces.META, "meta", Meta.serializer()) ?: Meta()
        set(value) = write(Spaces.META, "meta", Meta.serializer(), value)

    fun contacts(): List<Contact> = all(Spaces.CONTACTS, Contact.serializer())

    fun contact(user: String): Contact? = read(Spaces.CONTACTS, user, Contact.serializer())

    fun putContact(contact: Contact) = write(Spaces.CONTACTS, contact.user, Contact.serializer(), contact)

    fun removeContact(user: String) = store.remove(Spaces.CONTACTS, user)

    fun requests(): List<PairRequest> = all(Spaces.REQUESTS, PairRequest.serializer())

    fun putRequest(request: PairRequest) = write(Spaces.REQUESTS, request.user, PairRequest.serializer(), request)

    fun removeRequest(user: String) = store.remove(Spaces.REQUESTS, user)

    fun invites(): List<Invite> = all(Spaces.INVITES, Invite.serializer())

    fun putInvite(invite: Invite) = write(Spaces.INVITES, invite.token, Invite.serializer(), invite)

    fun removeInvite(invite: Invite) = store.remove(Spaces.INVITES, invite.token)

    fun linkOffers(): List<LinkOffer> = store.keys(Spaces.LINKS).filter { it != JOIN }.mapNotNull { read(Spaces.LINKS, it, LinkOffer.serializer()) }

    fun putLinkOffer(offer: LinkOffer) = write(Spaces.LINKS, offer.secret, LinkOffer.serializer(), offer)

    fun removeLinkOffer(offer: LinkOffer) = store.remove(Spaces.LINKS, offer.secret)

    var linkJoin: LinkJoin?
        get() = read(Spaces.LINKS, JOIN, LinkJoin.serializer())
        set(value) = if (value == null) store.remove(Spaces.LINKS, JOIN) else write(Spaces.LINKS, JOIN, LinkJoin.serializer(), value)

    /** Messages are keyed by time, so the space's sorted keys are the order they came in. */
    fun putMessage(message: TextMessage) =
        write(Spaces.MESSAGES, message.at.toString().padStart(15, '0') + "-" + message.id, TextMessage.serializer(), message)

    fun messages(): List<TextMessage> = all(Spaces.MESSAGES, TextMessage.serializer())

    fun messagesWith(user: String): List<TextMessage> = messages().filter { it.user == user }

    fun message(id: String): TextMessage? = messages().firstOrNull { it.id == id }

    fun removeMessagesOf(user: String) {
        store.keys(Spaces.MESSAGES).forEach { key ->
            if (read(Spaces.MESSAGES, key, TextMessage.serializer())?.user == user) store.remove(Spaces.MESSAGES, key)
        }
    }

    fun groups(): List<Group> = all(Spaces.GROUPS, Group.serializer())

    fun group(id: String): Group? = read(Spaces.GROUPS, id, Group.serializer())

    fun putGroup(group: Group) = write(Spaces.GROUPS, group.id, Group.serializer(), group)

    fun removeGroup(id: String) {
        store.remove(Spaces.GROUPS, id)
        removeMessagesOf(Group.chatOf(id))
    }

    private companion object {
        const val JOIN = "join"
    }
}
