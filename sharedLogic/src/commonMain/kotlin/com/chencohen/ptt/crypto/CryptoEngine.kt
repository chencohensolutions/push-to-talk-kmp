package com.chencohen.ptt.crypto

// The one door to the Signal protocol (docs/design.md, "Keys and sessions"). libsignal's README says use outside
// Signal is unsupported and its APIs change without notice, so every call into it sits behind this interface: the
// Android implementation is LibsignalCryptoEngine (androidMain, libsignal-android), iOS gets libsignal's Swift package
// later, and the shared tests use FakeCryptoEngine. Keys cross it in libsignal's serialized form (an identity key is
// 33 bytes: 0x05 and 32), as the server's API carries them in base64.

/** A device of a user, as libsignal names it: the user's name and the device number (1 to 127) the server gave it. */
data class DeviceAddress(val user: String, val device: Int) {
    override fun toString(): String = "$user.$device"
}

/** A one-time EC prekey's public half. */
class PublicPrekey(val keyId: Int, val publicKey: ByteArray)

/** A signed prekey's public half (EC or Kyber) and the identity key's signature over it. */
class SignedPublicPrekey(val keyId: Int, val publicKey: ByteArray, val signature: ByteArray)

/** One device's keys from `prekeyBundles`: what opens a session with it (PQXDH). */
class PrekeyBundle(
    val device: Int,
    val registrationId: Int,
    val signedPrekey: SignedPublicPrekey,
    /** Null when the device has no one-time EC prekey left. */
    val prekey: PublicPrekey?,
    /** A one-time Kyber prekey, or the device's last-resort one. */
    val kyberPrekey: SignedPublicPrekey,
)

/** What [CryptoEngine.encrypt] made: a libsignal message of [type] (`EnvelopeType.PREKEY_MESSAGE` or `MESSAGE`). */
class Ciphertext(val type: Int, val bytes: ByteArray)

/** What [CryptoEngine.decrypt] opened, and the identity key of the session it came on. */
class Decrypted(val plaintext: ByteArray, val senderIdentityKey: ByteArray)

/**
 * The safety number of two users: [digits] (60, shown as 12 groups of 5) and [scannable] (what the QR code on the
 * safety number screen carries). Both sides compute the same digits.
 */
class SafetyNumber(val digits: String, val scannable: ByteArray)

/** Why the engine refused. */
open class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A bundle's signature doesn't verify against the identity key, or a key isn't one. */
class InvalidKeysException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

/** The other side's identity key isn't the one this phone trusts for that user. */
class UntrustedIdentityException(val user: String, cause: Throwable? = null) : CryptoException("$user's identity key isn't the trusted one", cause)

/** No session with that device (encrypt), or the message can't be opened (decrypt: corrupt, replayed, unknown keys). */
class UndecryptableException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

/**
 * The Signal protocol for this phone: its identity key (one per user; linked phones will share it), its prekeys,
 * the sessions with other devices (one Double Ratchet per pair of devices), and safety numbers. Everything it keeps is
 * in the phone's encrypted store. Not thread-safe: the caller runs it on one thread at a time (Messaging's dispatcher).
 */
interface CryptoEngine {
    /** This phone's public identity key, 33 bytes; null before [createIdentity]. */
    fun identityKey(): ByteArray?

    /** Makes a new identity key pair and registration id, forgetting the old ones. Answers the public key. */
    fun createIdentity(): ByteArray

    /**
     * This phone's identity key pair, private half included, in libsignal's serialized form: what linking a phone
     * sends the new one, sealed (docs/design.md, "Linking a phone"). Null before [createIdentity].
     */
    fun exportIdentity(): ByteArray?

    /**
     * Takes [pair] (another phone's [exportIdentity]) as this phone's identity, with a new registration id of its own,
     * forgetting the old identity and its prekeys. Answers the public key. Throws [InvalidKeysException] for bytes that
     * aren't a key pair.
     */
    fun importIdentity(pair: ByteArray): ByteArray

    /** A new X25519 key pair for one linking: (private 32 bytes, public 33 bytes). Kept by the caller, not the engine. */
    fun newAgreementKey(): Pair<ByteArray, ByteArray>

    /** The X25519 shared secret of [private] (from [newAgreementKey]) and the other side's [public]. */
    fun agree(private: ByteArray, public: ByteArray): ByteArray

    /** libsignal's registration id of this phone, 1 to 16380 (made with the identity). */
    fun registrationId(): Int

    /** Makes and keeps a signed EC prekey (the identity key signs its public half). */
    fun generateSignedPrekey(keyId: Int, nowMillis: Long): SignedPublicPrekey

    /** Makes and keeps a Kyber (ML-KEM 1024) prekey, signed. A [lastResort] one may be used more than once. */
    fun generateKyberPrekey(keyId: Int, lastResort: Boolean, nowMillis: Long): SignedPublicPrekey

    /** Makes and keeps [count] one-time EC prekeys with ids from [firstId] (wrapping at 16777215). */
    fun generatePrekeys(firstId: Int, count: Int): List<PublicPrekey>

    /** Forgets signed EC prekeys other than [keep] made before [beforeMillis] (replaced weekly; kept a while for late messages). */
    fun removeSignedPrekeys(keep: Int, beforeMillis: Long)

    /** Trusts [identityKey] for [user] from now on: from a QR code, a checked pair request, or an accepted change. */
    fun trustIdentity(user: String, identityKey: ByteArray)

    /** The identity key trusted for [user]; null when none is. */
    fun trustedIdentity(user: String): ByteArray?

    /** Forgets the identity key trusted for [user] and every session with its devices. */
    fun forget(user: String)

    /**
     * Opens a session with [address] from its [bundle], checking the bundle's signatures against [identityKey] (which
     * must be the one trusted for the user). Throws [InvalidKeysException] or [UntrustedIdentityException].
     */
    fun startSession(address: DeviceAddress, identityKey: ByteArray, bundle: PrekeyBundle)

    fun hasSession(address: DeviceAddress): Boolean

    /** The devices of [user] this phone has a session with. */
    fun sessionDevices(user: String): List<Int>

    /** The registration id the session with [address] has for it; null without a session. */
    fun remoteRegistrationId(address: DeviceAddress): Int?

    fun deleteSession(address: DeviceAddress)

    /** Encrypts [plaintext] (already padded) for [address]. Throws [UndecryptableException] without a session. */
    fun encrypt(address: DeviceAddress, plaintext: ByteArray): Ciphertext

    /**
     * Opens a message of [type] (`EnvelopeType.PREKEY_MESSAGE` or `MESSAGE`) from [address]. A prekey message opens a
     * session; one from a user whose trusted key is another throws [UntrustedIdentityException].
     */
    fun decrypt(address: DeviceAddress, type: Int, bytes: ByteArray): Decrypted

    /** The safety number of [myUser] (this phone's identity key) and [theirUser] with [theirIdentityKey]. */
    fun safetyNumber(myUser: String, theirUser: String, theirIdentityKey: ByteArray): SafetyNumber

    /** Whether [scanned] (their safety number QR code) is the same safety number as ours. */
    fun matchesScanned(myUser: String, theirUser: String, theirIdentityKey: ByteArray, scanned: ByteArray): Boolean

    /** Forgets everything: identity, prekeys, sessions, trusted keys (another account or server). */
    fun wipe()
}

/** Key ids are 24-bit (libsignal's "medium" range); the server takes 0 to 16777215. */
const val MAX_KEY_ID = 0xFFFFFF

/** The id after [id], wrapping to 1. */
fun nextKeyId(id: Int): Int = if (id >= MAX_KEY_ID) 1 else id + 1
