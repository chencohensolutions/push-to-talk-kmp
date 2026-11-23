package com.chencohen.ptt.settings

import com.chencohen.ptt.discovery.KeptDiscovery
import kotlinx.serialization.json.Json

/** Minimal persistent string storage: SharedPreferences on Android, NSUserDefaults on iOS later. */
internal interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** What this phone keeps of its server and of itself (journal's `JournalSettings`, phase 1's part). */
internal class PttSettings(private val store: KeyValueStore) {
    /** The GraphQL endpoint in use, `https://host:port/query`; the one that answered last. Empty before enrolling. */
    var serverUrl: String
        get() = store.getString(KEY_SERVER_URL) ?: ""
        set(value) = store.putString(KEY_SERVER_URL, value)

    /**
     * Who the server is and where (`serverInfo.discovery`, or the enrollment QR code); null before a server told it.
     * Its fingerprint is pinned by the mTLS clients once kept.
     */
    var serverDiscovery: KeptDiscovery?
        get() = store.getString(KEY_SERVER_DISCOVERY)?.let { runCatching { json.decodeFromString(KeptDiscovery.serializer(), it) }.getOrNull() }
        set(value) = store.putString(KEY_SERVER_DISCOVERY, value?.let { json.encodeToString(KeptDiscovery.serializer(), it) })

    /** The server key's fingerprint the mTLS clients pin; null: they check the CA and the host name. */
    val serverFingerprint: String? get() = serverDiscovery?.fingerprint

    /**
     * The CA that signs the server's certificates, PEM (`serverInfo.caCertificate`): each server has its own. Once
     * kept, the mTLS clients accept a server certificate that chains to it, at any address, besides the pinned key.
     */
    var serverCa: String?
        get() = store.getString(KEY_SERVER_CA)
        set(value) = store.putString(KEY_SERVER_CA, value)

    /** Name of the client identity used for mTLS (the Android Keystore alias). */
    var identityAlias: String?
        get() = store.getString(KEY_IDENTITY_ALIAS)
        set(value) = store.putString(KEY_IDENTITY_ALIAS, value)

    /** When this device's certificate expires (epoch milliseconds); null when not known. */
    var certificateExpiresAt: Long?
        get() = store.getString(KEY_CERTIFICATE_EXPIRES_AT)?.toLongOrNull()
        set(value) = store.putString(KEY_CERTIFICATE_EXPIRES_AT, value?.toString())

    /** This device as the server knows it (enrollment, then `me`); null before enrolling. */
    var device: EnrolledDevice?
        get() = store.getString(KEY_DEVICE)?.let { runCatching { json.decodeFromString(EnrolledDevice.serializer(), it) }.getOrNull() }
        set(value) = store.putString(KEY_DEVICE, value?.let { json.encodeToString(EnrolledDevice.serializer(), it) })

    /** The server's version and build, from its last `serverInfo`; null before it answered. */
    /** serverInfo.keepsCalls: the server keeps a call while someone in it records (Record shows only then). */
    var serverKeepsCalls: Boolean
        get() = store.getString(KEY_KEEPS_CALLS) == "true"
        set(value) = store.putString(KEY_KEEPS_CALLS, value.toString())

    var serverVersion: String?
        get() = store.getString(KEY_SERVER_VERSION)
        set(value) = store.putString(KEY_SERVER_VERSION, value)

    /**
     * The inviter's Add contact code (PairCode.format) after enrolling with an invite link: the phone pairs with them
     * once its keys are on the server (Messaging.start), then forgets it. Null otherwise.
     */
    var pendingInvitePair: String?
        get() = store.getString(KEY_PENDING_INVITE_PAIR)
        set(value) = store.putString(KEY_PENDING_INVITE_PAIR, value)

    /**
     * The Link a phone code (LinkCode.format) this phone enrolled with: Messaging asks that phone for the identity key
     * once this one is connected, then forgets it. Null otherwise.
     */
    /** This user shows contacts when they're online (setPresenceVisible), as last set from this phone. */
    var presenceVisible: Boolean
        get() = store.getString(KEY_PRESENCE_VISIBLE) != "false"
        set(value) = store.putString(KEY_PRESENCE_VISIBLE, value.toString())

    /** This phone's FCM registration token, as Firebase last gave it; null before. */
    var pushToken: String?
        get() = store.getString(KEY_PUSH_TOKEN)
        set(value) = store.putString(KEY_PUSH_TOKEN, value)

    /** What the server was last told by registerPush ("device|token|notifications"): told again only when it changes. */
    var pushRegistered: String?
        get() = store.getString(KEY_PUSH_REGISTERED)
        set(value) = store.putString(KEY_PUSH_REGISTERED, value)

