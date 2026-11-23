package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.relay.PushKind
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
        assertEquals("123456", invite.passcode, "the passcode, sent beside the link")
        assertFalse("123456" in invite.link, "the passcode isn't in the link")
        assertEquals("123456", InviteLink.parse(invite.faceToFace)?.passcode, "only in the face-to-face QR code")
        assertEquals(InviteLink.parse(invite.link), InviteLink.parse(invite.faceToFace)?.copy(passcode = null))
        val link = assertNotNull(InviteLink.parse(invite.link))
        assertEquals("hen", link.inviter)
        assertEquals(FINGERPRINT, link.fingerprint)
        assertEquals(listOf("https://203.0.113.7:56400"), listOfNotNull(link.server))

        // Hen's own link is refused on her phone; on Dana's it asks "Pair with hen?", then pairs.
        assertTrue(hen.pairing.openInvite("Join me: ${invite.link}."))
        assertEquals(PairingStep.Failed(PairingError.OWN_CODE, "hen"), hen.pairing.currentState.step)
        // Dana, enrolled already, scans the invite's face-to-face QR code in Add contact: it redeems the invite.
        dana.pairing.scanned(invite.faceToFace)
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
            passcode = null,
        )
        assertEquals(link, InviteLink.parse(link.format()))
        // The face-to-face QR code carries the passcode too; a malformed one is left out.
        val faceToFace = link.withPasscode("042917")
        assertEquals(faceToFace, InviteLink.parse(faceToFace.format()))
        assertEquals("042917", InviteLink.parse(faceToFace.format())?.passcode)
        assertNull(InviteLink.parse(faceToFace.format().replace("p=042917", "p=0429"))?.passcode)
        assertEquals(link, InviteLink.find("Hen invited you: ${link.format()}!"))
        assertNull(InviteLink.parse(link.format().replace("v=1", "v=2")))
        assertNull(InviteLink.parse(link.format().replace("fingerprint=", "fingerprint=0")))
        assertEquals("abcdefghijklmnopqrstuv", link.enrollmentLink().code)
        assertEquals("ab12ab12ab12ab12", link.pairCode("t").server)
    }

    /** The invite's message: the steps, the link and the passcode, as Email the invite and Share send it. */
    @Test
    fun anInviteMessageCarriesTheLinkAndThePasscode() {
        val link = InviteLink(
            code = "abc123456defghijklmnop", server = "https://192.168.50.2:56400", fingerprint = "ab".repeat(32),
            key = null, addresses = emptyList(), inviter = "hen", identityKey = byteArrayOf(5) + ByteArray(32) { 3 },
            secret = ByteArray(16) { 1 }, email = "dana@example.com", passcode = null,
        ).format()
        val message = MadeInvite(link, "dana@example.com", 0, "hen", "042917", "$link&p=042917").message("Nov 29, 2026")
        assertEquals("hen invited you to Talk", message.subject)
        assertContains(message.body, "Open this link on your phone: $link\n")
        assertContains(message.body, "type this code: 042 917")
        assertContains(message.body, "until Nov 29, 2026")

        // What the friend's phone finds in it when the whole message is pasted, and the link with it.
        assertEquals("042917", InviteMessage.passcodeIn(message.body))
        assertEquals(link, InviteLink.find(message.body)?.format())
        assertEquals("123456", InviteMessage.passcodeIn("code 123-456"))
        assertNull(InviteMessage.passcodeIn("only the link: $link"), "digits in the invite code aren't the passcode")
        assertNull(InviteMessage.passcodeIn("call 0521234567"))

        // mailto: the address, the subject and the body, percent-encoded with CRLF line ends.
        val mailto = message.mailto("dana@example.com")
        assertTrue(mailto.startsWith("mailto:dana@example.com?subject=hen%20invited%20you%20to%20Talk&body=hen%20invited"), mailto)
        assertContains(mailto, "%0D%0A%0D%0A1.%20Install")
        assertContains(mailto, "ptt%3A%2F%2Finvite%3Fv%3D1%26code%3Dabc123456defghijklmnop")
        assertContains(mailto, "weren%E2%80%99t")
        assertEquals(1, mailto.count { it == '&' }, "the link's own & is encoded")
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
        // The server takes a code once: her screen shows a new one.
        val next = assertNotNull(hen.pairing.currentState.code)
        assertNotEquals(code, next, "a used code isn't shown again")
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

    /** Hen and Dana paired by QR code (verified on hen's side), both phones started. */
    private suspend fun TestScope.pairedByQr(server: FakeServer, hen: Phone, dana: Phone) {
        hen.controller.start()
        dana.controller.start()
        advanceUntilIdle()
        hen.pairing.open()
        advanceUntilIdle()
        dana.pairing.scanned(assertNotNull(hen.pairing.currentState.code))
        dana.pairing.pair()
        advanceUntilIdle()
        hen.pairing.review("dana")
        hen.pairing.accept()
        advanceUntilIdle()
        assertTrue(setOf("dana", "hen") in server.pairs)
    }

    @Test
    fun aSecondPhoneLinksAndGetsTheKeyAndTheContacts() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)

        // Hen's phone shows Link a phone; her new phone enrolled with its code (device 2) and starts.
        val (code, _) = hen.messaging.newLinkCode()
        assertEquals("hen", code.user)
        assertEquals(1, code.device)
        assertEquals("LINK-${code.code.removePrefix("LINK-")}", code.code)
        assertEquals(code, LinkCode.parse(code.format()))
        val second = Phone(server, "hen", 2, this, 3)
        second.settings.pendingLink = code.format()
        second.controller.start()
        advanceUntilIdle()

        // The request reached hen's phone, which answered once: the new phone has the same identity key and Dana.
        assertNull(second.settings.pendingLink)
        assertIs<KeysState.Ready>(second.state.keys)
        assertContentEquals(hen.engine.identityKey(), second.engine.identityKey())
        assertTrue(second.engine.registrationId() != 0)
        val danaThere = second.state.contacts.single()
        assertEquals("dana", danaThere.user)
        assertTrue(danaThere.verified, "verified on hen's phone, so on the linked one too")
        assertEquals(2, hen.state.linkedDevice)
        assertTrue(hen.store.linkOffers().isEmpty(), "the code is used up")

        // Both of hen's phones get Dana's texts, and see what the other sent.
        dana.messaging.sendText("hen", "to both")
        advanceUntilIdle()
        assertEquals("to both", hen.state.lastMessages["dana"]?.body)
        assertEquals("to both", second.state.lastMessages["dana"]?.body)
        second.messaging.sendText("dana", "from the new one")
        advanceUntilIdle()
        assertEquals("from the new one", dana.state.lastMessages["hen"]?.body)
        assertTrue(hen.store.messages().any { it.outgoing && it.user == "dana" && it.body == "from the new one" }, "the copy for hen's other phone")

        // A change on one of hen's phones reaches the other: Dana no longer verified, then a new contact.
        hen.messaging.setVerified("dana", false)
        advanceUntilIdle()
        assertFalse(second.store.contact("dana")!!.verified)
        val ori = Phone(server, "ori", 1, this, 4)
        ori.controller.start()
        advanceUntilIdle()
        second.pairing.open()
        advanceUntilIdle()
        ori.pairing.scanned(assertNotNull(second.pairing.currentState.code))
        ori.pairing.pair()
        advanceUntilIdle()
        second.pairing.review("ori")
        second.pairing.accept()
        advanceUntilIdle()
        val oriOnHen = assertNotNull(hen.store.contact("ori"))
        assertTrue(oriOnHen.verified, "paired in person on the other phone: verified here too")
        assertContentEquals(ori.engine.identityKey(), hen.engine.trustedIdentity("ori"))

        listOf(hen, dana, second, ori).forEach { it.stop() }
    }

    @Test
    fun aPhoneSeesItsUsersPhonesAndRevokesAnother() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)
        val (code, _) = hen.messaging.newLinkCode()
        val second = Phone(server, "hen", 2, this, 3)
        second.settings.pendingLink = code.format()
        second.controller.start()
        advanceUntilIdle()

        // This phone first, then by number; Dana's phone isn't hen's.
        val devices = hen.messaging.myDevices()
        assertEquals(listOf(1, 2), devices.map { it.deviceNumber })
        assertTrue(devices[0].current)
        assertFalse(devices[1].current)

        // Not itself; the other one, and then it gets nothing more.
        assertFalse(hen.messaging.revokeDevice(1))
        assertTrue(hen.messaging.revokeDevice(2))
        assertTrue(DeviceAddress("hen", 2) in server.revoked)
        assertEquals(listOf(1), hen.messaging.myDevices().map { it.deviceNumber })
        dana.messaging.sendText("hen", "only the first")
        advanceUntilIdle()
        assertEquals("only the first", hen.state.lastMessages["dana"]?.body)
        assertTrue(second.state.lastMessages["dana"]?.body != "only the first")

        listOf(hen, dana, second).forEach { it.stop() }
    }

    @Test
    fun aLinkRequestWithoutTheCodesSecretGetsNothing() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        hen.controller.start()
        advanceUntilIdle()
        val (code, _) = hen.messaging.newLinkCode()

        // Someone with a code for hen's account but not the QR code's secret (a code from the admin, say).
        val other = Phone(server, "hen", 2, this, 3)
        other.settings.pendingLink = code.copy(secret = ByteArray(LinkCode.SECRET_BYTES) { 9 }).format()
        other.controller.start()
        advanceUntilIdle()
        assertEquals(KeysState.Linking("hen", 1), other.state.keys)
        assertNull(other.engine.identityKey())
        assertNull(hen.state.linkedDevice)
        assertEquals(1, hen.store.linkOffers().size, "the real code still works")

        // Another user's phone can't send hen's phone a PROVISIONING envelope at all.
        val dana = Phone(server, "dana", 1, this, 2)
        dana.settings.pendingLink = code.format()
        dana.controller.start()
        advanceUntilIdle()
        assertNull(dana.store.linkJoin, "a code for another account is ignored")
        assertNull(hen.state.linkedDevice)

        listOf(hen, other, dana).forEach { it.stop() }
    }

    @Test
    fun linkCodesRoundTrip() {
        val code = LinkCode(
            code = "K7QM-4XPD", server = "https://203.0.113.7:56400", fingerprint = FINGERPRINT, key = null,
            addresses = listOf("https://192.168.50.2:56400"), user = "hen", device = 3, secret = ByteArray(32) { it.toByte() },
        )
        val text = code.format()
        assertTrue(text.startsWith("ptt://link?v=1&code=K7QM-4XPD"))
        assertEquals(code, LinkCode.parse(text))
        assertEquals(code, LinkCode.find("look: $text."))
        assertNull(LinkCode.parse(text.replace("v=1", "v=2")), "a newer version")
        assertNull(LinkCode.parse(text.replace("&d=3", "&d=0")))
        assertNull(LinkCode.parse(text.replace("ptt://link", "ptt://pair")))
        assertEquals(code.enrollmentLink().code, "K7QM-4XPD")
    }

    @Test
    fun textsAreDeliveredAndReadWithReceipts() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)
        val notified = mutableListOf<String>()
        val readOn = mutableListOf<String>()
        hen.messaging.notifier = object : MessageNotifier {
            override fun incoming(contact: Contact, unread: List<TextMessage>) {
                notified += unread.joinToString("|") { it.body }
            }

            override fun read(user: String) {
                readOn += user
            }
        }

        val sent = dana.messaging.sendText("hen", "hi hen")
        advanceUntilIdle()
        assertEquals(PushKind.MESSAGE, server.pushKinds.last { it != PushKind.SYNC })
        val got = hen.store.messagesWith("dana").single()
        assertEquals(sent.id, got.id, "the text's own id on both phones")
        assertFalse(got.read)
        assertEquals(1, hen.state.unread["dana"])
        assertEquals(listOf("hi hen"), notified)
        assertEquals(MessageStatus.DELIVERED, dana.store.message(sent.id)!!.status)

        hen.messaging.markRead("dana")
        advanceUntilIdle()
        assertEquals(MessageStatus.READ, dana.store.message(sent.id)!!.status)
        assertNull(hen.state.unread["dana"])
        assertEquals(listOf("dana"), readOn)

        // A conversation on screen reads what comes in at once, with no notification.
        hen.messaging.openChat = "dana"
        val second = dana.messaging.sendText("hen", "again")
        advanceUntilIdle()
        assertEquals(MessageStatus.READ, dana.store.message(second.id)!!.status)
        assertEquals(1, notified.size)
        listOf(hen, dana).forEach { it.stop() }
    }

    @Test
    fun anEditReplacesTheTextOnTheirPhoneAndOnlyTheAuthorCanEdit() = runTest {
        // 2026-11-24 (hen): editing a sent text.
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)
        val sent = hen.messaging.sendText("dana", "see you at 5")
        val theirs = dana.messaging.sendText("hen", "ok")
        advanceUntilIdle()
        assertTrue(hen.messaging.editText(sent.id, "see you at 6"))
        advanceUntilIdle()
        assertEquals("see you at 6", hen.store.message(sent.id)!!.body)
        assertNotNull(hen.store.message(sent.id)!!.editedAt)
        assertEquals("see you at 6", dana.store.message(sent.id)!!.body, "their phone has the new text")
        assertEquals(hen.store.message(sent.id)!!.editedAt, dana.store.message(sent.id)!!.editedAt, "and \"edited\"")
        // Not mine: hen can't edit dana's text, and dana can't edit hen's.
        assertFalse(hen.messaging.editText(theirs.id, "nope"))
        dana.messaging.editText(sent.id, "hacked")
        advanceUntilIdle()
        assertEquals("see you at 6", hen.store.message(sent.id)!!.body)
        assertEquals("see you at 6", dana.store.message(sent.id)!!.body)
        // An id nobody has: nothing changes.
        assertFalse(hen.messaging.editText("no-such-id", "x"))
        listOf(hen, dana).forEach { it.stop() }
    }

    @Test
    fun aDeleteRemovesItForEveryoneAndOnlyTheAuthorCanDelete() = runTest {
        // 2026-11-24 (hen): delete for everyone.
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)
        val mine = hen.messaging.sendText("dana", "oops")
        val theirs = dana.messaging.sendText("hen", "keep this")
        advanceUntilIdle()
        // Not the author: nothing changes, on either side.
        assertFalse(hen.messaging.deleteMessage(theirs.id))
        dana.messaging.deleteMessage(mine.id)
        advanceUntilIdle()
        assertEquals("oops", hen.store.message(mine.id)?.body)
        assertEquals("oops", dana.store.message(mine.id)?.body)
        // An id nobody has: ignored.
        assertFalse(hen.messaging.deleteMessage("no-such-id"))
        // The author: gone on hen's phone, a "Message deleted" mark on dana's.
        assertTrue(hen.messaging.deleteMessage(mine.id))
        advanceUntilIdle()
        assertNull(hen.store.message(mine.id))
        val left = dana.store.message(mine.id)!!
        assertTrue(left.deleted)
        assertEquals("", left.body)
        assertEquals("keep this", hen.store.message(theirs.id)?.body)
        listOf(hen, dana).forEach { it.stop() }
    }

    @Test
    fun aLiveEditShowsAndGoesOnCancelOrAfterAWhile() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)
        val sent = hen.messaging.sendText("dana", "hello")
        advanceUntilIdle()
        // Live typing off: only that it is being edited.
        hen.messaging.editLive(sent.id, "hello th")
        advanceTimeBy(500)
        assertTrue(sent.id in dana.state.liveEdits)
        assertNull(dana.state.liveEdits[sent.id]!!.text, "no letters with Live typing off")
        assertTrue(server.ephemeral.last(), "live only")
        hen.messaging.editLive(sent.id, null)
        advanceTimeBy(500)
        assertFalse(sent.id in dana.state.liveEdits, "a cancel clears it")
        assertEquals("hello", dana.store.message(sent.id)!!.body)
        // Live typing on: the letters, then gone after a while without a word.
        hen.messaging.setTalkSettings(hen.messaging.talkSettings().copy(liveTyping = true, liveTypingFor = mapOf("dana" to true)))
        hen.messaging.editLive(sent.id, "hello there")
        advanceTimeBy(500)
        assertEquals("hello there", dana.state.liveEdits[sent.id]?.text)
        hen.stop()
        advanceTimeBy(Draft.FRESH_MS + 1_000)
        assertFalse(sent.id in dana.state.liveEdits, "it fades")
        dana.stop()
    }

    @Test
    fun writingBackReadsTheirMessagesFirst() = runTest {
        // 2026-11-24 (hen): typing or a live draft to them counts as read, the receipt before the draft.
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)
        val first = dana.messaging.sendText("hen", "are you there?")
        advanceUntilIdle()
        assertEquals(MessageStatus.DELIVERED, dana.store.message(first.id)!!.status)
        hen.messaging.sendTyping("dana", true)
        advanceUntilIdle()
        assertEquals(MessageStatus.READ, dana.store.message(first.id)!!.status, "typing reads")
        assertNull(hen.state.unread["dana"])

        // With live typing: the receipt is there by the time the draft shows.
        hen.messaging.setTalkSettings(hen.messaging.talkSettings().copy(liveTyping = true, liveTypingFor = mapOf("dana" to true)))
        val second = dana.messaging.sendText("hen", "hello?")
        advanceUntilIdle()
        var readWhenDraftCame: MessageStatus? = null
        hen.messaging.draft("dana", "y")
        while (readWhenDraftCame == null) {
            advanceTimeBy(10)
            if ("hen" in dana.state.drafts) readWhenDraftCame = dana.store.message(second.id)!!.status
        }
        assertEquals(MessageStatus.READ, readWhenDraftCame, "read before the draft")
        listOf(hen, dana).forEach { it.stop() }
    }

    @Test
    fun anOfflineTextWaitsInTheOutbox() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)
        dana.controller.stop()
        advanceUntilIdle()

        val waiting = dana.messaging.sendText("hen", "later")
        assertEquals(MessageStatus.PENDING, waiting.status)
        assertTrue(hen.store.messagesWith("dana").isEmpty())

        dana.controller.start()
        advanceUntilIdle()
        assertEquals("later", hen.store.messagesWith("dana").single().body)
        assertEquals(MessageStatus.DELIVERED, dana.store.message(waiting.id)!!.status)
        listOf(hen, dana).forEach { it.stop() }
    }

    @Test
    fun aPushWakesThePhoneForItsQueue() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)
        hen.controller.stop()
        advanceUntilIdle()
        dana.messaging.sendText("hen", "wake up")
        advanceUntilIdle()
        assertTrue(hen.store.messagesWith("dana").isEmpty(), "asleep: nothing yet")

        hen.messaging.wake()
        advanceUntilIdle()
        assertEquals("wake up", hen.store.messagesWith("dana").single().body)
        assertEquals(com.chencohen.ptt.relay.SocketState.Stopped, hen.state.socket, "closed again after the wake-up")
        listOf(hen, dana).forEach { it.stop() }
    }

    @Test
    fun theGenderTravelsWithTheProfile() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)
        dana.messaging.setMyGender(Gender.FEMALE)
        advanceUntilIdle()
        assertEquals(Gender.FEMALE, hen.state.contacts.first { it.user == "dana" }.theirGender)
        dana.messaging.setMyGender(Gender.NOT_SET)
        advanceUntilIdle()
        assertEquals(Gender.NOT_SET, hen.state.contacts.first { it.user == "dana" }.theirGender)
        listOf(hen, dana).forEach { it.stop() }
    }

    @Test
    fun liveTypingSendsTheDraftLiveOnlyAndTheMessageReplacesIt() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)

        // Off (the default): nothing is sent.
        val before = server.ephemeral.size
        dana.messaging.draft("hen", "Hel")
        advanceTimeBy(500)
        assertEquals(before, server.ephemeral.size)
        assertFalse("dana" in hen.state.drafts)

        // The default turned on: hen was a contact already, so hen keeps its own (off).
        dana.messaging.setTalkSettings(dana.messaging.talkSettings().copy(liveTyping = true))
        assertFalse(dana.messaging.talkSettings().liveTypingTo("hen"), "turning the default on doesn't switch on existing contacts")
        assertTrue(dana.messaging.talkSettings().liveTypingTo("someone new"), "a new contact follows the default")
        dana.messaging.draft("hen", "Hel")
        advanceTimeBy(500)
        assertFalse("dana" in hen.state.drafts)
        // hen's own switch.
        dana.messaging.setTalkSettings(dana.messaging.talkSettings().let { it.copy(liveTypingFor = it.liveTypingFor + ("hen" to true)) })
        assertTrue(dana.messaging.talkSettings().liveTypingTo("hen"))
        dana.messaging.draft("hen", "Hel")
        dana.messaging.draft("hen", "Hello")
        advanceTimeBy(500)
        assertEquals("Hello", hen.state.drafts["dana"]?.text, "the newest text, coalesced")
        assertTrue(server.ephemeral.last(), "live only: never queued")
        assertEquals(PushKind.NONE, server.pushKinds.last())
        dana.messaging.draft("hen", "Hell")
        advanceTimeBy(500)
        assertEquals("Hell", hen.state.drafts["dana"]?.text, "a deletion shows too")
        dana.messaging.draftDone("hen")
        dana.messaging.sendText("hen", "Hello there")
        advanceUntilIdle()
        assertFalse("dana" in hen.state.drafts, "the message replaces the draft")
        listOf(hen, dana).forEach { it.stop() }
    }

    @Test
    fun typingPresenceAndThePushRegistration() = runTest {
        val server = FakeServer()
        val hen = Phone(server, "hen", 1, this, 1)
        val dana = Phone(server, "dana", 1, this, 2)
        pairedByQr(server, hen, dana)

        dana.messaging.sendTyping("hen", true)
        advanceUntilIdle()
        assertTrue("dana" in hen.state.typing)
        assertEquals(PushKind.NONE, server.pushKinds.last())
        dana.messaging.sendText("hen", "done typing")
        advanceUntilIdle()
        assertFalse("dana" in hen.state.typing, "a text ends typing")

        hen.messaging.refreshPresence()
        assertTrue(hen.state.presence["dana"]!!.online)
        hen.messaging.setPresenceVisible(false)
        assertFalse(hen.state.presence["dana"]!!.online, "hidden works both ways")

        hen.messaging.registerPush("token-1", notificationsEnabled = true)
        assertEquals("token-1" to true, server.pushTokens[hen.address])
        server.pushTokens.clear()
        hen.messaging.registerPush(null, notificationsEnabled = true)
        assertTrue(server.pushTokens.isEmpty(), "unchanged: not sent again")
        hen.messaging.registerPush(null, notificationsEnabled = false)
        assertEquals("token-1" to false, server.pushTokens[hen.address])
        listOf(hen, dana).forEach { it.stop() }
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
        // Refused by the server: kept as failed, not left in the outbox.
        assertEquals(MessageStatus.FAILED, dana.messaging.sendText("hen", "hi").status)
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
        // Every message says what its phone can play.
        assertEquals(content.copy(caps = ContentCodec.myCaps), ContentCodec.decode(padded))
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
