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

    /** An invite link for another email address than this account's. */
    INVITE_FOR_SOMEONE_ELSE,

    /** What was typed as the friend's email isn't an email address. */
    INVALID_EMAIL,

    /** The server's owner turned invites off. */
    INVITES_OFF,
}

/** An Invite a friend link this phone made, to share. */
data class MadeInvite(
    /** The link, `ptt://invite?…`: what the QR code and the share sheet carry. */
    val link: String,
    /** The address it is for; null for whoever opens it. */
    val email: String?,
    /** When it stops working, milliseconds since 1970. */
    val expiresAt: Long,
    /** The user who made it, for the message around the link. */
    val inviter: String,
    /** The six digits that go beside the link (not in it): the friend types them. */
    val passcode: String,
    /**
     * The link with the passcode in it, for the QR code shown face to face: a new phone that scans it needs nothing
     * else. Never sent: the message carries [link] and the passcode apart.
     */
    val faceToFace: String,
) {
    /** The message the link and its passcode go out in, with [until] as the phone writes [expiresAt]'s date. */
    fun message(until: String): InviteMessage = InviteMessage(inviter, link, passcode, until)
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
    /** The invite link made last on this screen; null before one is made. */
    val invite: MadeInvite? = null,
    val inviteLoading: Boolean = false,
    /** Why the invite couldn't be made. */
    val inviteError: PairingError? = null,
)

/**
 * Pairing by QR code (docs/design.md, "Pairing"): the Add contact screen shows this phone's code ([open]) and scans
 * theirs ([scanned], then [pair]); a pair request someone sent by scanning this phone's code is confirmed with
 * [review] and [accept]. The code is made again when it expires while the screen is open, and once it was used
 * (a pair request came by it); [leave] when the screen closes.
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
    private var screenOpen = false
    private var stepJob: Job? = null
    private var scannedCode: PairCode? = null
    private var scannedInvite: InviteLink? = null

    init {
        // Waiting for them: the PAIRED envelope (or the contacts) says when they confirmed.
        scope.launch {
            var requests: Set<PairRequest>? = null
            messaging.state.collect { state ->
                // A new pair request by QR code used this phone's code (the server takes a code once): show a new one.
                val now = state.requests.filter { !it.viaLink }.toSet()
                if (requests != null && (now - requests!!).isNotEmpty()) dropCode()
                requests = now
                val step = _state.value.step
                if (step is PairingStep.Waiting && state.contacts.any { it.user == step.user && it.paired }) {
                    setStep(PairingStep.Paired(step.user))
                }
            }
        }
    }

    /** The Add contact screen opened: shows this phone's code (a new one when there is none or it expired). */
    fun open() {
        screenOpen = true
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

    /** Forgets the shown code, which was used: the screen, when open, makes a new one at once. */
    private fun dropCode() {
        codeJob?.cancel()
        codeJob = null
        _state.update { it.copy(code = null, codeExpiresAt = null) }
        if (screenOpen) open()
    }

    /** The screen closed: stops making codes, and forgets a code that was read and not paired. */
    fun leave() {
        screenOpen = false
        codeJob?.cancel()
        stepJob?.cancel()
        scannedCode = null
        scannedInvite = null
        setStep(PairingStep.Showing)
    }

    /** Makes an Invite a friend link, for [email] when it isn't blank (only that address can redeem it then). */
    fun makeInvite(email: String) {
        if (_state.value.inviteLoading) return
        _state.update { it.copy(inviteLoading = true, inviteError = null, invite = null) }
        scope.launch {
            try {
                val made = withContext(work) { messaging.newInvite(email.takeIf { it.isNotBlank() }) }
                val link = made.link
                _state.update {
                    it.copy(invite = MadeInvite(link.format(), link.email, made.expiresAt, link.inviter, made.passcode, link.withPasscode(made.passcode).format()), inviteLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(inviteLoading = false, inviteError = e.toPairingError()) }
            }
        }
    }

    /** Forgets the invite shown (the sheet closed); the link itself still works. */
    fun clearInvite() {
        _state.update { it.copy(invite = null, inviteError = null) }
    }

    /** Whether [link] is an invite from the server this phone is enrolled on (else it registers there with it). */
    fun isForThisServer(link: InviteLink): Boolean =
        messaging.self() != null && messaging.serverPart() == PairCode.serverOf(link.fingerprint)

    /**
     * An invite link opened on this enrolled phone: "Pair with [inviter]?", as after scanning their code. [pair] then
     * redeems it and pairs. Ignored when [text] isn't an invite link.
     */
    fun openInvite(text: String): Boolean {
        val link = InviteLink.find(text) ?: return false
        val self = messaging.self()
        if (self != null && link.inviter == self.user) {
            setStep(PairingStep.Failed(PairingError.OWN_CODE, link.inviter))
            return true
        }
        val server = messaging.serverPart()
        if (server != null && PairCode.serverOf(link.fingerprint) != server) {
            setStep(PairingStep.Failed(PairingError.OTHER_SERVER, link.inviter))
            return true
        }
        scannedCode = null
        scannedInvite = link
        setStep(PairingStep.Scanned(link.inviter))
        return true
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
        if (openInvite(text)) return
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
        scannedInvite = null
        scannedCode = code
        setStep(PairingStep.Scanned(code.user))
    }

    /** Scanned → pairs with them: sends the pair request and confirms (an invite link is redeemed first). */
    fun pair() {
        val invite = scannedInvite
        val code = scannedCode
        val user = invite?.inviter ?: code?.user ?: return
        if (_state.value.step !is PairingStep.Scanned) return
        if (messaging.state.value.keys !is KeysState.Ready) return setStep(PairingStep.Failed(PairingError.NOT_READY, user))
        setStep(PairingStep.Sending(user))
        stepJob = scope.launch {
            val step = try {
                val paired = withContext(work) {
                    if (invite != null) messaging.acceptInvite(invite) else messaging.pairWith(code!!, verified = true)
                }
                scannedCode = null
                scannedInvite = null
                if (paired) PairingStep.Paired(user) else PairingStep.Waiting(user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairingStep.Failed(e.toPairingError(), user)
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
        scannedInvite = null
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
    is InviteRefusedException -> when (reason) {
        "OWN_INVITE" -> PairingError.OWN_CODE
        "EMAIL_MISMATCH" -> PairingError.INVITE_FOR_SOMEONE_ELSE
        "RATE_LIMITED" -> PairingError.RATE_LIMITED
        else -> PairingError.EXPIRED
    }
    is ApiException -> when (code) {
        "INVALID_EMAIL" -> PairingError.INVALID_EMAIL
        "INVITES_OFF" -> PairingError.INVITES_OFF
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
