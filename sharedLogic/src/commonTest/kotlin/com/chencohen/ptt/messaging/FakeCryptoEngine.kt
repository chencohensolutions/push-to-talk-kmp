package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.Ciphertext
import com.chencohen.ptt.crypto.CryptoEngine
import com.chencohen.ptt.crypto.Decrypted
import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.crypto.InvalidKeysException
import com.chencohen.ptt.crypto.PrekeyBundle
import com.chencohen.ptt.crypto.PublicPrekey
import com.chencohen.ptt.crypto.SafetyNumber
import com.chencohen.ptt.crypto.SignedPublicPrekey
import com.chencohen.ptt.crypto.UndecryptableException
import com.chencohen.ptt.crypto.UntrustedIdentityException
import com.chencohen.ptt.crypto.nextKeyId
import com.chencohen.ptt.relay.EnvelopeType
import kotlin.random.Random

/**
 * A stand-in for libsignal in the shared tests: no real cryptography. A "signature" is the identity key itself, so
 * a bundle signed by another key is refused as libsignal would; a "ciphertext" is the sender's identity key and the
 * plaintext, so the receiver learns the key its session came on. The first message of a session is a prekey
 * message. Trust works as the Android engine's: a user's key is trusted when none is kept yet or it is the kept one.
 */
class FakeCryptoEngine(private val random: Random = Random(1)) : CryptoEngine {
    private var identity: ByteArray? = null
    private var registration = 0
    private val trusted = mutableMapOf<String, ByteArray>()
    private val sessions = mutableMapOf<DeviceAddress, Int>()
    private val sentFirst = mutableSetOf<DeviceAddress>()
    val signedPrekeys = mutableMapOf<Int, Long>()
    val kyberPrekeys = mutableMapOf<Int, Boolean>()
    val prekeys = mutableSetOf<Int>()

    override fun identityKey(): ByteArray? = identity?.copyOf()

    override fun createIdentity(): ByteArray {
        identity = byteArrayOf(0x05) + random.nextBytes(32)
        registration = random.nextInt(1, 16381)
        return identity!!.copyOf()
    }

    override fun registrationId(): Int = registration

    override fun generateSignedPrekey(keyId: Int, nowMillis: Long): SignedPublicPrekey {
        signedPrekeys[keyId] = nowMillis
        return SignedPublicPrekey(keyId, byteArrayOf(0x05) + random.nextBytes(32), identity!!.copyOf())
    }

    override fun generateKyberPrekey(keyId: Int, lastResort: Boolean, nowMillis: Long): SignedPublicPrekey {
        kyberPrekeys[keyId] = lastResort
        return SignedPublicPrekey(keyId, byteArrayOf(0x08) + random.nextBytes(1568), identity!!.copyOf())
    }

    override fun generatePrekeys(firstId: Int, count: Int): List<PublicPrekey> {
        var id = firstId
        return List(count) {
            prekeys += id
            PublicPrekey(id, byteArrayOf(0x05) + random.nextBytes(32)).also { id = nextKeyId(id) }
        }
    }

    override fun removeSignedPrekeys(keep: Int, beforeMillis: Long) {
        signedPrekeys.entries.removeAll { it.key != keep && it.value < beforeMillis }
    }

    override fun trustIdentity(user: String, identityKey: ByteArray) {
        trusted[user] = identityKey.copyOf()
    }

    override fun trustedIdentity(user: String): ByteArray? = trusted[user]?.copyOf()

    override fun forget(user: String) {
        trusted.remove(user)
        sessions.keys.removeAll { it.user == user }
        sentFirst.removeAll { it.user == user }
    }

    override fun startSession(address: DeviceAddress, identityKey: ByteArray, bundle: PrekeyBundle) {
        val kept = trusted[address.user]
        if (kept != null && !kept.contentEquals(identityKey)) throw UntrustedIdentityException(address.user)
        if (!bundle.signedPrekey.signature.contentEquals(identityKey) || !bundle.kyberPrekey.signature.contentEquals(identityKey)) {
            throw InvalidKeysException("bad signature")
        }
        trusted[address.user] = identityKey.copyOf()
        sessions[address] = bundle.registrationId
        sentFirst.remove(address)
    }

    override fun hasSession(address: DeviceAddress): Boolean = address in sessions

    override fun sessionDevices(user: String): List<Int> = sessions.keys.filter { it.user == user }.map { it.device }.sorted()

    override fun remoteRegistrationId(address: DeviceAddress): Int? = sessions[address]

    override fun deleteSession(address: DeviceAddress) {
        sessions.remove(address)
        sentFirst.remove(address)
    }

    override fun encrypt(address: DeviceAddress, plaintext: ByteArray): Ciphertext {
        if (address !in sessions) throw UndecryptableException("no session with $address")
        val type = if (sentFirst.add(address)) EnvelopeType.PREKEY_MESSAGE else EnvelopeType.MESSAGE
        return Ciphertext(type, identity!! + plaintext)
    }

    override fun decrypt(address: DeviceAddress, type: Int, bytes: ByteArray): Decrypted {
        if (bytes.size < 33) throw UndecryptableException("too short")
        val key = bytes.copyOf(33)
        val kept = trusted[address.user]
        if (kept != null && !kept.contentEquals(key)) throw UntrustedIdentityException(address.user)
        if (type == EnvelopeType.PREKEY_MESSAGE) {
            trusted[address.user] = key
            sessions.getOrPut(address) { 0 }
            sentFirst += address
        } else if (address !in sessions) {
            throw UndecryptableException("no session with $address")
        }
        return Decrypted(bytes.copyOfRange(33, bytes.size), key)
    }

    override fun safetyNumber(myUser: String, theirUser: String, theirIdentityKey: ByteArray): SafetyNumber {
        val parts = listOf(myUser to identity!!, theirUser to theirIdentityKey).sortedBy { it.first }
        val seed = parts.fold(17L) { acc, (user, key) -> key.fold(acc * 31 + user.hashCode()) { a, b -> a * 131 + b } }
        val digits = Random(seed).let { r -> (1..60).joinToString("") { r.nextInt(10).toString() } }
        return SafetyNumber(digits, digits.encodeToByteArray())
    }

    override fun matchesScanned(myUser: String, theirUser: String, theirIdentityKey: ByteArray, scanned: ByteArray): Boolean =
        safetyNumber(myUser, theirUser, theirIdentityKey).scannable.contentEquals(scanned)

    override fun wipe() {
        identity = null
        registration = 0
        trusted.clear()
        sessions.clear()
        sentFirst.clear()
        signedPrekeys.clear()
        kyberPrekeys.clear()
        prekeys.clear()
    }
}
