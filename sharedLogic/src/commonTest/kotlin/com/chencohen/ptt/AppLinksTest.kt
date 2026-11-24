package com.chencohen.ptt

import com.chencohen.ptt.enrollment.parseEnrollmentLink
import com.chencohen.ptt.enrollment.findEnrollmentLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppLinksTest {
    private val enroll = "ptt://enroll?code=K7QM-4XPD&server=https%3A%2F%2Fhost%3A56400&fingerprint=" + "a".repeat(64)

    @Test
    fun aWebLinkReadsAsItsAppLink() {
        val web = AppLinks.web(enroll)
        assertEquals("https://chencohensolutions.github.io/push-to-talk-server-go/enroll#code=K7QM-4XPD&server=https%3A%2F%2Fhost%3A56400&fingerprint=" + "a".repeat(64), web)
        assertEquals(enroll, AppLinks.toApp(web))
        assertEquals("K7QM-4XPD", parseEnrollmentLink(web)?.code)
        // In a mail's words too, and the ptt:// form still works.
        assertNotNull(findEnrollmentLink("Open this link on your phone: $web. Thanks"))
        assertEquals("K7QM-4XPD", parseEnrollmentLink(enroll)?.code)
        // Anything else is left alone.
        assertEquals("https://example.com/x#y", AppLinks.web("https://example.com/x#y"))
        assertEquals("ptt://pair?x=1", AppLinks.toApp(AppLinks.web("ptt://pair?x=1")))
        // Invites, contact codes and link codes read the same in their web form.
        val invite = com.chencohen.ptt.messaging.InviteLink(
            code = "abc123456defghijklmnop", server = "https://192.168.50.2:56400", fingerprint = "ab".repeat(32),
            key = null, addresses = emptyList(), inviter = "hen", identityKey = byteArrayOf(5) + ByteArray(32) { 3 },
            secret = ByteArray(16) { 1 }, email = null, passcode = "123456",
        )
        assertEquals(invite.format(), com.chencohen.ptt.messaging.InviteLink.parse(AppLinks.web(invite.format()))?.format())
        assertEquals(invite.format(), com.chencohen.ptt.messaging.InviteLink.find("Open: ${AppLinks.web(invite.format())} thanks")?.format())
    }
}
