package com.chencohen.ptt

/**
 * Talk's links as web addresses (2026-11-24, hen: Gmail drops `ptt://` links, so an enrollment email's link couldn't
 * be tapped). `ptt://enroll?<query>` is also `https://chencohensolutions.github.io/push-to-talk-server-go/enroll#<query>`
 * (the server repo's GitHub Pages): the same words after `#`, the fragment, which a browser never sends to the site. The page
 * there opens Talk with the `ptt://` link ("Open in Talk"), or says how to get the app; with Talk installed, Android
 * opens it straight away (a verified App Link: chencohensolutions.github.io/.well-known/assetlinks.json, the site
 * repo of the same name, lists the app's keys). Both forms keep working.
 */
object AppLinks {
    const val HOST = "chencohensolutions.github.io"
    const val PATH = "/push-to-talk-server-go/"
    const val WEB = "https://$HOST$PATH"

    /** The kinds of link that have a web form. */
    private val KINDS = listOf("enroll", "invite", "link", "pair")

    private val WEB_LINK = Regex("""https://${Regex.escape(HOST)}${Regex.escape(PATH)}(${KINDS.joinToString("|")})/?#""")
    private val APP_LINK = Regex("""^ptt://(${KINDS.joinToString("|")})\?""")

    /** [text] with every web-form link written as its `ptt://` link (anything else left as it is). */
    fun toApp(text: String): String = WEB_LINK.replace(text) { "ptt://${it.groupValues[1]}?" }

    /** The web form of a `ptt://` link ([link] itself when it has none). */
    fun web(link: String): String = APP_LINK.find(link)?.let { m -> WEB + m.groupValues[1] + "#" + link.substring(m.range.last + 1) } ?: link
}
