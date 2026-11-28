package com.chencohen.ptt

import com.chencohen.ptt.util.logFailures
import com.chencohen.ptt.connection.ConnectionController
import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.crypto.IosCryptoEngine
import com.chencohen.ptt.enrollment.CertificateRenewal
import com.chencohen.ptt.enrollment.EnrollmentController
import com.chencohen.ptt.enrollment.HttpRenewApi
import com.chencohen.ptt.enrollment.IosDeviceKeyStore
import com.chencohen.ptt.ios.IosFolders
import com.chencohen.ptt.ios.IosHost
import com.chencohen.ptt.ios.SecureRandom
import com.chencohen.ptt.ios.toNSData
import com.chencohen.ptt.media.IosMediaFiles
import com.chencohen.ptt.messaging.ApolloMessagingApi
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.MessageNotifier
import com.chencohen.ptt.messaging.Messaging
import com.chencohen.ptt.messaging.MessagingController
import com.chencohen.ptt.messaging.MessagingStore
import com.chencohen.ptt.messaging.PairingController
import com.chencohen.ptt.network.IosMtlsHttpClientFactory
import com.chencohen.ptt.relay.KtorFrameConnector
import com.chencohen.ptt.server.ServerHttp
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.settings.SettingsController
import com.chencohen.ptt.settings.UserDefaultsStore
import com.chencohen.ptt.store.IosSecureStore
import com.chencohen.ptt.talk.Aes256Gcm
import com.chencohen.ptt.talk.IosClipFiles
import com.chencohen.ptt.talk.KtorBlobApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData

