package com.chencohen.ptt.talk

import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.messaging.ClipKind
import com.chencohen.ptt.messaging.FakeCryptoEngine
import com.chencohen.ptt.messaging.FakeServer
import com.chencohen.ptt.messaging.MessageStatus
import com.chencohen.ptt.messaging.Messaging
import com.chencohen.ptt.messaging.MessagingController
import com.chencohen.ptt.messaging.MessagingStore
import com.chencohen.ptt.relay.TalkEvent
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.settings.KeyValueStore
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.store.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TalkPrefsStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** A microphone the test speaks into: [say] gives packets as if recorded. */
private class FakeCapture : TalkCapture {
    var onPacket: ((ByteArray) -> Unit)? = null
    override fun start(onPacket: (ByteArray) -> Unit): Boolean {
        this.onPacket = onPacket
        return true
    }

    override fun stop() {
        onPacket = null
    }

    fun say(from: Int, count: Int) = repeat(count) { i -> onPacket!!(byteArrayOf(7, ((from + i) and 0xff).toByte(), ((from + i) shr 8).toByte())) }
}

/** A camera the test films with: [film] gives encoded frames as if recorded. */
private class FakeCamera : TalkVideoCapture {
    var onFrame: ((TalkVideo.Frame) -> Unit)? = null
    override fun start(onFrame: (TalkVideo.Frame) -> Unit): Boolean {
        this.onFrame = onFrame
        return true
    }

    override fun stop() {
        onFrame = null
    }

    /** Frame [i]: [size] bytes, all [i]; a key frame every 15th. */
    fun film(i: Int, size: Int) = onFrame!!(TalkVideo.Frame(i * 66L, i % 15 == 0, 90, ByteArray(size) { i.toByte() }, TalkVideo.H265))
}

/** A screen that keeps the pictures it was given. */
private class FakeVideoOut : TalkVideoOut {
    val frames = mutableListOf<TalkVideo.Frame>()
    val begun = mutableListOf<String>()
    override fun begin(clip: String) {
        begun += clip
    }

    override fun frame(clip: String, frame: TalkVideo.Frame) {
        frames += frame
    }

    override fun end(clip: String) {}
}

/** A loudspeaker that keeps what it played. */
private class FakePlayer : TalkPlayer {
    val played = mutableListOf<List<ByteArray>>()
    val live = mutableListOf<Boolean>()
    /** How each clip ended: "finish" (played out) or "stop" (cut). */
    val ends = mutableListOf<String>()
    override fun open(live: Boolean, catchUp: CatchUpMode, onProgress: (Long, Long) -> Unit, onDone: () -> Unit): Playback {
        val clip = mutableListOf<ByteArray>()
        played += clip
        this.live += live
        return object : Playback {
            override fun write(packets: List<ByteArray>) {
                clip += packets
            }

            override fun finish() {
                ends += "finish"
                onDone()
            }

            override fun stop() {
                ends += "stop"
                onDone()
            }
        }
    }
}

private class FakeHost(var allow: Boolean) : TalkHost {
    val notified = mutableListOf<String>()
    var prepared = 0
    override suspend fun prepareToPlay(chat: String, from: String): Boolean {
        prepared++
        return allow
    }

    override fun talkNotify(chat: String, from: String, clip: String) {
        notified += clip
    }

    override fun idle() {}
}

private class TalkPhone(val server: FakeServer, val user: String, scope: TestScope, seed: Int) {
    val settings = PttSettings(TalkPrefsStore()).apply {
        serverUrl = "https://203.0.113.7:56400/query"
        identityAlias = "ptt-device-$user-1"
        serverDiscovery = KeptDiscovery(fingerprint = "ab".repeat(32), id = "ID")
        device = EnrolledDevice(user, 1, "Pixel")
    }
    val address = DeviceAddress(user, 1)
    private val job = SupervisorJob()
    val work = CoroutineScope(scope.coroutineContext + job)
    val files = MemoryClipFiles()
    val messaging = Messaging(
        settings, FakeCryptoEngine(Random(seed)), MessagingStore(MemoryStore()), server.api(address), server.connector(address), work,
        now = { 1_000_000L }, random = Random(seed), clipFiles = files, blobApi = server.blobs(address),
    )
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val controller = MessagingController(messaging, dispatcher, main = dispatcher)
    val mic = FakeCapture()
    val speaker = FakePlayer()
    val host = FakeHost(allow = true)
    val camera = FakeCamera()
    val screen = FakeVideoOut()

