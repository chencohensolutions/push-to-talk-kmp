package com.chencohen.ptt.messaging

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlin.io.encoding.Base64

/**
 * What the Add contact QR code holds (docs/design.md, "The Add contact code"), version 1:
 *
 * `ptt://pair?v=1&u=<user>&k=<identity key>&t=<pairing token>&s=<secret>&f=<server>`
 *
 * [user] is the user name, [identityKey] the user's public identity key (33 bytes), [token] a `pairingToken` (22
 * characters, single use, 10 minutes), [secret] 16 random bytes that never reach the server, and [server] the first 16
 * hex digits of the server key's fingerprint, so a code from another server is told apart. Bytes are base64url without
 * padding. A phone that reads a higher `v` says it needs a newer app.
 */
data class PairCode(
    val user: String,
    val identityKey: ByteArray,
    val token: String,
    val secret: ByteArray,
    val server: String?,
) {
    fun format(): String = URLBuilder("$SCHEME://$HOST").apply {
        parameters.append("v", VERSION.toString())
        parameters.append("u", this@PairCode.user)
        parameters.append("k", base64Url.encode(identityKey))
        parameters.append("t", token)
        parameters.append("s", base64Url.encode(secret))
        server?.let { parameters.append("f", it) }
    }.buildString()

    override fun equals(other: Any?): Boolean = other is PairCode && user == other.user && identityKey.contentEquals(other.identityKey) &&
        token == other.token && secret.contentEquals(other.secret) && server == other.server

    override fun hashCode(): Int = user.hashCode() * 31 + token.hashCode()

    companion object {
        const val SCHEME = "ptt"
        const val HOST = "pair"
        const val VERSION = 1
        const val SECRET_BYTES = 16

        internal val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        /** The server part of a code: the first 16 hex digits of the fingerprint. */
        fun serverOf(fingerprint: String?): String? = fingerprint?.lowercase()?.take(16)?.takeIf { it.length == 16 }

        /** What a scan read: a code, a code of a newer version, or something else. */
        fun parse(text: String): PairCodeParse {
            val url = try {
                Url(text.trim())
            } catch (_: Exception) {
                return PairCodeParse.NotACode
            }
            if (url.protocol.name != SCHEME || url.host != HOST) return PairCodeParse.NotACode
            val version = url.parameters["v"]?.toIntOrNull() ?: return PairCodeParse.NotACode
            if (version > VERSION) return PairCodeParse.NewerVersion
            val user = url.parameters["u"]?.takeIf { it.isNotBlank() } ?: return PairCodeParse.NotACode
            val key = url.parameters["k"]?.let { runCatching { base64Url.decode(it) }.getOrNull() }
                ?.takeIf { it.size == 33 && it[0] == 0x05.toByte() } ?: return PairCodeParse.NotACode
            val token = url.parameters["t"]?.takeIf { it.isNotBlank() } ?: return PairCodeParse.NotACode
            val secret = url.parameters["s"]?.let { runCatching { base64Url.decode(it) }.getOrNull() }
                ?.takeIf { it.size == SECRET_BYTES } ?: return PairCodeParse.NotACode
            val server = url.parameters["f"]?.lowercase()?.takeIf { f -> f.length == 16 && f.all { it in "0123456789abcdef" } }
            return PairCodeParse.Code(PairCode(user, key, token, secret, server))
        }
    }
}

sealed interface PairCodeParse {
    data class Code(val code: PairCode) : PairCodeParse
    data object NewerVersion : PairCodeParse
    data object NotACode : PairCodeParse
}

/**
 * What the safety number QR code holds: `ptt://safety?v=1&n=<libsignal's scannable fingerprint, base64url>`. Only for
 * comparing on the safety number screen; it carries no secret.
 */
object SafetyCode {
    fun format(scannable: ByteArray): String = "ptt://safety?v=1&n=" + PairCode.base64Url.encode(scannable)

    /** The scannable bytes of a safety number code; null for anything else. */
    fun parse(text: String): ByteArray? {
        val url = runCatching { Url(text.trim()) }.getOrNull() ?: return null
        if (url.protocol.name != PairCode.SCHEME || url.host != "safety" || url.parameters["v"] != "1") return null
        return url.parameters["n"]?.let { runCatching { PairCode.base64Url.decode(it) }.getOrNull() }
    }
}
