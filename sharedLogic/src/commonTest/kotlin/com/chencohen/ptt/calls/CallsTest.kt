package com.chencohen.ptt.calls

import com.chencohen.ptt.messaging.CallContent
import com.chencohen.ptt.messaging.CallOutcome
import com.chencohen.ptt.messaging.Content
import com.chencohen.ptt.messaging.LiveToken
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.relay.PushKind
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
class CallsTest {
    private class FakeTransport : CallTransport {
        val joins = mutableListOf<Pair<ByteArray, Boolean>>()
        var leaves = 0
        var microphone = true
        override var listener: CallTransportListener? = null
        override suspend fun join(url: String, token: String, key: ByteArray, video: Boolean): Boolean {
            joins += key to video
            return true
        }
        // As LiveKitCallTransport: leaving stops saving, and what was saved is dropped unless asked for first.
        override fun leave() { leaves++; recording = false }
        override suspend fun setMicrophone(on: Boolean) { microphone = on }
        override suspend fun setCamera(on: Boolean) {}
        override fun switchCamera() {}
        var recording = false
        var saves = 0
        override fun startRecording(): Boolean { recording = true; return true }
        override fun stopRecording(): String? {
            if (!recording) return null
            recording = false
            return "call-${++saves}.mp4"
        }
    }

    private class FakeHost : CallHost {
        val rang = mutableListOf<CallUi>()
        val ongoing = mutableListOf<CallUi>()
        val ended = mutableListOf<CallUi>()
        val missed = mutableListOf<String>()
        override fun ring(call: CallUi) { rang += call }
        override fun ongoing(call: CallUi) { ongoing += call }
        override fun ended(call: CallUi) { ended += call }
        override fun missed(chat: String, caller: String, video: Boolean) { missed += chat }
    }

    private class Setup(val scope: TestScope, val self: String = "hen", save: SaveCalls = SaveCalls.OFF, val serverKeeps: Boolean = false) {
        val transport = FakeTransport()
        val host = FakeHost()
        val sent = mutableListOf<Triple<String, CallContent, Int>>()
        val entries = mutableMapOf<String, TextMessage>()
        val tokenAsks = mutableListOf<Triple<String, String?, String?>>()
        val keeps = mutableListOf<Pair<String?, Boolean>>()
        val logged = mutableListOf<String>()
        var leftTalk = 0
        val calls = Calls(
            scope = scope.backgroundScope,
            me = { self },
            tokens = { call, peer, group ->
                tokenAsks += Triple(call, peer, group)
                LiveToken("t", "c-$call", "$self.1", "/rtc", "")
            },
            serverUrl = { "https://server:56400/query" },
            sendContent = { chat, content, kind -> sent += Triple(chat, content.call!!, kind) },
            putEntry = { entries[it.id] = it },
            entry = { entries[it] },
            onCall = { leftTalk++ },
            saveCalls = { save },
            keepOnServer = { _, peer, group, on -> keeps += (peer ?: "g:$group") to on },
            serverKeepsCalls = { serverKeeps },
            logEvent = { _, _, _, event -> logged += event },
            now = { scope.testScheduler.currentTime + 1_000_000 },
            random = Random(2),
        ).also {
            it.transport = transport
            it.host = host
        }

        fun at() = scope.testScheduler.currentTime + 1_000_000
    }

    private fun invite(id: String = "a".repeat(32), video: Boolean = true) =
        CallContent(CallContent.INVITE, id, ByteArray(32) { 3 }, video, 0)

