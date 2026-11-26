package com.chencohen.ptt.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** A connection the test drives: what the "server" sends, what the phone sent, how it ends. */
private class Scripted : FrameConnection {
    val toPhone = Channel<ByteArray>(Channel.UNLIMITED)
    val fromPhone = mutableListOf<Frame>()
    var code: Int? = null

    fun envelope(id: String) = toPhone.trySend(Frames.encode(Frame(EnvelopeFrame(Envelope(id = id, type = EnvelopeType.PAIRED, senderUser = "dana")))))
    fun queueEmpty() = toPhone.trySend(Frames.encode(Frame(QueueEmptyFrame(QueueEmpty()))))
    fun end(code: Int?) {
        this.code = code
        toPhone.close()
    }

    override suspend fun receive(): ByteArray? = toPhone.receiveCatching().getOrNull()
    override suspend fun send(bytes: ByteArray) {
        fromPhone += Frames.decode(bytes)!!
    }
    override suspend fun close() {
        toPhone.close()
    }
    override suspend fun closeCode(): Int? = code
}

private class Harness(test: TestScope, val stored: suspend (Envelope) -> Boolean = { true }) {
    val scope = CoroutineScope(test.coroutineContext + SupervisorJob())
    val connections = mutableListOf<Scripted>()
    var revoked = 0
    var fail = false
    val socket = RelaySocket(
        scope = scope,
        connector = {
            if (fail) throw IllegalStateException("no route")
            Scripted().also { connections += it }
        },
        url = { "wss://203.0.113.7:56400/ws" },
        onEnvelope = { stored(it) },
        onRevoked = { revoked++ },
        random = Random(1),
    )
}

class RelaySocketTest {
    @Test
    fun aSlowEnvelopeDoesntHoldUpTheAnswerToMySend() = runTest {
        // An incoming message still being stored (2026-11-26: ~5 s on a phone) while this phone sends a receipt.
        val storing = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h = Harness(this) { storing.await(); true }
        h.socket.start()
        runCurrent()
        val c = h.connections.single()
        c.queueEmpty()
        runCurrent()
        c.envelope("slow")
        runCurrent()
        val sent = async { h.socket.send(Send(envelopes = emptyList())) }
        runCurrent()
        val clientId = assertIs<SendFrame>(c.fromPhone.last().kind).send.clientId
        c.toPhone.trySend(Frames.encode(Frame(SendResultFrame(SendResult(clientId = clientId, status = SendStatus.ACCEPTED)))))
        runCurrent()
        assertEquals(true, sent.isCompleted, "answered while the envelope is still being stored")
        assertEquals(SendStatus.ACCEPTED, sent.await().status)
        storing.complete(Unit)
        runCurrent()
        assertEquals(listOf("slow"), c.fromPhone.mapNotNull { (it.kind as? AckFrame)?.ack?.ids }.flatten(), "acknowledged once stored")
        h.socket.stop()
        h.scope.cancel()
    }

    @Test
    fun acknowledgesWhatWasStoredAndGoesLiveAfterTheQueue() = runTest {
        val h = Harness(this) { it.id != "bad" }
        h.socket.start()
        runCurrent()
        assertEquals(SocketState.Syncing, h.socket.state.value)
        val c = h.connections.single()
        c.envelope("a")
        c.envelope("bad")
        c.queueEmpty()
        runCurrent()
        assertEquals(SocketState.Live, h.socket.state.value)
        assertEquals(listOf(listOf("a")), c.fromPhone.map { assertIs<AckFrame>(it.kind).ack.ids }, "the one not stored stays queued")
        h.socket.stop()
        h.scope.cancel()
    }

    @Test
    fun connectsAgainWithBackoff() = runTest {
        val h = Harness(this)
        h.fail = true
        h.socket.start()
        runCurrent()
        assertIs<SocketState.Waiting>(h.socket.state.value)
        advanceTimeBy(1.5.seconds)
        runCurrent()
        assertIs<SocketState.Waiting>(h.socket.state.value)
        h.fail = false
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(SocketState.Syncing, h.socket.state.value)
        // The server drops it: tried again from the first delay.
        h.connections.single().end(null)
        runCurrent()
        assertEquals(1.seconds.inWholeMilliseconds, (h.socket.state.value as SocketState.Waiting).retryIn.inWholeMilliseconds, 200)
        advanceTimeBy(1.5.seconds)
        runCurrent()
        assertEquals(2, h.connections.size)
        h.socket.stop()
        h.scope.cancel()
    }

    @Test
    fun stopsWhenReplacedOrRevoked() = runTest {
        val h = Harness(this)
        h.socket.start()
        runCurrent()
        h.connections.single().end(RelaySocket.CODE_REPLACED)
        advanceUntilIdle()
        assertEquals(SocketState.Replaced, h.socket.state.value)
        assertEquals(1, h.connections.size)

        h.socket.start()
        runCurrent()
        h.connections.last().end(RelaySocket.CODE_REVOKED)
        advanceUntilIdle()
        assertEquals(SocketState.Revoked, h.socket.state.value)
        assertEquals(1, h.revoked)
        h.scope.cancel()
    }

    @Test
    fun aSendWaitsForItsResult() = runTest {
        val h = Harness(this)
        h.socket.start()
        runCurrent()
        val c = h.connections.single()
        val result = h.scope.async { h.socket.send(Send(envelopes = listOf(Envelope(recipientUser = "hen", recipientDevice = 1)))) }
        runCurrent()
        val sent = assertIs<SendFrame>(c.fromPhone.single().kind).send
        c.toPhone.trySend(Frames.encode(Frame(SendResultFrame(SendResult(clientId = sent.clientId, status = SendStatus.ACCEPTED, envelopeIds = listOf("x"))))))
        runCurrent()
        assertEquals(listOf("x"), result.await().envelopeIds)
        h.socket.stop()
        h.scope.cancel()
    }
}

private fun assertEquals(expected: Long, actual: Long, tolerance: Long) {
    kotlin.test.assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "$actual is not within $tolerance of $expected")
}
