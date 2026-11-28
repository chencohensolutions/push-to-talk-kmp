package com.chencohen.ptt.messaging

import com.chencohen.ptt.store.SecureStore
import com.chencohen.ptt.store.Spaces
import com.chencohen.ptt.util.SyncLock
import com.chencohen.ptt.util.locked
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
    /** The name this user gave them (Rename; 2026-11-22, hen), on all of this user's phones; null: none. */
    val nickname: String? = null,
    /** Their email as this user typed it (on all of this user's phones); null: none. */
    val email: String? = null,
    /** The email their own phone shared (ProfileContent, end to end); null: not shared. */
    val theirEmail: String? = null,
    /** How they want to be referred to (Gender), from their own phone (ProfileContent). */
    val theirGender: Int = 0,
) {
    /** The email shown and put in Android Contacts: this user's, else theirs. */
    val shownEmail: String? get() = email?.takeIf { it.isNotBlank() } ?: theirEmail?.takeIf { it.isNotBlank() }

    /** How they are shown: this user's name for them, else their own, else their user name. */
    val name: String get() = nickname?.takeIf { it.isNotBlank() } ?: displayName?.takeIf { it.isNotBlank() } ?: user
}


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
    /** A talk burst or a voice message instead of a text ([body] is then empty). */
    val clip: Clip? = null,
    /** A call instead of a text ([id] is the call's, [outgoing] this user called; phase 6). */
    val call: CallEntry? = null,
    /** When the other user read it (this phone's clock as their receipt came; 2026-11-23, hen: a tap on their circle). */
    val readAt: Long? = null,
    /** In a group: who has read it (mine only), and when (this phone's clock as each receipt came). */
    val readBy: Map<String, Long> = emptyMap(),
    /** Edited by its author (2026-11-24, hen): when, on the author's clock; the chat says "edited". */
    val editedAt: Long? = null,
    /** When it reached their phone (this phone's clock as the receipt came; mine, one-to-one). */
    val deliveredAt: Long? = null,
    /** In a group: whose phone has it (mine only), and when (this phone's clock as each receipt came; 2026-11-26, hen). */
    val deliveredTo: Map<String, Long> = emptyMap(),
    /** Deleted by its author (2026-11-24, hen): only "Message deleted" is left of it here. */
    val deleted: Boolean = false,
    /** One of the author's faces instead of words (Expression code + 1; 2026-11-24, hen); 0: a text. */
    val face: Int = 0,
    /** Reactions with faces, by who reacted ("" this user): their Expression code + 1. */
    val reactions: Map<String, Int> = emptyMap(),
    /** Shared pictures, videos or files ([body] is then the caption, or the fallback older apps show; 2026-11-28, hen). */
    val media: MediaContent? = null,
)

/** How a call went, for the chat and the Calls tab. */
enum class CallOutcome {
    /** Someone answered; [CallEntry.durationMs] is how long it ran. */
    ANSWERED,
    MISSED,
    DECLINED,
    /** Nobody answered this phone's call. */
    NO_ANSWER,
    BUSY,
    /** The caller hung up before anyone answered. */
    CANCELLED,
    /** Answered or declined on another phone of this user. */
    ELSEWHERE,
    /** The room couldn't be reached. */
    FAILED,
}

@Serializable
data class CallEntry(
    val video: Boolean,
    val outcome: CallOutcome,
    val durationMs: Long = 0,
    /** Saved on this phone (Save calls): the file's name in the app's calls folder. */
    val savedAs: String? = null,
    /**
     * The server kept this call (someone recorded it): the call's key, base64, which only the phones have, to download
     * and decrypt it later (docs/screens/calls.md, "Playing a kept call"). Null when the server never kept it.
     */
    val keptKey: String? = null,
)

/** What a clip is: a burst said with the Talk button, or a voice message recorded in the composer. */
enum class ClipKind { BURST, VOICE }

/**
 * A clip in a conversation (docs/design.md, "Phase 5a"): [id] is the blob's on the server and the message's, [key]
 * (base64) the AES-256-GCM key only the phones have.
 */
@Serializable
data class Clip(
    val id: String,
    val key: String,
    val kind: ClipKind,
    /** How long it is; 0 until it is whole. */
    val durationMs: Long = 0,
    /** This phone keeps the whole clip (ClipFiles), so it plays without the server. */
    val kept: Boolean = false,
    /** A voice message this phone sent: on the server (PUT /blob) already. */
    val uploaded: Boolean = false,
    /** An incoming clip was played here. */
    val heard: Boolean = false,
    /** Push-to-video: the camera too (TalkVideo). */
    val video: Boolean = false,
    /** An urgent push (2026-11-25, hen). */
    val urgent: Boolean = false,
    /** A push this phone said: what became of it at each listener ("user" to HeardStatus), as they told it. */
    val heardBy: Map<String, Int> = emptyMap(),
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
    /** Contacts this new install asked for their chat's history, not answered yet (asked again on each start). */
    val historyWanted: Set<String> = emptySet(),
)

