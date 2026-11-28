package com.chencohen.ptt.settings

import platform.Foundation.NSUserDefaults

/** [KeyValueStore] in the app's NSUserDefaults (Android's SharedPreferencesStore), under a prefix of its own. */
internal class UserDefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(PREFIX + key)

    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(PREFIX + key) else defaults.setObject(value, PREFIX + key)
    }

    private companion object {
        const val PREFIX = "ptt."
    }
}
