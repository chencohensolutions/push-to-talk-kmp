package com.chencohen.ptt.face

/**
 * A face's expression (2026-11-24, hen: "used as emoticons, smiling, sad etc"); [code] is what goes on the wire,
 * [emoji] what an app without faces shows instead.
 */
enum class Expression(val code: Int, val emoji: String) {
    NEUTRAL(0, "\uD83D\uDE42"),
    SMILE(1, "\uD83D\uDE0A"),
    LAUGH(2, "\uD83D\uDE02"),
    SAD(3, "\uD83D\uDE22"),
    SURPRISED(4, "\uD83D\uDE2E"),
    WINK(5, "\uD83D\uDE09"),
    ANGRY(6, "\uD83D\uDE20");

    companion object {
        fun of(code: Int): Expression? = entries.firstOrNull { it.code == code }
    }
}
