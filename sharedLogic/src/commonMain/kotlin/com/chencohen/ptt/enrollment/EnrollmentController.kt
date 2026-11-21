package com.chencohen.ptt.enrollment

import com.chencohen.ptt.connection.ConnectionStatus
import com.chencohen.ptt.connection.ObservationHandle
import com.apollographql.apollo.exception.ApolloNetworkException
import com.chencohen.ptt.discovery.DhtClient
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.discovery.ServerId
import com.chencohen.ptt.discovery.ServerRecord
import com.chencohen.ptt.discovery.answersAt
import com.chencohen.ptt.discovery.firstToAnswer
import kotlin.time.Duration.Companion.milliseconds
import com.chencohen.ptt.graphql.type.EnrollmentRejectedReason
import com.chencohen.ptt.network.MtlsHttpClientFactory
import com.chencohen.ptt.network.createApolloClient
import com.chencohen.ptt.network.describe
import com.chencohen.ptt.network.checkConnection
import com.chencohen.ptt.network.graphQlEndpoint
import com.chencohen.ptt.network.serverAddress
import com.chencohen.ptt.network.useClient
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.server.askServerCa
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Wizard screens, in order. */
sealed interface EnrollmentStep {
    data object Welcome : EnrollmentStep

    /** Server address and enrollment code. [checking] while the code is looked up. */
    data class EnterCode(val checking: Boolean = false, val error: EnrollmentError? = null) : EnrollmentStep

    /** The code is valid for [user]; [minutesLeft] until it expires. */
    data class Confirm(val user: String, val minutesLeft: Int) : EnrollmentStep

    data class Registering(val progress: RegistrationProgress) : EnrollmentStep

    /** [validUntil] is the certificate's expiry date (yyyy-mm-dd); [deviceNumber] the server's number for this device. */
    data class Enrolled(
        val user: String,
        val deviceName: String,
        val deviceNumber: Int,
        val serverAddress: String,
        val keyProtection: KeyProtection,
        val validUntil: String,
    ) : EnrollmentStep

    data class Failed(val error: EnrollmentError) : EnrollmentStep
}

enum class RegistrationProgress {
    CREATING_KEY,
    SENDING_REQUEST,
    SAVING_CERTIFICATE,
    TESTING_CONNECTION,
}

sealed interface EnrollmentError {
    data object MissingServerAddress : EnrollmentError
    data object MissingCode : EnrollmentError

    /** Wrong, expired or already used code. */
    data object InvalidCode : EnrollmentError
    data object RateLimited : EnrollmentError

    /** The server could not use the certificate request; a bug rather than a user error. */
    data object RequestRejected : EnrollmentError

    /** Could not talk to the server (address, network, TLS). */
    data class Unreachable(val message: String) : EnrollmentError

    /** The device's key store could not create the key or store the certificate. */
    data class KeyStoreFailed(val message: String) : EnrollmentError

    /** The certificate was issued but signing in with it did not work. */
    data class ConnectionTestFailed(val message: String) : EnrollmentError
}

data class EnrollmentState(
    val serverAddress: String,
    val code: String,
    val deviceName: String,
    val step: EnrollmentStep,
    /**
     * The server's id when the enrollment link said who it is ("T6DNBAM-IJR6WLU-…"): its key is checked against it
     * instead of the CA and the host name. Null otherwise.
     */
    val serverId: String? = null,
)

/** What a finished enrollment hands the app (ConnectionController.useEnrolledIdentity). */
internal class EnrolledIdentity(
    val serverUrl: String,
    val identityAlias: String,
    val discovery: KeptDiscovery?,
    val serverCa: String?,
    val device: EnrolledDevice,
)

/**
 * Shared logic of the device enrollment wizard: checks an enrollment code, creates a hardware-backed
 * key, trades a certificate request for a client certificate and hands the new identity to
 * [onEnrolled]. The server side is ptt-server's enrollment (journal-server-go's docs/authentication.md); the screens are
 * docs/screens/enrollment.md.
 */
