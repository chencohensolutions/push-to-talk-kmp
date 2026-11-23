package com.chencohen.ptt

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.chencohen.ptt.messaging.Gender

/**
 * Strings about a contact in their own Hebrew form (2026-11-23, hen): a string `x` may have `x_m`, `x_f` (and `x_many`
 * for several people) beside it; the contact's gender (shared from their phone) picks one, else the neutral `x`.
 * English has the same names, all the same text.
 */
object Gendered {
    fun genderOf(context: Context, user: String?): Int =
        user?.let { u -> messagingState(context).value.contacts.firstOrNull { it.user == u }?.theirGender } ?: Gender.NOT_SET

    fun id(context: Context, @StringRes neutral: Int, suffix: String?): Int {
        if (suffix == null) return neutral
        val name = context.resources.getResourceEntryName(neutral)
        val alt = context.resources.getIdentifier(name + suffix, "string", context.packageName)
        return if (alt != 0) alt else neutral
    }

    /** [neutral] in [user]'s form, formatted with [args]. */
    fun string(context: Context, @StringRes neutral: Int, user: String?, vararg args: Any): String =
        context.getString(id(context, neutral, Gender.suffix(genderOf(context, user))), *args)

    /** [neutral] for several people ([users]): the plural form, or one person's. */
    fun stringFor(context: Context, @StringRes neutral: Int, users: List<String>, vararg args: Any): String =
        context.getString(id(context, neutral, Gender.suffixFor(users.map { genderOf(context, it) })), *args)

    /** How the app addresses this user (Android 14+: Android's grammatical gender for the app's own strings). */
    fun applyMine(context: Context, gender: Int) {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val manager = context.getSystemService(android.app.GrammaticalInflectionManager::class.java) ?: return
        val want = when (gender) {
            Gender.MALE -> android.content.res.Configuration.GRAMMATICAL_GENDER_MASCULINE
            Gender.FEMALE -> android.content.res.Configuration.GRAMMATICAL_GENDER_FEMININE
            else -> android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED
        }
        if (manager.applicationGrammaticalGender != want) manager.setRequestedApplicationGrammaticalGender(want)
    }
}

/** [neutral] in [user]'s form (Gendered), in a composable. */
@Composable
internal fun genderedString(@StringRes neutral: Int, user: String?, vararg args: Any): String {
    val context = LocalContext.current
    return Gendered.string(context, neutral, user, *args)
}
