package com.chencohen.ptt.messaging

/**
 * The message an Invite a friend link goes out in (docs/design.md, "Invites"): the steps, the link and the invite's
 * six-digit passcode, from this phone's mail app ([mailto]) or any app through the share sheet ([body]). The server
 * sends no mail: this text is the whole invitation.
 *
 * The words are here rather than in the app's strings because the friend's phone reads them back: pasting the whole
 * message into the wizard finds the link and the passcode in it ([passcodeIn]).
 */
class InviteMessage(
    /** Who invites, as the friend knows them: "Hen Cohen" or "hen". */
    val inviter: String,
    /** The link, `ptt://invite?…`. */
    val link: String,
    /** Six digits, sent beside the link (not in it). */
    val passcode: String,
    /** When the link stops working, as the phone writes a date ("Nov 29, 2026"). */
    val until: String,
) {
    val subject: String get() = "$inviter invited you to $APP"

    /** The passcode as it is easier to read and type: "123 456". */
    val spacedPasscode: String get() = spaced(passcode)

    val body: String
        get() = """
            |$inviter invited you to $APP.
            |
            |1. Install the $APP app on your phone.
            |2. Open this link on your phone: $link
            |3. When the app asks, type this code: $spacedPasscode
            |
            |The link works once, until $until. If you weren’t expecting this, ignore this message.
            """.trimMargin()

    /**
     * A `mailto:` URI (RFC 6068) to [to] with [subject] and [body]: only mail apps answer it, so the message goes from
     * the user's own address. Android's ACTION_SENDTO takes it as it is.
     */
    fun mailto(to: String): String =
        "mailto:${encode(to, keepAt = true)}?subject=${encode(subject)}&body=${encode(body.replace("\n", "\r\n"))}"

    companion object {
        const val APP = "Talk"

        /** Six digits as "123 456". */
        fun spaced(passcode: String): String = if (passcode.length == 6) passcode.take(3) + " " + passcode.drop(3) else passcode

        private val LINK = Regex("""(ptt://|https://)\S+""")
        private val PASSCODE = Regex("""(?<![0-9A-Za-z])(\d{3})[ -]?(\d{3})(?![0-9A-Za-z])""")

        /**
         * The passcode in a pasted invite message: the first six digits standing alone (spaced "123 456" or not) once
         * the links are taken out, since an invite code may hold digits. Null when there is none.
         */
        fun passcodeIn(text: String): String? {
            val match = PASSCODE.find(LINK.replace(text, " ")) ?: return null
            return match.groupValues[1] + match.groupValues[2]
        }

        /** Percent-encodes everything but RFC 3986's unreserved characters (and '@' in an address). */
        private fun encode(text: String, keepAt: Boolean = false): String = buildString {
            for (byte in text.encodeToByteArray()) {
                val c = byte.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~" || (keepAt && ch == '@')) {
                    append(ch)
                } else {
                    append('%')
                    append(HEX[c shr 4])
                    append(HEX[c and 0xf])
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
