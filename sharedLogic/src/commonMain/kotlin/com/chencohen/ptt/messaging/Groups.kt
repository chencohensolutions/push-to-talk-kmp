package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.CryptoEngine
import com.chencohen.ptt.crypto.CryptoException
import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.relay.Envelope
import com.chencohen.ptt.relay.EnvelopeType
import com.chencohen.ptt.relay.ActiveDevice
import com.chencohen.ptt.relay.PushKind
import com.chencohen.ptt.relay.Send
import com.chencohen.ptt.relay.SendResult
import com.chencohen.ptt.relay.SendStatus
import com.chencohen.ptt.relay.UserDevices
import com.chencohen.ptt.settings.EnrolledDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.random.Random

/** The group isn't one this phone can do that in (gone, not a reader yet, or not the owner). */
class GroupUnavailableException(message: String) : Exception(message)

/**
 * Groups on the phone (docs/design.md, "Groups"; the server's docs/messaging.md, "Groups"). The server's `groups`
 * list only routes. Who reads a group is what its owner's devices say, in signed changes ([GroupStateContent]) sent
 * over the pairwise sessions: the name, the picture, and each member with the identity key the owner trusts. Each
 * device has a libsignal sender key per group, handed out over the pairwise sessions only to the readers the owner
 * named ([Group.readers]) and replaced when one goes or a device of theirs does. A group message is then encrypted once
 * (a group Send); when the server's devices aren't the ones it named (STALE_DEVICES), the new devices get the key and it
 * is sent again. A device on the server's list that isn't a reader's is named in the Send (the server insists) but gets
 * no key: what it is given, it can't open.
 *
 * Runs on Messaging's scope: one thing at a time. Nothing here awaits a Send from inside [receive]'s callers (the
 * read loop): what has to go out then is launched.
 */
