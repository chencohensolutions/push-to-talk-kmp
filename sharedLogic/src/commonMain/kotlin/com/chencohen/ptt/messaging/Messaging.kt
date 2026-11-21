package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.CryptoEngine
import com.chencohen.ptt.crypto.CryptoException
import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.crypto.SafetyNumber
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.network.describe
import com.chencohen.ptt.relay.Envelope
import com.chencohen.ptt.relay.EnvelopeType
import com.chencohen.ptt.relay.FrameConnector
import com.chencohen.ptt.relay.RelaySocket
import com.chencohen.ptt.relay.SocketState
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.settings.PttSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Duration.Companion.days

/** What the messaging screens render: the keys, the connection, the contacts, the pair requests, the last texts. */
data class MessagingState(
    val keys: KeysState = KeysState.Unknown,
    val socket: SocketState = SocketState.Stopped,
    /** Paired contacts and the ones waiting to confirm, by name. */
    val contacts: List<Contact> = emptyList(),
    /** Pair requests waiting for the user, oldest first. */
    val requests: List<PairRequest> = emptyList(),
    /** The last text of each contact, by user (phase 2's test of the transport). */
    val lastMessages: Map<String, TextMessage> = emptyMap(),
)

/**
 * The phone's side of keys, pairing and messages (docs/design.md, phase 2), under [MessagingController] and
 * [PairingController]. Everything runs on [scope], whose dispatcher runs one thing at a time: the crypto engine and the
 * store aren't thread-safe.
 */
