package com.chencohen.ptt.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
import com.chencohen.ptt.ui.talk.ChoiceRow

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
    /** Settings › Talk. */
    openTalk: () -> Unit = {},
    /** Chat colours for every chat (2026-11-27, hen), the same card as a contact's page. */
    chatColors: @Composable () -> Unit = {},
    openCalls: () -> Unit = {},
    /** Show when I'm online: on, off, or null while it is being changed. */
    presenceVisible: Boolean? = true,
    setPresenceVisible: (Boolean) -> Unit = {},
    /** Show when I can't hear (2026-11-25, hen; on by default): on, off, or null while it is being changed. */
    hearVisible: Boolean? = true,
    setHearVisible: (Boolean) -> Unit = {},
    /** Privacy › Live typing (off by default). */
    liveTyping: Boolean = false,
    setLiveTyping: (Boolean) -> Unit = {},
    /** Settings › App updates (docs/design.md, "App updates"). */
    updates: com.chencohen.ptt.UpdateState = com.chencohen.ptt.UpdateState.Latest(0),
    checkUpdates: () -> Unit = {},
    update: (com.chencohen.ptt.update.AppRelease) -> Unit = {},
    /** Install updates automatically (on by default). */
    automaticUpdates: Boolean = true,
    setAutomaticUpdates: (Boolean) -> Unit = {},
    /** Talk's contacts in Android Contacts, with Message and calls (off by default). */
    contacts: List<com.chencohen.ptt.messaging.Contact> = emptyList(),
    /** This user's email, shared with their contacts (null: none). */
    myEmail: String? = null,
    setMyEmail: (String) -> Unit = {},
    /** Settings › Profile: how the app refers to this user (Gender). */
    myGender: Int = 0,
    setMyGender: (Int) -> Unit = {},
    /** Settings › Notifications › Message sound: Talk's soft chime (true, the default) or the phone's sound. */
    softMessage: Boolean = true,
    setSoftMessage: (Boolean) -> Unit = {},
    /** Settings › Your picture (2026-11-24, hen). */
    openPicture: () -> Unit = {},
) {
    LaunchedEffect(Unit) { refresh() }
    SettingsScaffold(stringResource(R.string.settings_title), back) {
        SectionTitle(stringResource(R.string.settings_account_title))
        SettingsCard {
            // Your picture: the face as contacts see it, and a way to make or change it.
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = openPicture).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                com.chencohen.ptt.ui.contacts.Avatar("", 56.dp, name = state.device?.user ?: "?")
                androidx.compose.foundation.layout.Column(Modifier.padding(start = 14.dp)) {
                    Text(stringResource(R.string.settings_picture_title), color = com.chencohen.ptt.ui.style.LibraryColors.OnBackground, style = com.chencohen.ptt.ui.style.LibraryType.RowTitle)
                    Text(stringResource(R.string.settings_picture_body), color = com.chencohen.ptt.ui.style.LibraryColors.Muted, style = com.chencohen.ptt.ui.style.LibraryType.Caption)
                }
            }
            RowDivider()
            val device = state.device
            InfoRow(
                title = device?.deviceName ?: stringResource(R.string.settings_device_name),
                body = device?.let { listOfNotNull(it.user, myEmail).joinToString(" · ") }
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
            com.chencohen.ptt.ui.style.RowDivider()
            // Who can hear you (2026-11-25, hen): contacts see a badge when this phone is on Do Not Disturb, silent or in a call.
            com.chencohen.ptt.ui.talk.SwitchRow(
                stringResource(R.string.settings_hear_title),
                stringResource(if (hearVisible == false) R.string.settings_hear_off else R.string.settings_hear_on),
                hearVisible != false,
            ) { on -> if (hearVisible != null) setHearVisible(on) }
            com.chencohen.ptt.ui.style.RowDivider()
            // Live typing (2026-11-23, hen): the contact sees every letter, even what is deleted.
            com.chencohen.ptt.ui.talk.SwitchRow(stringResource(R.string.settings_live_typing_default), stringResource(R.string.settings_live_typing_body) + "\n" + stringResource(R.string.settings_live_typing_per_contact), liveTyping, setLiveTyping)
        }
        // Show in Android Contacts (2026-11-22, hen): asks for Contacts first.
        val context = androidx.compose.ui.platform.LocalContext.current
        var inContacts by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.chencohen.ptt.AndroidContacts.enabled(context) && com.chencohen.ptt.AndroidContacts.allowed(context)) }
        val askContacts = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                com.chencohen.ptt.AndroidContacts.set(context, true, contacts)
                inContacts = true
            }
        }
        SectionTitle(stringResource(R.string.settings_contacts_title))
        var editingEmail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var shownEmail by androidx.compose.runtime.remember(myEmail) { androidx.compose.runtime.mutableStateOf(myEmail) }
        SettingsCard {
            InfoRow(stringResource(R.string.settings_my_email), shownEmail ?: stringResource(R.string.settings_my_email_none), onClick = { editingEmail = true })
            RowDivider()
            // How the app refers to this user (2026-11-23, hen).
            var gender by androidx.compose.runtime.remember(myGender) { androidx.compose.runtime.mutableIntStateOf(myGender) }
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.profile_gender_title), color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                Text(stringResource(R.string.profile_gender_body), color = LibraryColors.Muted, style = LibraryType.Caption, modifier = Modifier.padding(bottom = 8.dp))
                GenderChoice(gender, { g ->
                    gender = g
                    setMyGender(g)
                })
            }
            RowDivider()
            com.chencohen.ptt.ui.talk.SwitchRow(stringResource(R.string.settings_android_contacts), stringResource(R.string.settings_android_contacts_body), inContacts) { on ->
                if (!on) {
                    com.chencohen.ptt.AndroidContacts.set(context, false, contacts)
                    inContacts = false
                } else if (com.chencohen.ptt.AndroidContacts.allowed(context)) {
                    com.chencohen.ptt.AndroidContacts.set(context, true, contacts)
                    inContacts = true
                } else {
                    askContacts.launch(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS))
                }
            }
        }
        if (editingEmail) {
            var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(shownEmail.orEmpty()) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { editingEmail = false },
                title = { Text(stringResource(R.string.settings_my_email)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Text(stringResource(R.string.settings_my_email_body), style = LibraryType.Caption, color = LibraryColors.Muted)
                        androidx.compose.material3.OutlinedTextField(
                            value = text, onValueChange = { text = it.take(254) }, singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        setMyEmail(text)
                        shownEmail = text.trim().ifBlank { null }
                        editingEmail = false
                    }) { Text(stringResource(R.string.contact_rename_save)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { editingEmail = false }) { Text(stringResource(R.string.contact_rename_cancel)) }
                },
            )
        }
        SectionTitle(stringResource(R.string.settings_notifications_title))
        SettingsCard {
            InfoRow(stringResource(R.string.settings_message_sound), null)
            ChoiceRow(stringResource(R.string.settings_message_sound_soft), stringResource(R.string.settings_message_sound_soft_body), softMessage) { setSoftMessage(true) }
            RowDivider()
            ChoiceRow(stringResource(R.string.settings_message_sound_phone), stringResource(R.string.settings_message_sound_phone_body), !softMessage) { setSoftMessage(false) }
        }
        chatColors()
        SectionTitle(stringResource(R.string.settings_talk_title))
        SettingsCard {
            InfoRow(stringResource(R.string.settings_talk_title), stringResource(R.string.settings_talk_body), onClick = openTalk)
        }
        SectionTitle(stringResource(R.string.settings_calls_title))
        SettingsCard {
            InfoRow(stringResource(R.string.settings_calls_row), stringResource(R.string.settings_calls_body), onClick = openCalls)
        }
        UpdatesSection(updates, checkUpdates, update, automaticUpdates, setAutomaticUpdates)
        ServerSection(state)
        AppVersionLine()
    }
}

