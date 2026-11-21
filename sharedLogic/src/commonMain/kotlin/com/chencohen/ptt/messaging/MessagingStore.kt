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

/** A text message (phase 2 sends them only as a test of the transport). */
@Serializable
data class TextMessage(val id: String, val user: String, val outgoing: Boolean, val body: String, val at: Long)

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

    /** Messages are keyed by time, so the space's sorted keys are the order they came in. */
    fun putMessage(message: TextMessage) =
        write(Spaces.MESSAGES, message.at.toString().padStart(15, '0') + "-" + message.id, TextMessage.serializer(), message)

    fun messages(): List<TextMessage> = all(Spaces.MESSAGES, TextMessage.serializer())

    fun removeMessagesOf(user: String) {
        store.keys(Spaces.MESSAGES).forEach { key ->
            if (read(Spaces.MESSAGES, key, TextMessage.serializer())?.user == user) store.remove(Spaces.MESSAGES, key)
        }
    }
}
