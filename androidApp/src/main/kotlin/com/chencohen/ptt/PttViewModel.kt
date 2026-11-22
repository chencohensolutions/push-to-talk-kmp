package com.chencohen.ptt

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.chencohen.ptt.connection.ConnectionController
import com.chencohen.ptt.enrollment.CertificateRenewal
import com.chencohen.ptt.enrollment.EnrollmentController
import com.chencohen.ptt.enrollment.RenewalResult
import com.chencohen.ptt.messaging.MessagingController
import com.chencohen.ptt.messaging.PairingController
import com.chencohen.ptt.settings.SettingsController

/** Keeps the shared controllers alive across configuration changes. */
class PttViewModel(application: Application) : AndroidViewModel(application) {
    val connection: ConnectionController = createConnectionController(application)
    val enrollment: EnrollmentController = createEnrollmentController(application, connection)
    val settings: SettingsController = createSettingsController(application, connection)
    val renewal: CertificateRenewal = createCertificateRenewal(application, connection)
    val messaging: MessagingController = createMessagingController(application, connection)
    val pairing: PairingController = createPairingController(application, connection)

    init {
        // In its last 30 days the certificate is renewed; offline, it is tried again at the next start.
        renewal.renewIfDue { if (it is RenewalResult.Failed) Log.w("Talk", "Renewing the certificate: ${it.message}") }
    }

    override fun onCleared() {
        pairing.close()
        messaging.close()
        settings.close()
        enrollment.close()
        renewal.close()
        connection.close()
    }
}