    /** Settings › Talk › Catch up to live (a `CatchUpMode` name); null: the default. */
    var catchUp: String?
        get() = store.getString(KEY_CATCH_UP)
        set(value) = store.putString(KEY_CATCH_UP, value)

    /** Settings › Talk › Stay connected: a foreground service keeps /ws open. */
    var stayConnected: Boolean
        get() = store.getString(KEY_STAY_CONNECTED) == "true"
        set(value) = store.putString(KEY_STAY_CONNECTED, value.toString())

    /** The conversation hardware buttons talk in (a user, or a group's chat); null: none chosen. */
    var defaultTalkChat: String?
        get() = store.getString(KEY_DEFAULT_TALK)?.takeIf { it.isNotEmpty() }
        set(value) = store.putString(KEY_DEFAULT_TALK, value)

    /** Settings › Talk › Hardware buttons: a held volume key or a headset button talks. */
    var hardwareButtons: Boolean
        get() = store.getString(KEY_HARDWARE_BUTTONS) == "true"
        set(value) = store.putString(KEY_HARDWARE_BUTTONS, value.toString())

    /** Settings › Calls › Save calls (a `SaveCalls` name); null: Off. */
    var saveCalls: String?
        get() = store.getString(KEY_SAVE_CALLS)
        set(value) = store.putString(KEY_SAVE_CALLS, value)

