package com.chencohen.ptt.talk

import com.chencohen.ptt.messaging.ApiException
import com.chencohen.ptt.messaging.Content
import com.chencohen.ptt.messaging.LiveToken
import com.chencohen.ptt.messaging.TalkKeyContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTalkTest {
    private class FakeTransport : TalkTransport {
        val joins = mutableListOf<Pair<String, Int>>()
        val keys = mutableMapOf<String, Pair<ByteArray, Int>>()
        var leaves = 0
        var talking = false
        val heard = mutableSetOf<String>()
        val silenced = mutableListOf<String>()
        var joinWorks = true
        var stale = false
        override fun stale() = stale
        override var listener: TalkTransportListener? = null
        override val capture: TalkCapture = object : TalkCapture {
            override fun start(onPacket: (ByteArray) -> Unit) = true
            override fun stop() {}
        }

        override suspend fun join(url: String, token: String, identity: String, ownKey: ByteArray, ownIndex: Int): Boolean {
            joins += url to ownIndex
            return joinWorks
        }

        override fun leave() { leaves++ }
        override fun setKey(identity: String, key: ByteArray, index: Int) { keys[identity] = key to index }
        override suspend fun setTalking(on: Boolean) { talking = on && micThere }
        /** The room has this phone's microphone track (published, muted until a press). */
        var micThere = true
        override val micReady get() = micThere
        override val talkingLive get() = talking
        override fun hears(identity: String) = identity in heard
        override fun silence(identity: String) { silenced += identity }
        val unsilenced = mutableListOf<String>()
        override fun unsilence(identity: String) { unsilenced += identity }
    }

    private class Setup(val scope: TestScope, liveOn: Boolean = true) {
        val transport = FakeTransport()
        /** The next asks whether the server has live talk fail (offline). */
        var availableFails = 0
        val sent = mutableListOf<Pair<String, Content>>()
        val tokenAsks = mutableListOf<Pair<String?, String?>>()
        val live = LiveTalk(
            scope = scope.backgroundScope,
            tokens = { peer, group ->
                tokenAsks += peer to group
                if (!liveOn) throw ApiException("LIVE_OFF", "no")
                LiveToken("t", "u-x", "hen.1", "/rtc", "")
            },
            available = {
                if (availableFails > 0) {
                    availableFails--
                    throw ApiException("NETWORK", "unreachable")
                }
                liveOn
            },
            serverUrl = { "https://192.168.50.2:56400/query" },
            sendContent = { chat, content, _ -> sent += chat to content },
            now = { scope.testScheduler.currentTime },
            random = Random(1),
        ).also { it.transport = transport }
    }

    @Test
    fun aRoomJoinedAsCallAudioInTheBackgroundIsJoinedAgainWhenItsChatComesOnScreen() = runTest {
        val s = Setup(this)
        s.live.warm("testbot")
        runCurrent()
        assertEquals(1, s.transport.joins.size)
        // The app opened: media may play now, the room's call audio is stale.
        s.transport.stale = true
        s.live.show("testbot")
        runCurrent()
        assertEquals(2, s.transport.joins.size, "joined again, as media")
        assertEquals(1, s.transport.leaves)
        assertEquals("testbot", s.live.state.value.room)
        // Not stale: showing it again keeps the room.
        s.transport.stale = false
        s.live.show(null)
        s.live.show("testbot")
        runCurrent()
        assertEquals(2, s.transport.joins.size)
    }

    @Test
    fun theChatOnScreenJoinsItsRoomAndSendsTheKey() = runTest {
        val s = Setup(this)
        s.live.show("sharon")
        runCurrent()
        assertEquals(listOf("wss://192.168.50.2:56400" to 1), s.transport.joins)
        assertEquals(listOf<Pair<String?, String?>>("sharon" to null), s.tokenAsks)
        assertEquals("sharon", s.live.state.value.room)
        val key = s.sent.single().second.talkKey!!
        assertEquals(LiveTalk.KEY_BYTES, key.key.size)
        assertEquals(1, key.index)
        // Someone joins later: they get the key too.
        s.transport.listener!!.joined("sharon.1")
        runCurrent()
        assertEquals(2, s.sent.size)
    }

    @Test
    fun aGroupAsksForItsGroupRoom() = runTest {
        val s = Setup(this)
        s.live.show("#abc")
        runCurrent()
        assertEquals(listOf<Pair<String?, String?>>(null to "abc"), s.tokenAsks)
    }

    @Test
    fun othersKeysAreSetAndKeptForTheNextRoom() = runTest {
        val s = Setup(this)
        s.live.takeKey("sharon.1", TalkKeyContent(ByteArray(32) { 7 }, 3))
        s.live.takeKey("bad.1", TalkKeyContent(ByteArray(5), 1))
        s.live.takeKey("bad.2", TalkKeyContent(ByteArray(32), LiveTalk.KEY_RING))
        assertEquals(setOf("sharon.1"), s.transport.keys.keys)
        s.transport.keys.clear()
        s.live.show("sharon")
        runCurrent()
        assertEquals(3, s.transport.keys["sharon.1"]!!.second)
    }

    @Test
    fun theRoomIsLeftAMinuteAfterTheLastBurstOnceOffScreen() = runTest {
        val s = Setup(this)
        s.live.show("sharon")
        runCurrent()
        s.live.show(null)
        s.live.burst("sharon", "sharon")
        advanceTimeBy(LiveTalk.STAY_AFTER_BURST - 1_000)
        runCurrent()
        assertEquals("sharon", s.live.state.value.room)
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(s.live.state.value.room)
        assertEquals(1, s.transport.leaves)
    }

    @Test
    fun theKeyIndexGoesRoundInTheKeyRing() = runTest {
        val s = Setup(this)
        repeat(LiveTalk.KEY_RING + 1) { i ->
            s.live.show(if (i % 2 == 0) "a" else "b")
            runCurrent()
        }
        assertTrue(s.transport.joins.all { it.second in 0 until LiveTalk.KEY_RING })
        assertEquals(1, s.transport.joins.last().second)
    }

    @Test
    fun withoutLiveKitNothingIsJoined() = runTest {
        val s = Setup(this, liveOn = false)
        s.live.show("sharon")
        runCurrent()
        assertTrue(s.transport.joins.isEmpty())
        assertNull(s.live.captureFor("sharon"))
    }

    @Test
    fun hearingLiveAndTheMicrophoneOnlyInTheRoom() = runTest {
        val s = Setup(this)
        s.live.show("sharon")
        runCurrent()
        s.transport.heard += "sharon.1"
        assertTrue(s.live.hearsLive("sharon", "sharon.1"))
        assertFalse(s.live.hearsLive("other", "sharon.1"))
        s.live.talking("sharon", true)
        assertTrue(s.transport.talking)
        s.live.talking("other", false)
        assertTrue(s.transport.talking)
        s.live.silence("sharon", "sharon.1")
        assertEquals(listOf("sharon.1"), s.transport.silenced)
        assertTrue(s.live.captureFor("sharon") != null)
        assertNull(s.live.captureFor("other"))
    }

    @Test
    fun talkingAnswersWhetherTheVoiceReallyWentIntoTheRoom() = runTest {
        // 2026-11-27: a press shown live that went nowhere. Talk sends a press into the room only when this says yes.
        val s = Setup(this)
        assertFalse(s.live.talking("sharon", true), "not in the room: the press isn't live there")
        s.live.show("sharon")
        runCurrent()
        assertTrue(s.live.talking("sharon", true))
        assertTrue(s.live.sendingLive("sharon"))
        assertFalse(s.live.sendingLive("other"))
        assertFalse(s.live.talking("sharon", false), "off: not live")
        assertFalse(s.live.sendingLive("sharon"))
        // The room has no microphone track of this phone's (it couldn't be opened): not live either.
        s.transport.micThere = false
        assertFalse(s.live.talking("sharon", true))
        // Let go before the microphone was ready: it doesn't come on after the release.
        s.transport.micThere = true
        assertFalse(s.live.talking("sharon", true) { false })
        assertFalse(s.transport.talking)
    }

    @Test
    fun aFailedAskWhetherTheServerHasLiveTalkIsAskedAgain() = runTest {
        // Opening a chat while offline used to switch live talk off for the rest of the app's run.
        val s = Setup(this)
        s.availableFails = 1
        s.live.show("sharon")
        runCurrent()
        assertTrue(s.transport.joins.isEmpty())
        s.live.show(null)
        s.live.show("sharon")
        runCurrent()
        assertEquals(1, s.transport.joins.size, "asked again, and joined")
    }

    @Test
    fun aLostRoomIsToldSoAPressIntoItCanEnd() = runTest {
        val s = Setup(this)
        val lost = mutableListOf<String>()
        s.live.onLost = { lost += it }
        s.live.show("sharon")
        runCurrent()
        s.transport.listener!!.left()
        runCurrent()
        assertNull(s.live.state.value.room)
        assertEquals(listOf("sharon"), lost)
    }

    @Test
    fun aSilencedTalkerIsHeardAgainAtTheirNextBurst() = runTest {
        // 2026-11-27, hen: "audio not always working": silenced once (its clip played), every later push from them in
        // the room stayed silent.
        val s = Setup(this)
        s.live.show("testbot")
        runCurrent()
        s.live.silence("testbot", "testbot.1")
        assertEquals(listOf("testbot.1"), s.transport.silenced)
        s.live.unsilence("testbot", "testbot.1")
        assertEquals(listOf("testbot.1"), s.transport.unsilenced)
        // Not for another room's talker.
        s.live.unsilence("other", "testbot.1")
        assertEquals(1, s.transport.unsilenced.size)
    }

    @Test
    fun theLatencyIsFromTheBurstToItsLiveAudio() = runTest {
        val s = Setup(this)
        s.live.show("sharon")
        runCurrent()
        s.live.burst("sharon", "sharon")
        advanceTimeBy(240)
        s.transport.listener!!.liveAudio("sharon.1")
        runCurrent()
        assertEquals(240L, s.live.state.value.lastLatencyMs)
    }
}
