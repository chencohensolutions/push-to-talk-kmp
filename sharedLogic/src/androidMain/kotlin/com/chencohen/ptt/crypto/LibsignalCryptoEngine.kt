package com.chencohen.ptt.crypto

import com.chencohen.ptt.relay.EnvelopeType
import com.chencohen.ptt.store.SecureStore
import com.chencohen.ptt.store.Spaces
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.Fingerprint
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.protocol.state.PreKeyBundle as SignalPreKeyBundle
import org.signal.libsignal.protocol.UntrustedIdentityException as SignalUntrustedIdentityException

/**
 * [CryptoEngine] on libsignal (org.signal:libsignal-android, pinned in gradle/libs.versions.toml; upgrades are
 * deliberate, its API changes without notice). Its state is in [store] (the encrypted database) through
 * [SignalStore]. [local] is this device's address, which libsignal records in its sessions.
 *
 * Safety numbers are libsignal's numeric fingerprints: version 2, 5200 iterations, the user names as the stable
 * identifiers, so both phones of a pair compute the same 60 digits.
 */
internal class LibsignalCryptoEngine(
    private val store: SecureStore,
    private val local: () -> DeviceAddress?,
) : CryptoEngine {
    private val signal = SignalStore(store)

    private fun localAddress(): SignalProtocolAddress =
        local()?.let { SignalProtocolAddress(it.user, it.device) } ?: throw IllegalStateException("This phone isn't registered")

    private fun toSignal(address: DeviceAddress) = SignalProtocolAddress(address.user, address.device)

    override fun identityKey(): ByteArray? = signal.identityKeyPairOrNull()?.publicKey?.serialize()

    override fun createIdentity(): ByteArray {
        val pair = IdentityKeyPair.generate()
        signal.saveLocalIdentity(pair, KeyHelper.generateRegistrationId(false))
        return pair.publicKey.serialize()
    }

    override fun exportIdentity(): ByteArray? = signal.identityKeyPairOrNull()?.serialize()

    override fun importIdentity(pair: ByteArray): ByteArray {
        val identity = try {
            IdentityKeyPair(pair)
        } catch (e: Exception) {
            throw InvalidKeysException("not an identity key pair", e)
        }
        signal.clearOwnKeys()
        signal.saveLocalIdentity(identity, KeyHelper.generateRegistrationId(false))
        return identity.publicKey.serialize()
    }

    override fun newAgreementKey(): Pair<ByteArray, ByteArray> {
        val pair = ECKeyPair.generate()
        return pair.privateKey.serialize() to pair.publicKey.serialize()
    }

    override fun agree(private: ByteArray, public: ByteArray): ByteArray = try {
        ECPrivateKey(private).calculateAgreement(ECPublicKey(public))
    } catch (e: Exception) {
        throw InvalidKeysException("not an X25519 key", e)
    }

    override fun registrationId(): Int = signal.getLocalRegistrationId()

    override fun generateSignedPrekey(keyId: Int, nowMillis: Long): SignedPublicPrekey {
        val pair = ECKeyPair.generate()
        val publicKey = pair.publicKey.serialize()
        val signature = signal.getIdentityKeyPair().privateKey.calculateSignature(publicKey)
        signal.storeSignedPreKey(keyId, SignedPreKeyRecord(keyId, nowMillis, pair, signature))
        return SignedPublicPrekey(keyId, publicKey, signature)
    }

    override fun generateKyberPrekey(keyId: Int, lastResort: Boolean, nowMillis: Long): SignedPublicPrekey {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val publicKey = pair.publicKey.serialize()
        val signature = signal.getIdentityKeyPair().privateKey.calculateSignature(publicKey)
        signal.storeKyberPreKey(keyId, KyberPreKeyRecord(keyId, nowMillis, pair, signature))
        if (lastResort) signal.markLastResort(keyId)
        return SignedPublicPrekey(keyId, publicKey, signature)
    }

    override fun generatePrekeys(firstId: Int, count: Int): List<PublicPrekey> {
        var id = firstId
        return List(count) {
            val pair = ECKeyPair.generate()
            signal.storePreKey(id, PreKeyRecord(id, pair))
            PublicPrekey(id, pair.publicKey.serialize()).also { id = nextKeyId(id) }
        }
    }

    override fun removeSignedPrekeys(keep: Int, beforeMillis: Long) {
        for (record in signal.loadSignedPreKeys()) {
            if (record.id != keep && record.timestamp < beforeMillis) signal.removeSignedPreKey(record.id)
        }
    }

    override fun trustIdentity(user: String, identityKey: ByteArray) {
        IdentityKey(identityKey) // refuses what isn't a key
        store.put(Spaces.IDENTITIES, user, identityKey)
    }

    override fun trustedIdentity(user: String): ByteArray? = store.get(Spaces.IDENTITIES, user)

    override fun forget(user: String) {
        store.remove(Spaces.IDENTITIES, user)
        signal.deleteAllSessions(user)
    }

    override fun startSession(address: DeviceAddress, identityKey: ByteArray, bundle: PrekeyBundle) {
        val trusted = trustedIdentity(address.user)
        if (trusted != null && !trusted.contentEquals(identityKey)) throw UntrustedIdentityException(address.user)
        val keys = try {
            SignalPreKeyBundle(
                bundle.registrationId,
                address.device,
                bundle.prekey?.keyId ?: SignalPreKeyBundle.NULL_PRE_KEY_ID,
                bundle.prekey?.let { ECPublicKey(it.publicKey) },
                bundle.signedPrekey.keyId,
                ECPublicKey(bundle.signedPrekey.publicKey),
                bundle.signedPrekey.signature,
                IdentityKey(identityKey),
                bundle.kyberPrekey.keyId,
                KEMPublicKey(bundle.kyberPrekey.publicKey),
                bundle.kyberPrekey.signature,
            )
        } catch (e: Exception) {
            throw InvalidKeysException("The keys of $address aren't keys", e)
        }
        try {
            // libsignal checks both signatures against the identity key here.
            SessionBuilder(signal, toSignal(address), localAddress()).process(keys)
        } catch (e: SignalUntrustedIdentityException) {
            throw UntrustedIdentityException(address.user, e)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw InvalidKeysException("The keys of $address don't verify", e)
        }
    }

    override fun hasSession(address: DeviceAddress): Boolean = signal.containsSession(toSignal(address))

    override fun sessionDevices(user: String): List<Int> = signal.sessionDevices(user).filter { hasSession(DeviceAddress(user, it)) }

    override fun remoteRegistrationId(address: DeviceAddress): Int? {
        if (!hasSession(address)) return null
        return runCatching { signal.loadSession(toSignal(address)).remoteRegistrationId }.getOrNull()
    }

    override fun deleteSession(address: DeviceAddress) = signal.deleteSession(toSignal(address))

    override fun encrypt(address: DeviceAddress, plaintext: ByteArray): Ciphertext {
        val message = try {
            SessionCipher(signal, localAddress(), toSignal(address)).encrypt(plaintext)
        } catch (e: SignalUntrustedIdentityException) {
            throw UntrustedIdentityException(address.user, e)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw UndecryptableException("No session with $address", e)
        }
        val type = when (message.type) {
            CiphertextMessage.PREKEY_TYPE -> EnvelopeType.PREKEY_MESSAGE
            CiphertextMessage.WHISPER_TYPE -> EnvelopeType.MESSAGE
            else -> throw UndecryptableException("libsignal made a message of type ${message.type}")
        }
        return Ciphertext(type, message.serialize())
    }

    override fun decrypt(address: DeviceAddress, type: Int, bytes: ByteArray): Decrypted {
        val remote = toSignal(address)
        try {
            val cipher = SessionCipher(signal, localAddress(), remote)
            return when (type) {
                EnvelopeType.PREKEY_MESSAGE -> {
                    val message = PreKeySignalMessage(bytes)
                    Decrypted(cipher.decrypt(message), message.identityKey.serialize())
                }
                EnvelopeType.MESSAGE -> {
                    val plaintext = cipher.decrypt(SignalMessage(bytes))
                    Decrypted(plaintext, signal.loadSession(remote).remoteIdentityKey.serialize())
                }
                else -> throw UndecryptableException("Not a libsignal message type: $type")
            }
        } catch (e: CryptoException) {
            throw e
        } catch (e: SignalUntrustedIdentityException) {
            throw UntrustedIdentityException(address.user, e)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw UndecryptableException("Can't open a message from $address", e)
        }
    }

    private fun fingerprint(myUser: String, theirUser: String, theirIdentityKey: ByteArray): Fingerprint =
        NumericFingerprintGenerator(FINGERPRINT_ITERATIONS).createFor(
            FINGERPRINT_VERSION,
            myUser.encodeToByteArray(),
            signal.getIdentityKeyPair().publicKey,
            theirUser.encodeToByteArray(),
            IdentityKey(theirIdentityKey),
        )

    override fun safetyNumber(myUser: String, theirUser: String, theirIdentityKey: ByteArray): SafetyNumber {
        val fingerprint = fingerprint(myUser, theirUser, theirIdentityKey)
        return SafetyNumber(fingerprint.displayableFingerprint.displayText, fingerprint.scannableFingerprint.serialized)
    }

    override fun matchesScanned(myUser: String, theirUser: String, theirIdentityKey: ByteArray, scanned: ByteArray): Boolean =
        try {
            fingerprint(myUser, theirUser, theirIdentityKey).scannableFingerprint.compareTo(scanned)
        } catch (_: Exception) {
            false
        }

    override fun wipe() {
        for (space in listOf(
            Spaces.IDENTITY, Spaces.PREKEYS, Spaces.SIGNED_PREKEYS, Spaces.KYBER_PREKEYS, Spaces.KYBER_LAST_RESORT,
            Spaces.KYBER_BASE_KEYS, Spaces.SESSIONS, Spaces.IDENTITIES, Spaces.SENDER_KEYS,
        )) store.clear(space)
    }

    private companion object {
        const val FINGERPRINT_VERSION = 2
        const val FINGERPRINT_ITERATIONS = 5200
    }
}
