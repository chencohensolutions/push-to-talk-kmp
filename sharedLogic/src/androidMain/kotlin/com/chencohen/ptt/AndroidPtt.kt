package com.chencohen.ptt

import android.content.Context
import android.os.Build
import com.chencohen.ptt.connection.ConnectionController
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
