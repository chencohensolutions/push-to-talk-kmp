package com.chencohen.ptt.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.InviteMessage
import com.chencohen.ptt.messaging.MadeInvite
import com.chencohen.ptt.messaging.PairingState
import com.chencohen.ptt.ui.QrImage
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.LoadingState
import com.chencohen.ptt.ui.style.PageNote
import com.chencohen.ptt.ui.style.PrimaryButton
import com.chencohen.ptt.ui.style.SecondaryButton
import com.chencohen.ptt.ui.style.SettingsScaffold
import java.text.DateFormat
import java.util.Date

class InviteActions(
    val back: () -> Unit = {},
    val make: (email: String) -> Unit = {},
    val share: (MadeInvite) -> Unit = {},
    val email: (MadeInvite) -> Unit = {},
    val copy: (MadeInvite) -> Unit = {},
    val another: () -> Unit = {},
)

/**
 * Invite a friend (docs/screens/invite.md): an optional email, then the link as a QR code and its six-digit code, with
 * Email the invite (when an email was typed), Share and Copy. This phone sends the invite: the server sends no mail.
 * The friend's phone enrolls with the link and the code, and pairs with this phone.
 */
@Composable
internal fun InviteScreen(state: PairingState, actions: InviteActions) {
    SettingsScaffold(title = stringResource(R.string.invite_title), back = actions.back) {
        val invite = state.invite
        if (invite == null) {
            var email by rememberSaveable { mutableStateOf("") }
            PageNote(stringResource(R.string.invite_intro), bottom = 16)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim().take(254) },
                    label = { Text(stringResource(R.string.invite_for_email)) },
                    supportingText = {
                        Text(state.inviteError?.let { pairingErrorText(it) } ?: stringResource(R.string.invite_for_email_help))
                    },
                    isError = state.inviteError != null,
                    singleLine = true,
                    enabled = !state.inviteLoading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    stringResource(R.string.invite_make),
                    { actions.make(email) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.inviteLoading,
                    busy = state.inviteLoading,
                )
            }
            if (state.inviteLoading) LoadingState(Modifier.heightIn(min = 120.dp))
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Face to face, the QR code carries the passcode too: the friend in the room needs nothing else.
                // What is sent (Email, Share, Copy) has the link and the code apart.
                QrImage(invite.faceToFace, stringResource(R.string.invite_code_label), Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.invite_qr_note), color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center)
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(invite.expiresAt))
                Text(
                    if (invite.email != null) stringResource(R.string.invite_made_for, invite.email!!, date)
                    else stringResource(R.string.invite_made_anyone, date),
                    color = LibraryColors.Muted,
                    style = LibraryType.Body,
                    textAlign = TextAlign.Center,
                )
                // The code goes beside the link, not in it: shown here too, for a friend who scans the QR code.
                Text(stringResource(R.string.invite_passcode_label), color = LibraryColors.Muted, style = LibraryType.Caption, modifier = Modifier.padding(top = 4.dp))
                Text(
                    InviteMessage.spaced(invite.passcode),
                    style = LibraryType.PageTitle.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                    textAlign = TextAlign.Center,
                )
                if (invite.email != null) {
                    // The link and its code from the user's own mail app, from their own address.
                    PrimaryButton(stringResource(R.string.invite_email_it), { actions.email(invite) }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = LibraryIcons.Chat)
                    Text(stringResource(R.string.invite_email_it_note), color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center)
                    SecondaryButton(stringResource(R.string.invite_share), { actions.share(invite) }, Modifier.fillMaxWidth(), icon = LibraryIcons.Share)
                } else {
                    PrimaryButton(stringResource(R.string.invite_share), { actions.share(invite) }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = LibraryIcons.Share)
                }
                SecondaryButton(stringResource(R.string.invite_copy), { actions.copy(invite) }, Modifier.fillMaxWidth(), icon = LibraryIcons.Copy)
                SecondaryButton(stringResource(R.string.invite_another), actions.another, Modifier.fillMaxWidth())
                Text(stringResource(R.string.invite_note), color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center)
            }
        }
    }
}

@Preview
@Composable
private fun InviteFormPreview() {
    TalkTheme { InviteScreen(PairingState(), InviteActions()) }
}

@Preview
@Composable
private fun InviteMadePreview() {
    TalkTheme {
        InviteScreen(PairingState(invite = MadeInvite("ptt://invite?v=1&code=x", "dana@example.com", 1_800_000_000_000, "hen", "123456", "ptt://invite?v=1&code=x&p=123456")), InviteActions())
    }
}