    init {
        messaging.talk.capture = mic
        messaging.talk.player = speaker
        messaging.talk.host = host
        messaging.talk.videoCapture = camera
        messaging.talk.videoOut = screen
    }

    val talk get() = messaging.talk
    val state get() = messaging.state.value
    fun chat(with: String) = state.conversations[with].orEmpty()

    fun stop() {
        controller.close()
        job.cancel()
    }
}

class TalkTest {
    private fun packets(from: Int, count: Int) = (from until from + count).map { byteArrayOf(7, (it and 0xff).toByte(), (it shr 8).toByte()) }

    private suspend fun TestScope.paired(): Triple<FakeServer, TalkPhone, TalkPhone> {
        val server = FakeServer()
        val hen = TalkPhone(server, "hen", this, 1)
        val dana = TalkPhone(server, "dana", this, 2)
        hen.controller.start()
        dana.controller.start()
        advanceUntilIdle()
        val (code, _) = hen.messaging.newPairCode()
        dana.messaging.pairWith(code, verified = true)
        advanceUntilIdle()
        assertTrue(hen.messaging.accept("dana"))
        advanceUntilIdle()
        return Triple(server, hen, dana)
    }

    @Test
    fun aBurstPlaysLiveOnTheOtherPhoneAndStaysInBothChats() = runTest {
        val (server, hen, dana) = paired()
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        assertEquals(TalkPhase.TALKING, hen.state.talk.own?.phase)
        val id = hen.state.talk.own!!.clip
        assertEquals("hen", dana.state.talk.talkers["hen"]?.user, "dana sees who talks")
        hen.mic.say(0, 25)
        advanceUntilIdle()
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()

        assertNull(hen.state.talk.own)
        assertTrue(dana.state.talk.talkers.isEmpty())
        assertEquals(listOf(true), dana.speaker.live)
        dana.speaker.played.single().forEachIndexed { i, p -> assertContentEquals(packets(0, 25)[i], p) }
        assertEquals(25, dana.speaker.played.single().size)
        assertTrue(dana.address to id in server.talkAcks, "dana's phone said it plays the burst")
        assertTrue(id in server.completeClips)

        val heard = dana.chat("hen").single()
        assertEquals(ClipKind.BURST, heard.clip?.kind)
        assertEquals(500L, heard.clip?.durationMs)
        assertTrue(heard.clip!!.kept && heard.clip!!.heard)
        val said = hen.chat("dana").single()
        assertTrue(said.outgoing)
        assertEquals(500L, said.clip?.durationMs)
        assertNotNull(hen.files.read(id))
        hen.stop()
        dana.stop()
    }

    @Test
    fun anIncomingBurstUnmutesTheMediaVolumeWhileItPlays() = runTest {
        val (_, hen, dana) = paired()
        val volume = FakeVolume()
        dana.talk.volume = volume
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        hen.mic.say(0, 10)
        advanceUntilIdle()
        assertEquals(9, volume.now, "turned up while hen talks (on by default)")
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        assertEquals(0, volume.now, "back off once it played")

        // Turned off in Settings › Talk: left muted.
        dana.messaging.setTalkSettings(dana.state.talkSettings.copy(unmuteForTalk = false))
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        hen.mic.say(0, 10)
        advanceUntilIdle()
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        assertEquals(listOf(9, 0), volume.sets)
        hen.stop()
        dana.stop()
    }

