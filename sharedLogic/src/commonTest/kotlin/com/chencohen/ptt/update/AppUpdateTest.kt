package com.chencohen.ptt.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateTest {
    private fun release(tag: String, vararg assets: String, prerelease: Boolean = false) = """
        {"tag_name":"$tag","html_url":"https://github.com/x/releases/tag/$tag","body":"Pink.","draft":false,"prerelease":$prerelease,
         "assets":[${assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://github.com/x/$it","size":65194670,"extra":1}""" }}],
         "author":{"login":"someone"}}
    """.trimIndent()

    @Test
    fun theLatestReleaseWithItsApkAndChecksums() {
        val r = AppUpdate.parseLatest(release("v1.2.0", "talk-1.2.0.apk", "SHA256SUMS"))!!
        assertEquals("1.2.0", r.version)
        assertEquals("talk-1.2.0.apk", r.apkName)
        assertEquals("https://github.com/x/talk-1.2.0.apk", r.apkUrl)
        assertEquals(65194670, r.apkSize)
        assertEquals("https://github.com/x/SHA256SUMS", r.sumsUrl)
        assertEquals("Pink.", r.notes)
    }

    @Test
    fun notAReleaseToUpdateTo() {
        assertNull(AppUpdate.parseLatest(release("v1.2.0", "talk-1.2.0.apk")), "no SHA256SUMS")
        assertNull(AppUpdate.parseLatest(release("v1.2.0", "SHA256SUMS", "talk-1.1.0.apk")), "another version's APK")
        assertNull(AppUpdate.parseLatest(release("v1.2.0", "talk-1.2.0.apk", "SHA256SUMS", prerelease = true)))
        assertNull(AppUpdate.parseLatest(release("dev-20261122", "talk-dev-20261122.apk", "SHA256SUMS")))
        assertNull(AppUpdate.parseLatest("not json"))
    }

    @Test
    fun versionsCompareByNumber() {
        assertTrue(AppUpdate.isNewer("1.2.0", "1.1.0"))
        assertTrue(AppUpdate.isNewer("1.10.0", "1.9.3"))
        assertTrue(AppUpdate.isNewer("2.0", "1.99.99"))
        assertFalse(AppUpdate.isNewer("1.1.0", "1.1.0"))
        assertFalse(AppUpdate.isNewer("1.0.9", "1.1.0"))
        assertFalse(AppUpdate.isNewer("1.1", "1.1.0"))
        assertFalse(AppUpdate.isNewer("1.1.0", "1.1.0-debug"))
        assertTrue(AppUpdate.isNewer("1.1.1", "1.1.0-debug"))
        assertFalse(AppUpdate.isNewer("x", "1.0.0"))
    }

    @Test
    fun theApksChecksumFromSha256Sums() {
        val sums = """
            3f9093ef8d2e6821da25587e2ae93c6f29db49c2b04566602f30116c8aa01567  talk-1.0.2.apk
            B893545CDDA3125EC3DEC043E838E3BB683B08C02C325650652399F104664F88 *talk-1.0.1.apk
        """.trimIndent()
        assertEquals("3f9093ef8d2e6821da25587e2ae93c6f29db49c2b04566602f30116c8aa01567", AppUpdate.sha256For(sums, "talk-1.0.2.apk"))
        assertEquals("b893545cdda3125ec3dec043e838e3bb683b08c02c325650652399f104664f88", AppUpdate.sha256For(sums, "talk-1.0.1.apk"))
        assertNull(AppUpdate.sha256For(sums, "talk-1.0.0.apk"))
        assertNull(AppUpdate.sha256For("abc  talk-1.0.2.apk", "talk-1.0.2.apk"))
    }

    @Test
    fun looksAgainAfterSixHours() {
        val h = 60 * 60 * 1000L
        assertTrue(AppUpdate.due(0, 10 * h))
        assertFalse(AppUpdate.due(10 * h, 15 * h))
        assertTrue(AppUpdate.due(10 * h, 16 * h))
        assertTrue(AppUpdate.due(10 * h, 9 * h), "the clock went back")
    }
}