/** App updates: the latest release or what is happening, Update, and Check now. */
@Composable
private fun UpdatesSection(
    updates: com.chencohen.ptt.UpdateState,
    check: () -> Unit,
    update: (com.chencohen.ptt.update.AppRelease) -> Unit,
    automatic: Boolean,
    setAutomatic: (Boolean) -> Unit,
) {
    SectionTitle(stringResource(R.string.update_title))
    SettingsCard {
        when (updates) {
            is com.chencohen.ptt.UpdateState.Latest -> InfoRow(
                stringResource(R.string.update_latest),
                if (updates.checkedAt > 0) {
                    stringResource(R.string.update_checked, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(updates.checkedAt)))
                } else {
                    null
                },
            )
            com.chencohen.ptt.UpdateState.Checking -> InfoRow(stringResource(R.string.update_checking), null)
            is com.chencohen.ptt.UpdateState.Available -> InfoRow(
                stringResource(R.string.update_available, updates.release.version),
                stringResource(R.string.update_tap),
                onClick = { update(updates.release) },
            )
            is com.chencohen.ptt.UpdateState.Downloading -> InfoRow(stringResource(R.string.update_downloading, updates.release.version, updates.percent), null)
            is com.chencohen.ptt.UpdateState.Installing -> InfoRow(stringResource(R.string.update_installing, updates.release.version), null)
            is com.chencohen.ptt.UpdateState.NeedsPermission -> InfoRow(
                stringResource(R.string.update_permission),
                stringResource(R.string.update_permission_body),
                onClick = { update(updates.release) },
            )
            is com.chencohen.ptt.UpdateState.Failed -> InfoRow(
                stringResource(R.string.update_failed, updates.message),
                if (updates.release != null) stringResource(R.string.update_tap_again) else null,
                onClick = updates.release?.let { r -> { update(r) } },
            )
            com.chencohen.ptt.UpdateState.DebugBuild -> InfoRow(stringResource(R.string.update_debug), stringResource(R.string.update_debug_body))
        }
        if (updates !is com.chencohen.ptt.UpdateState.DebugBuild) {
            RowDivider()
            com.chencohen.ptt.ui.talk.SwitchRow(stringResource(R.string.update_automatic), stringResource(R.string.update_automatic_body), automatic, setAutomatic)
            RowDivider()
            InfoRow(stringResource(R.string.update_check_now), null, onClick = check)
        }
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
