package com.chencohen.ptt.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.connection.ServerHealth
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.settings.SettingsState
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.style.InfoRow
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.PageNote
import com.chencohen.ptt.ui.style.RowDivider
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsCard
import com.chencohen.ptt.ui.style.SettingsScaffold

/**
 * Settings (docs/screens/settings.md), Signal's grouping as far as phase 2 goes: Account (this phone, Linked devices
 * with Link a phone, Register again) and Your server.
 */
@Composable
internal fun SettingsScreen(
    state: SettingsState,
    back: () -> Unit,
    refresh: () -> Unit,
    registerAgain: () -> Unit,
    linkPhone: () -> Unit = {},
    /** Show when I'm online: on, off, or null while it is being changed. */
    presenceVisible: Boolean? = true,
    setPresenceVisible: (Boolean) -> Unit = {},
) {
    LaunchedEffect(Unit) { refresh() }
    SettingsScaffold(stringResource(R.string.settings_title), back) {
        SectionTitle(stringResource(R.string.settings_account_title))
        SettingsCard {
            val device = state.device
            InfoRow(
                title = device?.deviceName ?: stringResource(R.string.settings_device_name),
                body = device?.let { stringResource(R.string.settings_device_detail, it.user, it.deviceNumber) }
                    ?: stringResource(R.string.settings_device_unknown),
            )
            RowDivider()
            InfoRow(stringResource(R.string.settings_linked_title), stringResource(R.string.settings_linked_body), onClick = linkPhone)
            RowDivider()
            InfoRow(
                title = stringResource(R.string.settings_register_again),
                body = stringResource(R.string.settings_register_again_body),
                onClick = registerAgain,
                destructive = true,
            )
        }
        SectionTitle(stringResource(R.string.settings_privacy_title))
        SettingsCard {
            InfoRow(
                title = stringResource(R.string.settings_presence_title),
                body = stringResource(
                    when (presenceVisible) {
                        true -> R.string.settings_presence_on
                        false -> R.string.settings_presence_off
                        null -> R.string.library_loading
                    },
                ),
                onClick = { presenceVisible?.let { setPresenceVisible(!it) } },
            )
        }
        ServerSection(state)
        AppVersionLine()
    }
}

/** Your server: where the app reaches it now, its id, its version, whether it answers. All can be selected to copy. */
@Composable
private fun ServerSection(state: SettingsState) {
    SectionTitle(stringResource(R.string.settings_server_title))
    SettingsCard {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    state.serverAddress.ifEmpty { stringResource(R.string.settings_server_none) },
                    color = LibraryColors.OnBackground,
                    style = LibraryType.RowTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        when {
                            state.connected -> R.string.settings_server_connected
                            state.health == ServerHealth.REJECTED -> R.string.settings_server_rejected
                            state.health == ServerHealth.OFFLINE -> R.string.settings_server_offline
                            else -> R.string.library_loading
                        },
                    ),
                    color = if (state.health == ServerHealth.OK) LibraryColors.Muted else LibraryColors.Warning,
                    style = LibraryType.Caption,
                )
                Text(
                    state.serverId?.let { stringResource(R.string.settings_server_id, it) } ?: stringResource(R.string.settings_server_no_id),
                    color = LibraryColors.Muted,
                    style = LibraryType.Caption,
                    fontFamily = if (state.serverId != null) FontFamily.Monospace else null,
                )
                if (state.serverVersion != null) {
                    Text(stringResource(R.string.settings_server_version, state.serverVersion!!), color = LibraryColors.Muted, style = LibraryType.Caption)
                }
                if (state.knownAddresses > 0) {
                    Text(stringResource(R.string.settings_server_addresses, state.knownAddresses), color = LibraryColors.Muted, style = LibraryType.Caption)
                }
            }
        }
    }
}

@Composable
private fun AppVersionLine() {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    if (version.isNotEmpty()) PageNote(stringResource(R.string.settings_app_version, version), top = 10)
}

@Preview
@Composable
private fun SettingsPreview() {
    TalkTheme {
        SettingsScreen(
            SettingsState(
                serverAddress = "203.0.113.7:56400",
                serverId = "T6DNBAM-IJR6WLU-GRP5KQM-KWWQCWV-R36TY3F-MFYELGR-RLVWBLM-HQBIEAE",
                serverVersion = "2026.11.21",
                device = EnrolledDevice("hen", 2, "Pixel 9 Pro"),
                knownAddresses = 4,
                health = ServerHealth.OK,
                connected = true,
            ),
            back = {}, refresh = {}, registerAgain = {},
        )
    }
}
