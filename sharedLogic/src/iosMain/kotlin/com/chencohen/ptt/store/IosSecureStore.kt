package com.chencohen.ptt.store

import com.chencohen.ptt.ios.IosDb
import com.chencohen.ptt.ios.toByteArray
import com.chencohen.ptt.ios.toNSData

/**
 * [SecureStore] on iOS: the app's own SQLite database, each value sealed with a key the Keychain keeps for this phone
 * only (iosApp/Talk/Host/SealedDb.swift). Used on Messaging's thread only.
 */
internal class IosSecureStore(private val db: IosDb) : SecureStore {
    override fun get(space: String, key: String): ByteArray? = db.get(space, key)?.toByteArray()

    override fun put(space: String, key: String, value: ByteArray) = db.put(space, key, value.toNSData())

    override fun remove(space: String, key: String) = db.remove(space, key)

    override fun keys(space: String): List<String> = db.keys(space)

    override fun clear(space: String) = db.clear(space)

    override fun clearAll() = db.clearAll()
}
