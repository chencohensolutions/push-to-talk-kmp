package com.chencohen.ptt.discovery

import com.chencohen.ptt.network.MtlsHttpClientFactory
import com.chencohen.ptt.server.ServerHttp
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.settings.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MapStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class KeptDiscoveryTest {
    private val fp = "ab".repeat(32)

    @Test
    fun theWorkingAddressGoesFirstThenTheServersThenTheOldOnes() {
        val first = KeptDiscovery.merge(
            null, "https://ptt.example.com:56400/query", fp, "ID", "cd".repeat(32),
            listOf("https://203.0.113.7:56400", "https://[2001:db8::7]:56400", "https://192.168.1.20:56400"),
        )
        assertEquals(
            listOf("https://ptt.example.com:56400", "https://203.0.113.7:56400", "https://[2001:db8::7]:56400", "https://192.168.1.20:56400"),
            first.addresses,
        )
        assertEquals("https://ptt.example.com:56400", first.enrolled)
        val second = KeptDiscovery.merge(first, "https://203.0.113.7:56400/query", fp.uppercase(), "ID", null, listOf("https://198.51.100.1:56400"))
        assertEquals(
            listOf("https://203.0.113.7:56400", "https://198.51.100.1:56400", "https://ptt.example.com:56400", "https://[2001:db8::7]:56400", "https://192.168.1.20:56400"),
            second.addresses,
        )
        assertEquals(fp, second.fingerprint)
        assertNull(second.key)
    }

    @Test
    fun atMostSixteenWithTheEnrolledAddressKept() {
        val many = (1..30).map { "https://10.0.0.$it:1" }
        val kept = KeptDiscovery.merge(null, "https://home.example:56400/query", fp, "ID", null, emptyList())
            .let { KeptDiscovery.merge(it, "https://10.0.1.1:1/query", fp, "ID", null, many) }
        assertEquals(KeptDiscovery.MAX_ADDRESSES, kept.addresses.size)
        assertEquals("https://10.0.1.1:1", kept.addresses.first())
        assertEquals("https://home.example:56400", kept.addresses.last())
        val found = kept.withFound(listOf("https://203.0.113.50:56400", "http://plain.example", "nonsense"))
        assertEquals("https://203.0.113.50:56400", found.addresses.first())
        assertTrue("https://home.example:56400" in found.addresses)
        assertEquals(KeptDiscovery.MAX_ADDRESSES, found.addresses.size)
    }

    @Test
    fun anotherKeyForgetsTheOldAddresses() {
        val old = KeptDiscovery.merge(null, "https://a.example:1/query", fp, "ID", null, listOf("https://b.example:2"))
        val new = KeptDiscovery.merge(old, "https://c.example:3/query", "ef".repeat(32), "ID2", null, emptyList())
        assertEquals(listOf("https://c.example:3"), new.addresses.take(1))
        assertTrue("https://b.example:2" !in new.addresses)
    }

    @Test
    fun originsOfAddresses() {
        assertEquals("https://[2001:db8::7]:56400", KeptDiscovery.originOf("https://[2001:db8::7]:56400/query"))
        assertEquals("https://example.com:443", KeptDiscovery.originOf("https://example.com"))
        assertNull(KeptDiscovery.originOf("http://example.com"))
    }
}