@OptIn(ExperimentalTime::class)
class EnrollmentController internal constructor(
    private val settings: PttSettings,
    private val clientFactory: MtlsHttpClientFactory,
    private val keyStore: DeviceKeyStore,
    defaultDeviceName: String,
    /**
     * The new identity, at the GraphQL endpoint it enrolled at, with who the server is when the link said (else null),
     * the CA that signs its certificates when it told, and this device as the server knows it.
     */
    private val onEnrolled: (EnrolledIdentity) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * What an address typed without a link is trusted by, besides the system's roots. Empty: no CA is built into the
     * app (each server has its own); a link's key or a publicly trusted certificate is.
     */
    private val trustedCas: List<ByteArray> = emptyList(),
    private val clock: Clock = Clock.System,
    /** The DHT lookup, when none of the link's addresses answers. */
    private val dhtLookup: suspend (key: String, fingerprint: String) -> ServerRecord? = { key, fingerprint ->
        DhtClient().lookup(key, fingerprint)
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    /**
     * The link scanned or opened, when it said who the server is (its fingerprint): enrollment's connections trust only
     * its key ([MtlsHttpClientFactory.createPinned]). Nothing of it goes into the settings until the device is enrolled
     * ([onEnrolled]): a link opened on an enrolled phone, and left, changes nothing the phone keeps.
     */
    private var pinned: EnrollmentLink? = null

    private val _state = MutableStateFlow(
        EnrollmentState(
            serverAddress = serverAddress(settings.serverUrl),
            code = "",
            deviceName = defaultDeviceName,
            step = EnrollmentStep.Welcome,
        )
    )
    val state: StateFlow<EnrollmentState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: EnrollmentState get() = _state.value

    fun start() {
        setStep(EnrollmentStep.EnterCode())
    }

    /** A whole enrollment link pasted into the address (or code) field opens it, as [openPasted] does. */
    fun updateServerAddress(address: String) {
        findEnrollmentLink(address)?.let { return openLink(it) }
        _state.update { it.copy(serverAddress = address, step = clearedCodeError(it.step)) }
    }

    fun updateCode(code: String) {
        findEnrollmentLink(code)?.let { return openLink(it) }
        _state.update { it.copy(code = cleanCode(code), step = clearedCodeError(it.step)) }
    }

    fun updateDeviceName(name: String) {
        _state.update { it.copy(deviceName = name) }
    }

    /** EnterCode → Confirm: looks the code up without using it. */
    fun checkCode() {
        val current = _state.value
        if (current.step !is EnrollmentStep.EnterCode || current.step.checking) return
        when {
            current.serverAddress.isBlank() -> return setStep(EnrollmentStep.EnterCode(error = EnrollmentError.MissingServerAddress))
            current.code.isBlank() -> return setStep(EnrollmentStep.EnterCode(error = EnrollmentError.MissingCode))
        }
        setStep(EnrollmentStep.EnterCode(checking = true))
        job = scope.launch {
            val next = try {
                val (address, preview) = reachServer(current.serverAddress) { previewEnrollment(it, current.code) }
                if (address != current.serverAddress) _state.update { it.copy(serverAddress = address) }
                EnrollmentStep.Confirm(preview.user, minutesUntil(preview.expiresAt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EnrollmentStep.EnterCode(error = e.toEnrollmentError())
            }
            setStep(next)
        }
    }

    /** Confirm → Registering → Enrolled or Failed. */
    fun register() {
        val current = _state.value
        if (current.step !is EnrollmentStep.Confirm) return
        var endpoint = graphQlEndpoint(current.serverAddress)
        val alias = IDENTITY_ALIAS_PREFIX + clock.now().toEpochMilliseconds()
        val deviceName = current.deviceName.trim()

        job = scope.launch {
            var keyCreated = false
            var succeeded = false
            try {
                setStep(EnrollmentStep.Registering(RegistrationProgress.CREATING_KEY))
                val key = try {
                    withContext(workDispatcher) { keyStore.generate(alias) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@launch setStep(EnrollmentStep.Failed(EnrollmentError.KeyStoreFailed(e.describe())))
                }
                keyCreated = true

                setStep(EnrollmentStep.Registering(RegistrationProgress.SENDING_REQUEST))
                val issued = try {
                    val csr = withContext(workDispatcher) {
                        CertificateRequest.build(deviceName, key.publicKeyInfo, key::sign)
                    }
                    val (address, issued) = reachServer(current.serverAddress) {
                        enrollDevice(it, current.code, deviceName, csr, key.protection)
                    }
                    endpoint = graphQlEndpoint(address)
                    issued
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@launch setStep(EnrollmentStep.Failed(e.toEnrollmentError()))
                }

                setStep(EnrollmentStep.Registering(RegistrationProgress.SAVING_CERTIFICATE))
                try {
                    withContext(workDispatcher) { keyStore.saveCertificate(alias, issued.certificateDer) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@launch setStep(EnrollmentStep.Failed(EnrollmentError.KeyStoreFailed(e.describe())))
                }

                setStep(EnrollmentStep.Registering(RegistrationProgress.TESTING_CONNECTION))
                var serverCa: String? = null
                val test = try {
                    createApolloClient(enrollmentClient(alias), endpoint).useClient {
                        checkConnection(it).status.also { status ->
                            // Each server has its own CA: kept, so the server is trusted by it from now on.
                            if (status == ConnectionStatus.Connected) serverCa = issued.caCertificate ?: askServerCa(it)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    ConnectionStatus.Error(e.describe())
                }
                if (test != ConnectionStatus.Connected) {
                    return@launch setStep(EnrollmentStep.Failed(EnrollmentError.ConnectionTestFailed(test.describe())))
                }

                val previous = settings.identityAlias
                settings.certificateExpiresAt = runCatching { Instant.parse(issued.expiresAt).toEpochMilliseconds() }.getOrNull()
                onEnrolled(
                    EnrolledIdentity(
                        serverUrl = endpoint,
                        identityAlias = alias,
                        discovery = pinned?.let { discoveryOf(it, endpoint) },
                        serverCa = serverCa,
                        device = EnrolledDevice(issued.user, issued.deviceNumber, issued.deviceName),
                    )
                )
                succeeded = true
                if (previous != null && previous != alias && previous.startsWith(IDENTITY_ALIAS_PREFIX)) {
                    withContext(workDispatcher) { runCatching { keyStore.delete(previous) } }
                }
                setStep(
                    EnrollmentStep.Enrolled(
                        user = issued.user,
                        deviceName = issued.deviceName,
                        deviceNumber = issued.deviceNumber,
                        serverAddress = serverAddress(endpoint),
                        keyProtection = key.protection,
                        validUntil = issued.expiresAt.substringBefore('T'),
                    )
                )
            } finally {
                // The key is useless without the identity being in use; don't leave it behind.
                if (keyCreated && !succeeded) {
                    withContext(NonCancellable + workDispatcher) { runCatching { keyStore.delete(alias) } }
                }
            }
        }
    }

    /** Leaves the current step; cancels a lookup or registration in progress. */
    fun back() {
        job?.cancel()
        _state.update {
            when (it.step) {
                is EnrollmentStep.EnterCode -> it.copy(step = EnrollmentStep.Welcome).also { forgetPin() }
                is EnrollmentStep.Confirm, is EnrollmentStep.Registering -> it.copy(step = EnrollmentStep.EnterCode())
                is EnrollmentStep.Failed -> it.copy(code = "", step = EnrollmentStep.EnterCode())
                EnrollmentStep.Welcome, is EnrollmentStep.Enrolled -> it
            }
        }
    }

    /**
     * Fills in the code (and the server, when the link has one) from an enrollment link the server printed as a QR
     * code, and checks it: the wizard opens on Confirm, or on the code screen with what was wrong. Ignored while a
     * device is being registered.
     */
    fun openLink(link: EnrollmentLink) {
        if (_state.value.step is EnrollmentStep.Registering) return
        job?.cancel()
        forgetPin()
        pinned = link.takeIf { it.fingerprint != null }
        _state.update {
            it.copy(
                serverAddress = link.server ?: link.addresses.firstOrNull() ?: it.serverAddress,
                code = cleanCode(link.code),
                step = EnrollmentStep.EnterCode(),
                serverId = pinned?.fingerprint?.let(ServerId::format),
            )
        }
        checkCode()
    }

    /** What a QR code scan read: an enrollment link (see [openLink]), or else just the code. */
    fun openScanned(text: String) {
        parseEnrollmentLink(text)?.let(::openLink) ?: updateCode(text)
    }

    /**
     * What the Paste button read from the clipboard: an enrollment link anywhere in it (as a chat app sends it, with
     * words around it) opens it as a scan does, with the server's key pinned when the link has one; else a code in it
     * fills in the code. [PasteResult.NOTHING] when it has neither, and nothing changes.
     */
    fun openPasted(text: String): PasteResult {
        findEnrollmentLink(text)?.let {
            openLink(it)
            return PasteResult.LINK
        }
        if (_state.value.step !is EnrollmentStep.EnterCode) return PasteResult.NOTHING
        // A plain eight-letter word ("tomorrow") is not a code: one from the server has a digit or its hyphen.
        val code = PASTED_CODE.findAll(text).map { it.value }.firstOrNull { c -> c.any { it == '-' || it.isDigit() } }
            ?: return PasteResult.NOTHING
        updateCode(code)
        return PasteResult.CODE
    }

    /** Back to the start, e.g. to enroll again later. */
    fun reset() {
        job?.cancel()
        forgetPin()
        _state.update { it.copy(code = "", serverAddress = serverAddress(settings.serverUrl), step = EnrollmentStep.Welcome) }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (EnrollmentState) -> Unit): ObservationHandle {
        val observation = scope.launch { state.collect(onChange) }
        return ObservationHandle { observation.cancel() }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun <T> anonymousApollo(serverAddress: String, block: suspend (com.apollographql.apollo.ApolloClient) -> T): T {
        val endpoint = graphQlEndpoint(serverAddress)
        val httpClient = try {
            enrollmentClient(null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw EnrollmentTransportException(e.describe())
        }
        return createApolloClient(httpClient, endpoint).useClient { block(it) }
    }

    /**
     * Runs [block] on the anonymous client at [address], trusting only the link's key when it has one. When [address] doesn't
     * answer at all and the link said who the server is, the link's other addresses are tried (400 ms apart, the first
     * to answer wins), then the DHT under its key, and [block] runs where the server answered. Gives that address too.
     */
    private suspend fun <T> reachServer(address: String, block: suspend (com.apollographql.apollo.ApolloClient) -> T): Pair<String, T> {
        try {
            return address to anonymousApollo(address, block)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // No answer at all (Apollo's NoDataException around its network error), not a refusal or an HTTP error.
            val network = generateSequence<Throwable>(e) { it.cause?.takeIf { cause -> cause !== it } }.take(10).any { it is ApolloNetworkException }
            val link = pinned?.takeIf { network } ?: throw e
            val found = findElsewhere(link, KeptDiscovery.originOf(graphQlEndpoint(address))) ?: throw e
            return found to anonymousApollo(found, block)
        }
    }

    private suspend fun findElsewhere(link: EnrollmentLink, failed: String?): String? {
        val fingerprint = link.fingerprint ?: return null
        val http = try {
            clientFactory.createPinned(null, fingerprint)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        try {
            val probe: suspend (String) -> Boolean = { http.answersAt(it) }
            firstToAnswer(link.addresses.filter { it != failed }, 400.milliseconds, probe)?.let { return it }
            val key = link.key ?: return null
            val record = try {
                dhtLookup(key, fingerprint)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: return null
            val found = record.addresses.mapNotNull(KeptDiscovery::originOf).distinct().filter { it != failed }
            return firstToAnswer(found, 400.milliseconds, probe)
        } finally {
            http.close()
        }
    }

    /** A client as [identityAlias] (none: anonymous) that trusts the link's key only, or else [trustedCas] and what is kept. */
    private suspend fun enrollmentClient(identityAlias: String?): HttpClient =
        pinned?.fingerprint?.let { clientFactory.createPinned(identityAlias, it) } ?: clientFactory.create(identityAlias, trustedCas)

    /** Forgets the link's key, when the user leaves without enrolling. */
    private fun forgetPin() {
        pinned = null
        _state.update { it.copy(serverId = null) }
    }

    private fun discoveryOf(link: EnrollmentLink, endpoint: String): KeptDiscovery {
        val fingerprint = link.fingerprint!!
        return KeptDiscovery.merge(
            old = null,
            workingUrl = endpoint,
            fingerprint = fingerprint,
            id = ServerId.format(fingerprint).orEmpty(),
            key = link.key,
            addresses = link.addresses,
        )
    }

    private fun minutesUntil(timestamp: String): Int =
        runCatching { ((Instant.parse(timestamp) - clock.now()).inWholeSeconds + 59) / 60 }
            .getOrDefault(0L).toInt().coerceAtLeast(0)

    private fun setStep(step: EnrollmentStep) {
        _state.update { it.copy(step = step) }
    }

    private fun clearedCodeError(step: EnrollmentStep): EnrollmentStep =
        if (step is EnrollmentStep.EnterCode && step.error != null) step.copy(error = null) else step

    companion object {
        /** Aliases of keys created by enrollment, as opposed to identities imported by the user. */
        const val IDENTITY_ALIAS_PREFIX = "ptt-device-"
    }
}

private const val CODE_LENGTH = 8
private val PASTED_CODE = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9]{4}-?[A-Za-z0-9]{4}(?![A-Za-z0-9])""")

/**
 * Keeps what the user typed to letters, digits and dashes, at most [CODE_LENGTH] letters and digits.
 * Pasted text (e.g. a terminal line with "Code  K7QM-4XPD" or extra lines) is reduced to the first
 * code-shaped word in it. The server ignores case and dashes.
 */
/**
 * What an enrollment QR code holds (see `ptt-server enroll-code`). [server] is null when
 * it has none. Also who the server is and where: its key's [fingerprint] (64 hex digits; the app pins it from the
 * first connection, so an address the certificate doesn't list works), its DHT [key] and its [addresses].
 */
data class EnrollmentLink(
    val code: String,
    val server: String?,
    val fingerprint: String? = null,
    val key: String? = null,
    val addresses: List<String> = emptyList(),
)

/**
 * `ptt://enroll?code=K7QM-4XPD&server=https%3A%2F%2Fhost%3A56400&fingerprint=…&key=…&address=…&address=…`; null
 * for any other link. A fingerprint or key that isn't 64 hex digits is left out, and addresses that aren't https.
 */
fun parseEnrollmentLink(link: String): EnrollmentLink? {
    val url = try {
        Url(link.trim())
    } catch (e: Exception) {
        return null
    }
    if (url.protocol.name != LINK_SCHEME || url.host != "enroll") return null
    val code = url.parameters["code"]?.takeIf { it.isNotBlank() } ?: return null
    fun hex64(name: String) = url.parameters[name]?.trim()?.lowercase()?.takeIf { v -> v.length == 64 && v.all { it in "0123456789abcdef" } }
    return EnrollmentLink(
        code = code,
        server = url.parameters["server"]?.takeIf { it.isNotBlank() },
        fingerprint = hex64("fingerprint"),
        key = hex64("key"),
        addresses = url.parameters.getAll("address").orEmpty().mapNotNull(KeptDiscovery::originOf).distinct(),
    )
}

/** What [EnrollmentController.openPasted] found in the clipboard. */
enum class PasteResult { LINK, CODE, NOTHING }

/** The scheme of enrollment links, `ptt://enroll?…` (the app opens them, AndroidManifest.xml). */
const val LINK_SCHEME = "ptt"

private val LINK_IN_TEXT = Regex("""ptt://enroll\?[^\s<>"']+""")

/** The first `ptt://enroll?…` link in [text] that [parseEnrollmentLink] reads, without punctuation a sentence put after it. */
fun findEnrollmentLink(text: String): EnrollmentLink? =
    LINK_IN_TEXT.findAll(text).firstNotNullOfOrNull { parseEnrollmentLink(it.value.trimEnd('.', ',', ';', ')', ']', '!', '?')) }

internal fun cleanCode(input: String): String {
    val pasted = input.any(Char::isWhitespace) || input.count(Char::isLetterOrDigit) > CODE_LENGTH
    val candidate = if (pasted) PASTED_CODE.find(input)?.value ?: input else input
    var letters = 0
    return buildString {
        for (c in candidate.uppercase()) {
            when {
                c == '-' && letters in 1 until CODE_LENGTH -> append(c)
                c in 'A'..'Z' || c in '0'..'9' -> if (letters < CODE_LENGTH) append(c).also { letters++ }
            }
        }
    }
}

private fun Exception.toEnrollmentError(): EnrollmentError = when (this) {
    is EnrollmentRejectedException -> when (reason) {
        EnrollmentRejectedReason.INVALID_CODE -> EnrollmentError.InvalidCode
        EnrollmentRejectedReason.RATE_LIMITED -> EnrollmentError.RateLimited
        else -> EnrollmentError.RequestRejected
    }
    else -> EnrollmentError.Unreachable(describe())
}

private fun ConnectionStatus.describe(): String = when (this) {
    is ConnectionStatus.Failed -> "HTTP $httpCode"
    is ConnectionStatus.Error -> message
    else -> toString()
}
