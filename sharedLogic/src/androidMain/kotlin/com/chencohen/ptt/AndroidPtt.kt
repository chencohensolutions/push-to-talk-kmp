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
import kotlinx.coroutines.withContext
import kotlin.random.asKotlinRandom
import com.chencohen.ptt.messaging.MessageNotifier
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

/** The encrypted database: one per process, opened on [messagingWork] at first use. */
private var secureStore: SqlCipherStore? = null

/** The one hub of the process: the screens' controllers and the push service share it. */
private var hub: Messaging? = null

/** What a revoked device does: the screens' connection checks again, so home shows Register again. */
@Volatile
private var onRevokedNow: () -> Unit = {}

/**
 * Keys, /ws, pairing and contacts, for the screens' two controllers: the process's one hub, with [connection] told
 * when the device is revoked (close code 4003).
 */
private fun messaging(context: Context, connection: ConnectionController): Messaging {
    onRevokedNow = { connection.connect() }
    return hub(context)
}

@Synchronized
private fun hub(context: Context): Messaging {
    hub?.let { return it }
    val settings = settings(context)
    val http = serverHttp(context)
    val store = secureStore ?: SqlCipherStore(context.applicationContext).also { secureStore = it }
    return Messaging(
        settings = settings,
        engine = LibsignalCryptoEngine(store) { settings.device?.let { DeviceAddress(it.user, it.deviceNumber) } },
        store = MessagingStore(store),
        api = ApolloMessagingApi(http),
        connector = KtorFrameConnector { http.client() },
        scope = CoroutineScope(SupervisorJob() + messagingWork),
        now = { System.currentTimeMillis() },
        // Pair, invite and link secrets, clip keys: from the system's secure generator.
        random = java.security.SecureRandom().asKotlinRandom(),
        onRevoked = { onRevokedNow() },
        clipFiles = com.chencohen.ptt.talk.AndroidClipFiles(context.applicationContext),
        blobApi = com.chencohen.ptt.talk.KtorBlobApi(http),
    ).also {
        it.talk.capture = com.chencohen.ptt.talk.AndroidTalkCapture(com.chencohen.ptt.talk.SpeakerphoneMic(context))
        it.talk.player = com.chencohen.ptt.talk.AndroidTalkPlayer()
        // The live path (phase 5b): LiveKit over the server's /rtc, with the same mTLS client as the rest.
        val mtls = AndroidMtlsHttpClientFactory(context)
        it.live.transport = com.chencohen.ptt.talk.LiveKitTalkTransport(context) { mtls.okHttpClient(settings.identityAlias, emptyList()) }
        it.calls.transport = com.chencohen.ptt.calls.LiveKitCallTransport(context) { mtls.okHttpClient(settings.identityAlias, emptyList()) }
        hub = it
    }
}

// --- For the push service and the notifications (androidApp), which run without a screen -----------------------

/** Who shows notifications for texts that come in, and takes them down when read. */
fun setMessageNotifier(context: Context, notifier: MessageNotifier) {
    hub(context).notifier = notifier
}

/** Who plays talk when the app isn't on screen (a Telecom talk session) and shows talk notifications. */
fun setTalkHost(context: Context, host: com.chencohen.ptt.talk.TalkHost) {
    hub(context).talk.host = host
}

/** Who rings and keeps the call session (phase 6). */
fun setCallHost(context: Context, host: com.chencohen.ptt.calls.CallHost) {
    hub(context).calls.host = host
}

/** The call room, for the call screen's video. */
fun callTransport(context: Context): com.chencohen.ptt.calls.LiveKitCallTransport =
    hub(context).calls.transport as com.chencohen.ptt.calls.LiveKitCallTransport

/** Answer, Decline or Hang up from the call's notification or Telecom (a headset button). */
suspend fun callAction(context: Context, action: String) {
    val messaging = hub(context)
    withContext(messagingWork) {
        when (action) {
            "accept" -> messaging.calls.accept()
            "decline" -> messaging.calls.decline()
            "hang_up" -> messaging.calls.hangUp()
            "mute" -> messaging.calls.setMicrophone(false)
            "unmute" -> messaging.calls.setMicrophone(true)
        }
    }
}

