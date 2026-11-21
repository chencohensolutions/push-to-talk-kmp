package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.relay.SocketState
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Prefs : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private const val FINGERPRINT = "ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12"

/** One phone: its settings, engine, store and Messaging, against [server]. */
private class Phone(val server: FakeServer, val user: String, val number: Int, scope: TestScope, seed: Int) {
    val settings = PttSettings(Prefs()).apply {
        serverUrl = "https://203.0.113.7:56400/query"
        identityAlias = "ptt-device-$user-$number"
        serverDiscovery = KeptDiscovery(fingerprint = FINGERPRINT, id = "ID")
        device = EnrolledDevice(user, number, "Pixel")
    }
    val engine = FakeCryptoEngine(Random(seed))
    val store = MessagingStore(MemoryStore())
    val address = DeviceAddress(user, number)
    private val job = SupervisorJob()
    val work = CoroutineScope(scope.coroutineContext + job)
    val messaging = Messaging(settings, engine, store, server.api(address), server.connector(address), work, now = { 1_000_000L }, random = Random(seed))
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val pairing = PairingController(messaging, dispatcher, now = { 1_000_000L }, main = dispatcher, refreshCodes = false)
    val controller = MessagingController(messaging, dispatcher, main = dispatcher)

    val state get() = messaging.state.value

    fun stop() {
        pairing.close()
        controller.close()
        job.cancel()
    }
}

class MessagingTest {
    @Test
    fun anInviteLinkPairsAFriendWhoIsEnrolledAlready() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        hen.controller.start()
        dana.controller.start()
        advanceUntilIdle()

        hen.pairing.makeInvite(" Dana@Example.com ")
        advanceUntilIdle()
        val invite = assertNotNull(hen.pairing.currentState.invite)
        assertEquals("dana@example.com", invite.email)
        assertEquals("123456", invite.emailCode, "the code to email with the link")
        val link = assertNotNull(InviteLink.parse(invite.link))
        assertEquals("hen", link.inviter)
        assertEquals(FINGERPRINT, link.fingerprint)
        assertEquals(listOf("https://203.0.113.7:56400"), listOfNotNull(link.server))

        // Hen's own link is refused on her phone; on Dana's it asks "Pair with hen?", then pairs.
        assertTrue(hen.pairing.openInvite("Join me: ${invite.link}."))
        assertEquals(PairingStep.Failed(PairingError.OWN_CODE, "hen"), hen.pairing.currentState.step)
        assertTrue(dana.pairing.openInvite("Join me: ${invite.link}."))
        assertEquals(PairingStep.Scanned("hen"), dana.pairing.currentState.step)
        dana.pairing.pair()
        advanceUntilIdle()
        // Hen's phone knew the invite's secret, so it confirmed without a tap.
        assertEquals(PairingStep.Paired("hen"), dana.pairing.currentState.step)
        assertTrue(setOf("hen", "dana") in server.pairs)
        val henOnDana = assertNotNull(dana.store.contact("hen"))
        val danaOnHen = assertNotNull(hen.store.contact("dana"))
        assertTrue(henOnDana.paired && danaOnHen.paired)
        assertFalse(henOnDana.verified, "a link may have been forwarded: not verified")
        assertFalse(danaOnHen.verified)
        assertTrue(hen.state.requests.isEmpty())

