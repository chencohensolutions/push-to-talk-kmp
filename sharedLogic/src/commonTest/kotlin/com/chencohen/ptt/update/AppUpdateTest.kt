package com.chencohen.ptt.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateTest {
    private val sha = "3F9093EF8D2E6821DA25587E2AE93C6F29DB49C2B04566602F30116C8AA01567"
    private fun release(version: String, url: String = "https://github.com/x/releases/download/v$version/talk-$version.apk", sha256: String = sha) =
        AppRelease(version, url, 65194670, sha256, "Pink.")

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
    fun aNewerReleaseIsOfferedWithItsChecksumInLowercase() {
        val offer = AppUpdate.offer(release("1.3.0"), "1.2.0")!!
        assertEquals("1.3.0", offer.version)
        assertEquals(sha.lowercase(), offer.sha256)
        assertEquals("talk-1.3.0.apk", offer.apkName)
    }

    @Test
    fun nothingToOffer() {
        assertNull(AppUpdate.offer(null, "1.2.0"), "the server read no release")
        assertNull(AppUpdate.offer(release("1.2.0"), "1.2.0"), "the same version")
        assertNull(AppUpdate.offer(release("1.1.0"), "1.2.0"), "an older one")
        assertNull(AppUpdate.offer(release("1.3.0", url = "http://github.com/talk.apk"), "1.2.0"), "not https")
        assertNull(AppUpdate.offer(release("1.3.0", sha256 = "abc"), "1.2.0"), "no real checksum")
    }

    @Test
    fun aPushNotifiesOncePerVersion() {
        val offer = AppUpdate.offer(release("1.3.0"), "1.2.0")
        assertTrue(AppUpdate.notify(offer, null))
        assertTrue(AppUpdate.notify(offer, "1.2.5"))
        assertFalse(AppUpdate.notify(offer, "1.3.0"))
        assertFalse(AppUpdate.notify(null, null))
    }

    @Test
    fun installsOnlyWhenNothingIsGoingOn() {
        val quiet = com.chencohen.ptt.messaging.MessagingState()
        assertTrue(AppUpdate.quiet(quiet))
        val t = com.chencohen.ptt.talk.TalkUiState()
        assertFalse(AppUpdate.quiet(quiet.copy(talk = t.copy(own = com.chencohen.ptt.talk.OwnTalk("dana", "c1", com.chencohen.ptt.talk.TalkPhase.TALKING, 0)))))
        assertFalse(AppUpdate.quiet(quiet.copy(talk = t.copy(playing = com.chencohen.ptt.talk.NowPlaying("dana", "c1", "dana", live = true)))))
        assertFalse(AppUpdate.quiet(quiet.copy(talk = t.copy(queued = listOf("c2")))))
        assertFalse(AppUpdate.quiet(quiet.copy(talk = t.copy(talkers = mapOf("dana" to com.chencohen.ptt.talk.Talker("dana", 1, "c1", 0))))))
        assertFalse(AppUpdate.quiet(quiet.copy(live = com.chencohen.ptt.talk.LiveState(room = "dana"))))
        assertFalse(AppUpdate.quiet(quiet.copy(live = com.chencohen.ptt.talk.LiveState(joining = true))))
    }
}
