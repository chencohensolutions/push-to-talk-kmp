package com.chencohen.ptt.crypto

import com.chencohen.ptt.relay.EnvelopeType
import com.chencohen.ptt.store.MemoryStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plan's risk check R2 on the JVM: libsignal itself (libsignal-client's desktop library, which the host tests
 * load) under LibsignalCryptoEngine. A session opened from a bundle checked against the identity key from a QR code
 * carries messages both ways, the safety numbers match, and keys signed by another identity, or another identity than
 * the trusted one, are refused.
 */
class LibsignalCryptoEngineTest {
    private val hen1 = DeviceAddress("hen", 1)
    private val dana1 = DeviceAddress("dana", 1)

    private fun engine(address: DeviceAddress) = LibsignalCryptoEngine(MemoryStore()) { address }

    /** [owner]'s keys as the server would hand them out. */
    private fun bundle(owner: LibsignalCryptoEngine, device: Int, oneTime: Boolean, base: Int = 1): PrekeyBundle {
        val signed = owner.generateSignedPrekey(base, 0)
        val kyber = owner.generateKyberPrekey(base, lastResort = !oneTime, nowMillis = 0)
        val prekey = if (oneTime) owner.generatePrekeys(base, 1).single() else null
        return PrekeyBundle(device, owner.registrationId(), signed, prekey, kyber)
    }

    @Test
    fun aSessionFromACheckedBundleCarriesMessagesBothWays() {
        val hen = engine(hen1)
        val dana = engine(dana1)
        val henKey = hen.createIdentity()
        val danaKey = dana.createIdentity()
        assertEquals(33, henKey.size)
        val keys = bundle(hen, 1, oneTime = true)
        assertEquals(33, keys.signedPrekey.publicKey.size)
        assertEquals(64, keys.signedPrekey.signature.size)
        assertEquals(1569, keys.kyberPrekey.publicKey.size)
        assertTrue(hen.registrationId() in 1..16380)

        // Dana scanned Hen's code: she trusts the key from it, and the bundle must be signed by it.
        dana.trustIdentity("hen", henKey)
        dana.startSession(hen1, henKey, keys)
        assertTrue(dana.hasSession(hen1))
        assertEquals(listOf(1), dana.sessionDevices("hen"))
        assertEquals(hen.registrationId(), dana.remoteRegistrationId(hen1))

        val first = dana.encrypt(hen1, "hello hen".encodeToByteArray())
        assertEquals(EnvelopeType.PREKEY_MESSAGE, first.type)
        val opened = hen.decrypt(dana1, first.type, first.bytes)
        assertEquals("hello hen", opened.plaintext.decodeToString())
        assertContentEquals(danaKey, opened.senderIdentityKey)
        assertContentEquals(danaKey, hen.trustedIdentity("dana"))

        val reply = hen.encrypt(dana1, "hi dana".encodeToByteArray())
        assertEquals(EnvelopeType.MESSAGE, reply.type)
        val back = dana.decrypt(hen1, reply.type, reply.bytes)
        assertEquals("hi dana", back.plaintext.decodeToString())
        assertContentEquals(henKey, back.senderIdentityKey)

        // A replayed message doesn't open twice.
        assertFailsWith<UndecryptableException> { hen.decrypt(dana1, first.type, first.bytes) }

        // Both phones show the same safety number, and each one's code matches on the other.
        val henNumber = hen.safetyNumber("hen", "dana", danaKey)
        val danaNumber = dana.safetyNumber("dana", "hen", henKey)
        assertEquals(60, henNumber.digits.length)
        assertEquals(henNumber.digits, danaNumber.digits)
        assertTrue(dana.matchesScanned("dana", "hen", henKey, henNumber.scannable))
        assertFalse(dana.matchesScanned("dana", "hen", henKey, byteArrayOf(1, 2, 3)))
    }