internal class Groups(
    private val engine: CryptoEngine,
    private val store: MessagingStore,
    private val api: MessagingApi,
    private val sender: Sender,
    private val send: suspend (Send) -> SendResult,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val random: Random,
    private val me: () -> EnrolledDevice?,
    private val publish: () -> Unit,
    private val onText: (Group, TextMessage) -> Unit,
    /** A clip's key in a group (a burst or a voice message): from [author], at the server's time. */
    private val onClip: (group: Group, author: String, clip: ClipContent, at: Long) -> Unit = { _, _, _, _ -> },
    private val onTyping: (Group, String, Boolean) -> Unit,
    /** A member device's live talk key in the group's room. */
    private val onTalkKey: (identity: String, key: TalkKeyContent) -> Unit = { _, _ -> },
    private val attempts: Int = 3,
) {
    private fun self(): EnrolledDevice = me() ?: throw IllegalStateException("This phone isn't registered")

    private fun myKey(): ByteArray = engine.identityKey() ?: throw IllegalStateException("No identity key yet")

    // --- The server's list ------------------------------------------------------------------------------------------

    /**
     * Brings the groups in line with the server's `groups` (after GROUP_CHANGED, and on connecting): who the server
     * routes to, invitations, and groups this user is out of. On the owner's devices, the signed members follow the
     * server's answers: an invited user who accepted becomes a member, one who left or declined goes.
     */
    suspend fun refresh() {
        val self = me() ?: return
        val listed = try {
            api.groups()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val byId = listed.associateBy { it.id }
        for (server in listed) {
            val old = store.group(server.id)
            store.putGroup(
                (old ?: Group(server.id, server.owner, createdAt = now())).copy(
                    // Listed, this user is in it or asked to be, unless the owner's last change left them out.
                    serverMembers = server.members, serverInvited = server.invited,
                    gone = old?.gone == true && old.version > 0 && old.member(self.user) == null,
                )
            )
        }
        for (group in store.groups()) {
            if (group.id in byId || group.gone) continue
            if (group.version == 0L) store.removeGroup(group.id) else store.putGroup(group.copy(gone = true, serverMembers = emptyList(), serverInvited = emptyList()))
        }
        publish()
        for (server in listed) if (server.owner == self.user) followServer(server.id)
    }

    /** The owner's side: the signed members follow the server's answers (accepted, left, declined). */
    private suspend fun followServer(id: String) {
        val self = self()
        val group = store.group(id)?.takeIf { it.owner == self.user && !it.gone && it.version > 0 } ?: return
        val accepted = group.invited.filter { it.user in group.serverMembers }
        val gone = (group.members + group.invited).filter { it.user != self.user && it.user !in group.serverMembers && it.user !in group.serverInvited }
        if (accepted.isEmpty() && gone.isEmpty()) return
        val goneUsers = gone.map { it.user }.toSet()
        change(
            group,
            members = group.members.filter { it.user !in goneUsers } + accepted,
            invited = group.invited.filter { it.user !in goneUsers && accepted.none { a -> a.user == it.user } },
            alsoTell = goneUsers,
        )
    }

    // --- The owner's changes ----------------------------------------------------------------------------------------

    /** A new group owned by this user, named [name], with [picture] (a small JPEG) or none. */
    suspend fun create(name: String, picture: ByteArray?): Group {
        val self = self()
        val server = api.createGroup()
        val group = Group(server.id, self.user, serverMembers = server.members, serverInvited = server.invited, createdAt = now())
        store.putGroup(group)
        return change(group, name = name.trim(), picture = picture, members = listOf(GroupMember(self.user, Base64.encode(myKey()))))
    }

    /** The owner invites [users] (paired contacts): on the server, then in a signed change to everyone. */
    suspend fun invite(id: String, users: List<String>): Group {
        var group = owned(id)
        val added = mutableListOf<GroupMember>()
        for (user in users.distinct()) {
            if (group.member(user) != null) continue
            val contact = store.contact(user)?.takeIf { it.paired && !it.blocked } ?: throw GroupUnavailableException("$user isn't a contact")
            val server = api.inviteToGroup(id, user)
            group = group.copy(serverMembers = server.members, serverInvited = server.invited)
            store.putGroup(group)
            added += GroupMember(user, contact.identityKey)
        }
        if (added.isEmpty()) return group
        return change(group, invited = group.invited + added)
    }

    /** The owner takes [user] out: on the server first, so nothing more reaches them, then a signed change. */
    suspend fun remove(id: String, user: String): Group {
        var group = owned(id)
        if (user == group.owner) throw GroupUnavailableException("The owner can't be removed")
        val server = api.removeFromGroup(id, user)
        group = group.copy(serverMembers = server.members, serverInvited = server.invited)
        store.putGroup(group)
        return change(group, members = group.members.filter { it.user != user }, invited = group.invited.filter { it.user != user }, alsoTell = setOf(user))
    }

    /** The owner renames the group or changes its picture ([picture] null: keeps it; empty: none). */
    suspend fun rename(id: String, name: String, picture: ByteArray?): Group = change(owned(id), name = name.trim(), picture = picture)

    /** The owner deletes the group: everyone is told, end to end, then the server forgets it. */
    suspend fun delete(id: String) {
        val group = owned(id)
        change(group, deleted = true)
        api.deleteGroup(id)
        store.group(id)?.let { store.putGroup(it.copy(gone = true, serverMembers = emptyList(), serverInvited = emptyList())) }
        publish()
    }

    private fun owned(id: String): Group {
        val group = store.group(id) ?: throw GroupUnavailableException("No such group")
        if (group.gone) throw GroupUnavailableException("The group is gone")
        if (group.owner != self().user) throw GroupUnavailableException("Only the owner can change the group")
        return group
    }

    /**
     * A signed change from this phone (the owner's): the group one version on, kept, and sent to everyone in it, to
     * [alsoTell] (who just went) and to this user's other phones. Someone gone means a new sender key.
     */
    private suspend fun change(
        group: Group,
        name: String = group.name,
        picture: ByteArray? = null,
        members: List<GroupMember> = group.members,
        invited: List<GroupMember> = group.invited,
        deleted: Boolean = false,
        alsoTell: Set<String> = emptySet(),
    ): Group {
        val self = self()
        val state = GroupStateContent(
            id = group.id,
            owner = self.user,
            version = maxOf(group.version + 1, now()),
            name = name,
            picture = picture ?: group.picture?.let(Base64::decode) ?: ByteArray(0),
            members = members.map { GroupMemberContent(it.user, Base64.decode(it.identityKey)) },
            invited = invited.map { GroupMemberContent(it.user, Base64.decode(it.identityKey)) },
            deleted = deleted,
        )
        val bytes = GroupStateCodec.encode(state)
        val change = GroupChangeContent(bytes, engine.sign(bytes))
        val updated = take(group, state, change)
        publish()
        val everyone = (state.members + state.invited).map { it.user }.toSet() + alsoTell - self.user
        for (user in everyone) {
            try {
                sender.send(user, Content(groupChange = change), pushKind = PushKind.SYNC)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreachable now (no phone with keys, blocked): they get the next change, or the server's list says enough.
            }
        }
        // Copies go to this user's other phones with every send above; alone in the group, they get one of their own.
        if (everyone.isEmpty()) runCatching { sender.send(self.user, Content(groupChange = change), pushKind = PushKind.SYNC) }
        return store.group(group.id) ?: updated
    }

    // --- Taking a change ----------------------------------------------------------------------------------------

    /**
     * A group change that came over a pairwise session from [from]: taken when it is signed by the identity key of the
     * group's owner (the user who sent it; this user's own key for a change from its other phones) and newer than the
     * one kept. Members this phone doesn't know get the key the owner named trusted (they show as "added by the owner,
     * not verified by you").
     */
    fun receiveChange(from: String, change: GroupChangeContent) {
        val self = me() ?: return
        val state = GroupStateCodec.decode(change.state) ?: return
        if (state.id.isEmpty() || state.owner != from) return
        val ownerKey = if (from == self.user) engine.identityKey() else engine.trustedIdentity(from)
        if (ownerKey == null || !engine.verify(ownerKey, change.state, change.signature)) return
        val old = store.group(state.id)
        if (old != null && (old.owner != state.owner || state.version <= old.version)) return
        // A group this phone never held (or let go of, declining) that it isn't in: nothing to keep.
        if (old == null && (state.deleted || (state.members + state.invited).none { it.user == self.user })) return
        take(old ?: Group(state.id, state.owner, createdAt = now()), state, change)
        // The server's list may have changed with it (an invitation): fetched again, not awaited in the read loop.
        scope.launch { refresh() }
    }

    private fun take(old: Group, state: GroupStateContent, change: GroupChangeContent): Group {
        val self = self()
        val members = state.members.map { GroupMember(it.user, Base64.encode(it.identityKey)) }
        val invited = state.invited.map { GroupMember(it.user, Base64.encode(it.identityKey)) }
        for (member in members + invited) {
            if (member.user == self.user || store.contact(member.user) != null) continue
            // Not a contact: the key the owner named is the one their sessions must be on.
            val key = Base64.decode(member.identityKey)
            val trusted = engine.trustedIdentity(member.user)
            if (trusted == null) {
                engine.trustIdentity(member.user, key)
            } else if (!trusted.contentEquals(key) && !knownElsewhere(member.user, old.id)) {
                engine.forget(member.user)
                engine.trustIdentity(member.user, key)
            }
        }
        val out = state.deleted || (members + invited).none { it.user == self.user }
        var group = old.copy(
            name = state.name,
            picture = state.picture.takeIf { it.isNotEmpty() }?.let(Base64::encode),
            version = state.version,
            members = members,
            invited = invited,
            gone = out,
            signedState = Base64.encode(GroupStateCodec.encode(state)),
        )
        // Someone stopped reading: this device's sender key is replaced before the next message.
        val readersBefore = old.readers.map { it.user }.toSet()
        if ((readersBefore - group.readers.map { it.user }.toSet()).isNotEmpty() || out) group = group.copy(senderKeyId = null, keyHolders = emptyMap())
        store.putGroup(group)
        return group
    }

    /** Whether [user] is a reader of another group this phone holds (their trusted key is kept for it). */
    private fun knownElsewhere(user: String, except: String) = store.groups().any { it.id != except && !it.gone && it.member(user) != null }

    /** Whether this phone keeps a session or trust for [user] because of a group (so it isn't forgotten as a stranger). */
    fun knows(user: String): Boolean = store.groups().any { !it.gone && it.member(user) != null }

    // --- Invitations ------------------------------------------------------------------------------------------------

    suspend fun accept(id: String) {
        val server = api.acceptGroupInvite(id)
        store.group(id)?.let { store.putGroup(it.copy(serverMembers = server.members, serverInvited = server.invited)) }
        publish()
    }

    /** Declines an invitation (the group goes from this phone) or leaves (its chat stays, read only). */
    suspend fun leave(id: String) {
        val group = store.group(id)
        api.leaveGroup(id)
        if (group == null || group.version == 0L || group.isInvitation(self().user)) {
            store.removeGroup(id)
        } else {
            store.putGroup(group.copy(gone = true, serverMembers = emptyList(), serverInvited = emptyList(), senderKeyId = null, keyHolders = emptyMap()))
        }
        publish()
    }

    // --- Sending --------------------------------------------------------------------------------------------------

    /**
     * Sends [content] to group [id], encrypted once with this device's sender key. On STALE_DEVICES the server's
     * devices are kept, readers' new devices get the key over their pairwise sessions, and it goes again; a device
     * that is gone means a new key for everyone.
     */
    suspend fun send(id: String, content: Content, pushKind: Int) {
        val self = self()
        repeat(attempts) {
            var group = store.group(id) ?: throw GroupUnavailableException("No such group")
            if (group.gone || !group.isReader(self.user)) throw GroupUnavailableException("Not in the group")
            if (group.senderKeyId == null) {
                group = group.copy(senderKeyId = newDistributionId(), keyHolders = emptyMap())
                store.putGroup(group)
            }
            val keyId = group.senderKeyId!!
            // Makes the key when it is new: libsignal encrypts only with a sender key it holds.
            engine.senderKeyDistribution(keyId)
            group = handOut(group)
            val ciphertext = engine.groupEncrypt(keyId, ContentCodec.encode(content.copy(group = id)))
            val devices = group.devices.keys.groupBy({ it.substringBeforeLast('.') }, { it.substringAfterLast('.').toInt() })
            val result = send(
                Send(
                    envelopes = listOf(Envelope(type = EnvelopeType.SENDER_KEY_MESSAGE, ciphertext = ciphertext)),
                    pushKind = pushKind,
                    group = id,
                    groupDevices = devices.entries.sortedBy { it.key }.map { (user, numbers) ->
                        UserDevices(user, numbers.sorted().map { n -> ActiveDevice(n, group.keyHolders["$user.$n"] ?: 0) })
                    },
                )
            )
            when (result.status) {
                SendStatus.ACCEPTED -> return
                SendStatus.STALE_DEVICES -> takeDevices(id, result.devices)
                else -> throw SendRefusedException(result.status, result.detail)
            }
        }
        throw SendRefusedException(SendStatus.STALE_DEVICES, "The group's devices kept changing")
    }

    /** The server's devices for the group; a key holder gone (or re-registered) means a new sender key. */
    private fun takeDevices(id: String, listed: List<UserDevices>) {
        val group = store.group(id) ?: return
        val devices = listed.flatMap { u -> u.devices.map { "${u.user}.${it.deviceNumber}" to it.registrationId } }.toMap()
        val lost = group.keyHolders.any { (device, registration) -> devices[device].let { it == null || (it != 0 && registration != 0 && it != registration) } }
        store.putGroup(if (lost) group.copy(devices = devices, senderKeyId = null, keyHolders = emptyMap()) else group.copy(devices = devices))
    }

    /** Gives the current sender key to the readers' devices (and this user's other phones) that don't have it yet. */
    private suspend fun handOut(start: Group): Group {
        val self = self()
        var group = start
        val keyId = group.senderKeyId ?: return group
        val readers = group.readers.map { it.user }.toSet() + self.user
        val missing = group.devices.keys.filter { it !in group.keyHolders }.groupBy { it.substringBeforeLast('.') }
        for ((user, wanting) in missing) {
            // A device the server routes to but the owner didn't name: never given the key.
            if (user !in readers) continue
            try {
                sender.send(user, Content(senderKey = SenderKeyContent(group.id, engine.senderKeyDistribution(keyId))), pushKind = PushKind.SYNC)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No phone with keys, or no session can be made (a changed key): that reader can't open what follows.
                continue
            }
            val given = wanting.associateWith { device -> engine.remoteRegistrationId(DeviceAddress(user, device.substringAfterLast('.').toInt())) ?: 0 }
            group = (store.group(group.id) ?: group).let { it.copy(keyHolders = it.keyHolders + given) }
            store.putGroup(group)
        }
        return group
    }

    private fun newDistributionId(): String {
        val b = random.nextBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    // --- Receiving -------------------------------------------------------------------------------------------------

    /**
     * A sender key from [from] over a pairwise session ([sessionKey]: the identity key that session is on). Taken only
     * from a reader the owner's devices named, on the key the owner named for them, or from this user's other phones.
     */
    fun receiveSenderKey(from: DeviceAddress, sessionKey: ByteArray, content: SenderKeyContent) {
        val self = me() ?: return
        val group = store.group(content.group)?.takeIf { !it.gone } ?: return
        if (from.user == self.user) {
            if (!sessionKey.contentEquals(engine.identityKey())) return
        } else {
            val member = group.readers.firstOrNull { it.user == from.user } ?: return
            if (Base64.encode(sessionKey) != member.identityKey) return
        }
        try {
            engine.takeSenderKey(from, content.distribution)
        } catch (_: CryptoException) {
        }
    }

    /** A SENDER_KEY_MESSAGE the server copied to this device: opened when it comes from a reader of the group. */
    fun receiveMessage(envelope: Envelope) {
        val self = me() ?: return
        val group = store.group(envelope.group)?.takeIf { !it.gone } ?: return
        val author = envelope.senderUser
        if (author != self.user && !group.isReader(author)) return
        val plaintext = try {
            engine.groupDecrypt(DeviceAddress(author, envelope.senderDevice), envelope.ciphertext)
        } catch (_: CryptoException) {
            return
        }
        val content = ContentCodec.decode(plaintext)?.takeIf { it.group == group.id } ?: return
        content.text?.let { text ->
            val id = text.id.ifEmpty { envelope.id }
            if (store.message(id) != null) return
            val message = TextMessage(
                id, group.chat, outgoing = author == self.user, body = text.body,
                at = if (author == self.user) text.sentAt else envelope.serverTimestamp, author = author,
                read = author == self.user,
            )
            onText(group, message)
        }
        content.typing?.let { if (author != self.user) onTyping(group, author, it.typing) }
        content.clip?.let { onClip(group, author, it, envelope.serverTimestamp) }
        content.talkKey?.let { onTalkKey("$author.${envelope.senderDevice}", it) }
    }
}
