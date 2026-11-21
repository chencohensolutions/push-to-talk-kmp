package com.chencohen.ptt

import android.content.Context
import android.os.Build
import com.chencohen.ptt.connection.ConnectionController
import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.crypto.LibsignalCryptoEngine
import com.chencohen.ptt.messaging.ApolloMessagingApi
import com.chencohen.ptt.messaging.Messaging
import com.chencohen.ptt.messaging.MessagingController
import com.chencohen.ptt.messaging.MessagingStore
import com.chencohen.ptt.messaging.PairingController
import com.chencohen.ptt.relay.KtorFrameConnector
import com.chencohen.ptt.store.SqlCipherStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import com.chencohen.ptt.enrollment.AndroidDeviceKeyStore
import com.chencohen.ptt.enrollment.CertificateRenewal
import com.chencohen.ptt.enrollment.EnrollmentController
import com.chencohen.ptt.enrollment.HttpRenewApi
import com.chencohen.ptt.network.AndroidMtlsHttpClientFactory
import com.chencohen.ptt.server.ServerHttp
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.settings.SettingsController
import com.chencohen.ptt.settings.SharedPreferencesStore

// Android entry points into the shared logic (journal's AndroidJournal.kt): the controllers the app keeps alive.

private fun settings(context: Context) = PttSettings(SharedPreferencesStore(context))

private var serverHttp: ServerHttp? = null

/** The app's one client for the server; one per process. */
@Synchronized
private fun serverHttp(context: Context): ServerHttp =
    serverHttp ?: ServerHttp(settings(context), AndroidMtlsHttpClientFactory(context)).also { serverHttp = it }

/** The connection to the server: home's status, and finding the server when its address changes. */
fun createConnectionController(context: Context): ConnectionController =
    ConnectionController(settings = settings(context), http = serverHttp(context))

/** The enrollment wizard; a successful enrollment switches [connection] to the new identity. */
fun createEnrollmentController(context: Context, connection: ConnectionController): EnrollmentController =
    EnrollmentController(
        settings = settings(context),
        clientFactory = AndroidMtlsHttpClientFactory(context),
        keyStore = AndroidDeviceKeyStore(),
        defaultDeviceName = Build.MODEL,
        onEnrolled = {
            connection.useEnrolledIdentity(it.serverUrl, it.identityAlias, it.discovery, it.serverCa, it.device)
        },
    )

/** Renews this device's certificate in its last 30 days; a renewal switches [connection] to the new identity. */
fun createCertificateRenewal(context: Context, connection: ConnectionController): CertificateRenewal {
    val settings = settings(context)
    return CertificateRenewal(
        settings = settings,
        keyStore = AndroidDeviceKeyStore(),
        api = HttpRenewApi(serverHttp(context)),
        test = CertificateRenewal.connectionTest(settings, AndroidMtlsHttpClientFactory(context)),
        onRenewed = connection::useRenewedIdentity,
    )
}

/** The Settings screen: the server, this device, and Register again. */
fun createSettingsController(context: Context, connection: ConnectionController): SettingsController =
    SettingsController(settings(context), connection.state)

/** Messaging's own thread: the crypto engine and the encrypted database run on it one thing at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
private val messagingWork = Dispatchers.IO.limitedParallelism(1)

private var messaging: Messaging? = null

/**
 * Keys, /ws, pairing and contacts; one per process. The database opens on [messagingWork] at first use. A revoked
 * device ([onRevoked], close code 4003) checks the connection again, so home shows Register again.
 */
@Synchronized
private fun messaging(context: Context, connection: ConnectionController): Messaging = messaging ?: run {
    val settings = settings(context)
    val http = serverHttp(context)
    val store = SqlCipherStore(context.applicationContext)
    Messaging(
        settings = settings,
        engine = LibsignalCryptoEngine(store) { settings.device?.let { DeviceAddress(it.user, it.deviceNumber) } },
        store = MessagingStore(store),
        api = ApolloMessagingApi(http),
        connector = KtorFrameConnector { http.client() },
        scope = CoroutineScope(SupervisorJob() + messagingWork),
        now = { System.currentTimeMillis() },
        onRevoked = { connection.connect() },
    ).also { messaging = it }
}

/** Keys, /ws, contacts and pair requests (docs/screens/contacts.md). */
fun createMessagingController(context: Context, connection: ConnectionController): MessagingController =
    MessagingController(messaging(context, connection), messagingWork)

/** The Add contact screen: this phone's code and scanning theirs (docs/screens/add-contact.md). */
fun createPairingController(context: Context, connection: ConnectionController): PairingController =
    PairingController(messaging(context, connection), messagingWork, now = { System.currentTimeMillis() })
