package com.chencohen.ptt.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.chencohen.ptt.R
import com.chencohen.ptt.media.ChatStorage
import com.chencohen.ptt.ui.style.InfoRow
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsCard
import kotlinx.coroutines.launch

/**
 * A chat's Storage (2026-11-28, hen: "show cache details and clear cache in specific chat settings screen"): what this
 * phone keeps of the chat's pictures, videos and files, and Clear cache, which removes only what can come again (the
 * previews still on the server, the originals someone else has); the only copies known stay (who holds what).
 */
@Composable
internal fun ChatStorageCard(
    chat: String,
    storage: suspend (String) -> ChatStorage,
    clear: suspend (String) -> ChatStorage,
    requests: suspend (String) -> List<com.chencohen.ptt.media.OriginalRequest>,
    names: (String) -> String,
) {
    var now by remember(chat) { mutableStateOf<ChatStorage?>(null) }
    var cleared by remember(chat) { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    var asked by remember(chat) { mutableStateOf<List<com.chencohen.ptt.media.OriginalRequest>>(emptyList()) }
    var allAsked by remember(chat) { mutableStateOf(false) }
    LaunchedEffect(chat) {
        now = storage(chat)
        asked = requests(chat)
    }
    val s = now ?: return
    SectionTitle(stringResource(R.string.chat_storage))
    SettingsCard {
        val lines = listOfNotNull(
            stringResource(R.string.chat_storage_sent, bytes(s.sent), s.sentCount).takeIf { s.sentCount > 0 },
            stringResource(R.string.chat_storage_got, bytes(s.got), s.gotCount).takeIf { s.gotCount > 0 },
            stringResource(R.string.chat_storage_copies, bytes(s.copies), s.copiesCount).takeIf { s.copiesCount > 0 },
            stringResource(R.string.chat_storage_only, bytes(s.only), s.onlyCount).takeIf { s.onlyCount > 0 },
            stringResource(R.string.chat_storage_shared, s.shared).takeIf { s.shared > 0 },
            stringResource(R.string.chat_storage_lost, s.lost).takeIf { s.lost > 0 },
        )
        InfoRow(bytes(s.sent + s.got + s.copies + s.only), lines.joinToString("\n").ifEmpty { stringResource(R.string.chat_storage_empty) }, onClick = null)
        val clearable = s.got - s.gotLasting + s.copies
        InfoRow(
            stringResource(R.string.chat_storage_clear),
            cleared?.let { stringResource(R.string.chat_storage_cleared, bytes(it)) } ?: stringResource(R.string.chat_storage_clear_body),
            onClick = if (clearable > 0) ({
                scope.launch {
                    val before = s.sent + s.got + s.copies + s.only
                    val after = clear(chat)
                    cleared = (before - (after.sent + after.got + after.copies + after.only)).coerceAtLeast(0)
                    now = after
                }
            }) else null,
        )
    }
    // Every request for one of my originals in this chat (2026-11-28, hen), newest first; refused ones too.
    if (asked.isNotEmpty()) {
        SectionTitle(stringResource(R.string.chat_requests))
        SettingsCard {
            val newest = asked.asReversed()
            val refused = asked.count { com.chencohen.ptt.media.RequestResult.refused(it.result) }
            InfoRow(
                stringResource(R.string.chat_requests_count, asked.size),
                stringResource(R.string.chat_requests_refused, refused).takeIf { refused > 0 },
                onClick = null,
                destructive = refused > 0,
            )
            (if (allAsked) newest else newest.take(REQUESTS_SHOWN)).forEach { r -> InfoRow(requestLine(r, names(r.user)), null, onClick = null) }
            if (!allAsked && newest.size > REQUESTS_SHOWN) InfoRow(stringResource(R.string.chat_requests_all), null, onClick = { allAsked = true })
        }
    }
}

/** Requests shown before All. */
private const val REQUESTS_SHOWN = 10

private fun bytes(n: Long): String = when {
    n < 1_000_000 -> "${(n + 999) / 1000} KB"
    n < 1_000_000_000 -> "%.1f MB".format(n / 1_000_000.0)
    else -> "%.2f GB".format(n / 1_000_000_000.0)
}
