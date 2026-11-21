package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.crypto.PrekeyBundle
import com.chencohen.ptt.crypto.PublicPrekey
import com.chencohen.ptt.crypto.SignedPublicPrekey
import com.chencohen.ptt.relay.Ack
import com.chencohen.ptt.relay.AckFrame
import com.chencohen.ptt.relay.ActiveDevice
import com.chencohen.ptt.relay.Envelope
import com.chencohen.ptt.relay.EnvelopeFrame
import com.chencohen.ptt.relay.EnvelopeType
import com.chencohen.ptt.relay.Frame
import com.chencohen.ptt.relay.FrameConnection
import com.chencohen.ptt.relay.FrameConnector
import com.chencohen.ptt.relay.Frames
import com.chencohen.ptt.relay.QueueEmpty
import com.chencohen.ptt.relay.QueueEmptyFrame
import com.chencohen.ptt.relay.Send
import com.chencohen.ptt.relay.SendFrame
import com.chencohen.ptt.relay.SendResult
import com.chencohen.ptt.relay.SendResultFrame
import com.chencohen.ptt.relay.SendStatus
import com.chencohen.ptt.relay.UserDevices
import kotlinx.coroutines.channels.Channel

/**
 * ptt-server's phase 2 rules in memory (its docs/messaging.md), for the shared tests: keys and prekey bundles,
 * pairing tokens, the pair request routed by a token, confirmPair and the PAIRED envelopes, blocks, contacts, and /ws
 * with its queue, acks, QueueEmpty and the Send checks (NOT_PAIRED, BLOCKED, STALE_DEVICES). The server's own tests
 * check the real thing; this one keeps the client honest about the contract.
 */
internal class FakeServer {
    private class Keys(
        val registrationId: Int,
        var signed: SignedPublicPrekey,
        var lastResort: SignedPublicPrekey,
        val prekeys: ArrayDeque<PublicPrekey> = ArrayDeque(),
        val kyber: ArrayDeque<SignedPublicPrekey> = ArrayDeque(),
    )

    val identityKeys = mutableMapOf<String, ByteArray>()
    private val keys = mutableMapOf<DeviceAddress, Keys>()
    private val tokens = mutableMapOf<String, String>()
    private val tokenFetches = mutableMapOf<String, Int>()
    private val pending = mutableMapOf<Set<String>, MutableSet<String>>()
    val pairs = mutableSetOf<Set<String>>()
    val blocks = mutableSetOf<Pair<String, String>>()
    val queues = mutableMapOf<DeviceAddress, MutableList<Envelope>>()
    private val connections = mutableMapOf<DeviceAddress, Connection>()
    private var nextId = 1
    var sends = 0
        private set

    private fun active(user: String): Map<Int, Int> =
        keys.filterKeys { it.user == user }.entries.associate { it.key.device to it.value.registrationId }

    private fun blocked(a: String, b: String) = (a to b) in blocks || (b to a) in blocks

