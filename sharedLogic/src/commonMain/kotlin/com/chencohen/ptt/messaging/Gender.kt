package com.chencohen.ptt.messaging

/**
 * How the app refers to someone in Hebrew (2026-11-23, hen): each user picks theirs (Settings › Profile), shared with
 * contacts end to end (ProfileContent), so others see "כותב" or "כותבת"; not set keeps the slash form ("כותב/ת").
 */
object Gender {
    const val NOT_SET = 0
    const val MALE = 1
    const val FEMALE = 2

    /** The string variant's suffix for one person: "_m", "_f", or none (the neutral string). */
    fun suffix(gender: Int): String? = when (gender) {
        MALE -> "_m"
        FEMALE -> "_f"
        else -> null
    }

    /** For several people (a group's typers): the plural ("_many"); for one, theirs. */
    fun suffixFor(genders: List<Int>): String? = when {
        genders.size > 1 -> "_many"
        genders.size == 1 -> suffix(genders[0])
        else -> null
    }
}
