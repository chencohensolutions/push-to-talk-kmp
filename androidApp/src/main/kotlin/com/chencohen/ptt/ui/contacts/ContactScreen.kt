package com.chencohen.ptt.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.style.EmptyState
import com.chencohen.ptt.ui.style.HomeCard
import com.chencohen.ptt.ui.style.InfoRow
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.PageNote
import com.chencohen.ptt.ui.style.PrimaryButton
import com.chencohen.ptt.ui.style.RowDivider
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsCard
import com.chencohen.ptt.ui.style.SettingsScaffold

class ContactActions(
    val back: () -> Unit = {},
    val acceptKeyChange: () -> Unit = {},
    val openSafetyNumber: () -> Unit = {},
    val sendTest: (String) -> Unit = {},
    val setBlocked: (Boolean) -> Unit = {},
    /** Asks first (a message with Remove), then unpairs. */
    val unpair: () -> Unit = {},
)

/**
 * A contact's page (docs/screens/contacts.md), Signal's conversation settings: who they are and how paired, a changed
 * safety number to accept, the safety number, phase 2's one-line test message, block and remove.
 */
@Composable
internal fun ContactScreen(user: String, contact: Contact?, last: TextMessage?, sending: Boolean, actions: ContactActions) {
    SettingsScaffold(title = user, back = actions.back, imePadding = true) {
        if (contact == null) {
            EmptyState(stringResource(R.string.contact_gone))
            return@SettingsScaffold
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(contact.user, 80.dp)
            Text(contact.user, color = LibraryColors.OnBackground, style = LibraryType.PageTitle, modifier = Modifier.padding(top = 12.dp))
            Text(
                when {
                    contact.blocked -> stringResource(R.string.contact_blocked, contact.user)
                    !contact.paired -> stringResource(R.string.contact_waiting)
                    else -> pluralStringResource(R.plurals.contact_paired_devices, contact.devices, contact.devices)
                },
                color = LibraryColors.Muted,
                style = LibraryType.Caption,
                textAlign = TextAlign.Center,
            )
        }
        if (contact.changedKey != null) {
            HomeCard(
                title = stringResource(R.string.contact_key_changed_title, contact.user),
                body = stringResource(R.string.contact_key_changed_body),
                action = stringResource(R.string.contact_key_changed_action),
                onAction = actions.acceptKeyChange,
                warning = true,
            )
        }
        SectionTitle(stringResource(R.string.contact_safety))
        SettingsCard {
            InfoRow(
                stringResource(R.string.contact_safety),
                stringResource(if (contact.verified) R.string.contact_safety_verified else R.string.contact_safety_unverified),
                onClick = actions.openSafetyNumber,
            )
        }
        if (contact.paired && !contact.blocked) TestMessage(contact.user, last, sending, actions.sendTest)
        Spacer(Modifier.height(20.dp))
        SettingsCard {
            if (contact.blocked) {
                InfoRow(stringResource(R.string.contact_unblock, contact.user), null, onClick = { actions.setBlocked(false) })
            } else {
                InfoRow(stringResource(R.string.contact_block, contact.user), stringResource(R.string.contact_block_body), onClick = { actions.setBlocked(true) }, destructive = true)
            }
            RowDivider()
            InfoRow(stringResource(R.string.contact_unpair, contact.user), stringResource(R.string.contact_unpair_body), onClick = actions.unpair, destructive = true)
        }
    }
}

/** Phase 2's transport test: one line, sent end to end, and the last line either way. */
@Composable
private fun TestMessage(user: String, last: TextMessage?, sending: Boolean, send: (String) -> Unit) {
    var text by rememberSaveable(user) { mutableStateOf("") }
    SectionTitle(stringResource(R.string.contact_test_title))
    PageNote(stringResource(R.string.contact_test_note), bottom = 8)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(500) },
            placeholder = { Text(stringResource(R.string.contact_test_hint)) },
            singleLine = true,
            enabled = !sending,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            stringResource(R.string.contact_test_send),
            {
                send(text.trim())
                text = ""
            },
            Modifier.padding(start = 8.dp),
            enabled = text.isNotBlank() && !sending,
            busy = sending,
        )
    }
    if (last != null) {
        PageNote(
            if (last.outgoing) stringResource(R.string.contact_test_last_out, last.body) else stringResource(R.string.contact_test_last_in, user, last.body),
            top = 8,
        )
    }
}

@Preview
@Composable
private fun ContactPreview() {
    TalkTheme {
        ContactScreen(
            "dana",
            Contact("dana", "", paired = true, devices = 2),
            TextMessage("1", "dana", outgoing = false, body = "Hi from the other phone", at = 0),
            sending = false,
            ContactActions(),
        )
    }
}

@Preview
@Composable
private fun ContactKeyChangedPreview() {
    TalkTheme { ContactScreen("dana", Contact("dana", "", paired = true, changedKey = "x", devices = 1), null, false, ContactActions()) }
}
