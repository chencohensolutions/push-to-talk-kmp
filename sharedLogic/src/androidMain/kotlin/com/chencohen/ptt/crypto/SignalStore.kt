package com.chencohen.ptt.crypto

import com.chencohen.ptt.store.SecureStore
import com.chencohen.ptt.store.Spaces
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * libsignal's stores over the phone's encrypted [SecureStore]: records are kept in libsignal's own serialized form,
 * keyed by id, or by "user/device" for sessions. An identity key is trusted for a user when none is kept yet (the
 * first message of a pair request, or a session started from a QR code that was trusted first) or when it is the kept
 * one; a changed key is never taken on its own (the user accepts it, MessagingController.acceptKeyChange).
 */
internal class SignalStore(private val store: SecureStore) : SignalProtocolStore {
    // --- Identity ---------------------------------------------------------------------------------------------------

    fun identityKeyPairOrNull(): IdentityKeyPair? = store.get(Spaces.IDENTITY, PAIR)?.let { IdentityKeyPair(it) }

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPairOrNull() ?: throw IllegalStateException("No identity key yet")

    override fun getLocalRegistrationId(): Int = store.get(Spaces.IDENTITY, REGISTRATION)?.decodeToString()?.toIntOrNull() ?: 0

    fun saveLocalIdentity(pair: IdentityKeyPair, registrationId: Int) {
        store.put(Spaces.IDENTITY, PAIR, pair.serialize())
        store.put(Spaces.IDENTITY, REGISTRATION, registrationId.toString().encodeToByteArray())
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val old = store.get(Spaces.IDENTITIES, address.name)
        val new = identityKey.serialize()
        if (old != null && old.contentEquals(new)) return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        // The kept key stays: a change needs the user (isTrustedIdentity refuses it first anyway).
        if (old != null) return IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        store.put(Spaces.IDENTITIES, address.name, new)
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val kept = store.get(Spaces.IDENTITIES, address.name) ?: return true
        return kept.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = store.get(Spaces.IDENTITIES, address.name)?.let { IdentityKey(it) }

    // --- One-time EC prekeys ----------------------------------------------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        store.get(Spaces.PREKEYS, preKeyId.toString())?.let { PreKeyRecord(it) } ?: throw InvalidKeyIdException("No prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = store.put(Spaces.PREKEYS, preKeyId.toString(), record.serialize())

    override fun containsPreKey(preKeyId: Int): Boolean = store.get(Spaces.PREKEYS, preKeyId.toString()) != null

    override fun removePreKey(preKeyId: Int) = store.remove(Spaces.PREKEYS, preKeyId.toString())

    // --- Signed EC prekeys ------------------------------------------------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        store.get(Spaces.SIGNED_PREKEYS, signedPreKeyId.toString())?.let { SignedPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("No signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        store.keys(Spaces.SIGNED_PREKEYS).mapNotNull { store.get(Spaces.SIGNED_PREKEYS, it)?.let { bytes -> SignedPreKeyRecord(bytes) } }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        store.put(Spaces.SIGNED_PREKEYS, signedPreKeyId.toString(), record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = store.get(Spaces.SIGNED_PREKEYS, signedPreKeyId.toString()) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) = store.remove(Spaces.SIGNED_PREKEYS, signedPreKeyId.toString())

    // --- Kyber prekeys ----------------------------------------------------------------------------------------------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        store.get(Spaces.KYBER_PREKEYS, kyberPreKeyId.toString())?.let { KyberPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("No Kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        store.keys(Spaces.KYBER_PREKEYS).mapNotNull { store.get(Spaces.KYBER_PREKEYS, it)?.let { bytes -> KyberPreKeyRecord(bytes) } }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        store.put(Spaces.KYBER_PREKEYS, kyberPreKeyId.toString(), record.serialize())

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = store.get(Spaces.KYBER_PREKEYS, kyberPreKeyId.toString()) != null

    fun markLastResort(kyberPreKeyId: Int) = store.put(Spaces.KYBER_LAST_RESORT, kyberPreKeyId.toString(), byteArrayOf(1))

    /**
     * A one-time Kyber prekey goes once used. The last-resort one stays, and libsignal asks that a second message
     * with the same base key (a replay of the first message) be refused.
     */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        if (store.get(Spaces.KYBER_LAST_RESORT, kyberPreKeyId.toString()) == null) {
            store.remove(Spaces.KYBER_PREKEYS, kyberPreKeyId.toString())
            return
        }
        val seen = "$kyberPreKeyId/$signedPreKeyId/" + baseKey.serialize().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        if (store.get(Spaces.KYBER_BASE_KEYS, seen) != null) throw ReusedBaseKeyException()
        store.put(Spaces.KYBER_BASE_KEYS, seen, byteArrayOf(1))
    }

    // --- Sessions ---------------------------------------------------------------------------------------------------

    private fun key(address: SignalProtocolAddress) = "${address.name}/${address.deviceId}"

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        store.get(Spaces.SESSIONS, key(address))?.let { SessionRecord(it) } ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { address ->
            store.get(Spaces.SESSIONS, key(address))?.let { SessionRecord(it) } ?: throw NoSessionException(address, "No session with $address")
        }

    /** The devices of [name] with a session (libsignal's convention leaves out device 1; [sessionDevices] doesn't). */
    override fun getSubDeviceSessions(name: String): List<Int> = sessionDevices(name).filter { it != 1 }

    fun sessionDevices(name: String): List<Int> =
        store.keys(Spaces.SESSIONS).filter { it.substringBeforeLast('/') == name }.mapNotNull { it.substringAfterLast('/').toIntOrNull() }.sorted()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = store.put(Spaces.SESSIONS, key(address), record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        store.get(Spaces.SESSIONS, key(address))?.let { runCatching { SessionRecord(it).hasSenderChain() }.getOrDefault(false) } ?: false

    override fun deleteSession(address: SignalProtocolAddress) = store.remove(Spaces.SESSIONS, key(address))

    override fun deleteAllSessions(name: String) {
        store.keys(Spaces.SESSIONS).filter { it.substringBeforeLast('/') == name }.forEach { store.remove(Spaces.SESSIONS, it) }
    }

    // --- Sender keys (groups, phase 4) ------------------------------------------------------------------------------

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        store.put(Spaces.SENDER_KEYS, "${key(sender)}/$distributionId", record.serialize())

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        store.get(Spaces.SENDER_KEYS, "${key(sender)}/$distributionId")?.let { SenderKeyRecord(it) }

    private companion object {
        const val PAIR = "pair"
        const val REGISTRATION = "registration"
    }
}