        // The invite worked once.
        dana.pairing.back()
        dana.pairing.openInvite(invite.link)
        dana.pairing.pair()
        advanceUntilIdle()
        assertEquals(PairingStep.Failed(PairingError.EXPIRED, "hen"), dana.pairing.currentState.step)
        hen.stop()
        dana.stop()
    }

    @Test
    fun aPhoneEnrolledByAnInvitePairsWithTheInviterOnceItsKeysAreUp() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        hen.controller.start()
        advanceUntilIdle()
        val link = hen.messaging.newInvite(null).link
        server.displayNames["dana"] = "Dana Cohen"

        // What enrollWithInvite left: the inviter's code with the token the server gave.
        val dana = Phone(server, "dana", 1, this, 2)
        dana.settings.pendingInvitePair = link.pairCode(server.newToken("hen")).format()
        dana.controller.start()
        advanceUntilIdle()
        assertNull(dana.settings.pendingInvitePair)
        assertTrue(setOf("hen", "dana") in server.pairs)
        assertTrue(assertNotNull(dana.store.contact("hen")).paired)
        hen.messaging.refreshContacts()
        assertEquals("Dana Cohen", assertNotNull(hen.store.contact("dana")).displayName)
        hen.stop()
        dana.stop()
    }

    @Test
    fun anInviteLinkReadsBack() {
        val link = InviteLink(
            code = "abcdefghijklmnopqrstuv", server = "https://192.168.50.2:56400", fingerprint = FINGERPRINT,
            key = null, addresses = listOf("https://192.168.50.2:56400", "https://[2001:db8::1]:56400"), inviter = "hen",
            identityKey = byteArrayOf(5) + ByteArray(32) { it.toByte() }, secret = ByteArray(16) { 9 }, email = "dana@example.com",
        )
        assertEquals(link, InviteLink.parse(link.format()))
        assertEquals(link, InviteLink.find("Hen invited you: ${link.format()}!"))
        assertNull(InviteLink.parse(link.format().replace("v=1", "v=2")))
        assertNull(InviteLink.parse(link.format().replace("fingerprint=", "fingerprint=0")))
        assertEquals("abcdefghijklmnopqrstuv", link.enrollmentLink().code)
        assertEquals("ab12ab12ab12ab12", link.pairCode("t").server)
    }

    @Test
    fun uploadsTheKeysOnceAndRefillsBelowTheMark() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        hen.controller.start()
        advanceUntilIdle()
        val ready = assertIs<KeysState.Ready>(hen.state.keys)
        assertEquals(100, ready.count.oneTimePrekeys)
        assertEquals(100, ready.count.oneTimeKyberPrekeys)
        assertNotNull(server.identityKeys["hen"])
        assertEquals(1, hen.engine.kyberPrekeys.count { it.value }, "one last-resort Kyber prekey")
        assertEquals(SocketState.Live, hen.state.socket)

        // Others use up all but 19 of the one-time prekeys: the next start uploads 100 more of each kind.
        val dana = server.api(DeviceAddress("dana", 1))
        server.identityKeys["dana"] = byteArrayOf(5)
        server.pairs += setOf("dana", "hen")
        repeat(81) { dana.prekeyBundles("hen", null) }
        hen.controller.stop()
        advanceUntilIdle()
        hen.controller.start()
        advanceUntilIdle()
        assertEquals(119, assertIs<KeysState.Ready>(hen.state.keys).count.oneTimePrekeys)
        assertEquals(1, hen.engine.kyberPrekeys.count { it.value }, "the last-resort key is uploaded once")
        hen.stop()
    }

    @Test
    fun aPhoneWithoutTheAccountsKeyDoesNotUploadOne() = runTest {
        val server = FakeServer()
        server.identityKeys["hen"] = byteArrayOf(0x05) + ByteArray(32) { 7 }
        val hen = Phone(server, "hen", 2, this, 1)
        hen.controller.start()
        advanceUntilIdle()
        assertEquals(KeysState.OtherIdentity, hen.state.keys)
        assertFalse(server.identityKeys["hen"]!!.contentEquals(hen.engine.identityKey()))
        hen.stop()
    }

    @Test
    fun twoUsersPairByQrCode() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        hen.controller.start()
        dana.controller.start()
        advanceUntilIdle()

        // Hen shows her code; Dana scans it.
        hen.pairing.open()
        advanceUntilIdle()
        val code = assertNotNull(hen.pairing.currentState.code)
        dana.pairing.scanned(code)
        assertEquals(PairingStep.Scanned("hen"), dana.pairing.currentState.step)
        dana.pairing.pair()
        advanceUntilIdle()
        assertEquals(PairingStep.Waiting("hen"), dana.pairing.currentState.step)
        assertEquals(listOf("hen"), dana.state.contacts.map { it.user })
        assertFalse(dana.state.contacts.single().paired)

        // Hen's phone got the pair request with the code's secret, and asks her.
        val request = hen.state.requests.single()
        assertEquals("dana", request.user)
        hen.pairing.review("dana")
        hen.pairing.accept()
        advanceUntilIdle()
        assertEquals(PairingStep.Paired("dana"), hen.pairing.currentState.step)
        assertTrue(setOf("dana", "hen") in server.pairs)

        // The PAIRED envelope tells Dana's phone.
        assertEquals(PairingStep.Paired("hen"), dana.pairing.currentState.step)
        assertTrue(dana.state.contacts.single().paired)
        assertTrue(hen.state.contacts.single { it.user == "dana" }.verified)

        // The safety numbers match, and each side's code verifies on the other.
        val henNumber = hen.messaging.safetyNumber("dana")!!
        val danaNumber = dana.messaging.safetyNumber("hen")!!
        assertEquals(henNumber.digits, danaNumber.digits)
        assertTrue(dana.messaging.verifyScanned("hen", henNumber.scannable))

        // A text goes end to end, both ways.
        dana.messaging.sendText("hen", "hello hen")
        advanceUntilIdle()
        assertEquals("hello hen", hen.state.lastMessages["dana"]?.body)
        hen.messaging.sendText("dana", "hi dana")
        advanceUntilIdle()
        assertEquals("hi dana", dana.state.lastMessages["hen"]?.body)
        assertTrue(server.queues.values.all { it.isEmpty() }, "every envelope was acknowledged")

        hen.stop()
        dana.stop()
    }

    @Test
    fun aPairRequestWithoutTheSecretIsDropped() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val eve = Phone(server, "eve", 1, this, 3)
        hen.controller.start()
        eve.controller.start()
        advanceUntilIdle()
        hen.pairing.open()
        advanceUntilIdle()
        val code = (PairCode.parse(hen.pairing.currentState.code!!) as PairCodeParse.Code).code
        // Eve has the token (say, from the server) but not the secret on Hen's screen.
        eve.messaging.pairWith(code.copy(secret = ByteArray(16)), verified = true)
        advanceUntilIdle()
        assertTrue(hen.state.requests.isEmpty())
        assertNull(hen.engine.trustedIdentity("eve"))
        hen.stop()
        eve.stop()
    }

    @Test
    fun keysOtherThanTheCodesAreRefused() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        hen.controller.start()
        dana.controller.start()
        advanceUntilIdle()
        hen.pairing.open()
        advanceUntilIdle()
        // The server swaps Hen's identity key for its own: Dana's phone compares it with the code's.
        server.identityKeys["hen"] = byteArrayOf(0x05) + ByteArray(32) { 9 }
        dana.pairing.scanned(hen.pairing.currentState.code!!)
        dana.pairing.pair()
        advanceUntilIdle()
        assertEquals(PairingStep.Failed(PairingError.KEYS_DIFFER, "hen"), dana.pairing.currentState.step)
        assertTrue(dana.state.contacts.isEmpty())
        assertEquals(0, server.sends)
        hen.stop()
        dana.stop()
    }

    @Test
    fun codesTheAppCantUseSayWhy() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        hen.controller.start()
        advanceUntilIdle()
        hen.pairing.scanned("https://example.com")
        assertEquals(PairingStep.Failed(PairingError.NOT_A_CODE, null), hen.pairing.currentState.step)
        hen.pairing.scanned("ptt://pair?v=2&u=dana")
        assertEquals(PairingStep.Failed(PairingError.NEWER_VERSION, null), hen.pairing.currentState.step)
        hen.pairing.open()
        advanceUntilIdle()
        hen.pairing.scanned(hen.pairing.currentState.code!!)
        assertEquals(PairingStep.Failed(PairingError.OWN_CODE, "hen"), hen.pairing.currentState.step)
        val other = PairCode("dana", byteArrayOf(0x05) + ByteArray(32), "t", ByteArray(16), "0000000000000000")
        hen.pairing.scanned(other.format())
        assertEquals(PairingStep.Failed(PairingError.OTHER_SERVER, "dana"), hen.pairing.currentState.step)
        hen.stop()
    }

    @Test
    fun unpairAndBlockAndAKeyChange() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        hen.controller.start()
        dana.controller.start()
        advanceUntilIdle()
        hen.pairing.open()
        advanceUntilIdle()
        dana.pairing.scanned(hen.pairing.currentState.code!!)
        dana.pairing.pair()
        advanceUntilIdle()
        hen.pairing.review("dana")
        hen.pairing.accept()
        advanceUntilIdle()

        hen.messaging.setBlocked("dana", true)
        advanceUntilIdle()
        assertTrue(hen.state.contacts.single().blocked)
        val refused = runCatching { dana.messaging.sendText("hen", "hi") }.exceptionOrNull()
        assertIs<SendRefusedException>(refused)
        hen.messaging.setBlocked("dana", false)

        // Dana starts over with a new identity key: Hen's phone is told, and asks before trusting it.
        dana.messaging.replaceIdentityKey()
        advanceUntilIdle()
        hen.controller.stop()
        advanceUntilIdle()
        hen.controller.start()
        advanceUntilIdle()
        val changed = hen.state.contacts.single()
        assertNotNull(changed.changedKey)
        assertFalse(changed.verified)
        hen.messaging.acceptKeyChange("dana")
        assertNull(hen.state.contacts.single().changedKey)

        hen.messaging.unpair("dana")
        advanceUntilIdle()
        assertTrue(hen.state.contacts.isEmpty())
        dana.messaging.refreshContacts()
        assertTrue(dana.state.contacts.isEmpty(), "the other side learns it from the server's contacts")
        hen.stop()
        dana.stop()
    }

    @Test
    fun anotherAccountOnThePhoneStartsFromNothing() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        hen.controller.start()
        advanceUntilIdle()
        val first = hen.engine.identityKey()
        hen.controller.stop()
        advanceUntilIdle()
        hen.settings.device = EnrolledDevice("ruth", 1, "Pixel")
        hen.controller.start()
        advanceUntilIdle()
        assertFalse(first.contentEquals(hen.engine.identityKey()))
        assertTrue(server.identityKeys["ruth"].contentEquals(hen.engine.identityKey()))
        hen.stop()
    }
}

class ContentTest {
    @Test
    fun padsToBlocksAndBack() {
        val content = Content(text = TextContent("hello", 42))
        val padded = ContentCodec.encode(content)
        assertEquals(0, padded.size % ContentCodec.BLOCK)
        assertEquals(content, ContentCodec.decode(padded))
        assertNull(ContentCodec.decode(ByteArray(160)))
    }

    @Test
    fun pairCodesRoundTrip() {
        val code = PairCode("hen.c", byteArrayOf(0x05) + ByteArray(32) { it.toByte() }, "AbCdEfGhIjKlMnOpQrStUv", ByteArray(16) { (it * 7).toByte() }, "ab12ab12ab12ab12")
        val text = code.format()
        assertTrue(text.startsWith("ptt://pair?v=1&u=hen.c&k="), text)
        assertEquals(PairCodeParse.Code(code), PairCode.parse(text))
        assertEquals(PairCodeParse.NotACode, PairCode.parse("ptt://pair?v=1&u=hen&k=AAAA&t=x&s=y"))
        val scannable = byteArrayOf(1, 2, 3)
        assertTrue(SafetyCode.parse(SafetyCode.format(scannable))!!.contentEquals(scannable))
    }
}