    fun api(device: DeviceAddress): MessagingApi = object : MessagingApi {
        override suspend fun myIdentityKey(): ByteArray? = identityKeys[device.user]

        override suspend fun uploadPrekeys(upload: PrekeyUpload): PrekeyCount {
            val held = identityKeys[device.user]
            if (held != null && !held.contentEquals(upload.identityKey)) throw ApiException(ApiCodes.IDENTITY_KEY_MISMATCH, "mismatch")
            val stored = keys[device]
            if (stored == null && (upload.signedPrekey == null || upload.kyberLastResort == null)) throw ApiException(ApiCodes.MISSING_KEYS, "missing")
            identityKeys[device.user] = upload.identityKey
            val k = stored ?: Keys(upload.registrationId, upload.signedPrekey!!, upload.kyberLastResort!!).also { keys[device] = it }
            upload.signedPrekey?.let { k.signed = it }
            upload.kyberLastResort?.let { k.lastResort = it }
            k.prekeys += upload.prekeys
            k.kyber += upload.kyberPrekeys
            return prekeyCount()
        }

        override suspend fun prekeyCount(): PrekeyCount {
            val k = keys[device]
            return PrekeyCount(k?.prekeys?.size ?: 0, k?.kyber?.size ?: 0, 20)
        }

        override suspend fun prekeyBundles(user: String, routingToken: String?): Bundles {
            val identity = identityKeys[user] ?: throw ApiException(ApiCodes.UNKNOWN_USER, "unknown")
            if (blocked(device.user, user)) throw ApiException(ApiCodes.BLOCKED, "blocked")
            val allowed = user == device.user || setOf(user, device.user) in pairs ||
                (routingToken != null && tokens[routingToken] == user && (tokenFetches[routingToken] ?: 0) < 5)
            if (!allowed) throw ApiException(ApiCodes.NOT_PAIRED, "not paired")
            routingToken?.let { tokenFetches[it] = (tokenFetches[it] ?: 0) + 1 }
            val bundles = keys.filterKeys { it.user == user && it != device }.entries.sortedBy { it.key.device }.map { (address, k) ->
                PrekeyBundle(address.device, k.registrationId, k.signed, k.prekeys.removeFirstOrNull(), k.kyber.removeFirstOrNull() ?: k.lastResort)
            }
            return Bundles(user, identity, bundles)
        }

        override suspend fun replaceIdentityKey(identityKey: ByteArray): Boolean {
            if (active(device.user).keys.any { it != device.device }) throw ApiException(ApiCodes.OTHER_DEVICES_ACTIVE, "others")
            identityKeys[device.user] = identityKey
            keys.keys.removeAll { it.user == device.user }
            pairs.filter { device.user in it }.flatMap { it - device.user }.forEach { contact ->
                announce(EnvelopeType.IDENTITY_KEY_CHANGED, device.user, identityKey, contact)
            }
            return true
        }

        override suspend fun pairingToken(): Pair<String, String> {
            val token = "token${nextId++}".padEnd(22, 'x')
            tokens[token] = device.user
            return token to "2026-11-21T10:10:00Z"
        }

        override suspend fun confirmPair(user: String): Boolean {
            val both = setOf(user, device.user)
            if (blocked(user, device.user)) throw ApiException(ApiCodes.BLOCKED, "blocked")
            if (both in pairs) return true
            val confirmed = pending[both] ?: throw ApiException(ApiCodes.NO_PAIRING, "no pairing")
            confirmed += device.user
            if (confirmed.size < 2) return false
            pending.remove(both)
            pairs += both
            announce(EnvelopeType.PAIRED, user, ByteArray(0), device.user)
            announce(EnvelopeType.PAIRED, device.user, ByteArray(0), user)
            return true
        }

        override suspend fun unpair(user: String): Boolean = pairs.remove(setOf(user, device.user))

        override suspend fun block(user: String): Boolean = blocks.add(device.user to user)

        override suspend fun unblock(user: String): Boolean = blocks.remove(device.user to user)

        override suspend fun contacts(): ServerContacts = ServerContacts(
            contacts = pairs.filter { device.user in it }.map { pair ->
                val other = (pair - device.user).first()
                ServerContact(other, identityKeys[other], "2026-11-21T10:00:00Z", (device.user to other) in blocks, active(other))
            },
            blocked = blocks.filter { it.first == device.user }.map { it.second },
        )
    }

    private fun announce(type: Int, about: String, payload: ByteArray, to: String) {
        for (device in keys.keys.filter { it.user == to }) {
            enqueue(Envelope(id = "e${nextId++}", senderUser = about, recipientUser = to, recipientDevice = device.device, type = type, ciphertext = payload))
        }
    }

    private fun enqueue(envelope: Envelope) {
        val to = DeviceAddress(envelope.recipientUser, envelope.recipientDevice)
        queues.getOrPut(to) { mutableListOf() } += envelope
        connections[to]?.deliver(envelope)
    }

