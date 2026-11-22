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
    /** Invite codes and who made them (createInvite), until redeemed. */
    val invites = mutableMapOf<String, String>()
    val displayNames = mutableMapOf<String, String>()
    /** registerPush: each device's token and notifications flag. */
    val pushTokens = mutableMapOf<DeviceAddress, Pair<String?, Boolean>>()
    val presenceHidden = mutableSetOf<String>()
    /** The push kind of each accepted Send, in order. */
    val pushKinds = mutableListOf<Int>()

    /** Link codes (createLinkCode) and whose they are. */
    val linkCodes = mutableMapOf<String, String>()
    val blocks = mutableSetOf<Pair<String, String>>()
    val queues = mutableMapOf<DeviceAddress, MutableList<Envelope>>()
    private val connections = mutableMapOf<DeviceAddress, Connection>()
    private var nextId = 1
    var sends = 0
        private set

    private fun active(user: String): Map<Int, Int> =
        keys.filterKeys { it.user == user }.entries.associate { it.key.device to it.value.registrationId }

    private fun blocked(a: String, b: String) = (a to b) in blocks || (b to a) in blocks

    /** A group as groups.json keeps it: only who is in it. */
    class FakeGroup(val id: String, val owner: String, val members: MutableList<String>, val invited: MutableList<String> = mutableListOf())

    val groups = mutableMapOf<String, FakeGroup>()

    private fun shareGroup(a: String, b: String) = groups.values.any { a in it.members && b in it.members }

    private fun FakeGroup.toServer() = ServerGroup(id, owner, members.toList(), invited.toList(), "2026-11-22T10:00:00Z")

    private fun groupOf(id: String, user: String): FakeGroup {
        val g = groups[id] ?: throw ApiException(ApiCodes.NO_GROUP, "no group")
        if (user !in g.members && user !in g.invited) throw ApiException(ApiCodes.NO_GROUP, "no group")
        return g
    }

    /** GROUP_CHANGED to every device of the group's users and of [also], but [by]. */
    private fun announceGroup(g: FakeGroup, by: DeviceAddress, also: Collection<String> = emptyList()) {
        for (user in (g.members + g.invited + also).toSet()) {
            for (device in keys.keys.filter { it.user == user && it != by }) {
                enqueue(Envelope(id = "e${nextId++}", senderUser = by.user, senderDevice = by.device, recipientUser = user, recipientDevice = device.device, type = EnvelopeType.GROUP_CHANGED, group = g.id))
            }
        }
    }

    /** Adds [user] to group [id] as a member on the server's list only (as editing groups.json would). */
    fun addMemberOnServerOnly(id: String, user: String) {
        groups.getValue(id).members += user
    }

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
            val allowed = user == device.user || setOf(user, device.user) in pairs || shareGroup(user, device.user) ||
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

        override suspend fun registerPush(token: String?, notificationsEnabled: Boolean): Boolean {
            pushTokens[device] = token to notificationsEnabled
            return true
        }

        override suspend fun setPresenceVisible(visible: Boolean): Boolean {
            if (visible) presenceHidden -= device.user else presenceHidden += device.user
            return visible
        }

        override suspend fun presence(users: List<String>): List<UserPresence> = users.filter { setOf(it, device.user) in pairs }.map { u ->
            val hidden = u in presenceHidden || device.user in presenceHidden
            UserPresence(u, !hidden && connections.keys.any { it.user == u }, null)
        }

        override suspend fun createLinkCode(): Pair<String, String> {
            val code = "LINK-${nextId++}"
            linkCodes[code] = device.user
            return code to "2026-11-21T10:10:00Z"
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
                ServerContact(other, identityKeys[other], "2026-11-21T10:00:00Z", (device.user to other) in blocks, active(other), displayNames[other])
            },
            blocked = blocks.filter { it.first == device.user }.map { it.second },
        )

        override suspend fun createInvite(email: String?): CreatedInvite {
            val code = "invite${nextId++}".padEnd(22, 'i')
            invites[code] = device.user
            return CreatedInvite(code, "2026-11-28T10:00:00Z", "123456")
        }

        override suspend fun groups(): List<ServerGroup> =
            groups.values.filter { device.user in it.members || device.user in it.invited }.map { it.toServer() }

        override suspend fun createGroup(): ServerGroup {
            val g = FakeGroup("g${nextId++}".padEnd(32, '0'), device.user, mutableListOf(device.user))
            groups[g.id] = g
            announceGroup(g, device)
            return g.toServer()
        }

        override suspend fun inviteToGroup(group: String, user: String): ServerGroup {
            val g = groupOf(group, device.user)
            if (g.owner != device.user) throw ApiException(ApiCodes.NOT_OWNER, "not owner")
            if (setOf(user, device.user) !in pairs) throw ApiException(ApiCodes.NOT_PAIRED, "not paired")
            if (user !in g.members && user !in g.invited) g.invited += user
            announceGroup(g, device)
            return g.toServer()
        }

        override suspend fun acceptGroupInvite(group: String): ServerGroup {
            val g = groupOf(group, device.user)
            if (device.user !in g.invited) throw ApiException(ApiCodes.NOT_INVITED, "not invited")
            g.invited -= device.user
            g.members += device.user
            announceGroup(g, device)
            return g.toServer()
        }

        override suspend fun leaveGroup(group: String): Boolean {
            val g = groupOf(group, device.user)
            if (g.owner == device.user) throw ApiException("OWNER_CANT_LEAVE", "owner")
            g.members -= device.user
            g.invited -= device.user
            announceGroup(g, device, listOf(device.user))
            return true
        }

        override suspend fun removeFromGroup(group: String, user: String): ServerGroup {
            val g = groupOf(group, device.user)
            if (g.owner != device.user) throw ApiException(ApiCodes.NOT_OWNER, "not owner")
            g.members -= user
            g.invited -= user
            announceGroup(g, device, listOf(user))
            return g.toServer()
        }

        override suspend fun deleteGroup(group: String): Boolean {
            val g = groupOf(group, device.user)
            if (g.owner != device.user) throw ApiException(ApiCodes.NOT_OWNER, "not owner")
            groups.remove(group)
            announceGroup(FakeGroup(g.id, g.owner, mutableListOf()), device, g.members + g.invited)
            return true
        }

        override suspend fun acceptInvite(code: String): InvitePairing {
            val inviter = invites[code] ?: throw InviteRefusedException("INVALID_CODE")
            if (inviter == device.user) throw InviteRefusedException("OWN_INVITE")
            invites.remove(code)
            return InvitePairing(inviter, newToken(inviter))
        }
    }

    /** A pairing token of [user], as pairingToken and redeeming an invite make. */
    fun newToken(user: String): String {
        val token = "token${nextId++}".padEnd(22, 'x')
        tokens[token] = user
        return token
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
        // PROVISIONING goes alone, to another enrolled device of the sender's own user, which may have no keys yet.
        if (send.envelopes.any { it.type == EnvelopeType.PROVISIONING }) {
            val e = send.envelopes.singleOrNull()
            if (e == null || e.recipientUser != from.user || e.recipientDevice == from.device) return result(SendStatus.INVALID)
            val id = "e${nextId++}"
            enqueue(e.copy(id = id, senderUser = from.user, senderDevice = from.device, serverTimestamp = 1_000_000L + nextId))
            return SendResult(send.clientId, SendStatus.ACCEPTED, envelopeIds = listOf(id))
        }
        if (send.group.isNotEmpty()) return sendGroup(from, send)
        val others = send.envelopes.map { it.recipientUser }.filter { it != from.user }.toSet()
        if (send.envelopes.isEmpty() || others.size > 1) return result(SendStatus.INVALID)
        val other = others.firstOrNull()
        if (other != null) {
            if (identityKeys[other] == null && active(other).isEmpty()) return result(SendStatus.UNKNOWN_USER)
            if (blocked(from.user, other)) return result(SendStatus.BLOCKED)
            if (setOf(from.user, other) !in pairs && !shareGroup(from.user, other)) {
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
        if (other != null && setOf(from.user, other) !in pairs && !shareGroup(from.user, other)) {
            val token = send.envelopes.first { it.recipientUser == other }.routingToken
            tokens.remove(token)
            pending.getOrPut(setOf(from.user, other)) { mutableSetOf() }
        }
        val ids = send.envelopes.map { e ->
            val id = "e${nextId++}"
            enqueue(e.copy(id = id, senderUser = from.user, senderDevice = from.device, routingToken = "", recipientRegistrationId = 0, serverTimestamp = 1_000_000L + nextId))
            id
        }
        pushKinds += send.pushKind
        return SendResult(send.clientId, SendStatus.ACCEPTED, envelopeIds = ids)
    }

    /** The group Sends accepted, in order: each envelope copied to every member device but the sender's. */
    val groupSends = mutableListOf<Send>()

    private fun sendGroup(from: DeviceAddress, send: Send): SendResult {
        val g = groups[send.group]
        if (g == null || from.user !in g.members) return SendResult(send.clientId, SendStatus.NOT_MEMBER)
        val e = send.envelopes.singleOrNull()
        if (e == null || e.type != EnvelopeType.SENDER_KEY_MESSAGE || e.recipientUser.isNotEmpty()) return SendResult(send.clientId, SendStatus.INVALID)
        val targets = g.members.filter { it == from.user || !blocked(from.user, it) }.sorted().associateWith { u ->
            active(u).filterKeys { !(u == from.user && it == from.device) }
        }
        val named = send.groupDevices.flatMap { u -> u.devices.map { DeviceAddress(u.user, it.deviceNumber) to it.registrationId } }
        val want = targets.flatMap { (u, d) -> d.keys.map { DeviceAddress(u, it) } }.toSet()
        val stale = named.map { it.first }.toSet() != want || named.size != want.size ||
            named.any { (a, r) -> r != 0 && targets[a.user]?.get(a.device) != r }
        if (stale) {
            return SendResult(send.clientId, SendStatus.STALE_DEVICES, devices = targets.map { (u, d) -> UserDevices(u, d.map { (n, r) -> ActiveDevice(n, r) }) })
        }
        groupSends += send
        val ids = want.sortedBy { it.toString() }.map { to ->
            val id = "e${nextId++}"
            enqueue(e.copy(id = id, senderUser = from.user, senderDevice = from.device, recipientUser = to.user, recipientDevice = to.device, group = g.id, serverTimestamp = 1_000_000L + nextId))
            id
        }
        pushKinds += send.pushKind
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
