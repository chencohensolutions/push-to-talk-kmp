package com.chencohen.ptt.network

import com.chencohen.ptt.ios.IosHost
import com.chencohen.ptt.ios.toByteArray
import com.chencohen.ptt.ios.toNSData
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.tls.ServerPin
import com.chencohen.ptt.tls.decodePemCertificates
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import platform.Foundation.NSData
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodClientCertificate
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential

/**
 * mTLS over NSURLSession (Ktor's Darwin engine), as AndroidMtlsHttpClientFactory does over OkHttp: the device's key
 * and certificate from the Keychain answer the server's request for a client certificate, and once the server's key
 * (the enrollment link, `serverInfo.discovery`) or its own CA (`serverInfo.caCertificate`) is kept, they are checked
 * instead of the system's roots and the host name, so the server is reached at any address (docs/design.md, "Finding
 * the server"). What the session is asked comes as challenges; the Keychain and SecTrust are the host's (Swift).
 */
internal class IosMtlsHttpClientFactory(private val host: IosHost, private val settings: PttSettings) : MtlsHttpClientFactory {
    override suspend fun create(identityAlias: String?, trustedCas: List<ByteArray>): HttpClient =
        client(identityAlias, trustedCas, onlyKey = null)

    override suspend fun createPinned(identityAlias: String?, fingerprint: String): HttpClient =
        client(identityAlias, emptyList(), onlyKey = fingerprint)

    /** [onlyKey]: trust only the server with that key's fingerprint, not what the settings keep. */
    private fun client(identityAlias: String?, trustedCas: List<ByteArray>, onlyKey: String?): HttpClient {
        // Read once, as on Android: ServerHttp makes a new client when what it trusts the server by changes.
        val pin = onlyKey ?: settings.serverFingerprint
        val serverCas = if (onlyKey != null) emptyList() else settings.serverCa?.let(::decodePemCertificates).orEmpty()
        val trust = ServerTrust(host, pin, serverCas.map { it.toNSData() }, trustedCas.map { it.toNSData() })
        return HttpClient(Darwin) {
            engine {
                configureSession {
                    // NSURLSession counts a quiet /ws as an idle request and ends it after this long (60 s by
                    // default); the server pings a quiet socket every 60 s, which may not count as traffic.
                    timeoutIntervalForRequest = 300.0
                    // A request waits for the network instead of failing at once only where the caller says so.
                    waitsForConnectivity = false
                }
                handleChallenge { _, _, challenge, completionHandler ->
                    val method = challenge.protectionSpace.authenticationMethod
                    when (method) {
                        NSURLAuthenticationMethodServerTrust -> trust.answer(challenge, completionHandler)
                        NSURLAuthenticationMethodClientCertificate -> {
                            // Without an identity no certificate is sent, which the server takes only for enrollment.
                            val credential = identityAlias?.let { host.clientCredential(it) }
                            if (credential != null) {
                                completionHandler(NSURLSessionAuthChallengeUseCredential, credential)
                            } else {
                                completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
                            }
                        }
                        else -> completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
                    }
                }
            }
            // No limits of its own: a request that needs others than the session's sets them.
            install(HttpTimeout)
            // /ws (relay/RelaySocket.kt): NSURLSession speaks HTTP/1.1 for it, as the server needs.
            install(WebSockets)
        }
    }
}

/**
 * Whether the server of a challenge is the one this phone knows (Android's KeptServerTrustManager): its certificate
 * chains to the server's own CA [serverCas] and is for a server, or its key hashes to [pin]; whatever its host name.
 * With neither kept, the system decides (its roots and the host name), with [extraCas] trusted too.
 */
private class ServerTrust(
    private val host: IosHost,
    private val pin: String?,
    private val serverCas: List<NSData>,
    private val extraCas: List<NSData>,
) {
    fun answer(challenge: NSURLAuthenticationChallenge, complete: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit) {
        val kept = pin != null || serverCas.isNotEmpty()
        if (!kept && extraCas.isEmpty()) return complete(NSURLSessionAuthChallengePerformDefaultHandling, null)
        val ok = if (kept) {
            (serverCas.isNotEmpty() && host.chainsToCas(challenge, serverCas)) ||
                (pin != null && host.serverCertificate(challenge)?.toByteArray()?.let { ServerPin.matches(it, pin) } == true)
        } else {
            // Not used by the app (it passes no extra CAs); kept for the interface's sake.
            host.chainsToCas(challenge, extraCas)
        }
        val credential = if (ok) host.trustCredential(challenge) else null
        if (credential != null) {
            complete(NSURLSessionAuthChallengeUseCredential, credential)
        } else {
            complete(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    }
}
