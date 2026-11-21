package com.chencohen.ptt.settings

import android.content.Context
import androidx.core.content.edit

internal class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("ptt_prefs", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit { if (value == null) remove(key) else putString(key, value) }
    }
}
