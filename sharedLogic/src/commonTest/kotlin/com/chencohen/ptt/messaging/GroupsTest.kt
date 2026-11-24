package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.relay.EnvelopeType
import com.chencohen.ptt.relay.PushKind
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.settings.KeyValueStore
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.store.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GroupPrefs : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** One phone against [server], as in MessagingTest. */
private class GroupPhone(val server: FakeServer, val user: String, scope: TestScope, seed: Int, number: Int = 1) {
    val settings = PttSettings(GroupPrefs()).apply {
        serverUrl = "https://203.0.113.7:56400/query"
        identityAlias = "ptt-device-$user-$number"
        serverDiscovery = KeptDiscovery(fingerprint = "ab".repeat(32), id = "ID")
        device = EnrolledDevice(user, number, "Pixel")
    }
    val engine = FakeCryptoEngine(Random(seed))
    val store = MessagingStore(MemoryStore())
    val address = DeviceAddress(user, number)
    private val job = SupervisorJob()
    val work = CoroutineScope(scope.coroutineContext + job)
    val messaging = Messaging(settings, engine, store, server.api(address), server.connector(address), work, now = { 1_000_000L }, random = Random(seed))
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val controller = MessagingController(messaging, dispatcher, main = dispatcher)

    val state get() = messaging.state.value

    fun group(id: String) = state.groups.single { it.id == id }

    fun chat(id: String) = state.conversations[Group.chatOf(id)].orEmpty()

    fun stop() {
        controller.close()
        job.cancel()
    }
}

class GroupsTest {
    /** [a] shows an Add contact code, [b] scans it, [a] confirms. */
    private suspend fun TestScope.pair(a: GroupPhone, b: GroupPhone) {
        val (code, _) = a.messaging.newPairCode()
        b.messaging.pairWith(code, verified = true)
        advanceUntilIdle()
        assertTrue(a.messaging.accept(b.user))
        advanceUntilIdle()
        assertTrue(setOf(a.user, b.user) in a.server.pairs)
    }

    /** Hen owns "Trip" with Dana and Noa in it; Dana and Noa never paired. */
    private suspend fun TestScope.trip(server: FakeServer): Triple<GroupPhone, GroupPhone, GroupPhone> {
        val hen = GroupPhone(server, "hen", this, 1)
        val dana = GroupPhone(server, "dana", this, 2)
        val noa = GroupPhone(server, "noa", this, 3)
        listOf(hen, dana, noa).forEach { it.controller.start() }
        advanceUntilIdle()
        pair(hen, dana)
        pair(hen, noa)
        return Triple(hen, dana, noa)
    }

    private suspend fun TestScope.makeTrip(hen: GroupPhone, dana: GroupPhone, noa: GroupPhone): String {
        val group = hen.messaging.groups.create("Trip", byteArrayOf(1, 2, 3))
        hen.messaging.groups.invite(group.id, listOf("dana", "noa"))
        advanceUntilIdle()
        // The invitation, with the name the owner signed, reaches both.
        for (phone in listOf(dana, noa)) {
            val invited = phone.group(group.id)
            assertTrue(invited.isInvitation(phone.user))
            assertEquals("Trip", invited.name)
            assertEquals("hen", invited.owner)
        }
        dana.messaging.groups.accept(group.id)
        noa.messaging.groups.accept(group.id)
        advanceUntilIdle()
        return group.id
    }

    @Test
    fun threeUsersChatInAGroup() = runTest {
        val server = FakeServer()
        val (hen, dana, noa) = trip(server)
        val id = makeTrip(hen, dana, noa)

        // Hen's phone saw them accept on the server and signed them in as members.
        val henGroup = hen.group(id)
        assertEquals(listOf("hen", "dana", "noa"), henGroup.members.map { it.user })
        assertTrue(henGroup.invited.isEmpty())
        for (phone in listOf(dana, noa)) {
            val g = phone.group(id)
            assertEquals(henGroup.version, g.version)
            assertEquals(listOf("hen", "dana", "noa"), g.readers.map { it.user })
            assertEquals(Base64.encode(byteArrayOf(1, 2, 3)), g.picture)
            assertFalse(g.isInvitation(phone.user))
        }
        // Noa isn't Dana's contact: the key the owner named is the one trusted, "not verified by you".
        assertNull(dana.store.contact("noa"))
        assertEquals(Base64.encode(noa.engine.identityKey()!!), Base64.encode(dana.engine.trustedIdentity("noa")!!))

        hen.messaging.sendText(Group.chatOf(id), "Hello trip")
        advanceUntilIdle()
        assertEquals(MessageStatus.SENT, hen.chat(id).single().status)
        for (phone in listOf(dana, noa)) {
            val m = phone.chat(id).single()
            assertEquals("Hello trip", m.body)
            assertEquals("hen", m.author)
            assertFalse(m.outgoing)
        }
        assertEquals(1, server.groupSends.size, "encrypted once, one group Send (after STALE_DEVICES)")
        assertEquals(1, server.groupSends.single().envelopes.size)

        noa.messaging.sendText(Group.chatOf(id), "Hi from Noa")
        advanceUntilIdle()
        assertEquals("noa", dana.chat(id).last().author)
        assertEquals("Hi from Noa", hen.chat(id).last().body)
        assertEquals(2, dana.state.unread[Group.chatOf(id)])
        dana.messaging.markRead(Group.chatOf(id))
        assertNull(dana.state.unread[Group.chatOf(id)])
        advanceUntilIdle()
        // Dana's read receipt reaches Hen through the group: her circle under Hen's message.
        assertTrue("dana" in hen.chat(id).first { it.outgoing }.readBy, "a member's read receipt in the group")
        assertFalse("hen" in hen.chat(id).first { it.outgoing }.readBy)

        // Rename: everyone takes the owner's newer change.
        hen.messaging.groups.rename(id, "Trip 2026", null)
        advanceUntilIdle()
        assertEquals("Trip 2026", dana.group(id).name)
        assertEquals("Trip 2026", noa.group(id).name)
        assertTrue(server.queues.values.all { it.isEmpty() }, "every envelope was acknowledged")
        listOf(hen, dana, noa).forEach { it.stop() }
    }

