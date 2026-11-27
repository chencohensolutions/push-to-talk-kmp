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
import kotlin.test.assertFalse
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

/** A talk room: the test says who is heard in it; [say] speaks into its microphone track. */
private class FakeRoom : TalkTransport {
    override var listener: TalkTransportListener? = null
    val mic = FakeCapture()
    override val capture: TalkCapture get() = mic
    var joined = false
    var live = false
    /** How long joining takes (a real room: about a second). */
    var joinTakes = 0L
    val heard = mutableSetOf<String>()
    val silenced = mutableListOf<String>()

    override suspend fun join(url: String, token: String, identity: String, ownKey: ByteArray, ownIndex: Int): Boolean {
        if (joinTakes > 0) kotlinx.coroutines.delay(joinTakes)
        joined = true
        return true
    }

    override fun leave() {
        joined = false
        live = false
    }

    override fun setKey(identity: String, key: ByteArray, index: Int) {}
    override suspend fun setTalking(on: Boolean) {
        live = on && joined
    }

    override val talkingLive get() = live
    override fun hears(identity: String) = identity in heard
    override fun silence(identity: String) {
        silenced += identity
    }

    /** The room is lost under the phone (the network), as LiveKit says it. */
    fun lose() {
        leave()
        listener!!.left()
    }
}

private class FakeHost(var allow: Boolean) : TalkHost {
    /** How long readying to play off screen takes (a talk session: seconds). */
    var takes = 0L
    val notified = mutableListOf<String>()
    /** Each notification's HeardStatus (0 for a plain missed push). */
    val notifiedWhy = mutableListOf<Int>()
    var prepared = 0
    override suspend fun prepareToPlay(chat: String, from: String): Boolean {
        prepared++
        if (takes > 0) kotlinx.coroutines.delay(takes)
        return allow
    }

    override fun talkNotify(chat: String, from: String, clip: String, video: Boolean, status: Int) {
        notified += clip
        notifiedWhy += status
    }

    override fun idle() {}
}

private class TalkPhone(val server: FakeServer, val user: String, scope: TestScope, seed: Int) {
    /** The phone's clock (ms). */
    var clock = 1_000_000L
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
        now = { clock }, random = Random(seed), clipFiles = files, blobApi = server.blobs(address),
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

    private suspend fun TestScope.paired(other: String = "dana"): Triple<FakeServer, TalkPhone, TalkPhone> {
        val server = FakeServer()
        val hen = TalkPhone(server, "hen", this, 1)
        val dana = TalkPhone(server, other, this, 2)
        hen.controller.start()
        dana.controller.start()
        advanceUntilIdle()
        val (code, _) = hen.messaging.newPairCode()
        dana.messaging.pairWith(code, verified = true)
        advanceUntilIdle()
        assertTrue(hen.messaging.accept(other))
        advanceUntilIdle()
        return Triple(server, hen, dana)
    }

    /** hen pushes to testbot ([urgent]: the red bell, from the press), ten packets, and lets go. */
    private suspend fun TestScope.push(hen: TalkPhone, urgent: Boolean = false): String {
        hen.work.launch { hen.talk.press("testbot", urgent = urgent) }
        advanceUntilIdle()
        val id = hen.state.talk.own!!.clip
        hen.mic.say(0, 10)
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        return id
    }

    @Test
    fun aPushHeardOutLoudTellsTheTalkerItIsHeard() = runTest {
        val (_, hen, bot) = paired("testbot")
        val id = push(hen)
        assertEquals(10, bot.speaker.played.single().size)
        assertEquals(mapOf("testbot" to HeardStatus.HEARING), hen.state.talk.heard[id])
        assertEquals(mapOf("testbot" to HeardStatus.HEARING), hen.chat("testbot").single().clip?.heardBy)
        hen.stop()
        bot.stop()
    }

