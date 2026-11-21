package com.chencohen.ptt

import com.chencohen.ptt.connection.ConnectionStatus
import com.chencohen.ptt.connection.ConnectionController
import com.chencohen.ptt.server.ServerHttp
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.enrollment.CertificateRequest
import com.chencohen.ptt.enrollment.Der
import com.chencohen.ptt.enrollment.DeviceKey
import com.chencohen.ptt.enrollment.DeviceKeyStore
import com.chencohen.ptt.enrollment.EnrollmentController
import com.chencohen.ptt.enrollment.EnrollmentError
import com.chencohen.ptt.enrollment.EnrollmentLink
import com.chencohen.ptt.enrollment.EnrollmentStep
import com.chencohen.ptt.enrollment.KeyProtection
import com.chencohen.ptt.enrollment.PasteResult
import com.chencohen.ptt.enrollment.cleanCode
import com.chencohen.ptt.enrollment.findEnrollmentLink
import com.chencohen.ptt.enrollment.parseEnrollmentLink
import com.chencohen.ptt.network.MtlsHttpClientFactory
import com.chencohen.ptt.network.graphQlEndpoint
import com.chencohen.ptt.network.serverAddress
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.settings.KeyValueStore
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
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class DerTest {
    @Test
    fun encodesObjectIdentifiers() {
        assertEquals("06072a8648ce3d0201", Der.objectIdentifier("1.2.840.10045.2.1").hex())
        assertEquals("0603550403", Der.objectIdentifier("2.5.4.3").hex())
    }

    @Test
    fun encodesLongLengths() {
        assertEquals("0400", Der.tlv(0x04, ByteArray(0)).hex())
        assertEquals("047f", Der.tlv(0x04, ByteArray(0x7F)).hex().take(4))
        assertEquals("048180", Der.tlv(0x04, ByteArray(0x80)).hex().take(6))
        assertEquals("04820100", Der.tlv(0x04, ByteArray(0x100)).hex().take(8))
    }
}

class CertificateRequestTest {
    private val point = hex("04" + "11".repeat(32) + "22".repeat(32))

    @Test
    fun buildsPkcs10RequestSignedOverItsInfo() {
        var signed = ByteArray(0)
        val csr = CertificateRequest.build("Pixel", CertificateRequest.ecP256PublicKeyInfo(point)) {
            signed = it
            hex("3006020101020102") // stand-in DER signature
        }

        val publicKeyInfo = "3059301306072a8648ce3d020106082a8648ce3d030107034200" + point.hex()
        val info = "3072" + "020100" + "3010310e300c06035504030c05506978656c" + publicKeyInfo + "a000"
        assertEquals(info, signed.hex(), "the signature must cover exactly the CertificationRequestInfo")
        assertEquals("30818b" + info + "300a06082a8648ce3d040302" + "030900" + "3006020101020102", csr.hex())
    }
}

class CleanCodeTest {
    @Test
    fun keepsTypedCodes() {
        assertEquals("K7QM-4XPD", cleanCode("k7qm-4xpd"))
        assertEquals("K7QM", cleanCode("K7QM"))
        assertEquals("K7QM-", cleanCode("K7QM-"))
        assertEquals("K7QM4XPD", cleanCode("K7QM4XPD9"))
        assertEquals("K7QM-4XPD", cleanCode("K7QM-4XPD!"))
    }

    @Test
    fun extractsCodeFromPastedText() {
        val pasted = "5MHH-0JDB\n2026/10/17 13:51:27 http: TLS handshake error from 127.0.0.1:55470: remote error"
        assertEquals("5MHH-0JDB", cleanCode(pasted))
        assertEquals("K7QM-4XPD", cleanCode("  Code     K7QM-4XPD"))
        assertEquals("K7QM-4XPD", cleanCode(" k7qm-4xpd "))
    }
}

class EnrollmentLinkTest {
    @Test
    fun readsCodeAndServer() {
        assertEquals(
            EnrollmentLink("K7QM-4XPD", "https://ptt.example.com:56400"),
            parseEnrollmentLink("ptt://enroll?code=K7QM-4XPD&server=https%3A%2F%2Fptt.example.com%3A56400"),
        )
        assertEquals(EnrollmentLink("K7QM-4XPD", null), parseEnrollmentLink("ptt://enroll?code=K7QM-4XPD"))
    }