    @Test
    fun withVideoTheCameraGoesInTheBurstAndShowsBesideTheVoice() = runTest {
        val (server, hen, dana) = paired()
        hen.talk.sendVideo = { _ -> true }
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        // A big key frame (more than one segment's worth) and small ones, between the voice's packets.
        hen.camera.film(0, 20_000)
        hen.mic.say(0, 10)
        for (i in 1..14) hen.camera.film(i, 1_500)
        hen.mic.say(10, 15)
        advanceUntilIdle()
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()

        assertEquals(25, dana.speaker.played.single().size, "the voice plays whole")
        dana.speaker.played.single().forEachIndexed { i, p -> assertContentEquals(packets(0, 25)[i], p) }
        assertEquals(15, dana.screen.frames.size, "every picture comes")
        assertEquals(20_000, dana.screen.frames[0].data.size)
        assertTrue(dana.screen.frames[0].key)
        assertEquals(90, dana.screen.frames[0].rotation)
        assertEquals(TalkVideo.H265, dana.screen.frames[0].codec)
        assertEquals((1..14).toList(), dana.screen.frames.drop(1).map { it.data[0].toInt() })
        val heard = dana.chat("hen").single()
        assertTrue(heard.clip!!.video)
        assertEquals(500L, heard.clip?.durationMs, "the length counts the voice only")
        assertTrue(server.segmentSizes.all { it < 64 * 1024 }, "every segment fits the server")
        hen.stop()
        dana.stop()
    }

    @Test
    fun severalVideoBurstsInARowEachShowTheirPictures() = runTest {
        val (_, hen, dana) = paired()
        hen.talk.sendVideo = { _ -> true }
        repeat(3) { n ->
            hen.work.launch { hen.talk.press("dana") }
            advanceUntilIdle()
            hen.camera.film(0, 4_000)
            hen.mic.say(0, 5)
            for (i in 1..4) hen.camera.film(i, 800)
            hen.mic.say(5, 5)
            advanceUntilIdle()
            hen.work.launch { hen.talk.release() }
            advanceUntilIdle()
            assertEquals(n + 1, dana.screen.begun.size, "burst ${n + 1} began its video")
            assertEquals(5 * (n + 1), dana.screen.frames.size, "burst ${n + 1} brought its 5 pictures")
            assertTrue(dana.screen.frames[5 * n].key, "burst ${n + 1} starts at a key frame")
        }
        assertEquals(3, dana.screen.begun.distinct().size)
        hen.stop()
        dana.stop()
    }

    @Test
    fun anOpenTalkScreenTellsTheOtherUserItIsHere() = runTest {
        val (_, hen, dana) = paired()
        hen.work.launch { hen.messaging.setHere("dana") }
        runCurrent()
        assertTrue("hen" in dana.state.here, "dana's phone knows hen's Talk screen for her is open")
        hen.work.launch { hen.messaging.setHere(null) }
        runCurrent()
        assertTrue("hen" !in dana.state.here, "closed: gone at once")
        hen.stop()
        dana.stop()
    }

    @Test
    fun anEmailSharedReachesTheOtherPhoneForItsContacts() = runTest {
        val (_, hen, dana) = paired()
        hen.work.launch { hen.messaging.setMyEmail(" hen@example.com ") }
        advanceUntilIdle()
        assertEquals("hen@example.com", dana.state.contacts.single { it.user == "hen" }.shownEmail)
        // Dana's own entry for him wins over what he shares.
        dana.work.launch { dana.messaging.setEmail("hen", "work@example.com") }
        advanceUntilIdle()
        assertEquals("work@example.com", dana.state.contacts.single { it.user == "hen" }.shownEmail)
        hen.stop()
        dana.stop()
    }

    @Test
    fun theWholeBurstPlaysFromTheFirstWordToTheLast() = runTest {
        val (_, hen, dana) = paired()
        // Spoken at once with the press, before the server gives the floor: kept, and sent once it does.
        hen.work.launch {
            hen.talk.press("dana")
            hen.mic.say(0, 7)
        }
        advanceUntilIdle()
        hen.mic.say(7, 18)
        advanceUntilIdle()
        // The last words, then the button comes up at once.
        hen.work.launch {
            hen.mic.say(25, 3)
            hen.talk.release()
        }
        advanceUntilIdle()
        val heard = dana.speaker.played.single()
        assertEquals(28, heard.size, "every packet from the press to the release plays")
        heard.forEachIndexed { i, p -> assertContentEquals(packets(0, 28)[i], p) }
        assertEquals(listOf("finish"), dana.speaker.ends, "the end of a burst plays out, it isn't cut")
        hen.stop()
        dana.stop()
    }

