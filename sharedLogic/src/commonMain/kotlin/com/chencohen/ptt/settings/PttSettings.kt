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

    companion object {
        private const val KEY_PENDING_INVITE_PAIR = "pending_invite_pair"
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
