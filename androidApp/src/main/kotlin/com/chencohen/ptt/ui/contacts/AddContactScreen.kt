package com.chencohen.ptt.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.PairingError
import com.chencohen.ptt.messaging.PairingState
import com.chencohen.ptt.messaging.PairingStep
import com.chencohen.ptt.ui.QrImage
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.style.EmptyState
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

class AddContactActions(
    val back: () -> Unit = {},
    val retryCode: () -> Unit = {},
    val scan: () -> Unit = {},
    val invite: () -> Unit = {},
    val pair: () -> Unit = {},
    val accept: () -> Unit = {},
    val decline: () -> Unit = {},
    val backToCode: () -> Unit = {},
    val done: () -> Unit = {},
)

/**
 * Add contact (docs/screens/add-contact.md): this phone's QR code and Scan their code; then the steps of one pairing,
 * each in place of the code: Pair with them?, sending, waiting for them, paired, or why not. A pair request from the
 * Chats card opens here on its Review step.
 */
@Composable
internal fun AddContactScreen(state: PairingState, user: String?, actions: AddContactActions) {
    SettingsScaffold(title = stringResource(R.string.add_title), back = actions.back) {
        when (val step = state.step) {
            PairingStep.Showing -> Showing(state, user, actions)
            is PairingStep.Scanned -> Question(
                title = stringResource(R.string.add_scanned_title, step.user),
                body = stringResource(R.string.add_scanned_body),
                user = step.user,
                primary = stringResource(R.string.add_pair) to actions.pair,
                secondary = stringResource(R.string.add_cancel) to actions.backToCode,
            )
            is PairingStep.Review -> Question(
                title = stringResource(R.string.add_review_title, step.user),
                body = stringResource(R.string.add_review_body, step.user),
                user = step.user,
                primary = stringResource(R.string.add_pair) to actions.accept,
                secondary = stringResource(R.string.add_decline) to actions.decline,
            )
            is PairingStep.Sending -> Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(step.user, 72.dp)
                LoadingState()
                Text(stringResource(R.string.add_sending, step.user), color = LibraryColors.Muted, style = LibraryType.Body)
            }
            is PairingStep.Waiting -> Question(
                title = stringResource(R.string.add_waiting_title, step.user),
                body = stringResource(R.string.add_waiting_body),
                user = step.user,
                primary = stringResource(R.string.add_done) to actions.done,
                secondary = null,
            )
            is PairingStep.Paired -> Question(
                title = stringResource(R.string.add_paired_title, step.user),
                body = stringResource(R.string.add_paired_body),
                user = step.user,
                primary = stringResource(R.string.add_done) to actions.done,
                secondary = null,
            )
            is PairingStep.Failed -> Question(
                title = stringResource(R.string.add_failed_title),
                body = pairingErrorText(step.error),
                user = step.user,
                primary = stringResource(R.string.add_back) to actions.backToCode,
                secondary = null,
            )
        }
    }
}

@Composable
private fun Showing(state: PairingState, user: String?, actions: AddContactActions) {
    PageNote(stringResource(R.string.add_intro), bottom = 16)
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            state.code != null -> {
                QrImage(com.chencohen.ptt.AppLinks.web(state.code!!), stringResource(R.string.add_code_label), Modifier.padding(top = 8.dp))
                if (user != null) {
                    Text(user, color = LibraryColors.OnBackground, style = LibraryType.PageTitle, modifier = Modifier.padding(top = 16.dp))
                }
                state.codeExpiresAt?.let { expires ->
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(expires))
                    Text(stringResource(R.string.add_code_expires, time), color = LibraryColors.Muted, style = LibraryType.Caption, modifier = Modifier.padding(top = 4.dp))
                }
            }
            state.codeLoading -> LoadingState(Modifier.heightIn(min = 240.dp))
            else -> EmptyState(
                state.codeError?.let { pairingErrorText(it) } ?: stringResource(R.string.pair_error_unreachable),
                actionLabel = stringResource(R.string.add_code_retry),
                onAction = actions.retryCode,
            )
        }
        PrimaryButton(
            stringResource(R.string.add_scan),
            actions.scan,
            Modifier.fillMaxWidth().padding(top = 28.dp),
            icon = LibraryIcons.Scan,
        )
        // Not together: one link, sent over any chat, enrolls them on this server and pairs them with you.
        SecondaryButton(
            stringResource(R.string.add_invite),
            actions.invite,
            Modifier.fillMaxWidth().padding(top = 8.dp),
            icon = LibraryIcons.Share,
        )
    }
}

/** One step: their letter, a title, a line, and the step's one or two actions. */
@Composable
private fun Question(title: String, body: String, user: String?, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (user != null) Avatar(user, 72.dp)
        Text(
            title,
            color = LibraryColors.OnBackground,
            style = LibraryType.PageTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(body, color = LibraryColors.Muted, style = LibraryType.Body, textAlign = TextAlign.Center)
        PrimaryButton(primary.first, primary.second, Modifier.fillMaxWidth().padding(top = 16.dp))
        if (secondary != null) SecondaryButton(secondary.first, secondary.second, Modifier.fillMaxWidth())
    }
}

@Preview
@Composable
private fun AddContactPreview() {
    TalkTheme {
        AddContactScreen(PairingState(code = "ptt://pair?v=1&u=hen", codeExpiresAt = 1_800_000_000_000), "hen", AddContactActions())
    }
}

@Preview
@Composable
private fun AddContactScannedPreview() {
    TalkTheme { AddContactScreen(PairingState(step = PairingStep.Scanned("dana")), "hen", AddContactActions()) }
}

@Preview
@Composable
private fun AddContactFailedPreview() {
    TalkTheme { AddContactScreen(PairingState(step = PairingStep.Failed(PairingError.KEYS_DIFFER, "dana")), "hen", AddContactActions()) }
}
