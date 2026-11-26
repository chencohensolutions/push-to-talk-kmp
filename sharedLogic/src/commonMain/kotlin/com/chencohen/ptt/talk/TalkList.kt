package com.chencohen.ptt.talk

/**
 * The Talk tab's live list (2026-11-26, hen: "the idea is to contact very quickly coworkers, also with messages"; concept
 * 1 of docs/screens/mocks/talk-home-mock.png): every chat (people and groups) as a row, and the filters over it.
 */
object TalkList {
    enum class Filter { ALL, HEARING, UNREAD, GROUPS }

    data class Row(
        val chat: String,
        val group: Boolean,
        /** Someone (not me) is talking there now. */
        val talking: Boolean,
        /** The newest message's time, 0 with none. */
        val lastAt: Long,
        /** When the contact or group came, for those with no message yet. */
        val since: Long,
        val unread: Int,
        /** Someone there can hear a push now (Hear.OK). */
        val hearing: Boolean,
    )

    /** Who is talking first, then the newest message first, then the newest contact or group. */
    fun order(rows: List<Row>): List<Row> =
        rows.sortedWith(compareByDescending<Row> { it.talking }.thenByDescending { it.lastAt }.thenByDescending { it.since }.thenBy { it.chat })

    fun filter(rows: List<Row>, filter: Filter): List<Row> = when (filter) {
        Filter.ALL -> rows
        Filter.HEARING -> rows.filter { it.hearing }
        Filter.UNREAD -> rows.filter { it.unread > 0 }
        Filter.GROUPS -> rows.filter { it.group }
    }

    /**
     * The open row: the one asked for while it's still shown; else the first shown. Someone who starts talking opens
     * their row (Push answers them), unless my push runs ([busy]).
     */
    fun open(asked: String?, shown: List<Row>, startedTalking: String?, busy: Boolean): String? = when {
        !busy && startedTalking != null && shown.any { it.chat == startedTalking } -> startedTalking
        asked != null && shown.any { it.chat == asked } -> asked
        else -> shown.firstOrNull()?.chat
    }
}
