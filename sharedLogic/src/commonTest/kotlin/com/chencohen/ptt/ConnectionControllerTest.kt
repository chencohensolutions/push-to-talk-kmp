package com.chencohen.ptt

import com.chencohen.ptt.connection.ConnectionController
import com.chencohen.ptt.connection.ConnectionStatus
import com.chencohen.ptt.connection.ServerHealth
import com.chencohen.ptt.discovery.ServerFinder
import com.chencohen.ptt.network.MtlsHttpClientFactory
import com.chencohen.ptt.network.checkConnection
import com.chencohen.ptt.network.createApolloClient
import com.chencohen.ptt.network.useClient
import com.chencohen.ptt.server.ServerHttp
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.settings.KeyValueStore
import com.chencohen.ptt.settings.PttSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val SERVER_URL = "https://10.0.2.2:56400/query"

private const val ME_JSON = """{"data":{"me":{"user":"hen","deviceNumber":3,"deviceName":"Pixel 9 Pro"}}}"""

private val SERVER_INFO_JSON =
    """{"data":{"serverInfo":{"version":"2026.11.21","caCertificate":null,"deviceNumber":3,""" +
        """"discovery":{"fingerprint":"${"ab".repeat(32)}","id":"MFZWI3D-BONSGYC","key":null,""" +
        """"addresses":["https://203.0.113.7:56400"]}}}}"""

private class InMemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private fun respondJson(json: String): MockRequestHandler = {
    respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}

/** Answers `me` and `serverInfo` as the server does. */
private val server: MockRequestHandler = { request ->
    val body = request.body.toByteArray().decodeToString()
    respondJson(if ("\"operationName\":\"ServerInfo\"" in body) SERVER_INFO_JSON else ME_JSON)(request)
}

private fun mockClient(handler: MockRequestHandler) = HttpClient(MockEngine(handler))

/** Waits for the in-flight connection attempt to finish (the mock engine may complete on another thread). */
private suspend fun ConnectionController.finalStatus(): ConnectionStatus =
    state.first { it.status !is ConnectionStatus.Connecting && it.status != ConnectionStatus.NotConnected }.status

class PttApiTest {
    private suspend fun fetch(handler: MockRequestHandler) =
        createApolloClient(mockClient(handler), SERVER_URL).useClient { checkConnection(it) }

    @Test
    fun answerMeansConnectedAndSaysWhoThisDeviceIs() = runTest {
        val result = fetch(respondJson(ME_JSON))
        assertEquals(ConnectionStatus.Connected, result.status)
        assertNull(result.serverError)
        assertEquals(EnrolledDevice("hen", 3, "Pixel 9 Pro"), result.device)
    }

    @Test
    fun sendsTheMeOperation() = runTest {
        var body = ""
        fetch { request ->
            body = request.body.toByteArray().decodeToString()
            respondJson(ME_JSON)(request)
        }
        assertContains(body, "\"operationName\":\"Me\"")
    }

    @Test
    fun graphQlErrorStillMeansConnected() = runTest {
        val result = fetch(respondJson("""{"errors":[{"message":"something broke"}],"data":null}"""))
        assertEquals(ConnectionStatus.Connected, result.status)
        assertEquals("something broke", result.serverError)
    }

    @Test
    fun rejectedCertificateIsHttpFailure() = runTest {
        val result = fetch { respond("unauthorized client certificate", HttpStatusCode.Unauthorized) }
        assertEquals(ConnectionStatus.Failed(401), result.status)
    }

