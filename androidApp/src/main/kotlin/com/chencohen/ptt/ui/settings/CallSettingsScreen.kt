package com.chencohen.ptt.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chencohen.ptt.R
import com.chencohen.ptt.calls.SaveCalls
import com.chencohen.ptt.messaging.TalkSettings
import com.chencohen.ptt.ui.style.PageNote
import com.chencohen.ptt.ui.style.RowDivider
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsCard
import com.chencohen.ptt.ui.style.SettingsScaffold
import com.chencohen.ptt.ui.talk.ChoiceRow

/** Settings › Calls (docs/screens/settings.md): Save calls, Off, Ask each time or Always. */
@Composable
internal fun CallSettingsScreen(settings: TalkSettings, back: () -> Unit, change: (TalkSettings) -> Unit) {
    SettingsScaffold(stringResource(R.string.settings_calls_title), back) {
        SectionTitle(stringResource(R.string.calls_save_title))
        SettingsCard {
            val modes = listOf(
                SaveCalls.OFF to (R.string.calls_save_off to R.string.calls_save_off_body),
                SaveCalls.ASK to (R.string.calls_save_ask to R.string.calls_save_ask_body),
                SaveCalls.ALWAYS to (R.string.calls_save_always to R.string.calls_save_always_body),
            )
            modes.forEachIndexed { i, (mode, words) ->
                if (i > 0) RowDivider()
                ChoiceRow(stringResource(words.first), stringResource(words.second), settings.saveCalls == mode) { change(settings.copy(saveCalls = mode)) }
            }
        }
        PageNote(stringResource(R.string.calls_save_note))
    }
}