    @Test
    fun ignoresOtherLinks() {
        assertNull(parseEnrollmentLink("ptt://enroll"))
        assertNull(parseEnrollmentLink("ptt://other?code=K7QM-4XPD"))
        assertNull(parseEnrollmentLink("https://enroll?code=K7QM-4XPD"))
        assertNull(parseEnrollmentLink("not a link"))
        // Journal's links are another app's.
        assertNull(parseEnrollmentLink("journal://enroll?code=K7QM-4XPD"))
        assertNull(parseEnrollmentLink("ptt://enroll?code=%20"))
    }

    @Test
    fun readsTheWholeQrCodeAsPttServerPrintsIt() {
        val fingerprint = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val key = "d72c51a2123f3273e7cb48745fd4d8c5044feb1be4b583c1d82538d894c8cbab"
        val link = parseEnrollmentLink(
            " ptt://enroll?code=K7QM-4XPD&server=https%3A%2F%2Fptt.example.com%3A56400&fingerprint=$fingerprint&key=$key" +
                "&address=https%3A%2F%2F203.0.113.7%3A56400&address=https%3A%2F%2F192.168.1.20%3A56400" +
                "&address=https%3A%2F%2F203.0.113.7%3A56400 ",
        )
        assertEquals(
            EnrollmentLink(
                code = "K7QM-4XPD",
                server = "https://ptt.example.com:56400",
                fingerprint = fingerprint,
                key = key,
                addresses = listOf("https://203.0.113.7:56400", "https://192.168.1.20:56400"),
            ),
            link,
        )
        assertEquals("K7QM-4XPD", findEnrollmentLink("Scan this: ptt://enroll?code=K7QM-4XPD.")?.code)
    }
}

class EndpointTest {
    @Test
    fun addsSchemeAndGraphQlPath() {
        assertEquals("https://192.168.50.2:8080/query", graphQlEndpoint(" 192.168.50.2:8080/ "))
        assertEquals("https://ptt.example/query", graphQlEndpoint("https://ptt.example"))
        assertEquals("https://h:8080/custom", graphQlEndpoint("https://h:8080/custom"))
        assertEquals("https://192.168.50.2:8080", serverAddress("https://192.168.50.2:8080/query"))
    }
}

private class FakeKeyStore : DeviceKeyStore {
    val keys = mutableSetOf<String>()
    val certificates = mutableMapOf<String, ByteArray>()

    override fun generate(alias: String): DeviceKey {
        keys += alias
        return object : DeviceKey {
            override val protection = KeyProtection.TEE
            override val publicKeyInfo = CertificateRequest.ecP256PublicKeyInfo(byteArrayOf(4) + ByteArray(64))
            override fun sign(data: ByteArray) = hex("3006020101020102")
        }
    }

    override fun saveCertificate(alias: String, certificateDer: ByteArray) {
        check(alias in keys)
        certificates[alias] = certificateDer
    }

    override fun delete(alias: String) {
        keys -= alias
        certificates -= alias
    }
}

private class MemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private val CERTIFICATE = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x05)

private const val PREVIEW_JSON =
    """{"data":{"enrollmentPreview":{"__typename":"EnrollmentPreview","user":"hen","expiresAt":"2026-10-17T14:32:00Z"}}}"""

private val ENROLLED_JSON =
    """{"data":{"enrollDevice":{"__typename":"EnrolledDevice","user":"hen","deviceName":"Pixel 9 Pro",""" +
        """"deviceNumber":2,"certificate":"${Base64.encode(CERTIFICATE)}","serial":"4fa107","expiresAt":"2027-10-17T14:24:00Z",""" +
        """"caCertificate":null}}}"""

private fun rejectedJson(field: String, reason: String) =
    """{"data":{"$field":{"__typename":"EnrollmentRejected","reason":"$reason"}}}"""

private const val ME_OK = """{"data":{"me":{"user":"hen","deviceNumber":2,"deviceName":"Pixel 9 Pro"}}}"""

@OptIn(ExperimentalTime::class)
class EnrollmentControllerTest {
    private val store = MemoryStore()
    private val keyStore = FakeKeyStore()
    private val requestedAliases = mutableListOf<String?>()
    private val requests = mutableListOf<String>()
    private var enrolled: Pair<String, String>? = null
    private var enrolledCa: String? = null
    private var enrolledDevice: EnrolledDevice? = null