    @Test
    fun transportErrorsAreReported() = runTest {
        val result = fetch { error("handshake failed") }
        assertEquals(ConnectionStatus.Error("handshake failed"), result.status)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {
    private val store = InMemoryStore()
    private val settings = PttSettings(store)
    private val requestedAliases = mutableListOf<String?>()

    private fun TestScope.controller(handler: MockRequestHandler = server, factory: MtlsHttpClientFactory? = null) = ConnectionController(
        settings = settings,
        http = ServerHttp(
            settings,
            factory ?: MtlsHttpClientFactory { alias, _ ->
                requestedAliases += alias
                mockClient(handler)
            },
            ServerFinder(settings, dht = { _, _ -> null }, shared = ServerFinder.Shared()),
        ),
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun startsNotEnrolled() = runTest {
        val state = controller().currentState
        assertEquals("", state.serverUrl)
        assertNull(state.identityAlias)
        assertEquals(ConnectionStatus.NotConnected, state.status)
        assertEquals(false, state.enrolled)
    }

    @Test
    fun connectBeforeEnrollingAsksForIt() = runTest {
        val controller = controller()
        controller.connect()
        assertEquals(ConnectionStatus.MissingServerUrl, controller.currentState.status)
        settings.serverUrl = SERVER_URL
        controller.connect()
        assertEquals(ConnectionStatus.MissingIdentity, controller.currentState.status)
        assertEquals(ServerHealth.REJECTED, controller.currentState.health)
    }

    @Test
    fun connectChecksTheServerAndKeepsWhatItSays() = runTest {
        val controller = controller()
        controller.useEnrolledIdentity(SERVER_URL, "ptt-device-1", device = EnrolledDevice("hen", 2, "Pixel"))
        controller.connect()
        assertEquals(ConnectionStatus.Connecting, controller.currentState.status)
        assertEquals(ConnectionStatus.Connected, controller.finalStatus())
        assertEquals(listOf<String?>("ptt-device-1"), requestedAliases)
        // `me` and serverInfo: the device's number and name as the server has them, who the server is and where.
        assertEquals(EnrolledDevice("hen", 3, "Pixel 9 Pro"), controller.currentState.device)
        assertEquals("2026.11.21", settings.serverVersion)
        val kept = settings.serverDiscovery!!
        assertEquals("MFZWI3D-BONSGYC", kept.id)
        assertEquals(listOf("https://10.0.2.2:56400", "https://203.0.113.7:56400"), kept.addresses)
    }

    @Test
    fun rejectedDeviceNeedsRegisteringAgain() = runTest {
        var status = HttpStatusCode.OK
        val controller = controller(handler = { request ->
            if (status == HttpStatusCode.OK) server(request) else respond("", status)
        })
        controller.useEnrolledIdentity(SERVER_URL, "ptt-device-1")
        controller.connect()
        assertEquals(ConnectionStatus.Connected, controller.finalStatus())

        status = HttpStatusCode.Unauthorized
        controller.connect()
        assertEquals(ConnectionStatus.Failed(401), controller.finalStatus())
        assertEquals(ServerHealth.REJECTED, controller.currentState.health)
    }

    @Test
    fun offlineItChecksAgainUntilTheServerAnswers() = runTest {
        var up = false
        var asked = 0
        val controller = controller(handler = { request ->
            asked++
            if (!up) throw kotlinx.io.IOException("Network is unreachable")
            server(request)
        })
        controller.useEnrolledIdentity(SERVER_URL, "ptt-device-1")
        controller.connect()
        controller.state.first { it.health == ServerHealth.OFFLINE }
        val askedOffline = asked
        val offlineAt = testScheduler.currentTime
        up = true
        controller.state.first { it.status == ConnectionStatus.Connected }
        assertEquals(true, asked > askedOffline)
        assertEquals(true, testScheduler.currentTime - offlineAt >= 5_000, "checked again after 5 s")
        controller.close()
    }

    @Test
    fun clientCreationFailureIsReported() = runTest {
        val controller = controller(factory = MtlsHttpClientFactory { _, _ -> error("Could not access the device key") })
        controller.useEnrolledIdentity(SERVER_URL, "ptt-device-1")
        controller.connect()
        assertEquals(ConnectionStatus.Error("Could not access the device key"), controller.finalStatus())
        controller.stop()
    }
}