/**
 * iOS's entry into the shared logic (Android's AndroidPtt.kt): made once by the app (TalkApp.swift) with its [host],
 * it builds the one hub of the process and the controllers the SwiftUI screens render. The screens only read each
 * controller's `currentState`, follow it with `observe`, and call its methods; everything else is here.
 *
 * [deviceName] is the phone's name as enrollment suggests it, [appVersion] what contacts are told (AboutContent), and
 * [screenWidthPx] / [screenHeightPx] the screen's pixels in portrait (shared media's previews are made as wide).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosPtt(
    private val host: IosHost,
    deviceName: String,
    appVersion: String,
    screenWidthPx: Int,
    screenHeightPx: Int,
) {
    private val settings = PttSettings(UserDefaultsStore())
    private val clientFactory = IosMtlsHttpClientFactory(host, settings)
    private val keyStore = IosDeviceKeyStore(host)
    private val http: ServerHttp

    /** Messaging's own thread of work: the crypto engine and the encrypted database run on it one thing at a time. */
    private val messagingWork = Dispatchers.IO.limitedParallelism(1)

    private val hub: Messaging

    /** Work nobody waits for, on Messaging's thread and on the main one; a failure is logged (logFailures). */
    private val background = CoroutineScope(SupervisorJob() + messagingWork + logFailures)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + logFailures)

    /** The connection to the server: home's status, and finding the server when its address changes. */
    val connection: ConnectionController

    /** The enrollment wizard; a successful enrollment switches [connection] to the new identity. */
    val enrollment: EnrollmentController

    /** Renews this device's certificate in its last 30 days. */
    val renewal: CertificateRenewal

    /** The Settings screen: the server, this device, and Register again. */
    val settingsController: SettingsController

    /** Keys, /ws, contacts, chats, talk and calls (docs/screens/contacts.md). */
    val messaging: MessagingController

    /** The Add contact screen: this phone's code and scanning theirs (docs/screens/add-contact.md). */
    val pairing: PairingController

    init {
        // CryptoKit's AES-GCM for clips, media and call frames (ClipCrypto, MediaCrypto).
        Aes256Gcm.host = host
        http = ServerHttp(settings, clientFactory)
        connection = ConnectionController(settings = settings, http = http)
        enrollment = EnrollmentController(
            settings = settings,
            clientFactory = clientFactory,
            keyStore = keyStore,
            defaultDeviceName = deviceName,
            onEnrolled = {
                connection.useEnrolledIdentity(it.serverUrl, it.identityAlias, it.discovery, it.serverCa, it.device)
            },
        )
        renewal = CertificateRenewal(
            settings = settings,
            keyStore = keyStore,
            api = HttpRenewApi(http),
            test = CertificateRenewal.connectionTest(settings, clientFactory),
            onRenewed = connection::useRenewedIdentity,
        )
        settingsController = SettingsController(settings, connection.state)

        val store = IosSecureStore(host.db)
        hub = Messaging(
            settings = settings,
            engine = IosCryptoEngine(host.signal, store) { settings.device?.let { DeviceAddress(it.user, it.deviceNumber) } },
            store = MessagingStore(store),
            api = ApolloMessagingApi(http),
            connector = KtorFrameConnector { http.client() },
            scope = CoroutineScope(SupervisorJob() + messagingWork + logFailures),
            now = { IosFolders.nowMillis() },
            // Pair, invite and link secrets, clip keys: from the system's secure generator.
            random = SecureRandom,
            // A revoked device (close code 4003): the connection checks again, so home shows Register again.
            onRevoked = { connection.connect() },
            clipFiles = IosClipFiles(),
            blobApi = KtorBlobApi(http),
            mediaFiles = IosMediaFiles(),
        ).also {
            it.screenWidthPx = minOf(screenWidthPx, screenHeightPx)
            it.screenHeightPx = maxOf(screenWidthPx, screenHeightPx)
            it.appVersion = appVersion
        }
        messaging = MessagingController(hub, messagingWork)
        pairing = PairingController(hub, messagingWork, now = { IosFolders.nowMillis() })
    }

    /** This phone's user name (empty before enrolling). */
    val myUser: String get() = settings.device?.user.orEmpty()

    /** Who shows notifications for texts that come in, and takes them down when read. */
    fun setMessageNotifier(notifier: MessageNotifier) {
        hub.notifier = notifier
    }

    /** Whether the network in use is unmetered (Wi-Fi): originals of what this phone shares go up at once then. */
    fun setUnmetered(unmetered: () -> Boolean) {
        hub.unmetered = unmetered
    }

    /** The name to show for a chat: a contact's name, or a group's. */
    fun chatName(chat: String, done: (String) -> Unit) = onWork(done) {
        Group.idOf(chat)?.let { id -> hub.store.group(id)?.name?.takeIf { it.isNotBlank() } } ?: hub.store.contact(chat)?.name ?: chat
    }

    /** [user]'s picture (this user's own: ""), a small WebP with a clear background; null: none. */
    fun pictureOf(user: String, done: (NSData?) -> Unit) = onWork(done) { hub.picture(user)?.toNSData() }

    /** [user]'s face in expression [code] (this user's own: ""); null: none. */
    fun faceImageOf(user: String, code: Int, done: (NSData?) -> Unit) = onWork(done) { hub.face(user, code)?.toNSData() }

    /** Talk's contacts as kept on this phone (not the screens' state, which is empty until it loads). */
    fun storedContacts(done: (List<Contact>) -> Unit) = onWork(done) { hub.store.contacts() }

    /** A push came (or the app woke in the background): connects, takes the queue, sends the outbox, closes again. */
    fun wakeForPush(done: () -> Unit) = onWork({ _: Unit -> done() }) { hub.wake() }

    /** Tells the server this phone's push token (null: the one kept) and whether it shows notifications. */
    fun registerPush(token: String?, notificationsEnabled: Boolean) {
        background.launch { runCatching { hub.registerPush(token, notificationsEnabled) } }
    }

    /** Runs [block] on Messaging's thread and gives [done] its answer on the main thread. */
    private fun <T> onWork(done: (T) -> Unit, block: suspend () -> T) {
        mainScope.launch {
            val result = withContext(messagingWork) { block() }
            done(result)
        }
    }

    fun close() {
        pairing.close()
        messaging.close()
        settingsController.close()
        enrollment.close()
        renewal.close()
        connection.close()
    }
}