/** The shared code's part of the [SecureStore], as JSON values. */
/** A buffered push held for its late key: where and whose, and its packets (base64) with which are audio. */
@kotlinx.serialization.Serializable
internal data class LateHeld(
    val chat: String,
    val from: String,
    val video: Boolean,
    val at: Long,
    val packets: List<String>,
    val audio: List<Boolean>,
)

private const val LATE_KEYS = "late_keys"

internal class MessagingStore(val store: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> read(space: String, key: String, serializer: KSerializer<T>): T? =
        store.get(space, key)?.let { decode(serializer, it) }

    private fun <T> write(space: String, key: String, serializer: KSerializer<T>, value: T) =
        store.put(space, key, json.encodeToString(serializer, value).encodeToByteArray())

    private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T? =
        runCatching { json.decodeFromString(serializer, bytes.decodeToString()) }.getOrNull()

    private fun <T> all(space: String, serializer: KSerializer<T>): List<T> = store.entries(space).mapNotNull { decode(serializer, it.second) }

    var meta: Meta
        get() = read(Spaces.META, "meta", Meta.serializer()) ?: Meta()
        set(value) = write(Spaces.META, "meta", Meta.serializer(), value)

    /** Buffered pushes read before their key came (Talk; 2026-11-26, hen), by burst: kept here, encrypted, until it does. */
    fun lateKey(id: String): LateHeld? = read(LATE_KEYS, id, LateHeld.serializer())

    fun putLateKey(id: String, held: LateHeld) = write(LATE_KEYS, id, LateHeld.serializer(), held)

    fun removeLateKey(id: String) = store.remove(LATE_KEYS, id)

    fun lateKeys(): List<String> = store.keys(LATE_KEYS)

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
    // The messages, kept in memory as well, in the store's order (2026-11-26: every message(id) read and decoded the
    // whole table, so storing one incoming message took 1-5 s and publish() as long again). Writes go to both.
    private val messageLock = SyncLock()
    private var messageCache: MutableMap<String, TextMessage>? = null
    private val keyById = mutableMapOf<String, String>()

    // Each chat's message keys in order, and its list as last handed out (2026-11-28, hen: "the chats should support
    // very very long history"): a write touches only its own chat (a binary search, no sort), and publishing hands out
    // the other chats' lists as they were, so its cost doesn't grow with the history.
    private val keysByUser = mutableMapOf<String, ArrayList<String>>()
    private val listByUser = mutableMapOf<String, List<TextMessage>>()

    private fun messageKey(message: TextMessage) = message.at.toString().padStart(15, '0') + "-" + message.id

    private fun cache(): MutableMap<String, TextMessage> = messageCache ?: run {
        val loaded = mutableMapOf<String, TextMessage>()
        // The whole history in one read of the store (a query per message made a long history slow to open).
        store.entries(Spaces.MESSAGES).forEach { (key, bytes) -> decode(TextMessage.serializer(), bytes)?.let { loaded[key] = it } }
        keyById.clear()
        keysByUser.clear()
        listByUser.clear()
        loaded.forEach { (key, m) ->
            keyById[m.id] = key
            keysByUser.getOrPut(m.user) { ArrayList() }.add(key)
        }
        keysByUser.values.forEach { it.sort() }
        messageCache = loaded
        loaded
    }

    private fun indexed(user: String, key: String) {
        val keys = keysByUser.getOrPut(user) { ArrayList() }
        val at = keys.binarySearch(key)
        if (at < 0) keys.add(-at - 1, key)
        listByUser.remove(user)
    }

    private fun unindexed(user: String, key: String) {
        val keys = keysByUser[user] ?: return
        val at = keys.binarySearch(key)
        if (at >= 0) keys.removeAt(at)
        if (keys.isEmpty()) keysByUser.remove(user)
        listByUser.remove(user)
    }

    fun putMessage(message: TextMessage) = messageLock.locked {
        val key = messageKey(message)
        write(Spaces.MESSAGES, key, TextMessage.serializer(), message)
        val c = cache()
        // The same message under another time: the old row goes (it was kept twice before).
        keyById[message.id]?.takeIf { it != key }?.let { old ->
            c.remove(old)?.let { unindexed(it.user, old) }
            store.remove(Spaces.MESSAGES, old)
        }
        c[key]?.takeIf { it.user != message.user }?.let { unindexed(it.user, key) }
        c[key] = message
        keyById[message.id] = key
        indexed(message.user, key)
    }

    fun messages(): List<TextMessage> = messageLock.locked { cache().entries.sortedBy { it.key }.map { it.value } }

    /** [user]'s messages, oldest first; the same list object until one of them changes. */
    fun messagesWith(user: String): List<TextMessage> = messageLock.locked { chatList(user) }

    private fun chatList(user: String): List<TextMessage> {
        val c = cache()
        return listByUser[user] ?: keysByUser[user].orEmpty().mapNotNull { c[it] }.also { listByUser[user] = it }
    }

    /** Every chat's messages, oldest first (lists of chats that didn't change are the ones handed out before). */
    fun conversations(): Map<String, List<TextMessage>> = messageLock.locked {
        cache()
        keysByUser.keys.associateWith { chatList(it) }
    }

    fun message(id: String): TextMessage? = messageLock.locked { val c = cache(); keyById[id]?.let { c[it] } }

    fun removeMessage(message: TextMessage) = messageLock.locked {
        val key = keyById[message.id] ?: messageKey(message)
        store.remove(Spaces.MESSAGES, key)
        cache().remove(key)?.let { unindexed(it.user, key) }
        keyById.remove(message.id)
    }

    fun removeMessagesOf(user: String) = messageLock.locked {
        val c = cache()
        keysByUser[user].orEmpty().toList().forEach { key ->
            store.remove(Spaces.MESSAGES, key)
            c.remove(key)?.let { keyById.remove(it.id) }
        }
        keysByUser.remove(user)
        listByUser.remove(user)
    }

    /** [user]'s picture (this user's own: ""), a small WebP with a clear background; null: none. */
    fun picture(user: String): ByteArray? = store.get(Spaces.PICTURES, user)

    /** Which picture [user] has (this user's own: ""); null: none. */
    fun pictureId(user: String): String? = store.get(Spaces.PICTURE_IDS, user)?.decodeToString()

    /** Every user with a picture here, with its id. */
    fun pictureIds(): Map<String, String> = store.keys(Spaces.PICTURE_IDS).associateWith { pictureId(it).orEmpty() }

    fun putPicture(user: String, id: String, bytes: ByteArray, faces: Map<Int, ByteArray> = emptyMap()) {
        removeFaces(user)
        store.put(Spaces.PICTURES, user, bytes)
        for ((code, image) in faces) store.put(Spaces.PICTURES, "$user#$code", image)
        store.put(Spaces.PICTURE_IDS, user, id.encodeToByteArray())
    }

    fun removePicture(user: String) {
        removeFaces(user)
        store.remove(Spaces.PICTURES, user)
        store.remove(Spaces.PICTURE_IDS, user)
    }

    /** [user]'s face in expression [code] (the Expression code); null: none. */
    fun face(user: String, code: Int): ByteArray? = store.get(Spaces.PICTURES, "$user#$code")

    /** The expressions [user] has faces for. */
    fun faceCodes(user: String): List<Int> = store.keys(Spaces.PICTURES).filter { it.startsWith("$user#") }.mapNotNull { it.substringAfter('#').toIntOrNull() }.sorted()

    private fun removeFaces(user: String) {
        store.keys(Spaces.PICTURES).filter { it.startsWith("$user#") }.forEach { store.remove(Spaces.PICTURES, it) }
    }

    /** The id of this user's picture last sent to [chat] (a contact or a group), or NO_PICTURE; null: never. */
    fun pictureSent(chat: String): String? = store.get(Spaces.PICTURE_SENT, chat)?.decodeToString()

    fun putPictureSent(chat: String, id: String) = store.put(Spaces.PICTURE_SENT, chat, id.encodeToByteArray())

    /**
     * Where the original of shared item [item] of message [message] is on this phone (a content URI this phone sent
     * it from; the app holds the permission to read it, and keeps no copy; 2026-11-27, hen).
     */
    fun mediaSource(message: String, item: String): String? = store.get(Spaces.MEDIA_SOURCES, "$message/$item")?.decodeToString()

    fun putMediaSource(message: String, item: String, source: String) = store.put(Spaces.MEDIA_SOURCES, "$message/$item", source.encodeToByteArray())

    fun removeMediaSource(message: String, item: String) = store.remove(Spaces.MEDIA_SOURCES, "$message/$item")

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