    @Test
    fun inDoNotDisturbAPushIsSavedSilentlyAndTheTalkerIsToldWhy() = runTest {
        val (_, hen, bot) = paired("testbot")
        bot.messaging.setHear(Hear.DND)
        advanceUntilIdle()
        val id = push(hen)
        assertTrue(bot.speaker.played.isEmpty(), "not out loud")
        assertEquals(listOf(id), bot.host.notified)
        assertEquals(listOf(HeardStatus.SAVED_DND), bot.host.notifiedWhy)
        assertEquals(ClipKind.BURST, bot.chat("hen").single().clip?.kind)
        assertEquals(false, bot.chat("hen").single().read, "kept unread: a missed push")
        assertEquals(HeardStatus.SAVED_DND, hen.state.talk.heard[id]?.get("testbot"))
        hen.stop()
        bot.stop()
    }

    @Test
    fun anUrgentPushGoesThroughDoNotDisturbOnlyWhereItIsLetThrough() = runTest {
        val (_, hen, bot) = paired("testbot")
        val gate = object : DndGate {
            var opened = 0
            var closed = 0
            override fun canOpen() = true
            override fun open(): Boolean { opened++; return true }
            override fun close() { closed++ }
        }
        bot.talk.dndGate = gate
        bot.messaging.setHear(Hear.DND)
        advanceUntilIdle()
        // Not let through for hen: saved, and hen is told so.
        val first = push(hen, urgent = true)
        assertTrue(bot.speaker.played.isEmpty())
        assertEquals(listOf(HeardStatus.SAVED_URGENT_NOT_ALLOWED), bot.host.notifiedWhy)
        assertEquals(HeardStatus.SAVED_URGENT_NOT_ALLOWED, hen.state.talk.heard[first]?.get("testbot"))
        assertEquals(true, hen.chat("testbot").first { it.id == first }.clip?.urgent)

        // Let through on hen's page: it plays, Do Not Disturb opened for it and put back after.
        bot.settings.urgentThroughDndChats = setOf("hen")
        val second = push(hen, urgent = true)
        assertEquals(10, bot.speaker.played.single().size)
        assertEquals(HeardStatus.HEARD_URGENT_DND, hen.state.talk.heard[second]?.get("testbot"))
        assertEquals(1, gate.opened)
        assertEquals(1, gate.closed)

        // A plain push in Do Not Disturb is still saved.
        push(hen)
        assertEquals(1, bot.speaker.played.size)
        hen.stop()
        bot.stop()
    }

    @Test
    fun aPushMadeUrgentWhileHeldIsLetThroughAfterAll() = runTest {
        val (_, hen, bot) = paired("testbot")
        bot.talk.dndGate = object : DndGate {
            override fun canOpen() = true
            override fun open() = true
            override fun close() {}
        }
        bot.settings.urgentThroughDndChats = setOf("hen")
        bot.messaging.setHear(Hear.DND)
        advanceUntilIdle()
        hen.work.launch { hen.talk.press("testbot") }
        advanceUntilIdle()
        val id = hen.state.talk.own!!.clip
        hen.mic.say(0, 5)
        advanceUntilIdle()
        assertEquals(HeardStatus.SAVED_DND, hen.state.talk.heard[id]?.get("testbot"), "saved at first")
        // Slid onto the red bell.
        assertTrue(hen.talk.markUrgent())
        advanceUntilIdle()
        assertTrue(hen.state.talk.own!!.urgent)
        hen.mic.say(5, 5)
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        assertEquals(HeardStatus.HEARD_URGENT_DND, hen.state.talk.heard[id]?.get("testbot"))
        assertTrue(bot.speaker.played.single().isNotEmpty(), "played after all (catch-up may shorten it)")
        hen.stop()
        bot.stop()
    }

