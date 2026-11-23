package com.chencohen.ptt.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
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
        val context = androidx.compose.ui.platform.LocalContext.current
        // What Android allows, read again each time the page comes back (from its settings).
        var resumed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
            resumed++
            onPauseOrDispose {}
        }
        val fullScreenAllowed = androidx.compose.runtime.remember(resumed) {
            android.os.Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
        }
        val overlayAllowed = androidx.compose.runtime.remember(resumed) { android.provider.Settings.canDrawOverlays(context) }
        fun openOverlaySetting() = runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")))
        }
        fun openFullScreenSetting() = runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:${context.packageName}")))
        }
        SettingsCard {
            SwitchRow(stringResource(R.string.talk_open_on_talk), stringResource(R.string.talk_open_on_talk_body), settings.openOnTalk) { on ->
                change(settings.copy(openOnTalk = on))
                // Turning it on asks Android for full-screen notifications when it needs to (Android 14+).
                // Either permission turns the screen on: over other apps (the floating window's), or full-screen intents.
                if (on && !fullScreenAllowed && !overlayAllowed) openFullScreenSetting()
            }
            if (settings.openOnTalk && !fullScreenAllowed && !overlayAllowed) {
                RowDivider()
                AllowRow(stringResource(R.string.talk_allow_full_screen)) { openFullScreenSetting() }
            }
            RowDivider()
            SwitchRow(stringResource(R.string.talk_reply_bubble), stringResource(R.string.talk_reply_bubble_body), settings.replyBubble) { on ->
                change(settings.copy(replyBubble = on))
                if (on && !overlayAllowed) openOverlaySetting()
            }
            if (settings.replyBubble && !overlayAllowed) {
                RowDivider()
                AllowRow(stringResource(R.string.talk_allow_overlay)) { openOverlaySetting() }
            }
            RowDivider()
            SwitchRow(stringResource(R.string.talk_quick_reply), stringResource(R.string.talk_quick_reply_body), settings.quickReply) { on ->
                change(settings.copy(quickReply = on))
                if (on && !overlayAllowed) openOverlaySetting()
            }
            RowDivider()
            SwitchRow(stringResource(R.string.talk_unmute), stringResource(R.string.talk_unmute_body), settings.unmuteForTalk) {
                change(settings.copy(unmuteForTalk = it))
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

/** A setting Android hasn't allowed yet: in the warning color, a tap opens Android's page for it. */
@Composable
private fun AllowRow(title: String, open: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { open() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        androidx.compose.material3.Text(title, color = com.chencohen.ptt.ui.style.LibraryColors.Warning, style = com.chencohen.ptt.ui.style.LibraryType.RowTitle)
        androidx.compose.material3.Text(stringResource(R.string.talk_allow_body), color = com.chencohen.ptt.ui.style.LibraryColors.Muted, style = com.chencohen.ptt.ui.style.LibraryType.Caption)
    }
}
