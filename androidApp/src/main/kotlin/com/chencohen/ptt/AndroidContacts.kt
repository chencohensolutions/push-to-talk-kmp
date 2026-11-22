package com.chencohen.ptt

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log
import com.chencohen.ptt.messaging.Contact

/**
 * Talk in Android's Contacts (2026-11-22, hen; Settings › Contacts › Show in Android Contacts, off by default): a
 * "Talk" account holds one entry per paired contact, by the name shown in Talk, with Message, Voice call and Video
 * call, which open Talk. Android joins it with a phone contact of the same name (or the user links them in Contacts).
 * Talk's contacts have no phone numbers, so nothing else is shared; turning it off removes the account and entries.
 */
object AndroidContacts {
    private const val TAG = "AndroidContacts"
    const val ACCOUNT_TYPE = "com.chencohen.ptt"
    const val MESSAGE = "vnd.android.cursor.item/vnd.com.chencohen.ptt.message"
    const val VOICE = "vnd.android.cursor.item/vnd.com.chencohen.ptt.voice"
    const val VIDEO = "vnd.android.cursor.item/vnd.com.chencohen.ptt.video"
    private const val PREFS = "android_contacts"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun account(context: Context) = Account(context.getString(R.string.app_name), ACCOUNT_TYPE)

    fun set(context: Context, on: Boolean, contacts: List<Contact>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        if (on) sync(context, contacts) else remove(context)
    }

    /** The entries as Talk's contacts are now: added, renamed, removed. */
    fun sync(context: Context, contacts: List<Contact>) {
        if (!enabled(context) || !allowed(context)) return
        runCatching { syncNow(context, contacts.filter { it.paired && !it.blocked }) }.onFailure { Log.w(TAG, "sync", it) }
    }

    private fun syncNow(context: Context, contacts: List<Contact>) {
        val account = account(context)
        val manager = AccountManager.get(context)
        if (manager.getAccountsByType(ACCOUNT_TYPE).none { it == account }) manager.addAccountExplicitly(account, null, null)
        val resolver = context.contentResolver
        val raw = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            .build()
        // What is there now: user → (raw id, name).
        val existing = mutableMapOf<String, Pair<Long, String?>>()
        resolver.query(raw, arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.SYNC1), null, null, null)?.use { c ->
            while (c.moveToNext()) existing[c.getString(1) ?: continue] = c.getLong(0) to c.getString(2)
        }
        val ops = ArrayList<ContentProviderOperation>()
        val wanted = contacts.associateBy { it.user }
        for ((user, row) in existing) {
            if (user !in wanted) ops += ContentProviderOperation.newDelete(android.content.ContentUris.withAppendedId(raw, row.first)).build()
        }
        val data = ContactsContract.Data.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        for (contact in contacts) {
            val row = existing[contact.user]
            if (row != null && row.second == contact.name) continue
            if (row != null) ops += ContentProviderOperation.newDelete(android.content.ContentUris.withAppendedId(raw, row.first)).build()
            val back = ops.size
            ops += ContentProviderOperation.newInsert(raw)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(ContactsContract.RawContacts.SOURCE_ID, contact.user)
                .withValue(ContactsContract.RawContacts.SYNC1, contact.name)
                .build()
            ops += ContentProviderOperation.newInsert(data)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.name)
                .build()
            for ((mime, words) in listOf(
                MESSAGE to R.string.contacts_action_message,
                VOICE to R.string.contacts_action_voice,
                VIDEO to R.string.contacts_action_video,
            )) {
                ops += ContentProviderOperation.newInsert(data)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(ContactsContract.Data.MIMETYPE, mime)
                    .withValue(ContactsContract.Data.DATA1, contact.user)
                    .withValue(ContactsContract.Data.DATA2, context.getString(R.string.app_name))
                    .withValue(ContactsContract.Data.DATA3, context.getString(words, contact.name))
                    .build()
            }
        }
        if (ops.isNotEmpty()) resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        Log.i(TAG, "synced ${contacts.size} contacts (${ops.size} changes)")
    }

    private fun remove(context: Context) {
        runCatching {
            val manager = AccountManager.get(context)
            manager.getAccountsByType(ACCOUNT_TYPE).forEach { manager.removeAccountExplicitly(it) }
        }.onFailure { Log.w(TAG, "remove", it) }
    }

    /** What a tap in Contacts asks: the user and Message, Voice or Video; null when it isn't Talk's. */
    fun opened(context: Context, intent: Intent?): Pair<String, String>? {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
        if (uri.authority != ContactsContract.AUTHORITY) return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else (c.getString(1) ?: return@use null) to (c.getString(0) ?: return@use null)
            }
        }.getOrNull()?.takeIf { it.second in listOf(MESSAGE, VOICE, VIDEO) }
    }
}

/** The "Talk" account's authenticator: nothing to sign in to. */
class TalkAuthenticatorService : Service() {
    private val authenticator by lazy {
        object : AbstractAccountAuthenticator(this) {
            override fun editProperties(r: AccountAuthenticatorResponse?, t: String?): Bundle? = null
            override fun addAccount(r: AccountAuthenticatorResponse?, t: String?, a: String?, f: Array<out String>?, o: Bundle?): Bundle? = null
            override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, o: Bundle?): Bundle? = null
            override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
            override fun getAuthTokenLabel(t: String?): String? = null
            override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
            override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, f: Array<out String>?): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}

/** The "Talk" account's sync adapter: Contacts asks it to sync; the entries come from Talk's contacts. */
class TalkContactsSyncService : Service() {
    private val adapter by lazy {
        object : AbstractThreadedSyncAdapter(applicationContext, true) {
            override fun onPerformSync(account: Account?, extras: Bundle?, authority: String?, provider: ContentProviderClient?, syncResult: SyncResult?) {
                AndroidContacts.sync(context, messagingState(context).value.contacts)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = adapter.syncAdapterBinder
}
