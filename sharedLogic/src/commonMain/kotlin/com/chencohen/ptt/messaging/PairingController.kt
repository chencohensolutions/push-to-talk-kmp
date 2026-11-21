package com.chencohen.ptt.messaging

import com.chencohen.ptt.connection.ObservationHandle
import com.chencohen.ptt.crypto.InvalidKeysException
import com.chencohen.ptt.crypto.UntrustedIdentityException
import com.chencohen.ptt.relay.SendStatus
import com.chencohen.ptt.relay.SocketUnavailableException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** The Add contact screen's steps (docs/screens/add-contact.md). */
sealed interface PairingStep {
    /** This phone's code is shown; scanning theirs is one tap away. */
    data object Showing : PairingStep

    /** Their code was read: "Pair with [user]?" */
    data class Scanned(val user: String) : PairingStep

    /** The pair request is on its way to [user]'s phones. */
    data class Sending(val user: String) : PairingStep

    /** This phone confirmed; [user] hasn't yet. */
    data class Waiting(val user: String) : PairingStep

    data class Paired(val user: String) : PairingStep

    /** A pair request from [user] (they scanned this phone's code): "Pair with [user]?" */
    data class Review(val user: String) : PairingStep

    data class Failed(val error: PairingError, val user: String?) : PairingStep
}

enum class PairingError {
    /** What the camera read isn't an Add contact code. */
    NOT_A_CODE,

    /** A code from a newer version of the app. */
    NEWER_VERSION,

    /** This phone's own code, or another phone's of the same account. */
    OWN_CODE,

    /** A code from a phone of another server. */
    OTHER_SERVER,

    /** The code was used or is older than 10 minutes: they need to show a new one. */
    EXPIRED,

    /** The server gave other keys than the code's: don't pair; scan again in person. */
    KEYS_DIFFER,

    /** None of their phones has keys yet. */
    NO_DEVICES,

    BLOCKED,

    /** No such user on the server. */
    NOT_FOUND,

    /** Too many codes made at once. */
    RATE_LIMITED,

    /** This phone's keys aren't on the server yet (KeysState isn't Ready). */
    NOT_READY,

    /** The server can't be reached. */
    UNREACHABLE,
}

data class PairingState(
    /** This phone's code, the text of the QR code; null while it is made or when it couldn't be. */
    val code: String? = null,
    /** When [code] stops working, milliseconds since 1970. */
    val codeExpiresAt: Long? = null,
    val codeLoading: Boolean = false,
    /** Why there is no code. */
    val codeError: PairingError? = null,
    val step: PairingStep = PairingStep.Showing,
)

/**
 * Pairing by QR code (docs/design.md, "Pairing"): the Add contact screen shows this phone's code ([open]) and scans
 * theirs ([scanned], then [pair]); a pair request someone sent by scanning this phone's code is confirmed with
 * [review] and [accept]. The code is made again when it expires while the screen is open; [leave] when it closes.
 */
