package com.chencohen.ptt.server

import com.apollographql.apollo.ApolloClient
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.discovery.ServerFinder
import com.chencohen.ptt.discovery.findServerOnNetworkErrors
import com.chencohen.ptt.graphql.ServerCaQuery
import com.chencohen.ptt.graphql.ServerInfoQuery
import com.chencohen.ptt.network.MtlsHttpClientFactory
import com.chencohen.ptt.network.createApolloClient
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.tls.decodePemCertificates
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The mTLS client for the server, made once per device identity, server address, pinned key and kept CA, and kept:
 * everything goes over it, so the phone holds one HTTP/2 connection to the server (one handshake, with a signature in
 * its secure hardware) instead of opening one per call. When the server stops answering, [finder] looks for it at its
 * other addresses and in the DHT (docs/design.md, "Finding the server").
 */
internal class ServerHttp(
    private val settings: PttSettings,
    private val clientFactory: MtlsHttpClientFactory,
    private val finder: ServerFinder = ServerFinder(settings),
) {
    private class Held(val alias: String, val url: String, val trust: List<String?>, val http: HttpClient, val apollo: ApolloClient)

    private val mutex = Mutex()
    private var held: Held? = null

    /**
     * The client replaced last: closed only when the next one replaces it, since requests may still be running on it
     * (the request that found the server at a new address is retried on the client it started on).
     */
    private var retired: Held? = null

    private suspend fun held(): Held? = mutex.withLock {
        val alias = settings.identityAlias ?: return null
        val url = settings.serverUrl.takeIf { it.isNotEmpty() } ?: return null
        // What the mTLS clients trust the server by: its pinned key and its own CA.
        val trust = listOf(settings.serverFingerprint, settings.serverCa)
        held?.takeIf { it.alias == alias && it.url == url && it.trust == trust }?.let { return it }
        retired?.apollo?.close() // closes its HttpClient too
        retired = held
        val http = clientFactory.create(alias, emptyList()).findServerOnNetworkErrors(finder)
        Held(alias, url, trust, http, createApolloClient(http, url)).also { held = it }
    }

    /** Null when the device isn't registered. */
    suspend fun client(): HttpClient? = held()?.http

    /** Runs [block] with the GraphQL client, as this device's identity; null when it isn't registered. */
    suspend fun <T> graphql(block: suspend (ApolloClient) -> T): T? = held()?.let { block(it.apollo) }

    /** [path] (and [query]) on the server's origin. */
    fun url(path: String, query: String? = null): String = URLBuilder(settings.serverUrl).apply {
        parameters.clear()
        fragment = ""
        encodedPath = path
    }.buildString() + (query?.let { "?$it" } ?: "")

    /**
     * Asks the server who it is and where (`serverInfo`) and keeps it: its discovery (key, id, DHT key and addresses,
     * merged with what was kept, the address that answered first), its CA, its version and this device's number.
     * Nothing changes when it doesn't answer.
     */
    suspend fun refreshServerInfo() {
        val info = try {
            graphql { apollo -> apollo.query(ServerInfoQuery()).execute().data?.serverInfo }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        info.discovery?.let { keepDiscovery(it.fingerprint, it.id, it.key, it.addresses) }
        info.caCertificate?.takeIf { decodePemCertificates(it).isNotEmpty() && it != settings.serverCa }?.let { settings.serverCa = it }
        settings.serverVersion = info.version
        settings.device?.takeIf { it.deviceNumber != info.deviceNumber }?.let { settings.device = it.copy(deviceNumber = info.deviceNumber) }
    }

    /** Keeps what the server at `serverUrl` said of itself (see [KeptDiscovery.merge]). */
    fun keepDiscovery(fingerprint: String, id: String, key: String?, addresses: List<String>) {
        if (fingerprint.length != 64) return
        settings.serverDiscovery = KeptDiscovery.merge(settings.serverDiscovery, settings.serverUrl, fingerprint, id, key, addresses)
    }
}

/**
 * The CA that signs the server's certificates, PEM (`serverInfo.caCertificate`), asked on its own; null when the
 * server doesn't tell or what it tells isn't a certificate.
 */
internal suspend fun askServerCa(apollo: ApolloClient): String? = try {
    apollo.query(ServerCaQuery()).execute().data?.serverInfo?.caCertificate
        ?.takeIf { decodePemCertificates(it).isNotEmpty() }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