    @Test
    fun aCallSendsTheInviteAndWaitsInTheRoom() = runTest {
        val s = Setup(this)
        assertTrue(s.calls.start("sharon", video = true))
        runCurrent()
        val (chat, invite, kind) = s.sent.single()
        assertEquals("sharon", chat)
        assertEquals(CallContent.INVITE, invite.kind)
        assertEquals(PushKind.CALL, kind)
        assertEquals(32, invite.id.length)
        assertEquals(32, invite.key.size)
        assertTrue(s.transport.joins.single().first.contentEquals(invite.key))
        assertEquals(Triple(invite.id, "sharon", null), s.tokenAsks.single())
        assertEquals(CallPhase.CALLING, s.calls.state.value!!.phase)
        assertEquals(1, s.leftTalk)
        // They join: the call is on.
        s.transport.listener!!.others(listOf("sharon.2"))
        runCurrent()
        assertEquals(CallPhase.ACTIVE, s.calls.state.value!!.phase)
        assertEquals(listOf("sharon"), s.calls.state.value!!.others)
        // A second call can't start meanwhile.
        assertFalse(s.calls.start("dana", video = false))
        // They leave: over, and kept in the chat.
        advanceTimeBy(5_000)
        s.transport.listener!!.others(emptyList())
        runCurrent()
        assertEquals(CallPhase.ENDED, s.calls.state.value!!.phase)
        val entry = s.entries[invite.id]!!
        assertEquals(CallOutcome.ANSWERED, entry.call!!.outcome)
        assertTrue(entry.outgoing)
        assertEquals(5_000L, entry.call!!.durationMs)
        assertEquals(CallContent.HANG_UP, s.sent.last().second.kind)
        advanceTimeBy(Calls.SHOW_ENDED + 1)
        assertNull(s.calls.state.value)
    }

    @Test
    fun anUnansweredCallEnds() = runTest {
        val s = Setup(this)
        s.calls.start("sharon", video = false)
        advanceTimeBy(Calls.RING_FOR + 1)
        runCurrent()
        assertEquals(CallOutcome.NO_ANSWER, s.calls.state.value!!.outcome)
        assertEquals(1, s.transport.leaves)
    }

    @Test
    fun aDeclineOrBusyEndsAOneToOneCall() = runTest {
        val s = Setup(this)
        s.calls.start("sharon", video = false)
        runCurrent()
        val id = s.sent.single().second.id
        s.calls.receive("sharon", "sharon", CallContent(CallContent.BUSY, id), s.at(), ownCopy = false)
        assertEquals(CallOutcome.BUSY, s.calls.state.value!!.outcome)
    }

    @Test
    fun anInviteRingsAndAnsweringJoinsWithItsKey() = runTest {
        val s = Setup(this)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        assertEquals(CallPhase.RINGING, s.host.rang.single().phase)
        s.calls.accept()
        runCurrent()
        assertEquals(CallContent.ACCEPT, s.sent.single().second.kind)
        assertTrue(s.transport.joins.single().first.contentEquals(ByteArray(32) { 3 }))
        assertEquals(CallPhase.CONNECTING, s.calls.state.value!!.phase)
        s.transport.listener!!.others(listOf("sharon.1"))
        runCurrent()
        assertEquals(CallPhase.ACTIVE, s.calls.state.value!!.phase)
        // They hang up.
        s.calls.receive("sharon", "sharon", CallContent(CallContent.HANG_UP, "a".repeat(32)), s.at(), ownCopy = false)
        assertEquals(CallOutcome.ANSWERED, s.calls.state.value!!.outcome)
        assertFalse(s.entries.values.single().outgoing)
    }

    @Test
    fun answeredOnAnotherPhoneStopsRingingHere() = runTest {
        val s = Setup(this)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.receive("sharon", "hen", CallContent(CallContent.ACCEPT, "a".repeat(32)), s.at(), ownCopy = true)
        assertEquals(CallOutcome.ELSEWHERE, s.calls.state.value!!.outcome)
        assertEquals(1, s.host.ended.size)
        assertTrue(s.host.missed.isEmpty())
    }