internal class Messaging(
    private val settings: PttSettings,
    private val engine: CryptoEngine,
    val store: MessagingStore,
    private val api: MessagingApi,
    connector: FrameConnector,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val random: Random = Random.Default,
    private val onRevoked: () -> Unit = {},
) {
    private val _state = MutableStateFlow(MessagingState())
    val state: StateFlow<MessagingState> = _state.asStateFlow()

    private val keys = KeyUploader(engine, api, store, now)

    val socket = RelaySocket(
        scope = scope,
        connector = connector,
        url = { wsUrl(settings.serverUrl) },
        onEnvelope = ::receive,
        beforeConnect = { deviceKey()?.let { keys.check(it) }?.let { count -> _state.update { it.copy(keys = KeysState.Ready(count)) } } },
        onRevoked = onRevoked,
        onLive = {
            refreshContacts()
            // Not awaited here: the pair request's SendResult comes through the very read loop onLive runs in.
            scope.launch { pairPendingInvite() }
        },
    )

    private val sender = Sender(engine, api, socket::send, { settings.device })

    init {
        scope.launchCollect(socket.state) { socket -> _state.update { it.copy(socket = socket) } }
    }

    private fun device(): EnrolledDevice? = settings.device?.takeIf { it.deviceNumber > 0 }

    /** This device as the server knows it; null before enrolling. */
    fun self(): EnrolledDevice? = device()

    /** The server part of this phone's codes (PairCode.server). */
    fun serverPart(): String? = PairCode.serverOf(settings.serverFingerprint)

    private fun deviceKey(): String? = device()?.let { "${it.user}/${it.deviceNumber}" }

    /** Uploads the keys if needed and opens /ws; the app came to the front. */
    suspend fun start() {
        val self = device() ?: return
        claim(self)
        publish()
        _state.update { it.copy(keys = if (it.keys is KeysState.Ready) it.keys else KeysState.Uploading) }
        val result = try {
            keys.ensure(deviceKey()!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeysState.Failed(e.describe())
        }
        _state.update { it.copy(keys = result) }
        // Without keys nobody can open a session with this phone, but what is queued for it can still be read.
        if (result !is KeysState.OtherIdentity) socket.start()
    }

    fun stop() = socket.stop()

    /** Stops for good (the app's process ends). */
    fun close() {
        socket.stop()
        scope.cancel()
    }

    /**
     * The store belongs to one user on one server: enrolled as another (Register again), everything of the old one
     * (keys, sessions, contacts, messages) is forgotten.
     */
    private fun claim(self: EnrolledDevice) {
        val owner = "${settings.serverFingerprint.orEmpty()}/${self.user}"
        val meta = store.meta
        if (meta.owner == owner) return
        if (meta.owner != null) {
            engine.wipe()
            store.store.clearAll()
        }
        store.meta = Meta(owner = owner)
    }

    /** Starts over with a new identity key (every other phone of the user revoked), then uploads the keys. */
    suspend fun replaceIdentityKey() {
        val self = device() ?: return
        val identity = engine.createIdentity()
        store.meta = store.meta.copy(uploadedFor = null)
        api.replaceIdentityKey(identity)
        // Every session was opened with the old identity: they start again (the contacts' trusted keys stay).
        for (contact in store.contacts()) {
            engine.forget(contact.user)
            engine.trustIdentity(contact.user, Base64.decode(contact.identityKey))
        }
        val result = keys.ensure("${self.user}/${self.deviceNumber}")
        _state.update { it.copy(keys = result) }
        socket.start()
    }

    // --- Pairing -------------------------------------------------------------------------------------------------

    /** A new Add contact code for this phone: a pairing token and a secret kept to recognise the pair request. */
    suspend fun newPairCode(): Pair<PairCode, Long> {
        val self = device() ?: throw IllegalStateException("This phone isn't registered")
        val identity = engine.identityKey() ?: throw IllegalStateException("No identity key yet")
        val (token, expiresAt) = api.pairingToken()
        val secret = random.nextBytes(PairCode.SECRET_BYTES)
        val expires = parseTime(expiresAt) ?: (now() + 10 * 60_000)
        store.putInvite(Invite(PairCode.base64Url.encode(secret), token, expires, now()))
        dropOldInvites()
        return PairCode(self.user, identity, token, secret, PairCode.serverOf(settings.serverFingerprint)) to expires
    }

    /** Old secrets go after the week a pairing may wait on the server. */
    private fun dropOldInvites() {
        store.invites().filter { now() > it.keptUntil() }.forEach(store::removeInvite)
    }

    /**
     * A new Invite a friend link from this phone (for [email] when not null): the server's invite code, where the
     * server is, and this phone's pairing part, with a secret kept to recognise the friend's pair request. Gives the
     * link and when it expires.
     */
    suspend fun newInvite(email: String?): Pair<InviteLink, Long> {
        val self = device() ?: throw IllegalStateException("This phone isn't registered")
        val identity = engine.identityKey() ?: throw IllegalStateException("No identity key yet")
        val discovery = settings.serverDiscovery ?: throw IllegalStateException("The server hasn't said who it is yet")
        val wanted = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val (code, expiresAt) = api.createInvite(wanted)
        val secret = random.nextBytes(PairCode.SECRET_BYTES)
        val expires = parseTime(expiresAt) ?: (now() + 7.days.inWholeMilliseconds)
        store.putInvite(Invite(PairCode.base64Url.encode(secret), code, expires, now(), viaLink = true))
        dropOldInvites()
        val link = InviteLink(
            code = code,
            server = KeptDiscovery.originOf(settings.serverUrl),
            fingerprint = discovery.fingerprint,
            key = discovery.key,
            addresses = discovery.candidates(),
            inviter = self.user,
            identityKey = identity,
            secret = secret,
            email = wanted,
        )
        return link to expires
    }

    /** Redeems [link] on this enrolled phone and pairs with the inviter. True when the pair is made. */
    suspend fun acceptInvite(link: InviteLink): Boolean {
        val pairing = api.acceptInvite(link.code)
        val token = pairing.token ?: throw ApiException(ApiCodes.RATE_LIMITED, "The server couldn't make a pairing token")
        return pairWith(link.pairCode(token), verified = false)
    }

    /**
     * After enrolling with an invite link: pairs with the inviter (settings.pendingInvitePair) once the socket is live,
     * and forgets it when that worked or can't work any more. Not verified: the link may have been forwarded.
     */
    private suspend fun pairPendingInvite() {
        val text = settings.pendingInvitePair ?: return
        val code = (PairCode.parse(text) as? PairCodeParse.Code)?.code
        if (code == null || device() == null) {
            settings.pendingInvitePair = null
            return
        }
        if (_state.value.keys !is KeysState.Ready) return
        try {
            pairWith(code, verified = false)
            settings.pendingInvitePair = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept for the next connection when the server or the socket wasn't there; else it can't work any more.
            if (e.toPairingError() != PairingError.UNREACHABLE) settings.pendingInvitePair = null
        }
    }

    /**
     * Pairs with whoever showed [code]: trusts the identity key from it, sends the pair request (with the code's
     * secret) to their devices through the server, and confirms. True when the pair is made, false while they haven't
     * confirmed.
     */
    suspend fun pairWith(code: PairCode, verified: Boolean): Boolean {
        val self = device() ?: throw IllegalStateException("This phone isn't registered")
        val identity = engine.identityKey() ?: throw IllegalStateException("No identity key yet")
        // A new start: the key from the code replaces whatever was trusted for them.
        engine.forget(code.user)
        engine.trustIdentity(code.user, code.identityKey)
        val key = Base64.encode(code.identityKey)
        val old = store.contact(code.user)
        store.putContact(
            Contact(
                user = code.user,
                identityKey = key,
                paired = old?.paired == true && old.identityKey == key,
                since = old?.since,
                // Their code scanned in person; a link (an invite) may have been forwarded.
                verified = verified || (old?.verified == true && old.identityKey == key),
                addedAt = old?.addedAt ?: now(),
            )
        )
        publish()
        try {
            sender.send(code.user, Content(pairRequest = PairRequestContent(code.secret, self.user, identity)), code.token)
        } catch (e: Exception) {
            // Nothing reached them: back to what was there before.
            engine.forget(code.user)
            if (old == null) {
                store.removeContact(code.user)
            } else {
                store.putContact(old)
                engine.trustIdentity(old.user, Base64.decode(old.identityKey))
            }
            publish()
            throw e
        }
        val paired = api.confirmPair(code.user)
        if (paired) markPaired(code.user)
        return paired
    }

    /** Confirms [user]'s pair request. True when the pair is made. */
    suspend fun accept(user: String): Boolean {
        val request = store.requests().firstOrNull { it.user == user } ?: return false
        val paired = api.confirmPair(user)
        val old = store.contact(user)
        store.putContact(
            Contact(
                user = user,
                identityKey = request.identityKey,
                paired = paired,
                since = old?.since,
                // The request carried the secret of this phone's code: whoever sent it saw the screen. An invite
                // link's secret may have been forwarded, so that one isn't verified.
                verified = !request.viaLink || (old?.verified == true && old.identityKey == request.identityKey),
                addedAt = old?.addedAt ?: now(),
            )
        )
        store.removeRequest(user)
        publish()
        if (paired) refreshContacts()
        return paired
    }

    fun decline(user: String) {
        store.removeRequest(user)
        if (store.contact(user) == null) engine.forget(user)
        publish()
    }

    private fun markPaired(user: String) {
        store.contact(user)?.let { store.putContact(it.copy(paired = true)) }
        publish()
    }

    // --- Contacts ------------------------------------------------------------------------------------------------

    /**
     * Brings the contacts in line with the server's `contacts`: who is paired, blocked, since when, with how many
     * devices, and whether the server lists another identity key than the one trusted.
     */
    suspend fun refreshContacts() {
        if (device() == null) return
        val answer = try {
            api.contacts()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val listed = answer.contacts.associateBy { it.user }
        for (contact in store.contacts()) {
            val server = listed[contact.user]
            if (server == null) {
                // Unpaired by the other side (or removed); one still waiting stays for the week a pairing may wait.
                if (contact.paired || now() - contact.addedAt > 7.days.inWholeMilliseconds) forgetContact(contact.user)
                continue
            }
            val serverKey = server.identityKey?.let(Base64::encode)
            store.putContact(
                contact.copy(
                    displayName = server.displayName ?: contact.displayName,
                    paired = true,
                    since = server.since,
                    blocked = server.blocked,
                    devices = server.devices.size,
                    changedKey = serverKey?.takeIf { it != contact.identityKey } ?: contact.changedKey?.takeIf { serverKey == null },
                )
            )
        }
        // Paired elsewhere (or this phone's store is new): trusted from the server until the safety number is compared.
        for (server in answer.contacts) {
            if (store.contact(server.user) != null) continue
            val key = server.identityKey ?: continue
            engine.trustIdentity(server.user, key)
            store.putContact(
                Contact(
                    user = server.user, displayName = server.displayName, identityKey = Base64.encode(key), paired = true, since = server.since,
                    blocked = server.blocked, devices = server.devices.size, verified = false, addedAt = now(),
                )
            )
        }
        publish()
    }

    suspend fun unpair(user: String) {
        api.unpair(user)
        forgetContact(user)
        publish()
    }

    suspend fun setBlocked(user: String, blocked: Boolean) {
        if (blocked) api.block(user) else api.unblock(user)
        store.contact(user)?.let { store.putContact(it.copy(blocked = blocked)) }
        publish()
    }

    /** Trusts the new identity key the server announced for [user]; the safety number must be compared again. */
    fun acceptKeyChange(user: String) {
        val contact = store.contact(user) ?: return
        val key = contact.changedKey ?: return
        engine.forget(user)
        engine.trustIdentity(user, Base64.decode(key))
        store.putContact(contact.copy(identityKey = key, changedKey = null, verified = false))
        publish()
    }

    fun safetyNumber(user: String): SafetyNumber? {
        val self = device() ?: return null
        val contact = store.contact(user) ?: return null
        return engine.safetyNumber(self.user, user, Base64.decode(contact.identityKey))
    }

    /** Compares their safety number code with ours; marks the contact verified when they match. */
    fun verifyScanned(user: String, scanned: ByteArray): Boolean {
        val self = device() ?: return false
        val contact = store.contact(user) ?: return false
        val matches = try {
            engine.matchesScanned(self.user, user, Base64.decode(contact.identityKey), scanned)
        } catch (_: Exception) {
            false
        }
        if (matches) setVerified(user, true)
        return matches
    }

    fun setVerified(user: String, verified: Boolean) {
        store.contact(user)?.let { store.putContact(it.copy(verified = verified)) }
        publish()
    }

    private fun forgetContact(user: String) {
        store.removeContact(user)
        store.removeRequest(user)
        store.removeMessagesOf(user)
        engine.forget(user)
    }

    // --- Messages ------------------------------------------------------------------------------------------------

    /** Sends [body] to [user], encrypted end to end (phase 2's test of the transport). */
    suspend fun sendText(user: String, body: String) {
        val at = now()
        val ids = sender.send(user, Content(text = TextContent(body, at)))
        store.putMessage(TextMessage(ids.firstOrNull() ?: "local-$at", user, outgoing = true, body = body, at = at))
        publish()
    }

    /** One envelope from /ws; true once it is handled (stored, or dropped for good), so it is acknowledged. */
    suspend fun receive(envelope: Envelope): Boolean {
        when (envelope.type) {
            EnvelopeType.PREKEY_MESSAGE, EnvelopeType.MESSAGE -> receiveMessage(envelope)
            EnvelopeType.IDENTITY_KEY_CHANGED -> {
                val contact = store.contact(envelope.senderUser)
                val key = Base64.encode(envelope.ciphertext)
                if (contact != null && key != contact.identityKey) {
                    store.putContact(contact.copy(changedKey = key, verified = false))
                }
            }
            EnvelopeType.PAIRED -> {
                markPaired(envelope.senderUser)
                refreshContacts()
            }
            // PROVISIONING (linking) and group messages come in later phases.
            else -> Unit
        }
        publish()
        return true
    }

    private fun receiveMessage(envelope: Envelope) {
        val self = device() ?: return
        val from = DeviceAddress(envelope.senderUser, envelope.senderDevice)
        val decrypted = try {
            engine.decrypt(from, envelope.type, envelope.ciphertext)
        } catch (_: CryptoException) {
            // Can't be opened now or ever (corrupt, replayed, an untrusted key): dropped, so it isn't sent again.
            return
        }
        val content = ContentCodec.decode(decrypted.plaintext) ?: return
        if (envelope.senderUser == self.user) {
            // A copy of what another phone of this user sent.
            val to = content.destination.takeIf { it.isNotEmpty() } ?: return
            content.text?.let { store.putMessage(TextMessage(envelope.id, to, outgoing = true, body = it.body, at = it.sentAt)) }
            return
        }
        content.pairRequest?.let { return pairRequest(envelope.senderUser, it, decrypted.senderIdentityKey) }
        content.text?.let { text ->
            val contact = store.contact(envelope.senderUser)?.takeIf { it.paired && !it.blocked } ?: return
            store.putMessage(TextMessage(envelope.id, contact.user, outgoing = false, body = text.body, at = envelope.serverTimestamp))
        }
    }

    /**
     * A pair request: kept for the user to confirm when it carries the secret of one of this phone's codes, names its
     * sender, and holds the identity key its session was opened with. Anything else is dropped.
     */
    private fun pairRequest(sender: String, request: PairRequestContent, sessionKey: ByteArray) {
        val secret = PairCode.base64Url.encode(request.secret)
        val invite = store.invites().firstOrNull { it.secret == secret && now() <= it.keptUntil() }
        if (invite == null || request.user != sender || !request.identityKey.contentEquals(sessionKey)) {
            if (store.contact(sender) == null) engine.forget(sender)
            return
        }
        store.removeInvite(invite)
        engine.trustIdentity(sender, sessionKey)
        store.putRequest(PairRequest(sender, Base64.encode(sessionKey), now(), viaLink = invite.viaLink))
        // Inviting them was the decision: an invite's request is confirmed without asking again.
        if (invite.viaLink) {
            scope.launch {
                try {
                    accept(sender)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Left as a request: the user confirms it by hand.
                }
            }
        }
    }

    /** Publishes what the store holds now. */
    fun publish() {
        val messages = store.messages()
        _state.update { state ->
            state.copy(
                contacts = store.contacts().sortedBy { it.user.lowercase() },
                requests = store.requests().sortedBy { it.receivedAt },
                lastMessages = messages.groupBy { it.user }.mapValues { (_, list) -> list.last() },
            )
        }
    }

    companion object {
        /** `wss://host:port/ws` for the GraphQL endpoint `https://host:port/query`; null before enrolling. */
        fun wsUrl(serverUrl: String): String? {
            if (!serverUrl.startsWith("https://")) return null
            val origin = serverUrl.removePrefix("https://").substringBefore('/')
            return "wss://$origin/ws"
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun parseTime(rfc3339: String): Long? = runCatching { kotlin.time.Instant.parse(rfc3339).toEpochMilliseconds() }.getOrNull()

private fun <T> CoroutineScope.launchCollect(flow: StateFlow<T>, block: (T) -> Unit) {
    launch { flow.collect { block(it) } }
}
