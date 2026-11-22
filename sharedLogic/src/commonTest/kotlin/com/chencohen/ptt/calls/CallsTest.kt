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
        override fun leave() { leaves++ }
        override suspend fun setMicrophone(on: Boolean) { microphone = on }
        override suspend fun setCamera(on: Boolean) {}
        override fun switchCamera() {}
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

    private class Setup(val scope: TestScope, val self: String = "hen") {
        val transport = FakeTransport()
        val host = FakeHost()
        val sent = mutableListOf<Triple<String, CallContent, Int>>()
        val entries = mutableMapOf<String, TextMessage>()
        val tokenAsks = mutableListOf<Triple<String, String?, String?>>()
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
    fun theRecordingBadgeFollowsTheOthers() = runTest {
        val s = Setup(this)
        s.calls.receive("sharon", "sharon", invite(), s.at(), ownCopy = false)
        s.calls.accept()
        runCurrent()
        s.calls.receive("sharon", "sharon", CallContent(CallContent.RECORDING, "a".repeat(32), recording = true), s.at(), ownCopy = false)
        assertEquals(setOf("sharon"), s.calls.state.value!!.recordingBy)
        s.calls.setRecording(true)
        runCurrent()
        assertEquals(setOf("sharon", "hen"), s.calls.state.value!!.recordingBy)
        assertTrue(s.sent.last().second.recording)
    }
}
