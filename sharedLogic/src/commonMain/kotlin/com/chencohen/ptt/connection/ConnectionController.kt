package com.chencohen.ptt.connection

import com.chencohen.ptt.util.logFailures
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.network.ConnectionResult
import com.chencohen.ptt.network.checkConnection
import com.chencohen.ptt.network.describe
import com.chencohen.ptt.server.ServerHttp
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.settings.PttSettings
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface ConnectionStatus {
    data object NotConnected : ConnectionStatus
    data object MissingServerUrl : ConnectionStatus
    data object MissingIdentity : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Failed(val httpCode: Int) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

/** What the home screens show about the server: nothing, an "Offline" pill, or a register-again card. */
enum class ServerHealth {
    /** Connected, or still checking: show nothing. */
    OK,

    /** Network error, timeout or server error: show the quiet "Offline" pill. */
    OFFLINE,

    /** The server refused this device's certificate (401), or the device has no identity: needs registering again. */
    REJECTED,
}

data class ConnectionState(
    val serverUrl: String,
    val identityAlias: String?,
    val status: ConnectionStatus,
    /** This device as the server knows it; null before enrolling. */
    val device: EnrolledDevice? = null,
    /** A GraphQL error the server answered the connection check with, while the connection itself worked. */
    val serverError: String? = null,
) {
    val health: ServerHealth = when (status) {
        ConnectionStatus.NotConnected, ConnectionStatus.Connecting, ConnectionStatus.Connected -> ServerHealth.OK
        is ConnectionStatus.Failed -> if (status.httpCode == 401 || status.httpCode == 403) ServerHealth.REJECTED else ServerHealth.OFFLINE
        is ConnectionStatus.Error -> ServerHealth.OFFLINE
        ConnectionStatus.MissingServerUrl, ConnectionStatus.MissingIdentity -> ServerHealth.REJECTED
    }

    val enrolled: Boolean get() = identityAlias != null
}

/** Handle returned by [ConnectionController.observe]; call [cancel] to stop receiving updates. */
fun interface ObservationHandle {
    fun cancel()
}

/**
 * The connection to the server (journal's `JournalController`): holds the server URL and this device's identity, and
 * checks the connection over mTLS with `me`. After each check that worked it asks `serverInfo` and keeps who the
 * server is and where (ServerHttp.refreshServerInfo), so the finder can find it when its address changes. While
 * offline it checks again after [retryDelays] (the last one repeating), and the finder behind [http] looks for the
 * server at its other addresses and in the DHT. Platform UIs render [state] and call [connect] (on start and each
 * time the app comes to the front) and [stop] (when it goes to the back).
 */
class ConnectionController internal constructor(
    private val settings: PttSettings,
    private val http: ServerHttp,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val retryDelays: List<Duration> = listOf(5.seconds, 15.seconds, 30.seconds, 60.seconds),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + logFailures)
    private var connectJob: Job? = null

    private val _state = MutableStateFlow(
        ConnectionState(
            serverUrl = settings.serverUrl,
            identityAlias = settings.identityAlias,
            status = ConnectionStatus.NotConnected,
            device = settings.device,
        )
    )
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: ConnectionState get() = _state.value

    /**
     * Switches to an identity created by enrollment; called by EnrollmentController. [discovery]: who the server is,
     * when the enrollment link said (its key is pinned from then on); null forgets what was kept of a server before.
     */
    internal fun useEnrolledIdentity(
        serverUrl: String,
        alias: String,
        discovery: KeptDiscovery? = null,
        serverCa: String? = null,
        device: EnrolledDevice? = null,
    ) {
        connectJob?.cancel()
        settings.serverUrl = serverUrl
        settings.identityAlias = alias
        // Perhaps another server: what was kept of the old one (its pinned key, its addresses, its CA) doesn't apply.
        settings.serverDiscovery = discovery
        settings.serverCa = serverCa
        settings.serverVersion = null
        settings.device = device
        _state.value = ConnectionState(serverUrl = serverUrl, identityAlias = alias, status = ConnectionStatus.NotConnected, device = device)
    }

    /** Switches to a renewed identity (CertificateRenewal): the same server, a new certificate; no new connection check. */
    internal fun useRenewedIdentity(alias: String) {
        settings.identityAlias = alias
        _state.update { it.copy(identityAlias = alias) }
    }

    /** Checks the connection now, and again while offline until it works or [stop] is called. */
    fun connect() {
        connectJob?.cancel()
        if (settings.serverUrl.isEmpty()) return setStatus(ConnectionStatus.MissingServerUrl)
        if (settings.identityAlias == null) return setStatus(ConnectionStatus.MissingIdentity)
        // The pill shows nothing while checking: an offline phone keeps its Offline pill until it is back.
        if (_state.value.health == ServerHealth.OK) setStatus(ConnectionStatus.Connecting)
        connectJob = scope.launch {
            var attempt = 0
            while (true) {
                val result = check()
                // The server may have been found at another address (ServerFinder).
                _state.update {
                    it.copy(
                        serverUrl = settings.serverUrl,
                        status = result.status,
                        serverError = result.serverError,
                        device = settings.device,
                    )
                }
                if (ConnectionState(settings.serverUrl, settings.identityAlias, result.status).health != ServerHealth.OFFLINE) break
                delay(retryDelays[attempt.coerceAtMost(retryDelays.lastIndex)])
                attempt++
            }
        }
    }

    /** Stops checking again while offline (the app went to the back). */
    fun stop() {
        connectJob?.cancel()
    }

    private suspend fun check(): ConnectionResult {
        val result = try {
            http.graphql { checkConnection(it) } ?: ConnectionResult(ConnectionStatus.MissingIdentity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionResult(ConnectionStatus.Error(e.describe()))
        }
        if (result.status == ConnectionStatus.Connected) {
            result.device?.let { settings.device = it }
            http.refreshServerInfo()
        }
        return result
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (ConnectionState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        scope.cancel()
    }

    private fun setStatus(status: ConnectionStatus) {
        _state.update { it.copy(status = status) }
    }
}
