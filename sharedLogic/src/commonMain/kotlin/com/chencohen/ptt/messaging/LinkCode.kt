@file:OptIn(ExperimentalSerializationApi::class)

package com.chencohen.ptt.messaging

import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.discovery.Seal
import com.chencohen.ptt.enrollment.EnrollmentLink
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.ByteString.Companion.toByteString

/**
 * What a Link a phone QR code holds (docs/design.md, "Linking a phone"), version 1:
 *
 * `ptt://link?v=1&code=<enrollment code>&server=…&fingerprint=…&key=…&address=…&u=<user>&d=<device>&s=<secret>`
 *
 * The server part is an enrollment link's, with a code the showing phone made for its own user (createLinkCode), so
 * the new phone enrolls as that user. [user] and [device] are the showing phone, which the new phone asks for the
 * identity key once it is enrolled; [secret] (32 random bytes) never reaches the server and is what makes the two
 * phones trust each other's PROVISIONING envelopes. The code is only ever shown on a screen, face to face: it is
 * never shared as a link.
 */
data class LinkCode(
    val code: String,
    val server: String?,
    val fingerprint: String,
    val key: String?,
    val addresses: List<String>,
    val user: String,
    val device: Int,
    val secret: ByteArray,
) {
    fun format(): String = URLBuilder("${PairCode.SCHEME}://$HOST").apply {
        parameters.append("v", VERSION.toString())
        parameters.append("code", this@LinkCode.code)
        server?.let { parameters.append("server", it) }
        parameters.append("fingerprint", fingerprint)
        this@LinkCode.key?.let { parameters.append("key", it) }
        addresses.forEach { parameters.append("address", it) }
        parameters.append("u", this@LinkCode.user)
        parameters.append("d", device.toString())
        parameters.append("s", PairCode.base64Url.encode(secret))
    }.buildString()

    /** How the new phone finds the server and enrolls: an enrollment link with the link code as its code. */
    fun enrollmentLink(): EnrollmentLink = EnrollmentLink(code, server, fingerprint, key, addresses)

    override fun equals(other: Any?): Boolean = other is LinkCode && format() == other.format()
    override fun hashCode(): Int = code.hashCode()

    companion object {
        const val HOST = "link"
        const val VERSION = 1
        const val SECRET_BYTES = 32

        /** A Link a phone code; null for anything else, or one with a part missing or malformed. */
        fun parse(text: String): LinkCode? {
            val url = runCatching { Url(text.trim()) }.getOrNull() ?: return null
            if (url.protocol.name != PairCode.SCHEME || url.host != HOST) return null
            val version = url.parameters["v"]?.toIntOrNull() ?: return null
            if (version > VERSION) return null
            val code = url.parameters["code"]?.takeIf { it.isNotBlank() } ?: return null
            val fingerprint = url.parameters["fingerprint"]?.trim()?.lowercase()
                ?.takeIf { v -> v.length == 64 && v.all { it in "0123456789abcdef" } } ?: return null
            val key = url.parameters["key"]?.trim()?.lowercase()?.takeIf { v -> v.length == 64 && v.all { it in "0123456789abcdef" } }
            val user = url.parameters["u"]?.takeIf { it.isNotBlank() } ?: return null
            val device = url.parameters["d"]?.toIntOrNull()?.takeIf { it in 1..127 } ?: return null
            val secret = url.parameters["s"]?.let { runCatching { PairCode.base64Url.decode(it) }.getOrNull() }
                ?.takeIf { it.size == SECRET_BYTES } ?: return null
            return LinkCode(
                code = code,
                server = url.parameters["server"]?.takeIf { it.isNotBlank() },
                fingerprint = fingerprint,
                key = key,
                addresses = url.parameters.getAll("address").orEmpty().mapNotNull(KeptDiscovery::originOf).distinct(),
                user = user,
                device = device,
                secret = secret,
            )
        }

        private val IN_TEXT = Regex("""ptt://link\?[^\s<>"']+""")

        /** The first Link a phone code in [text]. */
        fun find(text: String): LinkCode? =
            IN_TEXT.findAll(text).firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', ';', ')', ']', '!', '?')) }
    }
}