class ServerFinderTest {
    /** On real time: the mock server answers on its own threads, and virtual time would run out the probes' timeout. */
    private fun realTime(block: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

    private val fp = "ab".repeat(32)
    private val dhtKey = "cd".repeat(32)

    private fun settings(addresses: List<String>, key: String? = dhtKey) = PttSettings(MapStore()).apply {
        identityAlias = "hen"
        serverUrl = "https://old.example:56400/query"
        serverDiscovery = KeptDiscovery(fingerprint = fp, id = "ID", key = key, addresses = addresses, enrolled = "https://old.example:56400")
    }

    /** A server that answers only at [up] (hosts); the others fail as a dead address does. */
    private fun server(up: Set<String>, log: MutableList<String> = mutableListOf()) = MtlsHttpClientFactory { _, _ ->
        HttpClient(MockEngine { request ->
            log += "${request.url.host}${request.url.encodedPath}"
            if (request.url.host !in up) throw IOException("Connection refused")
            respond("ok:${request.url.host}", HttpStatusCode.OK)
        })
    }

    @Test
    fun aRequestThatFailsIsSentAgainWhereTheServerAnswers() = realTime {
        val settings = settings(listOf("https://old.example:56400", "https://203.0.113.9:56400", "https://192.168.1.20:56400"))
        val log = mutableListOf<String>()
        var dhtAsked = false
        val http = ServerHttp(
            settings, server(up = setOf("192.168.1.20"), log),
            ServerFinder(settings, dht = { _, _ -> dhtAsked = true; null }, shared = ServerFinder.Shared()),
        )
        val answer = http.client()!!.get(http.url("/media/abc")).bodyAsText()
        assertEquals("ok:192.168.1.20", answer)
        assertEquals("https://192.168.1.20:56400/query", settings.serverUrl)
        assertEquals("https://192.168.1.20:56400", settings.serverDiscovery!!.addresses.first())
        assertTrue("192.168.1.20/media/abc" in log)
        assertTrue(!dhtAsked)
        // The next request goes straight there.
        log.clear()
        assertEquals("ok:192.168.1.20", http.client()!!.get(http.url("/media/def")).bodyAsText())
        assertEquals(listOf("192.168.1.20/media/def"), log)
    }

    @Test
    fun theEnrolledAddressIsTriedBeforeThePublicOnes() = realTime {
        // The server lists its public addresses first; the phone enrolled at home, at the link's LAN server=.
        val settings = settings(listOf("https://203.0.113.9:56400", "https://198.51.100.1:56400", "https://192.168.1.20:56400")).apply {
            serverUrl = "https://203.0.113.9:56400/query"
            serverDiscovery = serverDiscovery!!.copy(enrolled = "https://192.168.1.20:56400")
        }
        val log = mutableListOf<String>()
        val http = ServerHttp(settings, server(up = setOf("192.168.1.20", "198.51.100.1"), log), ServerFinder(settings, dht = { _, _ -> null }, shared = ServerFinder.Shared()))
        assertEquals("ok:192.168.1.20", http.client()!!.get(http.url("/query")).bodyAsText())
        assertEquals(listOf("203.0.113.9/query", "192.168.1.20/query"), log.take(2))
    }

    @Test
    fun theDhtIsAskedWhenNoKeptAddressAnswers() = realTime {
        val settings = settings(listOf("https://old.example:56400", "https://203.0.113.9:56400"))
        var asked = 0
        val finder = ServerFinder(
            settings,
            dht = { key, fingerprint ->
                asked++
                assertEquals(dhtKey, key)
                assertEquals(fp, fingerprint)
                ServerRecord(listOf("https://198.51.100.20:56400"), 0)
            },
            shared = ServerFinder.Shared(),
        )
        val http = ServerHttp(settings, server(up = setOf("198.51.100.20")), finder)
        assertEquals("ok:198.51.100.20", http.client()!!.get(http.url("/query")).bodyAsText())
        assertEquals(1, asked)
        assertEquals("https://198.51.100.20:56400/query", settings.serverUrl)
        assertEquals("https://198.51.100.20:56400", settings.serverDiscovery!!.addresses.first())
    }

    @Test
    fun offlineTheDhtIsAskedAtMostEveryTwoMinutes() = realTime {
        val settings = settings(listOf("https://old.example:56400", "https://203.0.113.9:56400"))
        var asked = 0
        val finder = ServerFinder(settings, dht = { _, _ -> asked++; null }, addressesEvery = kotlin.time.Duration.ZERO, shared = ServerFinder.Shared())
        val http = ServerHttp(settings, server(up = emptySet()), finder)
        repeat(3) { assertFailsWith<IOException> { http.client()!!.get(http.url("/query")) } }
        assertEquals(1, asked)
        assertEquals("https://old.example:56400/query", settings.serverUrl)
    }

    @Test
    fun withoutAKeptKeyNothingIsTried() = realTime {
        val settings = PttSettings(MapStore()).apply {
            identityAlias = "hen"
            serverUrl = "https://old.example:56400/query"
        }
        val log = mutableListOf<String>()
        val http = ServerHttp(settings, server(up = setOf("203.0.113.9"), log), ServerFinder(settings, shared = ServerFinder.Shared()))
        assertFailsWith<IOException> { http.client()!!.get(http.url("/query")) }
        assertEquals(listOf("old.example/query"), log)
    }

    @Test
    fun theServerSaysWhoItIs() = realTime {
        val settings = PttSettings(MapStore()).apply {
            identityAlias = "hen"
            serverUrl = "https://ptt.example.com:56400/query"
        }
        val http = ServerHttp(settings, MtlsHttpClientFactory { _, _ -> HttpClient(MockEngine { respond("") }) })
        http.keepDiscovery(fp, "MFZWI3D-BONSGYC", dhtKey, listOf("https://203.0.113.7:56400"))
        val kept = settings.serverDiscovery!!
        assertEquals(fp, settings.serverFingerprint)
        assertEquals("MFZWI3D-BONSGYC", kept.id)
        assertEquals(listOf("https://ptt.example.com:56400", "https://203.0.113.7:56400"), kept.addresses)
        http.keepDiscovery("short", "x", null, emptyList())
        assertEquals(kept, settings.serverDiscovery)
    }
}

/** The order the finder tries addresses in, on virtual time: each probe starts its stagger after the one before. */
class ServerFinderOrderTest {
    private val fp = "ab".repeat(32)
    private val dhtKey = "cd".repeat(32)

