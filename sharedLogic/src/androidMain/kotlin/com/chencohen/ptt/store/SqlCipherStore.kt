package com.chencohen.ptt.store

import android.content.ContentValues
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.chencohen.ptt.enrollment.ANDROID_KEY_STORE
import com.chencohen.ptt.enrollment.androidKeyStore
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/**
 * [SecureStore] in one SQLCipher database, `ptt.db` in the app's databases folder (no backup, no transfer:
 * data_extraction_rules.xml). Its key is 32 random bytes, kept wrapped by an AES key in the Android Keystore
 * ([DatabaseKey]); the database is opened with it as a raw key, so no key derivation runs at each start. A database
 * whose key is lost (the Keystore was cleared) can't be read, and is replaced by an empty one: this phone then starts
 * over with a new identity key, as a lost phone does.
 *
 * Opened on first use, on the thread that uses it (Messaging's, never the main thread). Not thread-safe by itself.
 */
internal class SqlCipherStore(private val context: Context) : SecureStore {
    private val db: SQLiteDatabase by lazy { open() }

    private fun open(): SQLiteDatabase {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(FILE)
        file.parentFile?.mkdirs()
        val key = DatabaseKey(context)
        return try {
            openWith(file, key.get())
        } catch (e: Exception) {
            Log.w("Talk", "The encrypted database can't be opened; starting a new one", e)
            SQLiteDatabase.deleteDatabase(file)
            openWith(file, key.renew())
        }
    }

    private fun openWith(file: File, key: ByteArray): SQLiteDatabase {
        val raw = ("x'" + key.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + "'").encodeToByteArray()
        val db = SQLiteDatabase.openOrCreateDatabase(file, raw, null, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (space TEXT NOT NULL, k TEXT NOT NULL, v BLOB NOT NULL, PRIMARY KEY (space, k))")
        return db
    }

    override fun get(space: String, key: String): ByteArray? =
        db.rawQuery("SELECT v FROM kv WHERE space = ? AND k = ?", arrayOf<String>(space, key)).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }

    override fun put(space: String, key: String, value: ByteArray) {
        val values = ContentValues().apply {
            put("space", space)
            put("k", key)
            put("v", value)
        }
        db.insertWithOnConflict("kv", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun remove(space: String, key: String) {
        db.delete("kv", "space = ? AND k = ?", arrayOf<String>(space, key))
    }

    override fun keys(space: String): List<String> =
        db.rawQuery("SELECT k FROM kv WHERE space = ? ORDER BY k", arrayOf<String>(space)).use { c ->
            val keys = ArrayList<String>(c.count)
            while (c.moveToNext()) keys += c.getString(0)
            keys
        }

    override fun entries(space: String): List<Pair<String, ByteArray>> =
        db.rawQuery("SELECT k, v FROM kv WHERE space = ? ORDER BY k", arrayOf<String>(space)).use { c ->
            val entries = ArrayList<Pair<String, ByteArray>>()
            while (c.moveToNext()) entries += c.getString(0) to c.getBlob(1)
            entries
        }

    override fun clear(space: String) {
        db.delete("kv", "space = ?", arrayOf<String>(space))
    }

    override fun clearAll() {
        db.execSQL("DELETE FROM kv")
    }

    private companion object {
        const val FILE = "ptt.db"
    }
}

/**
 * The database key, wrapped by the Keystore's AES-256-GCM key `ptt-database` (which never leaves the secure
 * hardware), kept in the app's private preferences as base64 of the nonce and the ciphertext.
 */
internal class DatabaseKey(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ptt_database", Context.MODE_PRIVATE)

    fun get(): ByteArray {
        val wrapped = prefs.getString(PREF, null) ?: return renew()
        val bytes = Base64.decode(wrapped)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(create = false) ?: throw IllegalStateException("The database key's key is gone"), GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    /** A new random key, wrapped and kept. */
    fun renew(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
        val sealed = cipher.iv + cipher.doFinal(key)
        prefs.edit().putString(PREF, Base64.encode(sealed)).commit()
        return key
    }

    private fun wrappingKey(create: Boolean): SecretKey? {
        (androidKeyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "ptt-database"
        const val PREF = "wrapped_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
