package com.chencohen.ptt.ios

import platform.Foundation.NSData

/** What a call into [IosSignal] answered: its bytes and numbers, or [error] (one of [SignalFailure]) with [message]. */
class SignalReply(
    val data: NSData? = null,
    /** A second value: a signature beside a public key, the sender's identity key beside a plaintext. */
    val more: NSData? = null,
    val number: Int = 0,
    val text: String? = null,
    val error: Int = SignalFailure.NONE,
    val message: String? = null,
)

/** Why libsignal refused, as CryptoEngine's exceptions name it (IosCryptoEngine turns one into the other). */
object SignalFailure {
    const val NONE = 0

    /** Bytes that aren't a key, or a signature that doesn't verify. */
    const val INVALID_KEYS = 1

    /** The other side's identity key isn't the one kept for the user. */
    const val UNTRUSTED = 2

    /** No session, or a message that can't be opened. */
    const val UNDECRYPTABLE = 3

    /** This phone has no identity key yet, or isn't registered. */
    const val NO_IDENTITY = 4
}

/**
 * libsignal's Swift package behind CryptoEngine (docs/design.md, "Keys and sessions"): the calls that need libsignal
 * itself. Its records live in the encrypted store in libsignal's own serialized form, under the same spaces and keys
 * as Android's SignalStore, so what only reads or deletes records (trusted keys, session lists, wipe) stays in the
 * shared code (IosCryptoEngine). Addresses are a user name and a device number; [localUser] and [localDevice] are this
 * phone's, which libsignal records in its sessions. Not thread-safe: called on Messaging's thread only.
 */
interface IosSignal {
    /** This phone's public identity key, 33 bytes; null before [createIdentity]. */
    fun identityKey(): NSData?

    /** A new identity key pair and registration id, kept. Answers the public key. */
    fun createIdentity(): NSData

    /** The identity key pair, private half included, serialized; null before [createIdentity]. */
    fun exportIdentity(): NSData?

    /** Keeps [pair] (another phone's [exportIdentity]) with a new registration id. Answers the public key in `data`. */
    fun importIdentity(pair: NSData): SignalReply

    /** A new X25519 key pair: the private key in `data` (32 bytes), the public one in `more` (33 bytes). */
    fun makeAgreementKey(): SignalReply

    /** The shared secret of [privateKey] and [publicKey], in `data`. */
    fun agree(privateKey: NSData, publicKey: NSData): SignalReply

    fun registrationId(): Int

    /** Makes and keeps a signed EC prekey: its public key in `data`, the signature in `more`. */
    fun generateSignedPrekey(keyId: Int, nowMillis: Long): SignalReply

    /** Makes and keeps a Kyber prekey: its public key in `data`, the signature in `more`. */
    fun generateKyberPrekey(keyId: Int, nowMillis: Long): SignalReply

    /** Makes and keeps a one-time EC prekey: its public key in `data`. */
    fun generatePrekey(keyId: Int): SignalReply

    /** When the signed EC prekey record [serialized] was made (milliseconds); -1 when it isn't one. */
    fun signedPrekeyTimestamp(serialized: NSData): Long

    /** Whether [key] is a public identity key. */
    fun isIdentityKey(key: NSData): Boolean

    /** Opens a session from a device's bundle, checking its signatures against [identityKey]. [prekeyId] -1: none. */
    fun startSession(
        user: String,
        device: Int,
        localUser: String,
        localDevice: Int,
        identityKey: NSData,
        registrationId: Int,
        signedPrekeyId: Int,
        signedPrekey: NSData,
        signedPrekeySignature: NSData,
        prekeyId: Int,
        prekey: NSData?,
        kyberPrekeyId: Int,
        kyberPrekey: NSData,
        kyberPrekeySignature: NSData,
    ): SignalReply

    /** Whether the session record [serialized] can send (it has a current sender chain). */
    fun sessionUsable(serialized: NSData): Boolean

    /** The registration id the session record [serialized] has for the other side; -1 when it has none. */
    fun sessionRemoteRegistrationId(serialized: NSData): Int

    /** The message in `data`; `number` is 3 for a prekey message and 2 for a message (libsignal's types). */
    fun encrypt(user: String, device: Int, localUser: String, localDevice: Int, plaintext: NSData): SignalReply

    /** The plaintext in `data`, and in `more` the identity key of the session it came on. */
    fun decrypt(user: String, device: Int, localUser: String, localDevice: Int, prekeyMessage: Boolean, bytes: NSData): SignalReply

    /** The safety number: its 60 digits in `text`, what the QR code carries in `data`. */
    fun safetyNumber(myUser: String, theirUser: String, theirIdentityKey: NSData): SignalReply

    fun matchesScanned(myUser: String, theirUser: String, theirIdentityKey: NSData, scanned: NSData): Boolean

    /** This device's sender key for [distributionId] (made when there is none): its distribution message in `data`. */
    fun senderKeyDistribution(localUser: String, localDevice: Int, distributionId: String): SignalReply

    fun takeSenderKey(user: String, device: Int, distribution: NSData): SignalReply

    fun distributionIdOf(distribution: NSData): String?

    /** A SENDER_KEY_MESSAGE in `data`. */
    fun groupEncrypt(localUser: String, localDevice: Int, distributionId: String, plaintext: NSData): SignalReply

    /** The plaintext in `data`. */
    fun groupDecrypt(user: String, device: Int, bytes: NSData): SignalReply

    fun groupMessageDistributionId(bytes: NSData): String?

    /** The identity key's signature over [data], in `data`. */
    fun sign(data: NSData): SignalReply

    fun verify(identityKey: NSData, data: NSData, signature: NSData): Boolean
}