// --- What PROVISIONING envelopes carry ---------------------------------------------------------------------------
// Not Signal messages: the new phone has no keys yet. Two of them make a linking (docs/design.md, "Linking a phone"):
//
// 1. The new phone → the showing phone: a LinkRequest with a new X25519 public key and a MAC over it made with the
//    QR code's secret, so the showing phone knows the request comes from whoever scanned its screen.
// 2. The showing phone → the new phone: a LinkAnswer with its own new X25519 public key and LinkData sealed with
//    XChaCha20-Poly1305 under HKDF(X25519 shared secret, salt = the QR code's secret). The server, which relays both
//    and saw neither the secret nor a private key, can't open it or put its own key in the middle.

/** One PROVISIONING envelope's content. Exactly one is set. */
@Serializable
internal data class Provisioning(
    @ProtoNumber(1) val request: LinkRequest? = null,
    @ProtoNumber(2) val answer: LinkAnswer? = null,
)

@Serializable
internal class LinkRequest(
    @ProtoNumber(1) val publicKey: ByteArray = ByteArray(0),
    @ProtoNumber(2) val mac: ByteArray = ByteArray(0),
)

@Serializable
internal class LinkAnswer(
    @ProtoNumber(1) val publicKey: ByteArray = ByteArray(0),
    @ProtoNumber(2) val nonce: ByteArray = ByteArray(0),
    @ProtoNumber(3) val sealed: ByteArray = ByteArray(0),
)

/** What the new phone gets: the user's identity key pair, and the contacts with what this phone knows of them. */
@Serializable
internal class LinkData(
    @ProtoNumber(1) val identityKeyPair: ByteArray = ByteArray(0),
    @ProtoNumber(2) val contacts: List<LinkedContact> = emptyList(),
)

@Serializable
internal data class LinkedContact(
    @ProtoNumber(1) val user: String = "",
    @ProtoNumber(2) val identityKey: ByteArray = ByteArray(0),
    @ProtoNumber(3) val verified: Boolean = false,
    @ProtoNumber(4) val displayName: String = "",
) {
    override fun equals(other: Any?): Boolean = other is LinkedContact && user == other.user &&
        identityKey.contentEquals(other.identityKey) && verified == other.verified && displayName == other.displayName

    override fun hashCode(): Int = user.hashCode()
}

/** The ciphers of a linking, over [Seal]'s HKDF, HMAC and XChaCha20-Poly1305. */
internal object LinkCrypto {
    private val REQUEST_INFO = "ptt link request v1".encodeToByteArray()
    private val SEAL_INFO = "ptt link seal v1".encodeToByteArray()
    private val AAD = "ptt link v1".encodeToByteArray()

    /** The MAC a new phone puts on its request: over its public key and who it is, keyed by the QR code's secret. */
    fun requestMac(secret: ByteArray, publicKey: ByteArray, user: String, device: Int): ByteArray {
        val key = Seal.hkdfSha256(secret, ByteArray(0), REQUEST_INFO, 32)
        return (publicKey + "$user.$device".encodeToByteArray()).toByteString().hmacSha256(key.toByteString()).toByteArray()
    }

    fun macMatches(expected: ByteArray, got: ByteArray): Boolean {
        if (expected.size != got.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor got[i].toInt())
        return diff == 0
    }

    /** The key LinkData is sealed with, from the X25519 [shared] secret and the QR code's [secret]. */
    fun sealKey(shared: ByteArray, secret: ByteArray): ByteArray = Seal.hkdfSha256(shared, secret, SEAL_INFO, Seal.KEY_SIZE)

    fun seal(key: ByteArray, nonce: ByteArray, data: LinkData): ByteArray =
        Seal.sealXChaCha20Poly1305(key, nonce, ProtoBuf.encodeToByteArray(LinkData.serializer(), data), AAD)

    /** Null when it doesn't open (another key: not the phone that showed the code). */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray): LinkData? {
        if (nonce.size != Seal.NONCE_SIZE) return null
        val plain = Seal.openXChaCha20Poly1305(key, nonce, sealed, AAD) ?: return null
        return runCatching { ProtoBuf.decodeFromByteArray(LinkData.serializer(), plain) }.getOrNull()
    }

    fun encode(p: Provisioning): ByteArray = ProtoBuf.encodeToByteArray(Provisioning.serializer(), p)

    fun decode(bytes: ByteArray): Provisioning? = runCatching { ProtoBuf.decodeFromByteArray(Provisioning.serializer(), bytes) }.getOrNull()
}
