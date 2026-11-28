package com.chencohen.ptt.settings

import com.chencohen.ptt.util.logFailures
import com.chencohen.ptt.connection.ConnectionState
import com.chencohen.ptt.connection.ObservationHandle
import com.chencohen.ptt.connection.ServerHealth
import com.chencohen.ptt.network.serverAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What Settings shows (docs/screens/settings.md). */
data class SettingsState(
    /** Where the app reaches the server now, "host:port"; empty before enrolling. */
    val serverAddress: String,
    /** The server's id ("T6DNBAM-IJR6WLU-…") once known; null before. */
    val serverId: String?,
    /** The server's version once it answered; null before. */
    val serverVersion: String?,
    /** This device as the server knows it; null before enrolling. */
    val device: EnrolledDevice?,
    /** How many addresses the app keeps to find the server again (the enrolled one, `serverInfo`'s, the DHT's). */
    val knownAddresses: Int,
    /** Whether the server answers now: Settings says Connected, Offline, or Not registered. */
    val health: ServerHealth,
    val connected: Boolean,
)

/**
 * Settings: the server (address, id, version), this device (its name and number), and Register again. It reads what
 * [PttSettings] keeps, again each time the connection's state changes (a check that worked keeps `serverInfo`, the
 * finder may have moved the address).
 */
class SettingsController internal constructor(
    private val settings: PttSettings,
    private val connection: StateFlow<ConnectionState>,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + logFailures)
    private val _state = MutableStateFlow(read(connection.value))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: SettingsState get() = _state.value

    init {
        scope.launch { connection.collect { _state.value = read(it) } }
    }

    /** Reads the settings again (Settings opened). */
    fun refresh() {
        _state.value = read(connection.value)
    }

    private fun read(connection: ConnectionState): SettingsState {
        val discovery = settings.serverDiscovery
        return SettingsState(
            serverAddress = settings.serverUrl.takeIf { it.isNotEmpty() }?.let { serverAddress(it).removePrefix("https://") }.orEmpty(),
            serverId = discovery?.id?.takeIf { it.isNotEmpty() },
            serverVersion = settings.serverVersion,
            device = settings.device,
            knownAddresses = discovery?.addresses?.size ?: 0,
            health = connection.health,
            connected = connection.status == com.chencohen.ptt.connection.ConnectionStatus.Connected,
        )
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (SettingsState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()
}