    /** Answers by operation name; [overrides] replaces the default answer of an operation. */
    private fun server(overrides: Map<String, String> = emptyMap(), checkStatus: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler = { request ->
        val body = request.body.toByteArray().decodeToString()
        requests += body
        val operation = Regex("\"operationName\":\"(\\w+)\"").find(body)!!.groupValues[1]
        val json = overrides[operation] ?: when (operation) {
            "EnrollmentPreview" -> PREVIEW_JSON
            "EnrollDevice" -> ENROLLED_JSON
            else -> ME_OK
        }
        if (operation == "Me" && checkStatus != HttpStatusCode.OK) {
            respond("unauthorized client certificate", checkStatus)
        } else {
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun TestScope.controller(handler: MockRequestHandler = server()): EnrollmentController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return EnrollmentController(
            settings = PttSettings(store),
            clientFactory = MtlsHttpClientFactory { alias, _ ->
                requestedAliases += alias
                HttpClient(MockEngine(handler))
            },
            keyStore = keyStore,
            defaultDeviceName = "Pixel 9 Pro",
            onEnrolled = {
                enrolled = it.serverUrl to it.identityAlias
                enrolledCa = it.serverCa
                enrolledDevice = it.device
            },
            dispatcher = dispatcher,
            workDispatcher = dispatcher,
            clock = object : Clock {
                override fun now() = Instant.parse("2026-10-17T14:24:00Z")
            },
        )
    }

    private fun TestScope.connectionController() = PttSettings(store).let { settings ->
        ConnectionController(
            settings = settings,
            http = ServerHttp(settings, MtlsHttpClientFactory { _, _ -> error("unused") }),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private suspend fun EnrollmentController.awaitStep(predicate: (EnrollmentStep) -> Boolean): EnrollmentStep =
        state.first { predicate(it.step) }.step

    private suspend fun EnrollmentController.reachConfirm() {
        start()
        updateServerAddress("192.168.50.2:8080")
        updateCode("k7qm-4xpd")
        checkCode()
        awaitStep { it is EnrollmentStep.Confirm }
    }

    /**
     * An invite link on a phone that isn't enrolled: the invite is looked up, a code goes to the email typed, and
     * enrolling with it keeps the inviter's pairing code for Messaging.
     */
    @Test
    fun anInviteLinkEnrollsByEmailAndKeepsTheInvitersCode() = runTest {
        val identity = byteArrayOf(5) + ByteArray(32) { 3 }
        val link = com.chencohen.ptt.messaging.InviteLink(
            code = "abcdefghijklmnopqrstuv", server = "https://192.168.50.2:8080", fingerprint = "ab".repeat(32),
            key = null, addresses = emptyList(), inviter = "hen", identityKey = identity, secret = ByteArray(16) { 1 },
            email = null,
        )
        val enrolledWithInvite = """{"data":{"enrollWithInvite":{"__typename":"InviteEnrolled","device":""" +
            ENROLLED_JSON.substringAfter("\"enrollDevice\":").substringBefore("}}}").replace("\"__typename\":\"EnrolledDevice\",", "") +
            "}," + """"pairing":{"inviter":"hen","pairingToken":{"token":"tokentokentokentokento","expiresAt":"2026-10-17T14:34:00Z"}}}}}"""
        val controller = controller(server(mapOf(
            "InvitePreview" to """{"data":{"invitePreview":{"__typename":"InvitePreview","inviter":"hen","inviterDisplayName":"Hen Cohen","email":null,"expiresAt":"2026-10-24T14:24:00Z"}}}""",
            "InviteEmailCode" to """{"data":{"inviteEmailCode":{"__typename":"InviteEmailSent","email":"dana@example.com","expiresAt":"2026-10-17T14:39:00Z"}}}""",
            "EnrollWithInvite" to enrolledWithInvite,
        )))
        controller.start()
        controller.openPasted("Hen invited you: ${link.format()}")
        controller.awaitStep { it == EnrollmentStep.InviteEmail() }
        assertEquals(com.chencohen.ptt.enrollment.InviteInfo("hen", "Hen Cohen", null), controller.currentState.invite)

        controller.updateEmail("Dana@Example.com")
        controller.sendEmailCode()
        assertEquals(EnrollmentStep.InviteCode("dana@example.com"), controller.awaitStep { it is EnrollmentStep.InviteCode })
        controller.updateEmailCode("12 34 5")
        controller.registerWithInvite()
        assertEquals(
            EnrollmentStep.InviteCode("dana@example.com", error = EnrollmentError.Invite(com.chencohen.ptt.enrollment.InviteProblem.WRONG_EMAIL_CODE)),
            controller.currentState.step,
        )
        controller.updateEmailCode("123456")
        controller.updateDisplayName("Dana Cohen")
        controller.registerWithInvite()
        assertIs<EnrollmentStep.Enrolled>(controller.awaitStep { it is EnrollmentStep.Enrolled || it is EnrollmentStep.Failed })
        val request = requests.single { "EnrollWithInvite" in it }
        assertContains(request, "\"emailCode\":\"123456\"")
        assertContains(request, "\"displayName\":\"Dana Cohen\"")
        assertEquals(link.pairCode("tokentokentokentokento").format(), PttSettings(store).pendingInvitePair)
    }

    @Test
    fun validCodeShowsAccountWithoutClientCertificate() = runTest {
        val controller = controller()
        controller.start()
        controller.updateServerAddress("192.168.50.2:8080")
        controller.updateCode("k7qm-4xpd")
        controller.checkCode()

        assertEquals(EnrollmentStep.EnterCode(checking = true), controller.currentState.step)
        assertEquals(EnrollmentStep.Confirm(user = "hen", minutesLeft = 8), controller.awaitStep { it is EnrollmentStep.Confirm })
        assertEquals(listOf<String?>(null), requestedAliases)
        assertContains(requests.single(), "\"code\":\"K7QM-4XPD\"")
    }

    @Test
    fun linkFillsInAndChecksTheCode() = runTest {
        val controller = controller()
        controller.openLink(EnrollmentLink("k7qm-4xpd", "https://192.168.50.2:8080"))

        assertEquals(EnrollmentStep.Confirm(user = "hen", minutesLeft = 8), controller.awaitStep { it is EnrollmentStep.Confirm })
        assertEquals("https://192.168.50.2:8080", controller.currentState.serverAddress)
        assertContains(requests.single(), "\"code\":\"K7QM-4XPD\"")
    }

    @Test
    fun scannedTextThatIsNotALinkIsTheCode() = runTest {
        val controller = controller()
        controller.start()
        controller.openScanned("k7qm-4xpd")

        assertEquals("K7QM-4XPD", controller.currentState.code)
        assertEquals(EnrollmentStep.EnterCode(), controller.currentState.step)
    }

    @Test
    fun pastedLinkWithWordsAroundItOpensLikeAScan() = runTest {
        val controller = controller()
        controller.start()
        val result = controller.openPasted("Here you go: ptt://enroll?code=k7qm-4xpd&server=https%3A%2F%2F192.168.50.2%3A8080.\nThanks")

        assertEquals(PasteResult.LINK, result)
        assertEquals(EnrollmentStep.Confirm(user = "hen", minutesLeft = 8), controller.awaitStep { it is EnrollmentStep.Confirm })
        assertEquals("https://192.168.50.2:8080", controller.currentState.serverAddress)
    }

    @Test
    fun pastedTextWithOnlyACodeFillsInTheCode() = runTest {
        val controller = controller()
        controller.start()

        assertEquals(PasteResult.CODE, controller.openPasted("  Code     K7QM-4XPD"))
        assertEquals("K7QM-4XPD", controller.currentState.code)
        assertEquals(EnrollmentStep.EnterCode(), controller.currentState.step)
        assertEquals(PasteResult.NOTHING, controller.openPasted("see you tomorrow"))
        assertEquals("K7QM-4XPD", controller.currentState.code)
    }

    @Test
    fun linkPastedIntoAFieldOpensIt() = runTest {
        val controller = controller()
        controller.start()
        controller.updateCode("ptt://enroll?code=k7qm-4xpd&server=https%3A%2F%2F192.168.50.2%3A8080")

        assertEquals(EnrollmentStep.Confirm(user = "hen", minutesLeft = 8), controller.awaitStep { it is EnrollmentStep.Confirm })
        assertEquals("https://192.168.50.2:8080", controller.currentState.serverAddress)
    }

    @Test
    fun rejectedCodeStaysOnCodeScreen() = runTest {
        val controller = controller(server(mapOf("EnrollmentPreview" to rejectedJson("enrollmentPreview", "INVALID_CODE"))))
        controller.start()
        controller.updateServerAddress("192.168.50.2:56400")
        controller.updateCode("AAAA-AAAA")
        controller.checkCode()

        val step = controller.awaitStep { it is EnrollmentStep.EnterCode && !it.checking }
        assertEquals(EnrollmentStep.EnterCode(error = EnrollmentError.InvalidCode), step)

        controller.updateCode("AAAA-AAAB")
        assertEquals(EnrollmentStep.EnterCode(), controller.currentState.step, "typing clears the error")
    }

    @Test
    fun missingFieldsAreReportedLocally() = runTest {
        val controller = controller()
        controller.start()
        controller.updateServerAddress(" ")
        controller.checkCode()
        assertEquals(EnrollmentStep.EnterCode(error = EnrollmentError.MissingServerAddress), controller.currentState.step)
        controller.updateServerAddress("h")
        controller.checkCode()
        assertEquals(EnrollmentStep.EnterCode(error = EnrollmentError.MissingCode), controller.currentState.step)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun registrationCreatesKeyStoresCertificateAndSwitchesIdentity() = runTest {
        store.values["identity_alias"] = "ptt-device-1"
        keyStore.keys += "ptt-device-1"
        val controller = controller()
        controller.reachConfirm()
        controller.register()

        val step = assertIs<EnrollmentStep.Enrolled>(controller.awaitStep { it is EnrollmentStep.Enrolled || it is EnrollmentStep.Failed })
        assertEquals(
            EnrollmentStep.Enrolled("hen", "Pixel 9 Pro", 2, "https://192.168.50.2:8080", KeyProtection.TEE, "2027-10-17"),
            step,
        )
        val (url, alias) = enrolled!!
        assertEquals("https://192.168.50.2:8080/query", url)
        assertTrue(alias.startsWith(EnrollmentController.IDENTITY_ALIAS_PREFIX))
        assertTrue(CERTIFICATE.contentEquals(keyStore.certificates[alias]))
        assertEquals(setOf(alias), keyStore.keys, "the previous enrolled key is deleted")

        // Preview and enrollment without a certificate, then the connection test with the new identity.
        assertEquals(listOf(null, null, alias), requestedAliases)
        val enrollRequest = requests.single { "EnrollDevice" in it }
        assertContains(enrollRequest, "\"keyProtection\":\"TEE\"")
        assertContains(enrollRequest, "\"deviceName\":\"Pixel 9 Pro\"")
        assertNull(enrolledCa, "a server that doesn't tell its CA")
        assertEquals(EnrolledDevice("hen", 2, "Pixel 9 Pro"), enrolledDevice)
    }

    /** Each server has its own CA: asked once enrolled when enrollDevice didn't give it, and kept. */
    @Test
    fun registrationKeepsTheServersCa() = runTest {
        val pem = "-----BEGIN CERTIFICATE-----\nMAMCAQU=\n-----END CERTIFICATE-----\n"
        val answer = """{"data":{"serverInfo":{"caCertificate":${"\""}${pem.replace("\n", "\\n")}${"\""}}}}"""
        val controller = controller(server(mapOf("ServerCa" to answer)))
        controller.reachConfirm()
        controller.register()

        assertIs<EnrollmentStep.Enrolled>(controller.awaitStep { it is EnrollmentStep.Enrolled || it is EnrollmentStep.Failed })
        assertEquals(pem, enrolledCa)

        val connection = connectionController()
        connection.useEnrolledIdentity("https://h:8080/query", "ptt-device-2", serverCa = enrolledCa)
        assertEquals(pem, PttSettings(store).serverCa)
        // Enrolling at another server that doesn't tell its CA forgets the old one's.
        connection.useEnrolledIdentity("https://other:8080/query", "ptt-device-3")
        assertNull(PttSettings(store).serverCa)
    }

    @Test
    fun registrationTakesTheCaFromEnrollDevice() = runTest {
        val pem = "-----BEGIN CERTIFICATE-----\nMAMCAQU=\n-----END CERTIFICATE-----\n"
        val enrolledWithCa = ENROLLED_JSON.replace("\"caCertificate\":null", "\"caCertificate\":\"${pem.replace("\n", "\\n")}\"")
        val controller = controller(server(mapOf("EnrollDevice" to enrolledWithCa)))
        controller.reachConfirm()
        controller.register()

        assertIs<EnrollmentStep.Enrolled>(controller.awaitStep { it is EnrollmentStep.Enrolled || it is EnrollmentStep.Failed })
        assertEquals(pem, enrolledCa)
        assertTrue(requests.none { "ServerCa" in it }, "no need to ask serverInfo for it")
    }

    @Test
    fun rejectedEnrollmentDeletesTheKey() = runTest {
        val controller = controller(server(mapOf("EnrollDevice" to rejectedJson("enrollDevice", "INVALID_CODE"))))
        controller.reachConfirm()
        controller.register()

        assertEquals(EnrollmentStep.Failed(EnrollmentError.InvalidCode), controller.awaitStep { it is EnrollmentStep.Failed })
        assertTrue(keyStore.keys.isEmpty())
        assertNull(enrolled)

        controller.back()
        assertEquals("", controller.currentState.code)
        assertEquals(EnrollmentStep.EnterCode(), controller.currentState.step)
    }

    @Test
    fun failedConnectionTestDoesNotSwitchIdentity() = runTest {
        val controller = controller(server(checkStatus = HttpStatusCode.Unauthorized))
        controller.reachConfirm()
        controller.register()

        assertEquals(
            EnrollmentStep.Failed(EnrollmentError.ConnectionTestFailed("HTTP 401")),
            controller.awaitStep { it is EnrollmentStep.Failed },
        )
        assertTrue(keyStore.keys.isEmpty())
        assertNull(enrolled)
    }

    @Test
    fun enrolledIdentityReplacesTheSettings() = runTest {
        val connection = connectionController()
        connection.useEnrolledIdentity("https://h:8080/query", "ptt-device-2", device = EnrolledDevice("hen", 2, "Pixel"))
        assertEquals("https://h:8080/query", connection.currentState.serverUrl)
        assertEquals("ptt-device-2", connection.currentState.identityAlias)
        assertEquals(ConnectionStatus.NotConnected, connection.currentState.status)
        assertEquals(2, connection.currentState.device!!.deviceNumber)
        assertEquals("ptt-device-2", PttSettings(store).identityAlias)
        assertEquals(EnrolledDevice("hen", 2, "Pixel"), PttSettings(store).device)
    }
}

/**
 * Enrolling by id (docs/screens/enrollment.md "Enrolling by id"): the link says who the server is, the
 * connections pin its key from the first, and the other addresses, then the DHT, are tried when the first doesn't
 * answer. On real time: the probes have a timeout of their own, which virtual time would run out at once.
 */
@OptIn(ExperimentalTime::class)
class EnrollByIdTest {
    private val fingerprint = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    private val dhtKey = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"
    private val store = MemoryStore()
    private val settings = PttSettings(store)
    private val asked = mutableListOf<String>()
    private var enrolled: Triple<String, String, com.chencohen.ptt.discovery.KeptDiscovery?>? = null
    private var dhtAsked = 0

    /** The server, answering only at [up] (hosts), as a dead address fails elsewhere; the pin each client trusted. */
    private val pinsSeen = mutableListOf<String?>()

    private fun server(up: Set<String>) = HttpClient(MockEngine { request ->
        asked += request.url.host
        if (request.url.host !in up) throw kotlinx.io.IOException("No route to host")
        val body = request.body.toByteArray().decodeToString()
        val operation = Regex("\"operationName\":\"(\\w+)\"").find(body)?.groupValues?.get(1)
        val json = when (operation) {
            "EnrollmentPreview" -> PREVIEW_JSON
            "EnrollDevice" -> ENROLLED_JSON
            null -> "" // a probe
            else -> ME_OK
        }
        respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private fun controller(up: Set<String>, dht: List<String>? = null) = EnrollmentController(
        settings = settings,
        clientFactory = object : MtlsHttpClientFactory {
            override suspend fun create(identityAlias: String?, trustedCas: List<ByteArray>): HttpClient {
                pinsSeen += null
                return server(up)
            }

            override suspend fun createPinned(identityAlias: String?, fingerprint: String): HttpClient {
                pinsSeen += fingerprint
                return server(up)
            }
        },
        keyStore = FakeKeyStore(),
        defaultDeviceName = "Galaxy",
        onEnrolled = { enrolled = Triple(it.serverUrl, it.identityAlias, it.discovery) },
        dispatcher = kotlinx.coroutines.Dispatchers.Default,
        workDispatcher = kotlinx.coroutines.Dispatchers.Default,
        clock = object : Clock {
            override fun now() = Instant.parse("2026-10-17T14:24:00Z")
        },
        dhtLookup = { key, fp ->
            dhtAsked++
            assertEquals(dhtKey, key)
            assertEquals(fingerprint, fp)
            dht?.let { com.chencohen.ptt.discovery.ServerRecord(it, 0) }
        },
    )

    private fun link(server: String, vararg addresses: String) = parseEnrollmentLink(
        "ptt://enroll?code=K7QM-4XPD&server=" + server.replace(":", "%3A").replace("/", "%2F") +
            "&fingerprint=$fingerprint&key=$dhtKey" + addresses.joinToString("") { "&address=" + it.replace(":", "%3A").replace("/", "%2F") },
    )!!

    private suspend fun EnrollmentController.awaitStep(predicate: (EnrollmentStep) -> Boolean): EnrollmentStep =
        state.first { predicate(it.step) }.step

    private fun realTime(block: suspend () -> Unit) = runTest { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() } }

    @Test
    fun aPastedLinkPinsTheKeyAsAScannedOneDoes() = realTime {
        val controller = controller(up = setOf("203.0.113.7"))
        controller.start()
        val text = "ptt://enroll?code=K7QM-4XPD&server=https%3A%2F%2F203.0.113.7%3A56400&fingerprint=$fingerprint&key=$dhtKey"
        assertEquals(PasteResult.LINK, controller.openPasted("Join my server: $text"))
        assertIs<EnrollmentStep.Confirm>(controller.awaitStep { it is EnrollmentStep.Confirm || it is EnrollmentStep.EnterCode && it.error != null })
        assertEquals(listOf<String?>(fingerprint), pinsSeen)
        assertEquals("T6DNBAM-IJR6WLU-GRP5KQM-KWWQCWV-R36TY3F-MFYELGR-RLVWBLM-HQBIEAE", controller.currentState.serverId)
        controller.close()
    }

    @Test
    fun theLinkPinsTheKeyFromTheFirstConnection() = realTime {
        val controller = controller(up = setOf("203.0.113.7"))
        controller.openLink(link("https://203.0.113.7:56400"))
        assertIs<EnrollmentStep.Confirm>(controller.awaitStep { it is EnrollmentStep.Confirm || it is EnrollmentStep.EnterCode && it.error != null })
        assertEquals(listOf<String?>(fingerprint), pinsSeen)
        assertEquals("T6DNBAM-IJR6WLU-GRP5KQM-KWWQCWV-R36TY3F-MFYELGR-RLVWBLM-HQBIEAE", controller.currentState.serverId)
        controller.register()
        assertIs<EnrollmentStep.Enrolled>(controller.awaitStep { it is EnrollmentStep.Enrolled || it is EnrollmentStep.Failed })
        assertTrue(pinsSeen.all { it == fingerprint }, "every connection pinned the key: $pinsSeen")
        val (url, _, discovery) = enrolled!!
        assertEquals("https://203.0.113.7:56400/query", url)
        assertEquals(fingerprint, discovery!!.fingerprint)
        assertEquals(dhtKey, discovery.key)
        assertEquals("T6DNBAM-IJR6WLU-GRP5KQM-KWWQCWV-R36TY3F-MFYELGR-RLVWBLM-HQBIEAE", discovery.id)
        controller.close()
    }

    @Test
    fun anotherAddressOfTheLinkIsTriedWhenTheFirstDoesNotAnswer() = realTime {
        val controller = controller(up = setOf("192.168.1.20"))
        controller.openLink(link("https://203.0.113.7:56400", "https://203.0.113.7:56400", "https://192.168.1.20:56400"))
        assertIs<EnrollmentStep.Confirm>(controller.awaitStep { it is EnrollmentStep.Confirm || it is EnrollmentStep.EnterCode && it.error != null })
        assertEquals("https://192.168.1.20:56400", controller.currentState.serverAddress)
        assertEquals(0, dhtAsked)
        controller.register()
        assertIs<EnrollmentStep.Enrolled>(controller.awaitStep { it is EnrollmentStep.Enrolled || it is EnrollmentStep.Failed })
        val (url, _, discovery) = enrolled!!
        assertEquals("https://192.168.1.20:56400/query", url)
        assertEquals("https://192.168.1.20:56400", discovery!!.addresses.first())
        controller.close()
    }

    @Test
    fun theDhtIsAskedWhenNoAddressAnswers() = realTime {
        val controller = controller(up = setOf("198.51.100.20"), dht = listOf("https://198.51.100.20:56400"))
        controller.openLink(link("https://203.0.113.7:56400", "https://203.0.113.7:56400"))
        assertIs<EnrollmentStep.Confirm>(controller.awaitStep { it is EnrollmentStep.Confirm || it is EnrollmentStep.EnterCode && it.error != null })
        assertEquals(1, dhtAsked)
        assertEquals("https://198.51.100.20:56400", controller.currentState.serverAddress)
        controller.close()
    }

    @Test
    fun aLinkChangesNothingKeptUntilTheDeviceIsEnrolled() = realTime {
        // An enrolled phone opening someone's link: who it trusts and where it looks for its server stay as they were,
        // so its own connections never move to the link's server unless the user enrolls there.
        val before = com.chencohen.ptt.discovery.KeptDiscovery(fingerprint = "ab".repeat(32), id = "OLD")
        settings.serverDiscovery = before
        val controller = controller(up = setOf("192.168.1.20"))
        controller.openLink(link("https://203.0.113.7:56400", "https://203.0.113.7:56400", "https://192.168.1.20:56400"))
        controller.awaitStep { it is EnrollmentStep.Confirm }
        assertEquals(before, settings.serverDiscovery)
        assertTrue(pinsSeen.isNotEmpty() && pinsSeen.all { it == fingerprint }, "enrollment trusted only the link's key: $pinsSeen")
        controller.back() // to the code
        controller.back() // to the start
        assertEquals(before, settings.serverDiscovery)
        assertNull(controller.currentState.serverId)
        controller.close()
    }

    @Test
    fun aLinkWithoutAFingerprintWorksAsBefore() = realTime {
        val controller = controller(up = setOf("ptt.example.com"))
        controller.openLink(EnrollmentLink("K7QM-4XPD", "https://ptt.example.com:56400"))
        assertIs<EnrollmentStep.Confirm>(controller.awaitStep { it is EnrollmentStep.Confirm || it is EnrollmentStep.EnterCode && it.error != null })
        assertEquals(listOf<String?>(null), pinsSeen)
        controller.close()
    }
}

class EnrollmentLinkByIdTest {
    @Test
    fun readsWhoTheServerIs() {
        val link = parseEnrollmentLink(
            "ptt://enroll?address=https%3A%2F%2F203.0.113.7%3A56400&address=https%3A%2F%2F%5B2001%3Adb8%3A%3A7%5D%3A56400" +
                "&address=http%3A%2F%2Fplain.example&code=K7QM-4XPD&fingerprint=" + "AB".repeat(32) + "&key=" + "cd".repeat(32),
        )!!
        assertEquals("ab".repeat(32), link.fingerprint)
        assertEquals("cd".repeat(32), link.key)
        assertEquals(listOf("https://203.0.113.7:56400", "https://[2001:db8::7]:56400"), link.addresses)
        assertNull(link.server)
        val bad = parseEnrollmentLink("ptt://enroll?code=K7QM-4XPD&fingerprint=abc&key=zz")!!
        assertNull(bad.fingerprint)
        assertNull(bad.key)
    }

    @Test
    fun serverIdsAsTheServerWritesThem() {
        val ids = com.chencohen.ptt.discovery.ServerId
        // Made by journal-server-go's discovery.FormatID, which ptt-server copies.
        assertEquals("T6DNBAM-IJR6WLU-GRP5KQM-KWWQCWV-R36TY3F-MFYELGR-RLVWBLM-HQBIEAE",
            ids.format("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))
        assertEquals("AAAAAAA-AAAAAAA-AAAAAAA-AAAAAAA-AAAAAAA-AAAAAAA-AAAAAAA-AAAAAAA", ids.format("00".repeat(32)))
        assertEquals("7777777-777777N-7777777-777777N-7777777-777777N-7777777-77777Q4", ids.format("ff".repeat(32)))
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            ids.parse("t6dnbam ijr6wlu-grp5kqm-kwwqcwv-r36ty3f-mfyelgr-rlvwblm-hqbieae"))
        assertNull(ids.parse("T6DNBAM-IJR6WLU-GRP5KQM-KWWQCWV-R36TY3F-MFYELGR-RLVWBLM-HQBIEAF"), "a typo")
        assertNull(ids.format("abc"))
    }
}