/** Stay connected's service started or stopped. */
suspend fun keepConnected(context: Context, on: Boolean) {
    val messaging = hub(context)
    withContext(messagingWork) { messaging.keepConnected(on) }
}

/** Stay connected turned off from its notification. */
suspend fun setStayConnectedSetting(context: Context, on: Boolean) {
    val messaging = hub(context)
    withContext(messagingWork) { messaging.setTalkSettings(messaging.talkSettings().copy(stayConnected = on)) }
}

/** Talk in the default conversation from a hardware button: [down] pressed, or let go. */
suspend fun talkButton(context: Context, down: Boolean): Boolean {
    val messaging = hub(context)
    return withContext(messagingWork) {
        val chat = messaging.talkSettings().takeIf { it.hardwareButtons }?.defaultChat ?: return@withContext false
        if (down) messaging.talk.press(chat) else messaging.talk.release(true)
        true
    }
}

/** A talk notification's Play: the burst from the chat's history. */
suspend fun playClip(context: Context, id: String) {
    val messaging = hub(context)
    withContext(messagingWork) { messaging.talk.play(id) }
}

/** The name to show for a chat: a contact's name, or a group's. */
suspend fun chatName(context: Context, chat: String): String {
    val messaging = hub(context)
    return withContext(messagingWork) {
        com.chencohen.ptt.messaging.Group.idOf(chat)?.let { id -> messaging.store.group(id)?.name?.takeIf { it.isNotBlank() } }
            ?: messaging.store.contact(chat)?.let { it.displayName ?: it.user } ?: chat
    }
}

/** A push came: connects, takes the queue, sends the outbox, and closes again unless the app is on screen. */
suspend fun wakeForPush(context: Context) {
    val messaging = hub(context)
    withContext(messagingWork) { messaging.wake() }
}

/** Tells the server this phone's FCM token (null: the one kept) and whether it shows notifications, if that changed. */
suspend fun registerPush(context: Context, token: String?, notificationsEnabled: Boolean) {
    val messaging = hub(context)
    withContext(messagingWork) { messaging.registerPush(token, notificationsEnabled) }
}

/** A reply typed in a notification: read, sent (or kept in the outbox), with a connection opened for it. */
suspend fun replyFromNotification(context: Context, user: String, body: String) {
    val messaging = hub(context)
    withContext(messagingWork) {
        messaging.markRead(user)
        messaging.sendText(user, body)
        messaging.wake()
    }
}

/** Mark as read, from a notification. */
suspend fun markReadFromNotification(context: Context, user: String) {
    val messaging = hub(context)
    withContext(messagingWork) {
        messaging.markRead(user)
        messaging.wake()
    }
}

/** Keys, /ws, contacts and pair requests (docs/screens/contacts.md). */
fun createMessagingController(context: Context, connection: ConnectionController): MessagingController =
    MessagingController(messaging(context, connection), messagingWork)

/** The Add contact screen: this phone's code and scanning theirs (docs/screens/add-contact.md). */
fun createPairingController(context: Context, connection: ConnectionController): PairingController =
    PairingController(messaging(context, connection), messagingWork, now = { System.currentTimeMillis() })

/**
 * The latest app release the server read from GitHub (`appRelease`; docs/design.md, "App updates"); null when the
 * server has none, or this phone isn't enrolled. Throws when the server can't be reached.
 */
suspend fun appRelease(context: Context): com.chencohen.ptt.update.AppRelease? {
    val response = serverHttp(context).graphql { it.query(com.chencohen.ptt.graphql.AppReleaseQuery()).execute() } ?: return null
    response.exception?.let { throw it }
    response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
    val r = response.data?.appRelease ?: return null
    return com.chencohen.ptt.update.AppRelease(r.version, r.apkUrl, r.size.toLong(), r.sha256, r.notes.orEmpty())
}