    @Test
    fun thisPhonesHearStateGoesToTheServerAndContactsSeeIt() = runTest {
        val (server, hen, bot) = paired("testbot")
        bot.messaging.setHear(Hear.SILENT)
        advanceUntilIdle()
        hen.messaging.refreshPresence()
        advanceUntilIdle()
        assertEquals(Hear.SILENT, hen.state.presence["testbot"]?.hear)
        bot.messaging.setHearVisible(false)
        hen.messaging.refreshPresence()
        advanceUntilIdle()
        assertEquals(Hear.UNKNOWN, hen.state.presence["testbot"]?.hear)
        assertEquals(false, bot.settings.hearVisible)
        hen.stop()
        bot.stop()
    }

    @Test
    fun aBurstPlaysLiveOnTheOtherPhoneAndStaysInBothChats() = runTest {
        val (server, hen, dana) = paired()
        hen.work.launch { hen.talk.press("dana") }
        // Not past the stale-floor limit (75 s): the floor is still held.
        advanceTimeBy(2_000)
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
    fun anIncomingBurstPlaysAtTheTalkVolumeThenTheOldOneComesBack() = runTest {
        val (_, hen, dana) = paired()
        val volume = FakeVolume()
        dana.talk.volume = volume
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        hen.mic.say(0, 10)
        advanceUntilIdle()
        assertEquals(12, volume.now, "turned up while hen talks (on by default): ${volume.sets}")
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
        assertEquals(listOf(12, 0), volume.sets)
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
        val (server, hen, dana) = paired()
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
        assertEquals(emptyList(), server.segmentRefusals, "the segments went in order")
        hen.stop()
        dana.stop()
    }

    @Test
    fun segmentsWaitingForTheFloorNeverFallBehindNewOnes() = runTest {
        // 2026-11-23 (hen: "something is blocking calling and sending"): a new segment went out while those recorded
        // before the grant were still waiting; the server refused the gap and the floor stayed held.
        val (server, hen, dana) = paired()
        hen.work.launch {
            hen.talk.press("dana")
            hen.mic.say(0, 40)
        }
        // The grant and more voice at the same moment.
        hen.work.launch { hen.mic.say(40, 30) }
        advanceUntilIdle()
        hen.work.launch {
            hen.mic.say(70, 5)
            hen.talk.release()
        }
        advanceUntilIdle()
        assertEquals(emptyList(), server.segmentRefusals, "every segment in order")
        assertEquals(75, dana.speaker.played.single().size)
        hen.stop()
        dana.stop()
    }

    @Test
    fun aFloorWhoseEndNeverCameStopsShowingAfterTheServersLimit() = runTest {
        val (server, hen, dana) = paired()
        dana.work.launch { dana.talk.press("hen") }
        advanceTimeBy(1_000)
        assertTrue(hen.state.talk.talkers.containsKey("dana"))
        // The ENDED frame is lost: hen's phone lets it go by itself.
        server.dropTalkStates = true
        advanceTimeBy(Talk.STALE_TALKER_MS + 1)
        assertFalse(hen.state.talk.talkers.containsKey("dana"), "no stale floor blocks talk or calls")
        hen.stop()
        dana.stop()
    }

    @Test
    fun severalMembersTalkAtOnceInAGroupAndEachIsShownUntilTheirsEnds() = runTest {
        // 2026-11-25 (hen): the server gives every talker a floor of their own, in groups too; the app shows them all.
        val server = FakeServer()
        val hen = TalkPhone(server, "hen", this, 1)
        val dana = TalkPhone(server, "dana", this, 2)
        val sharon = TalkPhone(server, "sharon", this, 3)
        val id = "g1".padEnd(32, '0')
        server.groups[id] = FakeServer.FakeGroup(id, "hen", mutableListOf("hen", "dana", "sharon"))
        listOf(hen, dana, sharon).forEach { it.controller.start() }
        advanceUntilIdle()
        val chat = "#$id"
        dana.work.launch { dana.talk.press(chat) }
        advanceTimeBy(1_000)
        sharon.work.launch { sharon.talk.press(chat) }
        advanceTimeBy(1_000)
        assertEquals(TalkPhase.TALKING, dana.state.talk.own?.phase, "dana has her own floor")
        assertEquals(TalkPhase.TALKING, sharon.state.talk.own?.phase, "and so has sharon, at the same time")
        // Hen sees both, in the order they started; the newest is the chat's talker.
        assertEquals(listOf("dana", "sharon"), hen.state.talk.talking[chat]?.map { it.user })
        assertEquals("sharon", hen.state.talk.talkers[chat]?.user)
        // Each sees the other.
        assertEquals(listOf("sharon"), dana.state.talk.talking[chat]?.map { it.user })
        // Sharon lets go: dana alone is left, and is the chat's talker again.
        sharon.work.launch { sharon.talk.release() }
        advanceTimeBy(1_000)
        assertEquals(listOf("dana"), hen.state.talk.talking[chat]?.map { it.user })
        assertEquals("dana", hen.state.talk.talkers[chat]?.user)
        dana.work.launch { dana.talk.release() }
        advanceTimeBy(1_000)
        assertNull(hen.state.talk.talking[chat])
        assertNull(hen.state.talk.talkers[chat])
        listOf(hen, dana, sharon).forEach { it.stop() }
    }

    @Test
    fun aGroupTalkerWhoseEndNeverCameGoesAfterTheLimitLeavingTheOthers() = runTest {
        val server = FakeServer()
        val hen = TalkPhone(server, "hen", this, 1)
        val dana = TalkPhone(server, "dana", this, 2)
        val sharon = TalkPhone(server, "sharon", this, 3)
        val id = "g2".padEnd(32, '0')
        server.groups[id] = FakeServer.FakeGroup(id, "hen", mutableListOf("hen", "dana", "sharon"))
        listOf(hen, dana, sharon).forEach { it.controller.start() }
        advanceUntilIdle()
        val chat = "#$id"
        dana.work.launch { dana.talk.press(chat) }
        advanceTimeBy(Talk.STALE_TALKER_MS / 2)
        sharon.work.launch { sharon.talk.press(chat) }
        advanceTimeBy(1_000)
        assertEquals(2, hen.state.talk.talking[chat]?.size)
        // Their ends are lost: dana's floor goes stale first, sharon's stays shown until its own limit.
        server.dropTalkStates = true
        advanceTimeBy(Talk.STALE_TALKER_MS / 2)
        assertEquals(listOf("sharon"), hen.state.talk.talking[chat]?.map { it.user })
        assertEquals("sharon", hen.state.talk.talkers[chat]?.user)
        advanceTimeBy(Talk.STALE_TALKER_MS)
        assertNull(hen.state.talk.talking[chat])
        listOf(hen, dana, sharon).forEach { it.stop() }
    }

    @Test
    fun aBusyFromSomeoneElseIsNotWaitedFor() = runTest {
        // 2026-11-27 (hen: "remove this logic"): no "Waiting for …" anywhere. A server that still has one floor for
        // the conversation answers Busy: the press goes as busy, nothing of it sent or kept, and nothing waits.
        val (server, hen, dana) = paired()
        server.oneFloorPerConversation = true
        dana.work.launch {
            dana.talk.press("hen")
            dana.mic.say(0, 20)
        }
        advanceTimeBy(1_000)
        hen.work.launch {
            hen.talk.press("dana")
            hen.mic.say(100, 15)
        }
        advanceTimeBy(1_000)
        assertEquals(TalkPhase.BUSY, hen.state.talk.own?.phase)
        assertEquals("dana", hen.state.talk.own?.busyWith)
        dana.work.launch { dana.talk.release() }
        advanceTimeBy(5_000)
        assertEquals(1, server.talkStates.count { it.first == hen.address && it.second.event == TalkEvent.BUSY }, "not asked again")
        assertTrue(hen.chat("dana").none { it.outgoing && it.clip != null }, "nothing kept")
        assertTrue(dana.speaker.played.none { it.size == 15 }, "dana heard nothing of it")
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
        assertTrue(dana.host.notified.isEmpty(), "heard: no missed-push notification")
        hen.stop()
        dana.stop()
    }

    @Test
    fun aPushThatCameTooLateToPlayIsAMissedPush() = runTest {
        val (_, hen, dana) = paired()
        dana.controller.stop()
        advanceUntilIdle()
        hen.work.launch { hen.talk.press("dana") }
        advanceUntilIdle()
        val id = hen.state.talk.own!!.clip
        hen.mic.say(0, 10)
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        // The phone was away for a minute: the push is old when it gets it.
        dana.clock += 60_000
        dana.work.launch { dana.messaging.wake() }
        advanceUntilIdle()
        assertEquals(listOf(id), dana.host.notified)
        assertEquals(0, dana.host.prepared, "nothing played")
        assertTrue(dana.speaker.played.isEmpty())
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
    fun aPushWhileOfflineIsShownOfflineAndNothingIsKeptOrSent() = runTest {
        // 2026-11-25 (hen): a push that isn't live on screen sends nothing (it used to be kept as a voice message).
        val (server, hen, dana) = paired()
        hen.controller.stop()
        advanceUntilIdle()
        hen.work.launch { hen.talk.press("dana") }
        advanceTimeBy(100)
        assertEquals(TalkPhase.OFFLINE, hen.state.talk.own?.phase)
        assertNull(hen.mic.onPacket, "the microphone let go at once")
        hen.work.launch { hen.talk.release() }
        advanceUntilIdle()
        assertTrue(hen.chat("dana").isEmpty())
        hen.controller.start()
        advanceUntilIdle()
        assertTrue(dana.chat("hen").isEmpty())
        assertTrue(server.files.isEmpty())
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
    // --- Live for exactly as long as they transmit (2026-11-25, hen: the receiver kept showing the sender live) ------

    // --- The room and the clip (2026-11-27, hen: "sometimes the partner doesn't hear my first words") ---------------

    /** [phone] with a talk room it can join (the server has live talk). */
    private fun room(server: FakeServer, phone: TalkPhone): FakeRoom {
        server.liveTalk = true
        return FakeRoom().also { phone.messaging.live.transport = it }
    }

    @Test
    fun aPushPressedBeforeItsRoomIsJoinedGoesByItsClipFromTheFirstWord() = runTest {
        // It was shown live and reached nobody: into the room only, with the phone not in the room.
        val (server, hen, bot) = paired("testbot")
        val room = room(server, hen)
        room.joinTakes = 1_000
        hen.work.launch { hen.talk.press("testbot") }
        advanceTimeBy(200)
        assertEquals(TalkPhase.TALKING, hen.state.talk.own?.phase)
        assertFalse(room.live, "not in the room yet")
        val id = hen.state.talk.own!!.clip
        hen.mic.say(0, 10)
        hen.work.launch { hen.talk.release() }
        // Not until idle: the room joined for the burst is kept by the phone's clock, which stands still here.
        advanceTimeBy(5_000)
        assertTrue(id in server.completeClips, "the clip went up")
        assertEquals(10, bot.speaker.played.single().size)
        assertContentEquals(packets(0, 1).single(), bot.speaker.played.single().first(), "from the first word")
        hen.stop()
        bot.stop()
    }

    @Test
    fun aPushThatIsLiveInItsRoomGoesIntoTheRoomOnly() = runTest {
        val (server, hen, bot) = paired("testbot")
        val room = room(server, hen)
        hen.messaging.live.show("testbot")
        advanceTimeBy(100)
        assertEquals("testbot", hen.messaging.live.state.value.room)
        hen.work.launch { hen.talk.press("testbot") }
        advanceTimeBy(200)
        assertTrue(room.live, "the room's microphone is live")
        val id = hen.state.talk.own!!.clip
        // In the room, the room's microphone feeds the burst.
        room.mic.say(0, 10)
        hen.work.launch { hen.talk.release() }
        advanceTimeBy(2_000)
        assertNull(hen.state.talk.own)
        assertFalse(id in server.completeClips, "no clip upload: the room carried it")
        assertTrue(server.clips[id].isNullOrEmpty())
        assertNotNull(hen.files.read(id), "kept on the talker's own phone")
        hen.stop()
        bot.stop()
    }

    @Test
    fun aRoomLostDuringAPushSendsWhatWasSaidByItsClipAndEndsThePress() = runTest {
        val (server, hen, bot) = paired("testbot")
        val room = room(server, hen)
        hen.messaging.live.show("testbot")
        advanceTimeBy(100)
        hen.work.launch { hen.talk.press("testbot") }
        advanceTimeBy(200)
        val id = hen.state.talk.own!!.clip
        room.mic.say(0, 15)
        advanceTimeBy(100)
        assertTrue(server.clips[id].isNullOrEmpty(), "into the room only so far")
        room.lose()
        advanceTimeBy(2_000)
        assertNull(hen.state.talk.own, "not shown live with nothing going out")
        assertTrue(id in server.completeClips)
        assertEquals(15, bot.speaker.played.single().size)
        hen.stop()
        bot.stop()
    }

    @Test
    fun aTalkerWhoseVoiceComesLateInTheRoomIsHeardLiveNotSilenced() = runTest {
        // Silenced for good 400 ms after the start, with no clip to play instead (room only): not heard at all.
        val (server, hen, bot) = paired("testbot")
        val room = room(server, bot)
        bot.messaging.live.show("hen")
        advanceTimeBy(100)
        room.listener!!.joined("hen.1")
        bot.talk.onFrame(com.chencohen.ptt.relay.TalkStateFrame(com.chencohen.ptt.relay.TalkState(TalkEvent.STARTED, burstX, "hen", 1, peer = "hen", startedAt = bot.clock)))
        advanceTimeBy(1_200)
        assertTrue(room.silenced.isEmpty(), "still waited for: nothing else has come")
        assertNull(bot.state.talk.playing)
        // Their microphone opens now (its track was only just published).
        room.heard += "hen.1"
        advanceTimeBy(100)
        assertEquals(1, bot.messaging.live.state.value.heardCount, "heard live")
        assertTrue(room.silenced.isEmpty())
        assertTrue(bot.address to burstX in server.talkAcks, "the server was told this phone has it: no FCM wake")
        hen.stop()
        bot.stop()
    }

    @Test
    fun inTheRoomAClipThatPlaysInsteadOfTheLiveVoiceStartsAtItsFirstWord() = runTest {
        // While the phone waited for the live voice, the clip's first segments were taken unplayed.
        val (server, hen, bot) = paired("testbot")
        val room = room(server, bot)
        bot.messaging.live.show("hen")
        advanceTimeBy(100)
        room.listener!!.joined("hen.1")
        // hen's push goes by its clip (hen's phone has no room here); its voice never comes through bot's room.
        hen.work.launch { hen.talk.press("testbot") }
        advanceTimeBy(100)
        hen.mic.say(0, 25)
        advanceTimeBy(1_000)
        hen.work.launch { hen.talk.release() }
        // Not until idle: a room on screen is kept, and checked once a minute for ever.
        advanceTimeBy(5_000)
        assertEquals(listOf("hen.1"), room.silenced, "its live audio, if it came late, wouldn't play over the clip")
        assertEquals(25, bot.speaker.played.single().size)
        bot.speaker.played.single().forEachIndexed { i, p -> assertContentEquals(packets(0, 25)[i], p) }
        hen.stop()
        bot.stop()
    }

    @Test
    fun whatComesWhileAPhoneOffScreenReadiesToPlayIsPlayedFromItsFirstWord() = runTest {
        // Readying to play off screen takes seconds (a talk session); the segments that came meanwhile were skipped.
        val (_, hen, dana) = paired()
        dana.controller.stop()
        advanceUntilIdle()
        // Woken and connected, off screen (the wake keeps the connection while a burst plays).
        dana.work.launch { dana.messaging.wake() }
        advanceTimeBy(1_000)
        dana.host.takes = 2_000
        hen.work.launch { hen.talk.press("dana") }
        advanceTimeBy(100)
        hen.mic.say(0, 20)
        advanceTimeBy(500)
        hen.mic.say(20, 10)
        hen.work.launch { hen.talk.release() }
        advanceTimeBy(10_000)
        assertEquals(1, dana.host.prepared)
        assertEquals(30, dana.speaker.played.single().size)
        assertContentEquals(packets(0, 1).single(), dana.speaker.played.single().first())
        hen.stop()
        dana.stop()
    }

    private val burstX = "ab".repeat(16)

    /** testbot's floor starts on hen's phone, as the server tells it. */
    private fun TestScope.theyStart(hen: TalkPhone, burst: String = burstX) {
        hen.talk.onFrame(com.chencohen.ptt.relay.TalkStateFrame(com.chencohen.ptt.relay.TalkState(TalkEvent.STARTED, burst, "testbot", 1, peer = "testbot", startedAt = hen.clock)))
        runCurrent()
    }

    /** [seconds] go by on hen's phone, testbot beating once a second when [beating]. */
    private fun TestScope.seconds(hen: TalkPhone, seconds: Int, beating: Boolean, burst: String = burstX) = repeat(seconds) {
        hen.clock += 1_000
        if (beating) hen.talk.onBeat("testbot.1", burst)
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun TalkPhone.live(burst: String = burstX) = state.talk.talking["testbot"].orEmpty().any { it.clip == burst }

    @Test
    fun aSilentHoldStaysLiveWhileItsBeatsCome() = runTest {
        val (_, hen, bot) = paired("testbot")
        theyStart(hen)
        // Twenty seconds held without a word: beats only (no audio, no segments).
        seconds(hen, 20, beating = true)
        assertTrue(hen.live(), "still live: they are still pushing")
        hen.stop()
        bot.stop()
    }

    @Test
    fun whenTheBeatsStopLiveEndsWithinThreeSeconds() = runTest {
        val (_, hen, bot) = paired("testbot")
        theyStart(hen)
        seconds(hen, 3, beating = true)
        assertTrue(hen.live())
        seconds(hen, 2, beating = false)
        assertTrue(hen.live(), "two seconds without a beat: still live")
        seconds(hen, 2, beating = false)
        assertFalse(hen.live(), "gone by about three seconds after the last beat")
        hen.stop()
        bot.stop()
    }

    @Test
    fun aLostEndFrameIsCaughtByTheWatchdogAndAResumedTalkerComesBack() = runTest {
        val (_, hen, bot) = paired("testbot")
        theyStart(hen)
        seconds(hen, 2, beating = true)
        // They let go; the ENDED frame never arrives.
        seconds(hen, 4, beating = false)
        assertFalse(hen.live(), "cleared by the watchdog")
        assertTrue(hen.state.talk.talkers["testbot"] == null)
        // A beat of the same burst again (it was only the network): live again.
        seconds(hen, 1, beating = true)
        assertTrue(hen.live(), "back on the live row")
        // And its real end clears it at once, for good.
        hen.talk.onFrame(com.chencohen.ptt.relay.TalkStateFrame(com.chencohen.ptt.relay.TalkState(TalkEvent.ENDED, burstX, "testbot", 1, peer = "testbot", endReason = com.chencohen.ptt.relay.TalkEndReason.RELEASED)))
        runCurrent()
        assertFalse(hen.live())
        seconds(hen, 1, beating = true)
        assertFalse(hen.live(), "a late beat after the end doesn't bring it back")
        hen.stop()
        bot.stop()
    }

    @Test
    fun theirMicrophoneGoingQuietInTheRoomEndsLiveAtOnce() = runTest {
        val (_, hen, bot) = paired("testbot")
        theyStart(hen)
        seconds(hen, 1, beating = true)
        hen.talk.onStopped("testbot.1")
        runCurrent()
        assertFalse(hen.live(), "muted, unpublished or left the room: not live")
        hen.stop()
        bot.stop()
    }

    @Test
    fun anOlderTalkerWithoutBeatsIsLeftToItsFloorsEnd() = runTest {
        val (_, hen, bot) = paired("testbot")
        theyStart(hen)
        // No beat and no segment ever: the watchdog doesn't watch it (an older app heard only in the room).
        seconds(hen, 10, beating = false)
        assertTrue(hen.live())
        hen.stop()
        bot.stop()
    }

    @Test
    fun afterAReconnectAFloorWithNoRecentSignIsDropped() = runTest {
        val (_, hen, bot) = paired("testbot")
        theyStart(hen)
        seconds(hen, 1, beating = true)
        // /ws dropped for a while (the ENDED may have gone by), then came back.
        hen.clock += 5_000
        hen.talk.reconnected()
        runCurrent()
        assertFalse(hen.live(), "not kept as live from before the drop")
        hen.stop()
        bot.stop()
    }

    @Test
    fun beatsSealAndOpenOnlyWithTheTalkersKey() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = TalkBeat.seal(key, burstX, Random(3))
        assertEquals(burstX, TalkBeat.open(key, sealed))
        assertNull(TalkBeat.open(ByteArray(32) { 9 }, sealed))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(TalkBeat.open(key, sealed))
    }

    @Test
    fun aBufferedPushWhoseKeyIsLateStopsBeingLiveAndBecomesAMissedPushWhenTheKeyComes() = runTest {
        val (_, hen, bot) = paired("testbot")
        val id = "cd".repeat(16)
        bot.talk.bufferReader = object : Talk.BufferReader {
            override suspend fun read(blobId: String, talker: String, codec: String, onAudio: (ByteArray) -> Unit, onVideo: (TalkVideo.Frame) -> Unit) {
                packets(0, 25).forEach(onAudio)
            }
        }
        // hen's floor starts and ends; the server's buffered copy comes, but not the key's message.
        bot.talk.onFrame(com.chencohen.ptt.relay.TalkStateFrame(com.chencohen.ptt.relay.TalkState(TalkEvent.STARTED, id, "hen", 1, peer = "hen", startedAt = bot.clock)))
        runCurrent()
        bot.talk.onFrame(com.chencohen.ptt.relay.BufferedTalkFrame(com.chencohen.ptt.relay.BufferedTalk(blobId = "b1", talker = "hen.1", peer = "hen", burstId = id)))
        bot.talk.onFrame(com.chencohen.ptt.relay.TalkStateFrame(com.chencohen.ptt.relay.TalkState(TalkEvent.ENDED, id, "hen", 1, peer = "hen", endReason = com.chencohen.ptt.relay.TalkEndReason.RELEASED)))
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(bot.state.talk.playing, "not shown live while its key is missing")
        assertTrue(bot.state.talk.talkers.isEmpty())
        // Held in the encrypted store (not memory): an app restart doesn't lose it.
        assertNotNull(bot.messaging.store.lateKey(id))
        // The key's message comes late: a missed push, whole, to play.
        val key = ByteArray(com.chencohen.ptt.talk.ClipCrypto.KEY_BYTES) { 5 }
        bot.talk.receiveClip("hen", "hen", com.chencohen.ptt.messaging.ClipContent(id, key, com.chencohen.ptt.messaging.ClipContent.BURST, bot.clock), bot.clock, outgoingCopy = false)
        advanceUntilIdle()
        val m = bot.chat("hen").single { it.id == id }
        assertTrue(m.clip!!.kept, "kept from the held copy")
        assertEquals(500L, m.clip!!.durationMs)
        assertFalse(m.read)
        assertTrue(id in bot.host.notified, "a missed-push notification")
        assertNull(bot.messaging.store.lateKey(id), "gone from the store once it is the clip")
        hen.stop()
        bot.stop()
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
