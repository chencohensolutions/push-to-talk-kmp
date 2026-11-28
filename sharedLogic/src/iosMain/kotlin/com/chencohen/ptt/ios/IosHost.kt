package com.chencohen.ptt.ios

import platform.Foundation.NSData
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential

// What only Swift can do for the shared logic on iOS (iosApp/Talk/Host): CryptoKit, the Keychain and the Secure
// Enclave, the system's SQLite, and libsignal's Swift package. The shared code decides; these are the hands. Bytes
// cross as NSData (Data in Swift), failures as null or a message, since a Swift implementation of a Kotlin interface
// can't throw.

/** A device key made by [IosHost.generateDeviceKey]: its public point, or why it couldn't be made. */
class IosDeviceKey(
    /** The EC P-256 public key as an X9.63 point (0x04, X, Y: 65 bytes); null when [error] says why not. */
    val publicPoint: NSData?,
    /** The private key is in the Secure Enclave (a real phone); false in software (the simulator). */
    val secureEnclave: Boolean,
    val error: String?,
)

interface IosHost {
    /** libsignal (LibSignalClient), keeping its records in [db]. */
    val signal: IosSignal

    /** The phone's encrypted store (docs/design.md, "The encrypted store"; on iOS, SQLite with sealed values). */
    val db: IosDb

    // --- AES-256-GCM (CryptoKit): clips, media, call frames -----------------------------------------------------

    /** Ciphertext followed by the 16-byte tag; null when the key or the nonce has the wrong size. */
    fun aesGcmSeal(key: NSData, nonce: NSData, aad: NSData, plaintext: NSData): NSData?

    /** The plaintext; null when the tag doesn't verify. */
    fun aesGcmOpen(key: NSData, nonce: NSData, aad: NSData, ciphertext: NSData): NSData?

    // --- The device's mTLS key (the Keychain; the Secure Enclave on a phone) ------------------------------------

    /** A new EC P-256 key under [alias], replacing any there. */
    fun generateDeviceKey(alias: String): IosDeviceKey

    /** SHA-256/ECDSA over [data] with the key under [alias], in DER (X9.62); null when the key is gone. */
    fun signWithDeviceKey(alias: String, data: NSData): NSData?

    /** Keeps the certificate issued for [alias]'s key, so the two make an identity; null, or why it couldn't. */
    fun saveDeviceCertificate(alias: String, certificateDer: NSData): String?

    /** The certificate kept under [alias] (DER); null when none. */
    fun deviceCertificate(alias: String): NSData?

    fun deleteDeviceKey(alias: String)

    // --- TLS (NSURLSession's challenges, asked by IosMtlsHttpClientFactory) -------------------------------------

    /** The credential that presents [alias]'s key and certificate to the server; null when there is none. */
    fun clientCredential(alias: String): NSURLCredential?

    /** The server's own certificate in a server-trust [challenge] (DER); null when it sent none. */
    fun serverCertificate(challenge: NSURLAuthenticationChallenge): NSData?

    /**
     * Whether the server's chain in [challenge] ends at one of [cas] (DER) and is for a server (serverAuth), whatever
     * its host name: the server's own CA, kept after enrolling.
     */
    fun chainsToCas(challenge: NSURLAuthenticationChallenge, cas: List<NSData>): Boolean

    /** The credential that accepts the server of a server-trust [challenge], once the shared code checked it. */
    fun trustCredential(challenge: NSURLAuthenticationChallenge): NSURLCredential?
}

/** One entry of the encrypted store: its key and its opened value. */
class IosEntry(val key: String, val value: NSData)

/** The encrypted store's one table of (space, key) → value, as SecureStore has it. Called on Messaging's thread only. */
interface IosDb {
    fun get(space: String, key: String): NSData?

    fun put(space: String, key: String, value: NSData)

    fun remove(space: String, key: String)

    /** The keys of [space], sorted. */
    fun keys(space: String): List<String>

    /** Every key of [space] with its value, sorted by key, in one read (the chats' history at the start). */
    fun entries(space: String): List<IosEntry>

    fun clear(space: String)

    fun clearAll()
}
