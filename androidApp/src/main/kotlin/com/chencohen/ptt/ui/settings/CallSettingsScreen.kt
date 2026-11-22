package com.chencohen.ptt.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        // How an incoming call rings: Talk's soft chime (the default) or the phone's ringtone (2026-11-22, hen).
        SectionTitle(stringResource(R.string.calls_ring_title))
        SettingsCard {
            ChoiceRow(stringResource(R.string.calls_ring_soft), stringResource(R.string.calls_ring_soft_body), settings.softRing) { change(settings.copy(softRing = true)) }
            RowDivider()
            ChoiceRow(stringResource(R.string.calls_ring_phone), stringResource(R.string.calls_ring_phone_body), !settings.softRing) { change(settings.copy(softRing = false)) }
        }
        // The codec this phone sends its camera in; H.265 only with a hardware encoder (2026-11-22, hen).
        val hevc = androidx.compose.runtime.remember { com.chencohen.ptt.calls.LiveKitCallTransport.hasHevcEncoder() }
        SectionTitle(stringResource(R.string.calls_video_title))
        SettingsCard {
            ChoiceRow(stringResource(R.string.calls_video_vp8), stringResource(R.string.calls_video_vp8_body), settings.callVideo == com.chencohen.ptt.calls.CallVideo.VP8) {
                change(settings.copy(callVideo = com.chencohen.ptt.calls.CallVideo.VP8))
            }
            if (hevc) {
                RowDivider()
                ChoiceRow(stringResource(R.string.calls_video_h265), stringResource(R.string.calls_video_h265_body), settings.callVideo == com.chencohen.ptt.calls.CallVideo.H265) {
                    change(settings.copy(callVideo = com.chencohen.ptt.calls.CallVideo.H265))
                }
            }
        }
        if (!hevc) PageNote(stringResource(R.string.calls_video_no_h265))
        // Where Save as file puts a kept call: a folder picked with Android's picker (SD card and USB drives too).
        SectionTitle(stringResource(R.string.calls_folder_title))
        val context = androidx.compose.ui.platform.LocalContext.current
        var label by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.chencohen.ptt.ui.calls.CallsFolder.label(context)) }
        var chosen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.chencohen.ptt.ui.calls.CallsFolder.tree(context) != null) }
        val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) {
                com.chencohen.ptt.ui.calls.CallsFolder.set(context, tree)
                label = com.chencohen.ptt.ui.calls.CallsFolder.label(context)
                chosen = true
            }
        }
        SettingsCard {
            ChoiceRow(stringResource(R.string.calls_folder_pick), label, false) { pick.launch(null) }
            if (chosen) {
                RowDivider()
                ChoiceRow(stringResource(R.string.calls_folder_use_default), stringResource(R.string.calls_folder_default), false) {
                    com.chencohen.ptt.ui.calls.CallsFolder.set(context, null)
                    label = com.chencohen.ptt.ui.calls.CallsFolder.label(context)
                    chosen = false
                }
            }
        }
        PageNote(stringResource(R.string.calls_folder_note))
    }
}