    fun send(from: DeviceAddress, send: Send): SendResult {
        sends++
        fun result(status: Int, detail: String = "") = SendResult(send.clientId, status, detail = detail)
        val others = send.envelopes.map { it.recipientUser }.filter { it != from.user }.toSet()
        if (send.envelopes.isEmpty() || others.size > 1) return result(SendStatus.INVALID)
        val other = others.firstOrNull()
        if (other != null) {
            if (identityKeys[other] == null && active(other).isEmpty()) return result(SendStatus.UNKNOWN_USER)
            if (blocked(from.user, other)) return result(SendStatus.BLOCKED)
            if (setOf(from.user, other) !in pairs) {
                val token = send.envelopes.filter { it.recipientUser == other }.map { it.routingToken }.toSet().singleOrNull()
                if (token == null || tokens[token] != other) return result(SendStatus.NOT_PAIRED)
            }
        }
        val want = (other?.let { u -> active(u).keys.map { DeviceAddress(u, it) } }.orEmpty() +
            active(from.user).keys.filter { it != from.device }.map { DeviceAddress(from.user, it) }).toSet()
        val got = send.envelopes.map { DeviceAddress(it.recipientUser, it.recipientDevice) }.toSet()
        val staleRegistration = send.envelopes.any { e ->
            e.recipientRegistrationId != 0 && keys[DeviceAddress(e.recipientUser, e.recipientDevice)]?.registrationId != e.recipientRegistrationId
        }
        if (want != got || staleRegistration) {
            return SendResult(
                send.clientId, SendStatus.STALE_DEVICES,
                devices = listOfNotNull(other, from.user).map { u -> UserDevices(u, active(u).map { (n, r) -> ActiveDevice(n, r) }) },
            )
        }
        if (other != null && setOf(from.user, other) !in pairs) {
            val token = send.envelopes.first { it.recipientUser == other }.routingToken
            tokens.remove(token)
            pending.getOrPut(setOf(from.user, other)) { mutableSetOf() }
        }
        val ids = send.envelopes.map { e ->
            val id = "e${nextId++}"
            enqueue(e.copy(id = id, senderUser = from.user, senderDevice = from.device, routingToken = "", recipientRegistrationId = 0, serverTimestamp = 1_000_000L + nextId))
            id
        }
        return SendResult(send.clientId, SendStatus.ACCEPTED, envelopeIds = ids)
    }

    /** Closes [device]'s connection with [code], as the server does on 4001 and 4003. */
    fun close(device: DeviceAddress, code: Int) {
        connections.remove(device)?.end(code)
    }

    fun connector(device: DeviceAddress): FrameConnector = FrameConnector {
        connections.remove(device)?.end(4001)
        Connection(device).also { c ->
            connections[device] = c
            queues[device].orEmpty().forEach(c::deliver)
            c.incoming.trySend(Frames.encode(Frame(QueueEmptyFrame(QueueEmpty()))))
        }
    }

    private inner class Connection(val device: DeviceAddress) : FrameConnection {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private var code: Int? = null

        fun deliver(envelope: Envelope) {
            incoming.trySend(Frames.encode(Frame(EnvelopeFrame(envelope))))
        }

        fun end(code: Int) {
            this.code = code
            incoming.close()
        }

        override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()

        override suspend fun send(bytes: ByteArray) {
            when (val kind = Frames.decode(bytes)?.kind) {
                is AckFrame -> queues[device]?.removeAll { it.id in kind.ack.ids }
                is SendFrame -> incoming.trySend(Frames.encode(Frame(SendResultFrame(send(device, kind.send)))))
                else -> Unit
            }
        }

        override suspend fun close() {
            if (connections[device] === this) connections.remove(device)
            incoming.close()
        }

        override suspend fun closeCode(): Int? = code
    }

}