    @Test
    fun theLastAddressThenTheServersListThenTheDht() = runTest {
        // Enrolled at home.example; serverInfo then listed the public, IPv6 and LAN addresses; the last that worked
        // (the one that just failed) is the public one.
        val settings = PttSettings(MapStore()).apply {
            identityAlias = "hen"
            serverUrl = "https://home.example:56400/query"
        }
        val http = ServerHttp(settings, MtlsHttpClientFactory { _, _ -> HttpClient(MockEngine { respond("") }) })
        http.keepDiscovery(fp, "ID", dhtKey, listOf("https://203.0.113.7:56400", "https://[2001:db8::7]:56400", "https://192.168.1.20:56400"))
        settings.serverUrl = "https://203.0.113.7:56400/query"
        settings.serverDiscovery = settings.serverDiscovery!!.working("https://203.0.113.7:56400")

        val probed = mutableListOf<String>()
        var dhtAskedAfter = -1
        val finder = ServerFinder(
            settings,
            dht = { key, fingerprint ->
                assertEquals(dhtKey, key)
                assertEquals(fp, fingerprint)
                dhtAskedAfter = probed.size
                ServerRecord(listOf("https://198.51.100.20:56400", "https://203.0.113.7:56400"), 0)
            },
            shared = ServerFinder.Shared(),
        )
        val found = finder.find("https://203.0.113.7:56400") { origin ->
            probed += origin
            origin == "https://198.51.100.20:56400"
        }

        assertEquals("https://198.51.100.20:56400", found)
        assertEquals(
            listOf(
                // The kept list without the address that failed: the enrolled one, then serverInfo's in its order.
                "https://home.example:56400", "https://[2001:db8::7]:56400", "https://192.168.1.20:56400",
                // Then the DHT's record, without the one that failed.
                "https://198.51.100.20:56400",
            ),
            probed,
        )
        assertEquals(3, dhtAskedAfter, "the DHT is asked only after every kept address failed")
        assertEquals("https://198.51.100.20:56400/query", settings.serverUrl)
        assertEquals("https://198.51.100.20:56400", settings.serverDiscovery!!.addresses.first())
    }

    @Test
    fun aKeptAddressThatAnswersMeansNoDhtLookup() = runTest {
        val settings = PttSettings(MapStore()).apply {
            identityAlias = "hen"
            serverUrl = "https://203.0.113.7:56400/query"
            serverDiscovery = KeptDiscovery(
                fingerprint = fp, id = "ID", key = dhtKey,
                addresses = listOf("https://203.0.113.7:56400", "https://192.168.1.20:56400"),
            )
        }
        var dhtAsked = false
        val finder = ServerFinder(settings, dht = { _, _ -> dhtAsked = true; null }, shared = ServerFinder.Shared())
        assertEquals("https://192.168.1.20:56400", finder.find("https://203.0.113.7:56400") { it == "https://192.168.1.20:56400" })
        assertTrue(!dhtAsked)
        assertEquals("https://192.168.1.20:56400/query", settings.serverUrl)
    }
}

class SettingsServerTest {
    @Test
    fun settingsShowsTheAddressTheIdAndTheDevice() {
        val settings = PttSettings(MapStore()).apply { serverUrl = "https://ptt.example.com:56400/query" }
        val connection = kotlinx.coroutines.flow.MutableStateFlow(
            com.chencohen.ptt.connection.ConnectionState(settings.serverUrl, "a", com.chencohen.ptt.connection.ConnectionStatus.NotConnected),
        )
        val controller = com.chencohen.ptt.settings.SettingsController(settings, connection, kotlinx.coroutines.Dispatchers.Unconfined)
        assertEquals("ptt.example.com:56400", controller.currentState.serverAddress)
        assertNull(controller.currentState.serverId)
        assertNull(controller.currentState.device)
        settings.serverUrl = "https://203.0.113.7:56400/query"
        settings.serverDiscovery = KeptDiscovery(fingerprint = "ab".repeat(32), id = "MFZWI3D-BONSGYC", addresses = listOf("https://203.0.113.7:56400"))
        settings.serverVersion = "2026.11.21"
        settings.device = com.chencohen.ptt.settings.EnrolledDevice("hen", 3, "Pixel 9 Pro")
        // A connection check that worked reads them again.
        connection.value = connection.value.copy(status = com.chencohen.ptt.connection.ConnectionStatus.Connected)
        assertEquals("203.0.113.7:56400", controller.currentState.serverAddress)
        assertEquals("MFZWI3D-BONSGYC", controller.currentState.serverId)
        assertEquals("2026.11.21", controller.currentState.serverVersion)
        assertEquals(3, controller.currentState.device!!.deviceNumber)
        assertEquals(1, controller.currentState.knownAddresses)
        assertTrue(controller.currentState.connected)
        controller.close()
    }
}