    @Test
    fun aRemovedMemberCantReadLaterMessages() = runTest {
        val server = FakeServer()
        val (hen, dana, noa) = trip(server)
        val id = makeTrip(hen, dana, noa)
        dana.messaging.sendText(Group.chatOf(id), "before")
        advanceUntilIdle()
        assertEquals("before", noa.chat(id).single().body)
        val oldKey = assertNotNull(dana.group(id).senderKeyId)

        hen.messaging.groups.remove(id, "noa")
        advanceUntilIdle()
        assertTrue(noa.group(id).gone, "Noa's phone knows it is out")
        assertEquals(listOf("hen", "dana"), dana.group(id).readers.map { it.user })
        assertNull(dana.group(id).senderKeyId, "Dana's key goes with Noa")

        dana.messaging.sendText(Group.chatOf(id), "after")
        advanceUntilIdle()
        assertEquals("after", hen.chat(id).last().body)
        assertEquals(listOf("before"), noa.chat(id).map { it.body })
        val newKey = assertNotNull(dana.group(id).senderKeyId)
        assertNotEquals(oldKey, newKey)
        assertFalse(noa.engine.takenSenderKeys.any { it.second == newKey }, "Noa never got Dana's new key")

        // Even a copy of the new message, had the server sent it, wouldn't open on Noa's phone.
        val sent = server.groupSends.last().envelopes.single()
        assertTrue(runCatching { noa.engine.groupDecrypt(DeviceAddress("dana", 1), sent.ciphertext) }.isFailure)
        listOf(hen, dana, noa).forEach { it.stop() }
    }

    @Test
    fun aMemberAddedOnlyOnTheServersListGetsNoSenderKey() = runTest {
        val server = FakeServer()
        val (hen, dana, noa) = trip(server)
        val id = makeTrip(hen, dana, noa)
        // Eve is enrolled with keys, and someone with the server's files put her in the group.
        val eve = GroupPhone(server, "eve", this, 4)
        eve.controller.start()
        advanceUntilIdle()
        server.addMemberOnServerOnly(id, "eve")

        hen.messaging.sendText(Group.chatOf(id), "secret plan")
        advanceUntilIdle()
        assertEquals("secret plan", dana.chat(id).single().body)
        assertEquals("secret plan", noa.chat(id).single().body)
        // The server copied it to Eve (the Send had to name her device), but no sender key went to her.
        val toEve = server.groupSends.last().groupDevices.single { it.user == "eve" }.devices.single()
        assertEquals(0, toEve.registrationId, "named for the server, given nothing")
        assertTrue(eve.engine.takenSenderKeys.isEmpty())
        assertTrue(eve.chat(id).isEmpty())
        assertTrue(runCatching { eve.engine.groupDecrypt(DeviceAddress("hen", 1), server.groupSends.last().envelopes.single().ciphertext) }.isFailure)

        // Nor do the members take hers: her sender key and group message are dropped.
        val eveSender = Sender(eve.engine, server.api(eve.address), eve.messaging.socket::send, { eve.settings.device })
        eve.engine.trustIdentity("dana", dana.engine.identityKey()!!)
        val distributionId = "00000000-0000-4000-8000-000000000001"
        eveSender.send("dana", Content(senderKey = SenderKeyContent(id, eve.engine.senderKeyDistribution(distributionId))))
        advanceUntilIdle()
        assertFalse(dana.engine.takenSenderKeys.any { it.first.user == "eve" })
        listOf(hen, dana, noa, eve).forEach { it.stop() }
    }

