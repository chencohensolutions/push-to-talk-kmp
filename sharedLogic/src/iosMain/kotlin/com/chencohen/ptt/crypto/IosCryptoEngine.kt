package com.chencohen.ptt.crypto

import com.chencohen.ptt.ios.IosSignal
import com.chencohen.ptt.ios.SignalFailure
import com.chencohen.ptt.ios.SignalReply
import com.chencohen.ptt.ios.toByteArray
import com.chencohen.ptt.ios.toNSData
import com.chencohen.ptt.relay.EnvelopeType
import com.chencohen.ptt.store.SecureStore
import com.chencohen.ptt.store.Spaces

/**
 * [CryptoEngine] on iOS: libsignal's Swift package through [signal] (the app's SignalEngine.swift, the same pinned
 * version as Android's libsignal-android), with the rules LibsignalCryptoEngine has kept here in Kotlin: which key is
 * trusted for a user, which sessions there are, what a wipe forgets. The records are in [store] under the same spaces
 * and keys as on Android (SignalStore). [local] is this device's address, which libsignal records in its sessions.
 */
internal class IosCryptoEngine(
    private val signal: IosSignal,
    private val store: SecureStore,
    private val local: () -> DeviceAddress?,
) : CryptoEngine {
    private fun localAddress(): DeviceAddress = local() ?: throw IllegalStateException("This phone isn't registered")

    /** The reply's bytes, or the exception its failure stands for ([what] says what was being done). */
    private fun SignalReply.orThrow(what: String, user: String? = null): SignalReply {
        when (error) {
            SignalFailure.NONE -> return this
            SignalFailure.INVALID_KEYS -> throw InvalidKeysException("$what: ${message ?: "not keys"}")
            SignalFailure.UNTRUSTED -> throw UntrustedIdentityException(user ?: what)
            SignalFailure.NO_IDENTITY -> throw IllegalStateException(message ?: "No identity key yet")
            else -> throw UndecryptableException("$what: ${message ?: "can't be opened"}")
        }
    }

    private fun SignalReply.bytes(what: String, user: String? = null): ByteArray =
        orThrow(what, user).data?.toByteArray() ?: throw UndecryptableException("$what: nothing came back")

    override fun identityKey(): ByteArray? = signal.identityKey()?.toByteArray()

    override fun createIdentity(): ByteArray = signal.createIdentity().toByteArray()

    override fun exportIdentity(): ByteArray? = signal.exportIdentity()?.toByteArray()

    override fun importIdentity(pair: ByteArray): ByteArray = signal.importIdentity(pair.toNSData()).bytes("not an identity key pair")

    override fun newAgreementKey(): Pair<ByteArray, ByteArray> {
        val reply = signal.makeAgreementKey().orThrow("a new agreement key")
        return (reply.data ?: error("no private key")).toByteArray() to (reply.more ?: error("no public key")).toByteArray()
    }

    override fun agree(private: ByteArray, public: ByteArray): ByteArray =
        signal.agree(private.toNSData(), public.toNSData()).bytes("not an X25519 key")

    override fun registrationId(): Int = signal.registrationId()

    private fun signed(keyId: Int, reply: SignalReply): SignedPublicPrekey {
        reply.orThrow("a signed prekey")
        return SignedPublicPrekey(keyId, (reply.data ?: error("no public key")).toByteArray(), (reply.more ?: error("no signature")).toByteArray())
    }

    override fun generateSignedPrekey(keyId: Int, nowMillis: Long): SignedPublicPrekey =
        signed(keyId, signal.generateSignedPrekey(keyId, nowMillis))

    override fun generateKyberPrekey(keyId: Int, lastResort: Boolean, nowMillis: Long): SignedPublicPrekey {
        val key = signed(keyId, signal.generateKyberPrekey(keyId, nowMillis))
        if (lastResort) store.put(Spaces.KYBER_LAST_RESORT, keyId.toString(), byteArrayOf(1))
        return key
    }

    override fun generatePrekeys(firstId: Int, count: Int): List<PublicPrekey> {
        var id = firstId
        return List(count) {
            PublicPrekey(id, signal.generatePrekey(id).bytes("a prekey")).also { id = nextKeyId(id) }
        }
    }

    override fun removeSignedPrekeys(keep: Int, beforeMillis: Long) {
        for (key in store.keys(Spaces.SIGNED_PREKEYS)) {
            if (key.toIntOrNull() == keep) continue
            val record = store.get(Spaces.SIGNED_PREKEYS, key) ?: continue
            val madeAt = signal.signedPrekeyTimestamp(record.toNSData())
            if (madeAt in 0 until beforeMillis) store.remove(Spaces.SIGNED_PREKEYS, key)
        }
    }

    override fun trustIdentity(user: String, identityKey: ByteArray) {
        if (!signal.isIdentityKey(identityKey.toNSData())) throw InvalidKeysException("$user's key isn't a key")
        store.put(Spaces.IDENTITIES, user, identityKey)
    }

    override fun trustedIdentity(user: String): ByteArray? = store.get(Spaces.IDENTITIES, user)

    override fun forget(user: String) {
        store.remove(Spaces.IDENTITIES, user)
        store.keys(Spaces.SESSIONS).filter { it.substringBeforeLast('/') == user }.forEach { store.remove(Spaces.SESSIONS, it) }
    }

    override fun startSession(address: DeviceAddress, identityKey: ByteArray, bundle: PrekeyBundle) {
        val trusted = trustedIdentity(address.user)
        if (trusted != null && !trusted.contentEquals(identityKey)) throw UntrustedIdentityException(address.user)
        val me = localAddress()
        signal.startSession(
            user = address.user,
            device = address.device,
            localUser = me.user,
            localDevice = me.device,
            identityKey = identityKey.toNSData(),
            registrationId = bundle.registrationId,
            signedPrekeyId = bundle.signedPrekey.keyId,
            signedPrekey = bundle.signedPrekey.publicKey.toNSData(),
            signedPrekeySignature = bundle.signedPrekey.signature.toNSData(),
            prekeyId = bundle.prekey?.keyId ?: -1,
            prekey = bundle.prekey?.publicKey?.toNSData(),
            kyberPrekeyId = bundle.kyberPrekey.keyId,
            kyberPrekey = bundle.kyberPrekey.publicKey.toNSData(),
            kyberPrekeySignature = bundle.kyberPrekey.signature.toNSData(),
        ).orThrow("The keys of $address", address.user)
    }

    private fun sessionKey(address: DeviceAddress) = "${address.user}/${address.device}"

    override fun hasSession(address: DeviceAddress): Boolean =
        store.get(Spaces.SESSIONS, sessionKey(address))?.let { signal.sessionUsable(it.toNSData()) } ?: false

    override fun sessionDevices(user: String): List<Int> =
        store.keys(Spaces.SESSIONS).filter { it.substringBeforeLast('/') == user }.mapNotNull { it.substringAfterLast('/').toIntOrNull() }
            .sorted().filter { hasSession(DeviceAddress(user, it)) }

    override fun remoteRegistrationId(address: DeviceAddress): Int? {
        if (!hasSession(address)) return null
        val record = store.get(Spaces.SESSIONS, sessionKey(address)) ?: return null
        return signal.sessionRemoteRegistrationId(record.toNSData()).takeIf { it >= 0 }
    }

    override fun deleteSession(address: DeviceAddress) = store.remove(Spaces.SESSIONS, sessionKey(address))

    override fun encrypt(address: DeviceAddress, plaintext: ByteArray): Ciphertext {
        val me = localAddress()
        val reply = signal.encrypt(address.user, address.device, me.user, me.device, plaintext.toNSData()).orThrow("No session with $address", address.user)
        val type = when (reply.number) {
            SIGNAL_PREKEY_TYPE -> EnvelopeType.PREKEY_MESSAGE
            SIGNAL_WHISPER_TYPE -> EnvelopeType.MESSAGE
            else -> throw UndecryptableException("libsignal made a message of type ${reply.number}")
        }
        return Ciphertext(type, (reply.data ?: throw UndecryptableException("No session with $address")).toByteArray())
    }

    override fun decrypt(address: DeviceAddress, type: Int, bytes: ByteArray): Decrypted {
        val prekey = when (type) {
            EnvelopeType.PREKEY_MESSAGE -> true
            EnvelopeType.MESSAGE -> false
            else -> throw UndecryptableException("Not a libsignal message type: $type")
        }
        val me = localAddress()
        val reply = signal.decrypt(address.user, address.device, me.user, me.device, prekey, bytes.toNSData())
            .orThrow("Can't open a message from $address", address.user)
        val plaintext = reply.data ?: throw UndecryptableException("Can't open a message from $address")
        val sender = reply.more ?: throw UndecryptableException("A message from $address came on no identity key")
        return Decrypted(plaintext.toByteArray(), sender.toByteArray())
    }

    override fun safetyNumber(myUser: String, theirUser: String, theirIdentityKey: ByteArray): SafetyNumber {
        val reply = signal.safetyNumber(myUser, theirUser, theirIdentityKey.toNSData()).orThrow("The safety number with $theirUser")
        return SafetyNumber(reply.text.orEmpty(), (reply.data ?: error("no scannable safety number")).toByteArray())
    }

    override fun matchesScanned(myUser: String, theirUser: String, theirIdentityKey: ByteArray, scanned: ByteArray): Boolean =
        signal.matchesScanned(myUser, theirUser, theirIdentityKey.toNSData(), scanned.toNSData())

    override fun senderKeyDistribution(distributionId: String): ByteArray {
        val me = localAddress()
        return signal.senderKeyDistribution(me.user, me.device, distributionId).bytes("The sender key $distributionId")
    }

    override fun takeSenderKey(sender: DeviceAddress, distribution: ByteArray) {
        val reply = signal.takeSenderKey(sender.user, sender.device, distribution.toNSData())
        if (reply.error != SignalFailure.NONE) throw InvalidKeysException("The sender key of $sender can't be taken: ${reply.message}")
    }

    override fun distributionIdOf(distribution: ByteArray): String? = signal.distributionIdOf(distribution.toNSData())?.lowercase()

    override fun groupEncrypt(distributionId: String, plaintext: ByteArray): ByteArray {
        val me = localAddress()
        return signal.groupEncrypt(me.user, me.device, distributionId, plaintext.toNSData()).bytes("No sender key $distributionId")
    }

    override fun groupDecrypt(sender: DeviceAddress, bytes: ByteArray): ByteArray =
        signal.groupDecrypt(sender.user, sender.device, bytes.toNSData()).bytes("Can't open a group message from $sender")

    override fun groupMessageDistributionId(bytes: ByteArray): String? = signal.groupMessageDistributionId(bytes.toNSData())?.lowercase()

    override fun sign(data: ByteArray): ByteArray = signal.sign(data.toNSData()).bytes("A signature")

    override fun verify(identityKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        signal.verify(identityKey.toNSData(), data.toNSData(), signature.toNSData())

    override fun wipe() {
        for (space in listOf(
            Spaces.IDENTITY, Spaces.PREKEYS, Spaces.SIGNED_PREKEYS, Spaces.KYBER_PREKEYS, Spaces.KYBER_LAST_RESORT,
            Spaces.KYBER_BASE_KEYS, Spaces.SESSIONS, Spaces.IDENTITIES, Spaces.SENDER_KEYS,
        )) store.clear(space)
    }

    private companion object {
        /** libsignal's CiphertextMessage types. */
        const val SIGNAL_WHISPER_TYPE = 2
        const val SIGNAL_PREKEY_TYPE = 3
    }
}