    @Test
    fun theFloorIsBusyWhileTheOtherTalks() = runTest {
        val (server, hen, dana) = paired()
        dana.work.launch { dana.talk.press("hen") }
        advanceUntilIdle()
        hen.work.launch { hen.talk.press("dana") }
        advanceTimeBy(1_000)
        assertEquals(TalkPhase.BUSY, hen.state.talk.own?.phase)
        assertEquals("dana", hen.state.talk.own?.busyWith)
        assertTrue(server.talkStates.any { it.first == hen.address && it.second.event == TalkEvent.BUSY })
        advanceUntilIdle()
        assertNull(hen.state.talk.own, "Busy shows for a moment")
        assertTrue(hen.chat("dana").none { it.outgoing }, "nothing of hen's was kept")
        hen.stop()
        dana.stop()
    }

    @Test
    fun aPhoneThatMissesTheForwardedSegmentsReadsTheClipFromTheServer() = runTest {
        val (server, hen, dana) = paired()
        server.dropForwarded = true
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        hen.mic.say(100, 12)
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        assertEquals(12, dana.speaker.played.single().size)
        assertTrue(dana.chat("hen").single().clip!!.kept)
        hen.stop()
        dana.stop()
    }

    @Test
    fun aSleepingPhonePlaysTheBurstFromItsStartWhenWoken() = runTest {
        val (_, hen, dana) = paired()
        dana.controller.stop()
        advanceUntilIdle()
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        hen.mic.say(0, 30)
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()

        dana.work.launch { dana.messaging.wake() }
        advanceUntilIdle()
        assertEquals(1, dana.host.prepared, "a talk session was asked for")
        assertEquals(30, dana.speaker.played.single().size)
        hen.stop()
        dana.stop()
    }

    @Test
    fun notifyOnlyShowsANotificationInsteadOfPlaying() = runTest {
        val (_, hen, dana) = paired()
        dana.settings.notifyOnlyChats = setOf("hen")
        dana.controller.stop()
        advanceUntilIdle()
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        val id = hen.state.talk.own!!.clip
        hen.mic.say(0, 10)
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        dana.work.launch { dana.messaging.wake() }
        advanceUntilIdle()
        assertEquals(listOf(id), dana.host.notified)
        assertTrue(dana.speaker.played.isEmpty())
        assertEquals(ClipKind.BURST, dana.chat("hen").single().clip?.kind)
        hen.stop()
        dana.stop()
    }

    @Test
    fun aBurstSaidOfflineGoesAsAVoiceMessageOnceConnected() = runTest {
        val (server, hen, dana) = paired()
        hen.controller.stop()
        advanceUntilIdle()
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        assertEquals(TalkPhase.OFFLINE, hen.state.talk.own?.phase)
        hen.mic.say(0, 40)
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        val kept = hen.chat("dana").single()
        assertEquals(ClipKind.VOICE, kept.clip?.kind)
        assertEquals(MessageStatus.PENDING, kept.status)

        hen.controller.start()
        advanceUntilIdle()
        assertEquals(MessageStatus.DELIVERED, hen.chat("dana").single().status)
        assertNotNull(server.files[kept.id])
        val got = dana.chat("hen").single()
        assertEquals(ClipKind.VOICE, got.clip?.kind)
        assertEquals(800L, got.clip?.durationMs)
        assertTrue(dana.speaker.played.isEmpty(), "a voice message doesn't play by itself")

        dana.work.launch { dana.talk.play(got.id) }
        advanceUntilIdle()
        assertEquals(40, dana.speaker.played.single().size)
        assertEquals(listOf(false), dana.speaker.live)
        hen.stop()
        dana.stop()
    }

