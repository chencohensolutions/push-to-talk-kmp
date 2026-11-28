package com.chencohen.ptt.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.TalkSettings
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.PageNote
import com.chencohen.ptt.ui.style.RowDivider
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsCard
import com.chencohen.ptt.ui.style.SettingsScaffold
import com.chencohen.ptt.ui.talk.SwitchRow

/** The cache limits a slider stops at, in GB (1 GB at least, 10 GB by default; 2026-11-28, hen). */
private val CACHE_STEPS = listOf(1, 2, 3, 5, 10, 20, 30, 50, 100)

/**
 * Settings › Storage (docs/screens/settings.md): how much this phone keeps of the pictures and videos it got, what was
 * read longest ago going first; clearing it when the phone runs low; streaming original videos.
 */
@Composable
internal fun StorageSettingsScreen(settings: TalkSettings, used: suspend () -> Long, back: () -> Unit, change: (TalkSettings) -> Unit) {
    SettingsScaffold(stringResource(R.string.storage_title), back) {
        var usedBytes by remember { mutableLongStateOf(-1L) }
        LaunchedEffect(settings.mediaCacheGb, settings.clearCacheWhenLow) { usedBytes = used() }
        SectionTitle(stringResource(R.string.storage_cache_section))
        SettingsCard {
            val chosen = CACHE_STEPS.indexOfFirst { it >= settings.mediaCacheGb }.takeIf { it >= 0 } ?: CACHE_STEPS.lastIndex
            var at by remember(chosen) { mutableFloatStateOf(chosen.toFloat()) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.storage_cache_limit), color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                    Text(stringResource(R.string.storage_gb, CACHE_STEPS[at.toInt().coerceIn(0, CACHE_STEPS.lastIndex)]), color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                }
                Slider(
                    value = at,
                    onValueChange = { at = it },
                    onValueChangeFinished = { change(settings.copy(mediaCacheGb = CACHE_STEPS[at.toInt().coerceIn(0, CACHE_STEPS.lastIndex)])) },
                    valueRange = 0f..CACHE_STEPS.lastIndex.toFloat(),
                    steps = CACHE_STEPS.size - 2,
                )
                if (usedBytes >= 0) {
                    Text(stringResource(R.string.storage_cache_used, gb(usedBytes)), color = LibraryColors.Muted, style = LibraryType.Caption)
                }
            }
            RowDivider()
            SwitchRow(stringResource(R.string.storage_clear_low), stringResource(R.string.storage_clear_low_body), settings.clearCacheWhenLow) { on ->
                change(settings.copy(clearCacheWhenLow = on))
            }
        }
        PageNote(stringResource(R.string.storage_cache_note))
        SectionTitle(stringResource(R.string.storage_videos_section))
        SettingsCard {
            SwitchRow(stringResource(R.string.storage_stream_videos), stringResource(R.string.storage_stream_videos_body), settings.streamVideos) { on ->
                change(settings.copy(streamVideos = on))
            }
        }
        PageNote(stringResource(R.string.storage_stream_note))
        // What I share stays on the server (2026-11-28, hen: "keep forever everything").
        SectionTitle(stringResource(R.string.storage_server_section))
        PageNote(stringResource(R.string.storage_server_note))
    }
}

private fun gb(bytes: Long): String = java.lang.String.format(java.util.Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024 * 1024))