    /**
     * Linking a phone: the new phone takes the identity key pair the old one exported, with a registration id of its
     * own; a session from its keys opens under the same identity key; both sides of an X25519 exchange agree.
     */
    @Test
    fun aLinkedPhoneTakesTheIdentityAndTheExchangeAgrees() {
        val hen = engine(hen1)
        val henKey = hen.createIdentity()
        val linked = engine(DeviceAddress("hen", 2))
        linked.createIdentity()
        val public = linked.importIdentity(hen.exportIdentity()!!)
        assertContentEquals(henKey, public)
        assertContentEquals(henKey, linked.identityKey())
        assertTrue(linked.registrationId() in 1..16380)

        val dana = engine(dana1)
        dana.createIdentity()
        dana.trustIdentity("hen", henKey)
        dana.startSession(DeviceAddress("hen", 2), henKey, bundle(linked, 2, oneTime = true))
        val sealed = dana.encrypt(DeviceAddress("hen", 2), "to the new phone".encodeToByteArray())
        linked.trustIdentity("dana", dana.identityKey()!!)
        assertEquals("to the new phone", linked.decrypt(dana1, sealed.type, sealed.bytes).plaintext.decodeToString())

        val (aPrivate, aPublic) = hen.newAgreementKey()
        val (bPrivate, bPublic) = linked.newAgreementKey()
        assertEquals(33, aPublic.size)
        assertContentEquals(hen.agree(aPrivate, bPublic), linked.agree(bPrivate, aPublic))
        assertFailsWith<InvalidKeysException> { linked.importIdentity(ByteArray(10)) }
    }

    @Test
    fun keysSignedByAnotherIdentityAreRefused() {
        val hen = engine(hen1)
        val eve = engine(DeviceAddress("eve", 1))
        val dana = engine(dana1)
        val henKey = hen.createIdentity()
        eve.createIdentity()
        dana.createIdentity()
        // The server hands out keys Eve signed, under Hen's identity key from the code.
        val forged = bundle(eve, 1, oneTime = true)
        dana.trustIdentity("hen", henKey)
        assertFailsWith<InvalidKeysException> { dana.startSession(hen1, henKey, forged) }
        assertFalse(dana.hasSession(hen1))
    }

    @Test
    fun anIdentityOtherThanTheTrustedOneIsRefused() {
        val hen = engine(hen1)
        val eve = engine(DeviceAddress("eve", 1))
        val dana = engine(dana1)
        hen.createIdentity()
        val eveKey = eve.createIdentity()
        dana.createIdentity()
        dana.trustIdentity("hen", hen.identityKey()!!)
        assertFailsWith<UntrustedIdentityException> { dana.startSession(hen1, eveKey, bundle(eve, 1, oneTime = true)) }
    }

    @Test
    fun theLastResortKyberKeyServesWhenNoOneTimeKeyIsLeft() {
        val hen = engine(hen1)
        val henKey = hen.createIdentity()
        val keys = bundle(hen, 1, oneTime = false)
        repeat(2) { n ->
            val sender = engine(DeviceAddress("user$n", 1))
            sender.createIdentity()
            sender.trustIdentity("hen", henKey)
            sender.startSession(hen1, henKey, keys)
            val message = sender.encrypt(hen1, "from $n".encodeToByteArray())
            assertEquals("from $n", hen.decrypt(DeviceAddress("user$n", 1), message.type, message.bytes).plaintext.decodeToString())
        }
    }

    @Test
    fun forgettingAUserDropsTheSessionsAndTheTrust() {
        val hen = engine(hen1)
        val dana = engine(dana1)
        val henKey = hen.createIdentity()
        dana.createIdentity()
        dana.trustIdentity("hen", henKey)
        dana.startSession(hen1, henKey, bundle(hen, 1, oneTime = true))
        dana.forget("hen")
        assertFalse(dana.hasSession(hen1))
        assertEquals(null, dana.trustedIdentity("hen"))
        dana.wipe()
        assertEquals(null, dana.identityKey())
    }
}