class PairingController internal constructor(
    private val messaging: Messaging,
    private val work: CoroutineDispatcher,
    private val now: () -> Long,
    main: CoroutineDispatcher = Dispatchers.Main,
    /** Make a new code when the shown one is about to expire (off in tests, whose clock doesn't move). */
    private val refreshCodes: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val _state = MutableStateFlow(PairingState())
    val state: StateFlow<PairingState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: PairingState get() = _state.value

    private var codeJob: Job? = null
    private var stepJob: Job? = null
    private var scannedCode: PairCode? = null

    init {
        // Waiting for them: the PAIRED envelope (or the contacts) says when they confirmed.
        scope.launch {
            messaging.state.collect { state ->
                val step = _state.value.step
                if (step is PairingStep.Waiting && state.contacts.any { it.user == step.user && it.paired }) {
                    setStep(PairingStep.Paired(step.user))
                }
            }
        }
    }

    /** The Add contact screen opened: shows this phone's code (a new one when there is none or it expired). */
    fun open() {
        if (_state.value.step !is PairingStep.Showing) setStep(PairingStep.Showing)
        if (codeJob?.isActive == true) return
        codeJob = scope.launch {
            while (true) {
                val expires = _state.value.codeExpiresAt
                if (_state.value.code == null || expires == null || expires - now() < 30_000) makeCode()
                if (!refreshCodes) return@launch
                val next = _state.value.codeExpiresAt ?: return@launch
                delay((next - now() - 30_000).coerceAtLeast(5_000))
            }
        }
    }

    /** The screen closed: stops making codes, and forgets a code that was read and not paired. */
    fun leave() {
        codeJob?.cancel()
        stepJob?.cancel()
        scannedCode = null
        setStep(PairingStep.Showing)
    }

    private suspend fun makeCode() {
        _state.update { it.copy(codeLoading = true, codeError = null) }
        try {
            val (code, expires) = withContext(work) { messaging.newPairCode() }
            _state.update { it.copy(code = code.format(), codeExpiresAt = expires, codeLoading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(code = null, codeExpiresAt = null, codeLoading = false, codeError = e.toPairingError()) }
        }
    }

    /** What the camera read. */
    fun scanned(text: String) {
        val code = when (val parsed = PairCode.parse(text)) {
            PairCodeParse.NotACode -> return setStep(PairingStep.Failed(PairingError.NOT_A_CODE, null))
            PairCodeParse.NewerVersion -> return setStep(PairingStep.Failed(PairingError.NEWER_VERSION, null))
            is PairCodeParse.Code -> parsed.code
        }
        val self = messaging.self()
        if (self != null && code.user == self.user) return setStep(PairingStep.Failed(PairingError.OWN_CODE, code.user))
        val server = messaging.serverPart()
        if (code.server != null && server != null && code.server != server) {
            return setStep(PairingStep.Failed(PairingError.OTHER_SERVER, code.user))
        }
        scannedCode = code
        setStep(PairingStep.Scanned(code.user))
    }

    /** Scanned → pairs with them: sends the pair request and confirms. */
    fun pair() {
        val code = scannedCode ?: return
        if (_state.value.step !is PairingStep.Scanned) return
        if (messaging.state.value.keys !is KeysState.Ready) return setStep(PairingStep.Failed(PairingError.NOT_READY, code.user))
        setStep(PairingStep.Sending(code.user))
        stepJob = scope.launch {
            val step = try {
                val paired = withContext(work) { messaging.pairWith(code) }
                scannedCode = null
                if (paired) PairingStep.Paired(code.user) else PairingStep.Waiting(code.user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairingStep.Failed(e.toPairingError(), code.user)
            }
            setStep(step)
        }
    }

    /** Opens [user]'s pair request. */
    fun review(user: String) {
        if (messaging.state.value.requests.none { it.user == user }) return
        setStep(PairingStep.Review(user))
    }

    /** Review → confirms the pair. */
    fun accept() {
        val user = (_state.value.step as? PairingStep.Review)?.user ?: return
        setStep(PairingStep.Sending(user))
        stepJob = scope.launch {
            val step = try {
                if (withContext(work) { messaging.accept(user) }) PairingStep.Paired(user) else PairingStep.Waiting(user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairingStep.Failed(e.toPairingError(), user)
            }
            setStep(step)
        }
    }

    /** Review → declines: the request is forgotten. */
    fun decline() {
        val user = (_state.value.step as? PairingStep.Review)?.user ?: return
        scope.launch { withContext(work) { messaging.decline(user) } }
        setStep(PairingStep.Showing)
    }

    /** Back to the code (from a result or an error). */
    fun back() {
        stepJob?.cancel()
        scannedCode = null
        setStep(PairingStep.Showing)
    }

    private fun setStep(step: PairingStep) {
        _state.update { it.copy(step = step) }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (PairingState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        leave()
        scope.cancel()
    }
}

internal fun Exception.toPairingError(): PairingError = when (this) {
    is UntrustedIdentityException, is InvalidKeysException -> PairingError.KEYS_DIFFER
    is NoDevicesException -> PairingError.NO_DEVICES
    is SendRefusedException -> when (status) {
        SendStatus.BLOCKED -> PairingError.BLOCKED
        SendStatus.NOT_PAIRED -> PairingError.EXPIRED
        SendStatus.UNKNOWN_USER -> PairingError.NOT_FOUND
        else -> PairingError.UNREACHABLE
    }
    is ApiException -> when (code) {
        ApiCodes.NOT_PAIRED, ApiCodes.NO_PAIRING -> PairingError.EXPIRED
        ApiCodes.BLOCKED -> PairingError.BLOCKED
        ApiCodes.UNKNOWN_USER -> PairingError.NOT_FOUND
        ApiCodes.RATE_LIMITED -> PairingError.RATE_LIMITED
        else -> PairingError.UNREACHABLE
    }
    is SocketUnavailableException -> PairingError.UNREACHABLE
    is IllegalStateException -> PairingError.NOT_READY
    else -> PairingError.UNREACHABLE
}
