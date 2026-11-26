package com.chencohen.ptt.relay

import com.chencohen.ptt.network.describe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One open `/ws`: binary messages in and out, and how it ended. */
internal interface FrameConnection {
    /** The next binary message; null once the connection is closed. */
    suspend fun receive(): ByteArray?

    suspend fun send(bytes: ByteArray)

    suspend fun close()

    /** The close code the server gave (4001, 4003…), once [receive] answered null; null when none came. */
    suspend fun closeCode(): Int?
}

/** Opens `/ws` at a `wss://` URL as this device. */
internal fun interface FrameConnector {
    suspend fun connect(url: String): FrameConnection
}

/**
 * `/ws` over Ktor's WebSockets on the app's mTLS client (OkHttp on Android, which speaks HTTP/1.1 for a WebSocket as
 * the server needs, presents the device certificate and pins the server's key). [client] is the current one of
 * ServerHttp; it must have the WebSockets plugin installed (AndroidMtlsHttpClientFactory does).
 */
internal class KtorFrameConnector(private val client: suspend () -> HttpClient?) : FrameConnector {
    override suspend fun connect(url: String): FrameConnection {
        val http = client() ?: throw IllegalStateException("This phone isn't registered")
        val session = http.webSocketSession(url)
        return object : FrameConnection {
            override suspend fun receive(): ByteArray? {
                while (true) {
                    val frame = session.incoming.receiveCatching().getOrNull() ?: return null
                    if (frame is WsFrame.Binary) return frame.readBytes()
                }
            }

            override suspend fun send(bytes: ByteArray) = session.send(WsFrame.Binary(true, bytes))

            override suspend fun close() = session.close(CloseReason(CloseReason.Codes.NORMAL, "bye"))

            override suspend fun closeCode(): Int? = try {
                withTimeout(2.seconds) { session.closeReason.await()?.code?.toInt() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Where `/ws` stands (Settings shows it). */
sealed interface SocketState {
    /** Not started, or stopped (the app went to the back). */
    data object Stopped : SocketState

    data object Connecting : SocketState

    /** Connected; the envelopes queued while away are still coming. */
    data object Syncing : SocketState

    /** Connected, the queue taken: what comes now is live. */
    data object Live : SocketState

    /** The connection failed or dropped; trying again after [retryIn]. */
    data class Waiting(val retryIn: Duration, val error: String?) : SocketState

    /** Another connection of this device took over (close code 4001); not tried again until started again. */
    data object Replaced : SocketState

    /** This device was revoked (close code 4003). */
    data object Revoked : SocketState
}

/** A Send the relay couldn't take: not connected, or no SendResult in time. */
class SocketUnavailableException(message: String) : Exception(message)

/**
 * The device's `/ws` (the server's docs/messaging.md, "/ws"), in the foreground only for now: [start] when the app comes
 * to the front, [stop] when it goes to the back. Each envelope goes to [onEnvelope] in order and is acknowledged once
 * it returns true (stored); one it fails on stays queued on the server and comes again on the next connection. A
 * dropped connection is opened again after [backoff] (the last repeating, with some jitter); [beforeConnect] runs
 * before each try (a GraphQL call, so the finder follows the server to a new address first). Code 4001 (this device
 * connected elsewhere) and 4003 (revoked) stop it.
 */
internal class RelaySocket(
    private val scope: CoroutineScope,
    private val connector: FrameConnector,
    private val url: () -> String?,
    private val onEnvelope: suspend (Envelope) -> Boolean,
    private val beforeConnect: suspend () -> Unit = {},
    private val onRevoked: () -> Unit = {},
    private val onLive: suspend () -> Unit = {},
    private val onPresence: (Presence) -> Unit = {},
    /** Talk's frames (TalkState, ClipSegment), live only: never acknowledged. */
    private val onTalk: suspend (FrameKind) -> Unit = {},
    private val backoff: List<Duration> = listOf(1.seconds, 2.seconds, 5.seconds, 10.seconds, 30.seconds),
    private val sendTimeout: Duration = 20.seconds,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow<SocketState>(SocketState.Stopped)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private var job: Job? = null
    private var connection: FrameConnection? = null
    private val sendMutex = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<SendResult>>()
    private var nextClientId = 1L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        val open = connection
        connection = null
        if (open != null) scope.launch(NonCancellable) { runCatching { open.close() } }
        failPending("The connection was closed")
        _state.value = SocketState.Stopped
    }

    /** Sends [send] (its client id is filled in) and waits for its SendResult. */
    suspend fun send(send: Send): SendResult {
        val open = connection ?: throw SocketUnavailableException("Not connected to the server")
        val clientId = "c" + nextClientId++
        val result = CompletableDeferred<SendResult>()
        pending[clientId] = result
        try {
            sendMutex.withLock { open.send(Frames.encode(Frame(SendFrame(send.copy(clientId = clientId))))) }
            return withTimeout(sendTimeout) { result.await() }
        } catch (e: CancellationException) {
            if (result.isCompleted) return result.getCompleted()
            throw if (e is kotlinx.coroutines.TimeoutCancellationException) SocketUnavailableException("The server didn't answer") else e
        } catch (e: SocketUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw SocketUnavailableException(e.describe())
        } finally {
            pending.remove(clientId)
        }
    }

    /**
     * Sends one frame that has no answer of its own (talk's TalkStart, ClipSegment, TalkEnd, TalkAck); false when not
     * connected or the connection broke.
     */
    suspend fun sendFrame(kind: FrameKind): Boolean {
        val open = connection ?: return false
        return try {
            sendMutex.withLock { open.send(Frames.encode(Frame(kind))) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun run() {
        var attempt = 0
        while (true) {
            _state.value = SocketState.Connecting
            var error: String? = null
            try {
                runCatching { beforeConnect() }.onFailure { if (it is CancellationException) throw it }
                val target = url() ?: throw IllegalStateException("No server address")
                val open = connector.connect(target)
                connection = open
                attempt = 0
                _state.value = SocketState.Syncing
                when (read(open)) {
                    CODE_REPLACED -> return finish(SocketState.Replaced)
                    CODE_REVOKED -> {
                        onRevoked()
                        return finish(SocketState.Revoked)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.describe()
            } finally {
                connection = null
                failPending("The connection was closed")
            }
            val wait = backoff[attempt.coerceAtMost(backoff.lastIndex)]
            attempt++
            val jittered = wait * (0.8 + 0.4 * random.nextDouble())
            _state.value = SocketState.Waiting(jittered, error)
            delay(jittered)
        }
    }

    private fun finish(state: SocketState) {
        _state.value = state
        job = null
    }

    /**
     * Reads frames until the connection closes; answers the close code. Envelopes (and the queue's end) are handled in
     * order by their own worker, so a slow one never holds up the rest (2026-11-26: while an incoming message was being
     * stored, the SendResults of this phone's own receipts and live drafts waited behind it, ~5 s on a phone, and they
     * went out in a burst).
     */
    private suspend fun read(open: FrameConnection): Int? = kotlinx.coroutines.coroutineScope {
        val inbox = kotlinx.coroutines.channels.Channel<FrameKind>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val worker = launch {
            for (kind in inbox) {
                when (kind) {
                    is EnvelopeFrame -> {
                        val stored = try {
                            onEnvelope(kind.envelope)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (stored) runCatching { sendMutex.withLock { open.send(Frames.encode(Frame(AckFrame(Ack(listOf(kind.envelope.id)))))) } }
                            .onFailure { if (it is CancellationException) throw it }
                    }
                    is QueueEmptyFrame -> {
                        _state.value = SocketState.Live
                        runCatching { onLive() }.onFailure { if (it is CancellationException) throw it }
                    }
                    else -> Unit
                }
            }
        }
        try {
            readFrames(open, inbox)
        } finally {
            inbox.close()
            worker.cancel()
        }
    }

    private suspend fun readFrames(open: FrameConnection, inbox: kotlinx.coroutines.channels.Channel<FrameKind>): Int? {
        while (true) {
            val bytes = open.receive() ?: return open.closeCode()
            when (val kind = Frames.decode(bytes)?.kind) {
                is EnvelopeFrame, is QueueEmptyFrame -> inbox.send(kind)
                is SendResultFrame -> pending[kind.sendResult.clientId]?.complete(kind.sendResult)
                is PingFrame -> sendMutex.withLock { open.send(Frames.encode(Frame(PongFrame(Pong(kind.ping.id))))) }
                is PresenceFrame -> onPresence(kind.presence)
                is TalkStateFrame, is ClipSegmentFrame, is BufferedTalkFrame -> try {
                    onTalk(kind)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                is PongFrame, is AckFrame, is SendFrame, is TalkStartFrame, is TalkEndFrame, is TalkAckFrame, is HearStateFrame, null -> Unit
            }
        }
    }

    private fun failPending(message: String) {
        pending.values.forEach { it.completeExceptionally(SocketUnavailableException(message)) }
        pending.clear()
    }

    companion object {
        /** The server closed this connection because the device connected again elsewhere. */
        const val CODE_REPLACED = 4001

        /** The device was revoked. */
        const val CODE_REVOKED = 4003
    }
}
