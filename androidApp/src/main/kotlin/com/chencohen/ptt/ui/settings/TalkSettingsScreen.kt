package com.chencohen.ptt.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.TalkSettings
import com.chencohen.ptt.talk.CatchUpMode
import com.chencohen.ptt.ui.style.PageNote
import com.chencohen.ptt.ui.style.RowDivider
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsCard
import com.chencohen.ptt.ui.style.SettingsScaffold
import com.chencohen.ptt.ui.talk.ChoiceRow
import com.chencohen.ptt.ui.talk.SwitchRow

/**
 * Settings › Talk (docs/screens/settings.md): Catch up to live, Stay connected, the conversation hardware buttons
 * talk in, and the buttons themselves.
 */
@Composable
internal fun TalkSettingsScreen(
    settings: TalkSettings,
    /** The conversations a button can talk in: chat key and name. */
    chats: List<Pair<String, String>>,
    back: () -> Unit,
    change: (TalkSettings) -> Unit,
) {
    SettingsScaffold(stringResource(R.string.settings_talk_title), back) {
        SectionTitle(stringResource(R.string.talk_catch_up_title))
        SettingsCard {
            val modes = listOf(
                CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP to (R.string.talk_catch_up_both to R.string.talk_catch_up_both_body),
                CatchUpMode.SHORTEN_PAUSES to (R.string.talk_catch_up_pauses to R.string.talk_catch_up_pauses_body),
                CatchUpMode.OFF to (R.string.talk_catch_up_off to R.string.talk_catch_up_off_body),
            )
            modes.forEachIndexed { i, (mode, words) ->
                if (i > 0) RowDivider()
                ChoiceRow(stringResource(words.first), stringResource(words.second), settings.catchUp == mode) { change(settings.copy(catchUp = mode)) }
            }
        }
        SectionTitle(stringResource(R.string.talk_background_title))
        SettingsCard {
            SwitchRow(stringResource(R.string.talk_full_volume), stringResource(R.string.talk_full_volume_body), settings.fullVolumeBackground) {
                change(settings.copy(fullVolumeBackground = it))
            }
            RowDivider()
            SwitchRow(stringResource(R.string.talk_stay_connected), stringResource(R.string.talk_stay_connected_body), settings.stayConnected) {
                change(settings.copy(stayConnected = it))
            }
        }
        PageNote(stringResource(R.string.talk_stay_connected_note))
        SectionTitle(stringResource(R.string.talk_buttons_title))
        SettingsCard {
            SwitchRow(stringResource(R.string.talk_buttons), stringResource(R.string.talk_buttons_body), settings.hardwareButtons) {
                change(settings.copy(hardwareButtons = it))
            }
        }
        SectionTitle(stringResource(R.string.talk_default_title))
        SettingsCard {
            ChoiceRow(stringResource(R.string.talk_default_none), null, settings.defaultChat == null) { change(settings.copy(defaultChat = null)) }
            chats.forEach { (chat, name) ->
                RowDivider()
                ChoiceRow(name, null, settings.defaultChat == chat) { change(settings.copy(defaultChat = chat)) }
            }
        }
    }
}
