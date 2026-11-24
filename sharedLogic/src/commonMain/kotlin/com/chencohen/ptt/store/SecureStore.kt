package com.chencohen.ptt.store

/**
 * What the phone keeps of its keys, sessions, contacts and messages, encrypted at rest: on Android one SQLCipher
 * database whose key is wrapped by an Android Keystore key (`SqlCipherStore`). It is a set of named spaces of keys and
 * byte values, so the shared code and the libsignal stores read and write the same few calls and the tests run on
 * [MemoryStore]. The plan named SQLDelight over SQLCipher; one key-value table needs no code generator, and the
 * history of phase 3 can still move to tables of its own in the same database.
 */
interface SecureStore {
    fun get(space: String, key: String): ByteArray?

    fun put(space: String, key: String, value: ByteArray)

    fun remove(space: String, key: String)

    /** The keys of [space], sorted. */
    fun keys(space: String): List<String>

    fun clear(space: String)

    /** Empties every space. */
    fun clearAll()
}

/** The spaces in use: the libsignal state (written by the platform engine) and the shared code's own. */
object Spaces {
    /** This phone's identity key pair and registration id. */
    const val IDENTITY = "identity"
    const val PREKEYS = "prekeys"
    const val SIGNED_PREKEYS = "signed_prekeys"
    const val KYBER_PREKEYS = "kyber_prekeys"
    /** Which Kyber prekeys are last-resort ones (kept after use). */
    const val KYBER_LAST_RESORT = "kyber_last_resort"
    /** Base keys seen with a last-resort Kyber prekey: a second message with the same one is refused. */
    const val KYBER_BASE_KEYS = "kyber_base_keys"
    const val SESSIONS = "sessions"
    /** The identity key trusted for each user. */
    const val IDENTITIES = "identities"
    const val SENDER_KEYS = "sender_keys"

    /** Shared code: counters and flags (MessagingStore). */
    const val META = "meta"
    const val CONTACTS = "contacts"
    /** The secrets of the Add contact codes this phone showed. */
    const val INVITES = "invites"
    /** Pair requests waiting for the user. */
    const val REQUESTS = "requests"
    const val MESSAGES = "messages"
    /** Linking a phone: the Link a phone codes this phone showed, or the one it scanned to join (MessagingStore). */
    const val LINKS = "links"
    /** Groups, by id (MessagingStore). */
    const val GROUPS = "groups"
    /** Pictures (2026-11-24, hen): each user's own (this user's under ""), its id, and which id each chat was sent. */
    const val PICTURES = "pictures"
    const val PICTURE_IDS = "picture_ids"
    const val PICTURE_SENT = "picture_sent"
}

/** A [SecureStore] in memory: the tests', and a fallback that keeps nothing. */
class MemoryStore : SecureStore {
    private val spaces = mutableMapOf<String, MutableMap<String, ByteArray>>()

    override fun get(space: String, key: String): ByteArray? = spaces[space]?.get(key)?.copyOf()

    override fun put(space: String, key: String, value: ByteArray) {
        spaces.getOrPut(space) { mutableMapOf() }[key] = value.copyOf()
    }

    override fun remove(space: String, key: String) {
        spaces[space]?.remove(key)
    }

    override fun keys(space: String): List<String> = spaces[space]?.keys?.sorted().orEmpty()

    override fun clear(space: String) {
        spaces.remove(space)
    }

    override fun clearAll() = spaces.clear()
}