    @Test
    fun aVoiceMessageFromTheComposer() = runTest {
        val (server, hen, dana) = paired()
        hen.work.launch { hen.talk.startVoice("dana") }
        advanceUntilIdle()
        assertEquals(TalkPhase.VOICE, hen.state.talk.own?.phase)
        assertTrue(server.floors.isEmpty(), "a voice message takes no floor")
        hen.mic.say(0, 50)
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        assertEquals(MessageStatus.DELIVERED, hen.chat("dana").single().status)
        assertEquals(1000L, dana.chat("hen").single().clip?.durationMs)
        hen.stop()
        dana.stop()
    }

    @Test
    fun aCancelledVoiceMessageIsNotSent() = runTest {
        val (server, hen, dana) = paired()
        hen.work.launch { hen.talk.startVoice("dana") }
        advanceUntilIdle()
        hen.mic.say(0, 50)
        hen.work.launch { hen.talk.release(send = false) }
        advanceUntilIdle()
        assertTrue(hen.chat("dana").isEmpty())
        assertTrue(server.files.isEmpty())
        hen.stop()
        dana.stop()
    }
}

class ClipCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val clip = "0123456789abcdef0123456789abcdef"

    @Test
    fun aSegmentOpensOnlyAsItself() {
        val packets = listOf(byteArrayOf(1, 2, 3), ByteArray(0), ByteArray(70) { 9 })
        val sealed = ClipCrypto.seal(key, clip, 4, last = false, packets)
        val opened = assertNotNull(ClipCrypto.open(key, clip, 4, false, sealed))
        assertEquals(3, opened.size)
        opened.zip(packets).forEach { (a, b) -> assertContentEquals(b, a) }
        assertNull(ClipCrypto.open(key, clip, 5, false, sealed), "moved to another index")
        assertNull(ClipCrypto.open(key, clip, 4, true, sealed), "marked last")
        assertNull(ClipCrypto.open(key, "f" + clip.drop(1), 4, false, sealed), "moved to another clip")
        assertNull(ClipCrypto.open(ByteArray(32), clip, 4, false, sealed), "another key")
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertNull(ClipCrypto.open(key, clip, 4, false, sealed), "changed")
    }

    @Test
    fun recordsAreReadAcrossSplitReads() {
        val records = (0..3).map { ClipRecords.record(it, it == 3, ByteArray(20 + it) { b -> b.toByte() }) }
        val all = concat(records)
        val reader = ClipRecords.Reader()
        val got = (0 until all.size step 7).flatMap { reader.feed(all.copyOfRange(it, minOf(all.size, it + 7))) }
        assertEquals(listOf(0, 1, 2, 3), got.map { it.index })
        assertTrue(got.last().last)
        assertEquals(23, got.last().ciphertext.size)
    }
}

class CatchUpTest {
    @Test
    fun shortensPausesThenSpeedsUpUntilBackAtTheTarget() {
        val c = CatchUp(CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP)
        assertEquals(CatchUpStep.Play(1f), c.next(300, silent = false), "within the slack")
        assertEquals(CatchUpStep.Play(1.1f), c.next(900, silent = false))
        repeat(5) { assertEquals(CatchUpStep.Play(1.1f), c.next(880, silent = true), "a pause keeps its start") }
        assertEquals(CatchUpStep.Drop, c.next(860, silent = true))
        assertEquals(CatchUpStep.Play(1.25f), c.next(3_000, silent = false), "far behind")
        assertEquals(CatchUpStep.Play(1f), c.next(240, silent = false), "back at the target")
        assertEquals(CatchUpStep.Play(1f), c.next(320, silent = false), "not again until past the slack")
    }

    @Test
    fun theOtherModes() {
        val pauses = CatchUp(CatchUpMode.SHORTEN_PAUSES)
        assertEquals(CatchUpStep.Play(1f), pauses.next(900, silent = false))
        repeat(5) { pauses.next(900, silent = true) }
        assertEquals(CatchUpStep.Drop, pauses.next(900, silent = true))
        val off = CatchUp(CatchUpMode.OFF)
        repeat(10) { assertEquals(CatchUpStep.Play(1f), off.next(5_000, silent = true)) }
    }

    @Test
    fun silence() {
        assertTrue(Silence.isSilent(ShortArray(960)))
        assertTrue(!Silence.isSilent(ShortArray(960) { if (it % 2 == 0) 3000 else -3000 }))
    }
}