    @Test
    fun theCallerHangingUpWhileRingingIsAMissedCall() = runTest {
        val s = Setup(this)
        s.calls.receive("#g1", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.receive("#g1", "dana", CallContent(CallContent.HANG_UP, "a".repeat(32)), s.at(), ownCopy = false)
        assertEquals(CallPhase.RINGING, s.calls.state.value!!.phase)
        s.calls.receive("#g1", "sharon", CallContent(CallContent.HANG_UP, "a".repeat(32)), s.at(), ownCopy = false)
        assertEquals(CallOutcome.MISSED, s.calls.state.value!!.outcome)
        assertEquals(listOf("#g1"), s.host.missed)
        val entry = s.entries.values.single()
        assertFalse(entry.read)
        assertEquals("sharon", entry.author)
    }

    @Test
    fun aSecondInviteIsBusyAndAnOldOneDoesntRing() = runTest {
        val s = Setup(this)
        s.calls.start("dana", video = false)
        runCurrent()
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        runCurrent()
        assertEquals(CallContent.BUSY, s.sent.last().second.kind)
        assertEquals("sharon", s.sent.last().first)
        assertEquals(CallOutcome.MISSED, s.entries["a".repeat(32)]!!.call!!.outcome)

        val t = Setup(this)
        t.calls.receive("sharon", "sharon", invite("b".repeat(32)), t.at() - Calls.RING_IF_NEWER - 1, ownCopy = false)
        assertNull(t.calls.state.value)
        assertTrue(t.host.rang.isEmpty())
        assertEquals(listOf("sharon"), t.host.missed)
    }

    @Test
    fun ownInvitesAndBadKeysDontRing() = runTest {
        val s = Setup(this)
        s.calls.receive("sharon", "hen", invite(), s.at(), ownCopy = true)
        s.calls.receive("sharon", "sharon", CallContent(CallContent.INVITE, "c".repeat(32), ByteArray(4), false, 0), s.at(), ownCopy = false)
        assertNull(s.calls.state.value)
    }

    @Test
    fun saveCallsAlwaysSavesFromTheStartAndKeepsTheFile() = runTest {
        val s = Setup(this, save = SaveCalls.ALWAYS)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.accept()
        runCurrent()
        s.transport.listener!!.others(listOf("sharon.1"))
        runCurrent()
        assertTrue(s.calls.state.value!!.saving)
        assertTrue(s.transport.recording)
        assertTrue(s.sent.any { it.second.kind == CallContent.RECORDING && it.second.recording })
        s.calls.hangUp()
        assertFalse(s.transport.recording)
        assertEquals("call-1.mp4", s.entries.values.single().call!!.savedAs)
    }

    @Test
    fun saveCallsAskEachTimeAsksOnceTheCallIsOn() = runTest {
        val s = Setup(this, save = SaveCalls.ASK)
        s.calls.start("sharon", video = false)
        runCurrent()
        assertFalse(s.calls.state.value!!.askToSave)
        s.transport.listener!!.others(listOf("sharon.1"))
        runCurrent()
        assertTrue(s.calls.state.value!!.askToSave)
        assertFalse(s.transport.recording)
        s.calls.dismissAsk()
        assertFalse(s.calls.state.value!!.askToSave)
        // Saved later by hand, then stopped: the file is kept for the entry.
        s.calls.setRecording(true)
        s.calls.setRecording(false)
        s.transport.listener!!.others(emptyList())
        runCurrent()
        assertEquals("call-1.mp4", s.entries.values.single().call!!.savedAs)
    }

    @Test
    fun theRecordingBadgeFollowsTheOthers() = runTest {
        val s = Setup(this)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.accept()
        runCurrent()
        s.transport.listener!!.others(listOf("sharon.1"))
        runCurrent()
        s.calls.receive("sharon", "sharon", CallContent(CallContent.RECORDING, "a".repeat(32), recording = true), s.at(), ownCopy = false)
        assertEquals(setOf("sharon"), s.calls.state.value!!.recordingBy)
        s.calls.setRecording(true)
        runCurrent()
        assertEquals(setOf("sharon", "hen"), s.calls.state.value!!.recordingBy)
        assertTrue(s.sent.last().second.recording)
    }

    /** 2026-11-22 (hen): the server keeps a call only while someone records it: Save asks, Stop and the end stop. */
    @Test
    fun recordingAsksTheServerToKeepTheCallUntilItStops() = runTest {
        val s = Setup(this)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.accept()
        runCurrent()
        s.transport.listener!!.others(listOf("sharon.1"))
        runCurrent()
        assertEquals(emptyList(), s.keeps, "nothing kept before anyone records")
        s.calls.setRecording(true)
        runCurrent()
        assertEquals(listOf<Pair<String?, Boolean>>("sharon" to true), s.keeps)
        s.calls.setRecording(false)
        runCurrent()
        assertEquals("sharon" to false, s.keeps.last())
        // Hanging up while it records leaves the stop to the server (the recording device left the room).
        s.calls.setRecording(true)
        runCurrent()
        val asked = s.keeps.size
        s.calls.hangUp()
        runCurrent()
        assertEquals(asked, s.keeps.size)
    }

    @Test
    fun theServersRecordingShowsAndAnyoneInTheCallCanStopIt() = runTest {
        val s = Setup(this)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.accept()
        runCurrent()
        s.transport.listener!!.others(listOf("sharon.1"))
        s.transport.listener!!.serverRecording("sharon.1")
        runCurrent()
        assertEquals("sharon", s.calls.state.value!!.serverRecordingBy)
        s.calls.setRecording(false)
        runCurrent()
        assertEquals(listOf<Pair<String?, Boolean>>("sharon" to false), s.keeps, "Stop works for a recording sharon started")
        s.transport.listener!!.serverRecording(null)
        runCurrent()
        assertEquals(null, s.calls.state.value!!.serverRecordingBy)
    }

    @Test
    fun roomMetadataNamesTheRecordingDevice() {
        assertEquals("sharon.1", RoomMetadata.recorderOf("""{"recording":true,"by":"sharon.1","startedAt":"2026-11-22T16:00:00Z"}"""))
        assertEquals(null, RoomMetadata.recorderOf("""{"recording":false}"""))
        assertEquals(null, RoomMetadata.recorderOf(""))
        assertEquals(null, RoomMetadata.recorderOf("not json"))
    }

    @Test
    fun withAServerThatKeepsCallsSaveDoesntRecordOnThePhone() = runTest {
        val s = Setup(this, serverKeeps = true)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.accept()
        runCurrent()
        s.transport.listener!!.others(listOf("sharon.1"))
        runCurrent()
        s.calls.setRecording(true)
        runCurrent()
        assertTrue(s.calls.state.value!!.saving, "the badge shows")
        assertEquals(false, s.transport.recording, "nothing records or re-encodes on the phone")
        assertEquals(listOf<Pair<String?, Boolean>>("sharon" to true), s.keeps, "the server keeps it")
        s.calls.setRecording(false)
        runCurrent()
        assertEquals(0, s.transport.saves)
        assertEquals("sharon" to false, s.keeps.last())
    }

    @Test
    fun viewsGoToTheSenderAndIntoTheKeptCallsLog() = runTest {
        val s = Setup(this, serverKeeps = true)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.accept()
        runCurrent()
        s.transport.listener!!.others(listOf("sharon.1"))
        s.transport.listener!!.cameraFormat(90, 960, 720)
        runCurrent()
        assertEquals(0.75f, s.calls.state.value!!.ownAspect, "held upright: 720 wide for 960 high")
        s.calls.setView("sharon", fill = true, aspect = 0.5f)
        runCurrent()
        val view = s.sent.last().second
        assertEquals(CallContent.VIEW, view.kind)
        assertEquals("sharon", view.of)
        assertEquals(true, view.fill)
        assertEquals(emptyList(), s.logged, "nothing is logged before the server keeps the call")
        // Keeping begins: the log gets the camera and the view as they are now.
        s.transport.listener!!.serverRecording("hen.1")
        runCurrent()
        assertEquals(2, s.logged.size)
        val key = s.transport.joins.single().first
        val events = s.logged.map { CallEvent.open(key, kotlin.io.encoding.Base64.decode(it))!! }
        assertEquals(setOf(CallEvent.CAMERA, CallEvent.VIEW), events.map { it.t }.toSet())
        // Sharon's view of hen's video outlines hen's preview.
        s.calls.receive("sharon", "sharon", CallContent(CallContent.VIEW, s.calls.state.value!!.id, fill = true, aspect = 2f, of = "hen"), s.at(), ownCopy = false)
        runCurrent()
        assertEquals(CallView(true, 2f), s.calls.state.value!!.viewers["sharon"])
    }

    @Test
    fun whoLeftAGroupCallIsNoLongerShownSaving() = runTest {
        val s = Setup(this)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.accept()
        runCurrent()
        s.transport.listener!!.others(listOf("sharon.1", "dana.1"))
        runCurrent()
        s.calls.receive("sharon", "dana", CallContent(CallContent.RECORDING, "a".repeat(32), recording = true), s.at(), ownCopy = false)
        assertEquals(setOf("dana"), s.calls.state.value!!.recordingBy)
        s.transport.listener!!.others(listOf("sharon.1"))
        runCurrent()
        assertEquals(emptySet(), s.calls.state.value!!.recordingBy)
    }
}
