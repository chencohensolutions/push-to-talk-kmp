package com.chencohen.ptt.messaging

import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.enrollment.EnrollmentLink
import io.ktor.http.URLBuilder
import io.ktor.http.Url

/**
 * What an Invite a friend link holds (docs/design.md, "Invites"), version 1:
 *
 * `ptt://invite?v=1&code=<invite code>&server=…&fingerprint=…&key=…&address=…&u=<user>&k=<identity key>&s=<secret>&e=<email>&p=<passcode>`
 *
 * The server part (`server`, `fingerprint`, `key`, `address`) is an enrollment link's, so a phone that isn't
 * enrolled finds the server and pins its key the same way; [code] is the server's invite code (createInvite). The
 * inviter's part (`u`, `k`, `s`) is the Add contact code's without a token: redeeming the invite gives one. [email] is
 * the address the invite is for, when it is for one.
 *
 * [passcode] (`p`) is only in the QR code the inviter's screen shows face to face ([withPasscode]): there the friend is
 * in the room, and scanning it is the whole invite. The link sent by mail or a chat app leaves it out, and the
 * passcode goes beside it, so that the link alone, wherever it ends up, redeems nothing (docs/design.md, "Invites").
 */
data class InviteLink(
    val code: String,
    val server: String?,
    val fingerprint: String,
    val key: String?,
    val addresses: List<String>,
    val inviter: String,
    val identityKey: ByteArray,
    val secret: ByteArray,
    val email: String?,
    val passcode: String?,
) {
    /** This link with [passcode] in it: what the face-to-face QR code carries. */
    fun withPasscode(passcode: String): InviteLink = copy(passcode = passcode)

    fun format(): String = URLBuilder("${PairCode.SCHEME}://$HOST").apply {
        parameters.append("v", VERSION.toString())
        parameters.append("code", this@InviteLink.code)
        server?.let { parameters.append("server", it) }
        parameters.append("fingerprint", fingerprint)
        this@InviteLink.key?.let { parameters.append("key", it) }
        addresses.forEach { parameters.append("address", it) }
        parameters.append("u", inviter)
        parameters.append("k", PairCode.base64Url.encode(identityKey))
        parameters.append("s", PairCode.base64Url.encode(secret))
        email?.let { parameters.append("e", it) }
        passcode?.let { parameters.append("p", it) }
    }.buildString()

    /** How a phone that isn't enrolled finds the server: an enrollment link with the invite code as its code. */
    fun enrollmentLink(): EnrollmentLink = EnrollmentLink(code, server, fingerprint, key, addresses)

    /** The inviter's Add contact code, once redeeming the invite gave [token]. */
    fun pairCode(token: String): PairCode = PairCode(inviter, identityKey, token, secret, PairCode.serverOf(fingerprint))

    override fun equals(other: Any?): Boolean = other is InviteLink && format() == other.format()
    override fun hashCode(): Int = code.hashCode()

    companion object {
        const val HOST = "invite"
        const val VERSION = 1

        /** An invite link; null for anything else, or one with a part missing or malformed. */
        fun parse(text: String): InviteLink? {
            val url = runCatching { Url(com.chencohen.ptt.AppLinks.toApp(text.trim())) }.getOrNull() ?: return null
            if (url.protocol.name != PairCode.SCHEME || url.host != HOST) return null
            val version = url.parameters["v"]?.toIntOrNull() ?: return null
            if (version > VERSION) return null
            val code = url.parameters["code"]?.takeIf { it.isNotBlank() } ?: return null
            val fingerprint = url.parameters["fingerprint"]?.trim()?.lowercase()
                ?.takeIf { v -> v.length == 64 && v.all { it in "0123456789abcdef" } } ?: return null
            val key = url.parameters["key"]?.trim()?.lowercase()?.takeIf { v -> v.length == 64 && v.all { it in "0123456789abcdef" } }
            val inviter = url.parameters["u"]?.takeIf { it.isNotBlank() } ?: return null
            val identityKey = url.parameters["k"]?.let { runCatching { PairCode.base64Url.decode(it) }.getOrNull() }
                ?.takeIf { it.size == 33 && it[0] == 0x05.toByte() } ?: return null
            val secret = url.parameters["s"]?.let { runCatching { PairCode.base64Url.decode(it) }.getOrNull() }
                ?.takeIf { it.size == PairCode.SECRET_BYTES } ?: return null
            return InviteLink(
                code = code,
                server = url.parameters["server"]?.takeIf { it.isNotBlank() },
                fingerprint = fingerprint,
                key = key,
                addresses = url.parameters.getAll("address").orEmpty().mapNotNull(KeptDiscovery::originOf).distinct(),
                inviter = inviter,
                identityKey = identityKey,
                secret = secret,
                email = url.parameters["e"]?.takeIf { it.contains('@') },
                passcode = url.parameters["p"]?.takeIf { p -> p.length == 6 && p.all { it in '0'..'9' } },
            )
        }

        private val IN_TEXT = Regex("""ptt://invite\?[^\s<>"']+""")

        /** The first invite link in [text] (as a chat app sends it, with words around it). */
        fun find(text: String): InviteLink? =
            IN_TEXT.findAll(com.chencohen.ptt.AppLinks.toApp(text)).firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', ';', ')', ']', '!', '?')) }
    }
}
