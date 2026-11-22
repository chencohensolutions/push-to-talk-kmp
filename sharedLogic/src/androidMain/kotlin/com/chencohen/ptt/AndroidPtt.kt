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
import kotlinx.coroutines.launch
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

/** Push-to-video's camera, for the Talk screen's preview. */
var talkVideoCapture: com.chencohen.ptt.talk.AndroidTalkVideoCapture? = null
    private set

private fun hasDecoder(mime: String): Boolean = runCatching {
    android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }
}.getOrDefault(false)

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
        com.chencohen.ptt.talk.TalkAudio.appContext = context.applicationContext
        it.talk.player = com.chencohen.ptt.talk.AndroidTalkPlayer()
        // The live path (phase 5b): LiveKit over the server's /rtc, with the same mTLS client as the rest.
        val mtls = AndroidMtlsHttpClientFactory(context)
        it.live.transport = com.chencohen.ptt.talk.LiveKitTalkTransport(context) { mtls.okHttpClient(settings.identityAlias, emptyList()) }
        // Push-to-video: this phone's camera, and where bursts' video shows; and what it can play, said to the others.
        it.talk.videoCapture = com.chencohen.ptt.talk.AndroidTalkVideoCapture(context) {
            com.chencohen.ptt.talk.AndroidTalkVideoCapture.hasEncoder(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC) && it.videoH265For(it.talk.videoChat)
        }.also { capture -> talkVideoCapture = capture }
        it.talk.videoOut = com.chencohen.ptt.talk.AndroidTalkVideoOut
        com.chencohen.ptt.messaging.ContentCodec.myCaps = com.chencohen.ptt.messaging.ContentCodec.CAPS_SAID or
            (if (hasDecoder(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC)) com.chencohen.ptt.messaging.ContentCodec.CAPS_H265 else 0)
        it.calls.transport = com.chencohen.ptt.calls.LiveKitCallTransport(
            context,
            videoCodec = { if (settings.callVideo == com.chencohen.ptt.calls.CallVideo.H265.name && com.chencohen.ptt.calls.LiveKitCallTransport.hasHevcEncoder()) "h265" else "vp8" },
        ) { mtls.okHttpClient(settings.identityAlias, emptyList()) }
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

/**
 * The reply window (docs/screens/notifications.md, "Talk"): for 30 s after a burst played in the background, a
 * hardware talk button talks back to [replyTo] instead of the default conversation.
 */
object TalkReply {
    @Volatile
    var replyTo: String? = null

    @Volatile
    var until: Long = 0

    fun open(chat: String, now: Long = System.currentTimeMillis()) {
        replyTo = chat
        until = now + 30_000
    }

    fun chat(now: Long = System.currentTimeMillis()): String? = replyTo?.takeIf { now < until }
}

/** Talk from a hardware button: [down] pressed, or let go; in the reply window to its sender, else the default conversation. */
suspend fun talkButton(context: Context, down: Boolean): Boolean {
    val messaging = hub(context)
    return withContext(messagingWork) {
        if (!down) {
            if (messaging.talk.state.value.own == null) return@withContext false
            messaging.talk.release(true)
            return@withContext true
        }
        val chat = TalkReply.chat() ?: messaging.talkSettings().takeIf { it.hardwareButtons }?.defaultChat ?: return@withContext false
        messaging.talk.press(chat)
        true
    }
}

/** Talk back from a notification: start talking in [chat], or end it ([send] false drops it). */
suspend fun talkBack(context: Context, chat: String?, down: Boolean, send: Boolean = true) {
    val messaging = hub(context)
    // Connected for the burst (the wake-up's own /ws may have closed), without waiting for it here.
    if (down) kotlinx.coroutines.CoroutineScope(messagingWork).launch { messaging.wake() }
    withContext(messagingWork) {
        if (down) chat?.let { messaging.talk.press(it) } else messaging.talk.release(send)
    }
}

/**
 * Talk from the car (Android Auto): [capture] (the car's microphone) for this burst in [chat]; [down] false ends it
 * ([send] false drops it) and puts the phone's microphone back.
 */
suspend fun carTalk(context: Context, chat: String?, capture: com.chencohen.ptt.talk.TalkCapture?, down: Boolean, send: Boolean = true) {
    val messaging = hub(context)
    if (down) kotlinx.coroutines.CoroutineScope(messagingWork).launch { messaging.wake() }
    withContext(messagingWork) {
        if (down) {
            messaging.talk.captureOverride = capture
            chat?.let { messaging.talk.press(it) }
        } else {
            messaging.talk.release(send)
            messaging.talk.captureOverride = null
        }
    }
}

/** Stop from the playing notification: what plays, and what waits. */
suspend fun stopTalkPlayback(context: Context) {
    val messaging = hub(context)
    withContext(messagingWork) { messaging.talk.stopPlaying() }
}

/** Messaging's state, for the background talk notifications. */
/** Changes Talk's settings from outside the screens (the reply bubble's Video). */
fun setTalkSettings(context: Context, talk: com.chencohen.ptt.messaging.TalkSettings) = hub(context).setTalkSettings(talk)

fun messagingState(context: Context): kotlinx.coroutines.flow.StateFlow<com.chencohen.ptt.messaging.MessagingState> = hub(context).state

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

/** Nothing is going on that an app update would cut off: no call, talk or live room (AppUpdate.quiet). */
fun talkQuiet(context: Context): Boolean = com.chencohen.ptt.update.AppUpdate.quiet(hub(context).state.value)

/**
 * The conversations a talk widget can be set up for (chat key and name), Quick talk ones first: paired contacts not
 * blocked, and groups this user reads (TalkWidgetChoices).
 */
suspend fun talkWidgetChoices(context: Context): List<Pair<String, String>> {
    val messaging = hub(context)
    val me = settings(context).device?.user.orEmpty()
    return withContext(messagingWork) {
        val contacts = messaging.store.contacts().filter { it.paired && !it.blocked }
            .map { it.user to (it.displayName?.takeIf { n -> n.isNotBlank() } ?: it.user) }
        val groups = messaging.store.groups().filter { !it.gone && it.isReader(me) }.map { it.chat to it.name.ifBlank { it.chat } }
        com.chencohen.ptt.talk.TalkWidgetChoices.order(contacts + groups, messaging.talkSettings().quickTalk)
    }
}

/** This phone's user name (empty before enrolling). */
fun myUser(context: Context): String = settings(context).device?.user.orEmpty()
