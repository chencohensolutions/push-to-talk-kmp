package com.chencohen.ptt.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                if (on && !fullScreenAllowed) openFullScreenSetting()
            }
            // Full-screen notifications turn the screen on over the lock screen most surely (2026-11-23).
            if (settings.openOnTalk && !fullScreenAllowed) {
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
            if (settings.openOnTalk) {
                RowDivider()
                SwitchRow(stringResource(R.string.talk_low_battery), stringResource(R.string.talk_low_battery_body), settings.lowBatteryNoWake) { on ->
                    change(settings.copy(lowBatteryNoWake = on))
                }
                RowDivider()
                // Turn the screen off right after talk: Android's device admin, force-lock only (2026-11-23, hen).
                var screenOff by androidx.compose.runtime.remember(resumed) { androidx.compose.runtime.mutableStateOf(com.chencohen.ptt.ScreenOff.on(context)) }
                val why = stringResource(R.string.talk_screen_off_why)
                SwitchRow(stringResource(R.string.talk_screen_off), stringResource(R.string.talk_screen_off_body), screenOff) { on ->
                    if (on) {
                        runCatching { context.startActivity(com.chencohen.ptt.ScreenOff.request(context, why)) }
                    } else {
                        com.chencohen.ptt.ScreenOff.off(context)
                        screenOff = false
                    }
                }
            }
            RowDivider()
            SwitchRow(stringResource(R.string.talk_quick_reply), stringResource(R.string.talk_quick_reply_body), settings.quickReply) { on ->
                change(settings.copy(quickReply = on))
                if (on && !overlayAllowed) openOverlaySetting()
            }
            RowDivider()
            // Follow face on push-to-video (2026-11-23, hen: "optional, off by default"); calls keep their own.
            val followPrefs = androidx.compose.runtime.remember { context.getSharedPreferences("call_view", android.content.Context.MODE_PRIVATE) }
            var followTalk by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(followPrefs.getBoolean(com.chencohen.ptt.ui.talk.FOLLOW_TALK, false)) }
            // Get camera ready while talking (2026-11-24, hen: on by default): off, the camera opens when the slide up starts.
            SwitchRow(stringResource(R.string.talk_camera_ready), stringResource(R.string.talk_camera_ready_body), settings.cameraReady) { on ->
                change(settings.copy(cameraReady = on))
            }
            SwitchRow(stringResource(R.string.talk_clip_upload), stringResource(R.string.talk_clip_upload_body), settings.clipUpload) { on ->
                change(settings.copy(clipUpload = on))
            }
            RowDivider()
            SwitchRow(stringResource(R.string.talk_follow_face), stringResource(R.string.talk_follow_face_body), followTalk) { on ->
                followTalk = on
                followPrefs.edit().putBoolean(com.chencohen.ptt.ui.talk.FOLLOW_TALK, on).apply()
            }
            RowDivider()
            // Talk volume (2026-11-25, hen: "turn up the volume when incoming voice"): on, the media volume is set to the
            // level below while someone talks; the volume keys meanwhile set it too.
            SwitchRow(stringResource(R.string.talk_volume), stringResource(R.string.talk_volume_body), settings.unmuteForTalk) {
                change(settings.copy(unmuteForTalk = it))
            }
            if (settings.unmuteForTalk) TalkVolumeSlider(settings.talkVolume) { change(settings.copy(talkVolume = it)) }
            RowDivider()
            SwitchRow(stringResource(R.string.talk_boost), stringResource(R.string.talk_boost_body), settings.boostVoice) {
                change(settings.copy(boostVoice = it))
            }
            RowDivider()
            // On by default (2026-11-25, hen): music and videos in other apps go quieter while someone talks.
            SwitchRow(stringResource(R.string.talk_lower_others), stringResource(R.string.talk_lower_others_body), settings.lowerOthersForTalk) {
                change(settings.copy(lowerOthersForTalk = it))
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

/** Settings › Talk › Talk volume's level, 10% to 100% of the media volume; kept when the finger lifts. */
@Composable
private fun TalkVolumeSlider(share: Double, set: (Double) -> Unit) {
    var value by androidx.compose.runtime.remember(share) { androidx.compose.runtime.mutableFloatStateOf(share.toFloat()) }
    val label = stringResource(R.string.talk_volume_level, kotlin.math.round(value * 100).toInt())
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        androidx.compose.material3.Text(label, color = com.chencohen.ptt.ui.style.LibraryColors.Muted, style = com.chencohen.ptt.ui.style.LibraryType.Caption)
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { set(value.toDouble()) },
            valueRange = 0.1f..1f,
            modifier = androidx.compose.ui.Modifier.semantics { contentDescription = label },
        )
    }
}
