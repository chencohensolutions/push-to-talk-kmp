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
        assertEquals("https://chencohensolutions.github.io/push-to-talk-kmp/enroll#code=K7QM-4XPD&server=https%3A%2F%2Fhost%3A56400&fingerprint=" + "a".repeat(64), web)
        assertEquals(enroll, AppLinks.toApp(web))
        assertEquals("K7QM-4XPD", parseEnrollmentLink(web)?.code)
        // In a mail's words too, and the ptt:// form still works.
        assertNotNull(findEnrollmentLink("Open this link on your phone: $web. Thanks"))
        assertEquals("K7QM-4XPD", parseEnrollmentLink(enroll)?.code)
        // Anything else is left alone.
        assertEquals("https://example.com/x#y", AppLinks.web("https://example.com/x#y"))
        assertEquals("ptt://pair?x=1", AppLinks.toApp(AppLinks.web("ptt://pair?x=1")))
    }
}