    @Test
    fun onlyTheOwnersSignedChangesAreTaken() = runTest {
        val server = FakeServer()
        val (hen, dana, noa) = trip(server)
        val id = makeTrip(hen, dana, noa)
        val version = noa.group(id).version

        // Dana sends Noa a change in hen's name, signed with Dana's own key: refused.
        val forged = GroupStateContent(id = id, owner = "hen", version = version + 10, name = "Dana's now", members = listOf(GroupMemberContent("dana", dana.engine.identityKey()!!)))
        val bytes = GroupStateCodec.encode(forged)
        val danaSender = Sender(dana.engine, server.api(dana.address), dana.messaging.socket::send, { dana.settings.device })
        danaSender.send("noa", Content(groupChange = GroupChangeContent(bytes, dana.engine.sign(bytes))), pushKind = PushKind.SYNC)
        advanceUntilIdle()
        assertEquals("Trip", noa.group(id).name)
        assertEquals(version, noa.group(id).version)
        listOf(hen, dana, noa).forEach { it.stop() }
    }

    @Test
    fun declineLeaveAndDelete() = runTest {
        val server = FakeServer()
        val hen = GroupPhone(server, "hen", this, 1)
        val dana = GroupPhone(server, "dana", this, 2)
        val noa = GroupPhone(server, "noa", this, 3)
        listOf(hen, dana, noa).forEach { it.controller.start() }
        advanceUntilIdle()
        pair(hen, dana)
        pair(hen, noa)
        val id = hen.messaging.groups.create("Family", null).id
        hen.messaging.groups.invite(id, listOf("dana", "noa"))
        advanceUntilIdle()

        // Noa declines: the invitation goes from her phone, and hen's signed list drops her.
        noa.messaging.groups.leave(id)
        dana.messaging.groups.accept(id)
        advanceUntilIdle()
        assertTrue(noa.state.groups.none { it.id == id })
        assertEquals(listOf("hen", "dana"), hen.group(id).members.map { it.user })
        assertTrue(hen.group(id).invited.isEmpty())

        // Dana leaves: her chat stays, read only; hen's list drops her.
        dana.messaging.groups.leave(id)
        advanceUntilIdle()
        assertTrue(dana.group(id).gone)
        assertEquals(listOf("hen"), hen.group(id).members.map { it.user })

        // Hen invites Dana again, she accepts, then hen deletes the group.
        hen.messaging.groups.invite(id, listOf("dana"))
        advanceUntilIdle()
        assertTrue(dana.group(id).isInvitation("dana"))
        dana.messaging.groups.accept(id)
        advanceUntilIdle()
        assertFalse(dana.group(id).gone)
        hen.messaging.groups.delete(id)
        advanceUntilIdle()
        assertTrue(dana.group(id).gone)
        assertTrue(hen.group(id).gone)
        assertTrue(server.groups.isEmpty())
        // A text to a gone group fails at once.
        assertEquals(MessageStatus.FAILED, dana.messaging.sendText(Group.chatOf(id), "anyone?").status)
        listOf(hen, dana, noa).forEach { it.stop() }
    }

    @Test
    fun picturesReachContactsAndGroupMembers() = runTest {
        // 2026-11-24 (hen): a picture goes end to end to contacts and to each group; only once each, and its removal too.
        val server = FakeServer()
        val (hen, dana, noa) = trip(server)
        val id = makeTrip(hen, dana, noa)
        val face = ByteArray(2_000) { (it % 251).toByte() }
        hen.messaging.setMyPicture(face)
        advanceUntilIdle()
        val pictureId = hen.state.pictures[""]
        assertNotNull(pictureId)
        for (phone in listOf(dana, noa)) {
            assertEquals(pictureId, phone.state.pictures["hen"], "${phone.user} got it")
            assertContentEquals(face, phone.messaging.picture("hen"))
        }
        // Said again (the app opened): no second copy of the bytes.
        val before = server.groupSends.size
        hen.messaging.shareProfile()
        advanceUntilIdle()
        assertEquals(before, server.groupSends.size, "the group already has this picture")

        hen.messaging.setMyPicture(null)
        advanceUntilIdle()
        assertNull(hen.state.pictures[""])
        for (phone in listOf(dana, noa)) assertNull(phone.state.pictures["hen"], "${phone.user}: gone")
        listOf(hen, dana, noa).forEach { it.stop() }
    }

    @Test
    fun groupChangedEnvelopesCarryTheGroup() = runTest {
        val server = FakeServer()
        val hen = GroupPhone(server, "hen", this, 1)
        hen.controller.start()
        advanceUntilIdle()
        val id = hen.messaging.groups.create("Solo", null).id
        // Alone with one phone, a message goes to nobody and is accepted.
        hen.messaging.sendText(Group.chatOf(id), "note to self")
        advanceUntilIdle()
        assertEquals(MessageStatus.SENT, hen.chat(id).single().status)
        assertEquals(EnvelopeType.SENDER_KEY_MESSAGE, server.groupSends.single().envelopes.single().type)
        hen.stop()
    }
}