    /** The chats set to When I'm away: Notify only (the rest play aloud). */
    var notifyOnlyChats: Set<String>
        get() = store.getString(KEY_NOTIFY_ONLY)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }?.toSet().orEmpty()
        set(value) = store.putString(KEY_NOTIFY_ONLY, json.encodeToString(value.sorted()))

    /** The chats marked Quick talk, in the order marked (the Talk tab's pages). */
    var quickTalkChats: List<String>
        get() = store.getString(KEY_QUICK_TALK)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
        set(value) = store.putString(KEY_QUICK_TALK, json.encodeToString(value))

    /** The Talk tab's page shown last. */
    var quickTalkPage: String?
        get() = store.getString(KEY_QUICK_TALK_PAGE)?.takeIf { it.isNotEmpty() }
        set(value) = store.putString(KEY_QUICK_TALK_PAGE, value)

    /** Settings › Talk › Open Talk when someone talks to me (off unless turned on). */
    var openOnTalk: Boolean
        get() = store.getString(KEY_OPEN_ON_TALK) == "true"
        set(value) = store.putString(KEY_OPEN_ON_TALK, value.toString())

    /** Settings › Talk › Reply bubble over other apps (off unless turned on). */
    var replyBubble: Boolean
        // On unless turned off (2026-11-22, hen: "floating window as default").
        get() = defaultsV2().let { store.getString(KEY_REPLY_BUBBLE) != "false" }
        set(value) = store.putString(KEY_REPLY_BUBBLE, value.toString())

    /** Settings › Calls › Video: CallVideo's name; null (never chosen): H.265 where the phone has an encoder for it. */
    var callVideo: String?
        get() = defaultsV2().let { store.getString(KEY_CALL_VIDEO) }
        set(value) = store.putString(KEY_CALL_VIDEO, value)

    /**
     * Once, for phones from before the floating window and H.265 became the defaults (2026-11-22, hen): every setting
     * used to be written whenever one changed, so a stored "off" or "VP8" there was most likely never chosen; they
     * go back to unset. Since then only a setting that changes is written.
     */
    private fun defaultsV2() {
        if (store.getString(KEY_DEFAULTS) == "2") return
        if (store.getString(KEY_REPLY_BUBBLE) == "false") store.putString(KEY_REPLY_BUBBLE, null)
        if (store.getString(KEY_CALL_VIDEO) == "VP8") store.putString(KEY_CALL_VIDEO, null)
        store.putString(KEY_DEFAULTS, "2")
    }

    /** Push-to-video (off unless turned on on a Talk screen), and the reply bubble showing video (on unless off). */
    var pushVideo: Boolean
        get() = store.getString(KEY_PUSH_VIDEO) == "true"
        set(value) = store.putString(KEY_PUSH_VIDEO, value.toString())
    var bubbleVideo: Boolean
        // On unless turned off (2026-11-22, hen: a received video plays by default, in the bubble too).
        get() = store.getString(KEY_BUBBLE_VIDEO) != "false"
        set(value) = store.putString(KEY_BUBBLE_VIDEO, value.toString())

    /** Settings › Talk › Unmute for incoming talk: on unless turned off (2026-11-22, hen). */
    var unmuteForTalk: Boolean
        get() = store.getString(KEY_UNMUTE_FOR_TALK) != "false"
        set(value) = store.putString(KEY_UNMUTE_FOR_TALK, value.toString())

    /** Settings › Calls › Ringtone: Talk's soft chime unless the phone's ringtone was chosen. */
    var softRing: Boolean
        get() = store.getString(KEY_SOFT_RING) != "false"
        set(value) = store.putString(KEY_SOFT_RING, value.toString())

    /** Settings › Notifications › Message sound: Talk's soft chime unless the phone's sound was chosen. */
    var softMessage: Boolean
        get() = store.getString(KEY_SOFT_MESSAGE) != "false"
        set(value) = store.putString(KEY_SOFT_MESSAGE, value.toString())

    /** Settings › Calls › Advanced › 60 fps video: off unless turned on. */
    var video60: Boolean
        get() = store.getString(KEY_VIDEO_60) == "true"
        set(value) = store.putString(KEY_VIDEO_60, value.toString())

    /** Call settings › Advanced › Show video stats: off unless turned on. */
    var videoStats: Boolean
        get() = store.getString(KEY_VIDEO_STATS) == "true"
        set(value) = store.putString(KEY_VIDEO_STATS, value.toString())

    /** Settings › Talk › Quick reply window: on unless turned off. */
    var quickReply: Boolean
        get() = store.getString(KEY_QUICK_REPLY) != "false"
        set(value) = store.putString(KEY_QUICK_REPLY, value.toString())

    /** This user's email, shared with their paired contacts (Settings › Contacts › Your email); null: none. */
    var myEmail: String?
        get() = store.getString(KEY_MY_EMAIL)
        set(value) = store.putString(KEY_MY_EMAIL, value)

    var pendingLink: String?
        get() = store.getString(KEY_PENDING_LINK)
        set(value) = store.putString(KEY_PENDING_LINK, value)

    companion object {
        private const val KEY_PENDING_INVITE_PAIR = "pending_invite_pair"
        private const val KEY_PENDING_LINK = "pending_link"
        private const val KEY_QUICK_TALK = "talk_quick_chats"
        private const val KEY_KEEPS_CALLS = "server_keeps_calls"
        private const val KEY_OPEN_ON_TALK = "talk_open_on_talk"
        private const val KEY_REPLY_BUBBLE = "talk_reply_bubble"
        private const val KEY_DEFAULTS = "defaults_version"
        private const val KEY_CALL_VIDEO = "call_video"
        private const val KEY_MY_EMAIL = "my_email"
        private const val KEY_PUSH_VIDEO = "talk_push_video"
        private const val KEY_BUBBLE_VIDEO = "talk_bubble_video"
        private const val KEY_UNMUTE_FOR_TALK = "unmute_for_talk"
        private const val KEY_SOFT_RING = "call_soft_ring"
        private const val KEY_SOFT_MESSAGE = "message_soft_sound"
        private const val KEY_VIDEO_60 = "video_60fps"
        private const val KEY_VIDEO_STATS = "video_stats"
        private const val KEY_QUICK_REPLY = "quick_reply"
        private const val KEY_QUICK_TALK_PAGE = "talk_quick_page"
        private const val KEY_CATCH_UP = "talk_catch_up"
        private const val KEY_STAY_CONNECTED = "talk_stay_connected"
        private const val KEY_DEFAULT_TALK = "talk_default_chat"
        private const val KEY_HARDWARE_BUTTONS = "talk_hardware_buttons"
        private const val KEY_NOTIFY_ONLY = "talk_notify_only"
        private const val KEY_SAVE_CALLS = "save_calls"
        private const val KEY_PUSH_TOKEN = "push_token"
        private const val KEY_PRESENCE_VISIBLE = "presence_visible"
        private const val KEY_PUSH_REGISTERED = "push_registered"
        private val json = Json { ignoreUnknownKeys = true }
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_DISCOVERY = "server_discovery"
        private const val KEY_SERVER_CA = "server_ca"
        private const val KEY_IDENTITY_ALIAS = "identity_alias"
        private const val KEY_CERTIFICATE_EXPIRES_AT = "certificate_expires_at"
        private const val KEY_DEVICE = "device"
        private const val KEY_SERVER_VERSION = "server_version"
    }
}

/** This device as its server knows it: whose it is, its number (1 to 127) and its name. */
@kotlinx.serialization.Serializable
data class EnrolledDevice(
    val user: String,
    val deviceNumber: Int,
    val deviceName: String,
)
