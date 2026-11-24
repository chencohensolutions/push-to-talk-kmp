package com.chencohen.ptt.ui.settings

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.ShownLinkCode
import com.chencohen.ptt.ui.QrImage
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.LoadingState
import com.chencohen.ptt.ui.style.PageNote
import com.chencohen.ptt.ui.style.PrimaryButton
import com.chencohen.ptt.ui.style.SecondaryButton
import com.chencohen.ptt.ui.style.SettingsScaffold
import java.text.DateFormat
import java.util.Date

/** What Link a phone shows: the code being made, the code, why there is none, or the phone just linked. */
sealed interface LinkPhoneView {
    data object Loading : LinkPhoneView
    data class Showing(val code: ShownLinkCode) : LinkPhoneView
    data class Failed(val message: String) : LinkPhoneView
    data class Linked(val device: Int) : LinkPhoneView
}

/**
 * Link a phone (docs/screens/link-phone.md): a QR code for the user's new phone to scan when it registers. That phone
 * joins this account and gets its keys and contacts from this one, sealed end to end, while this screen is open.
 */
@Composable
internal fun LinkPhoneScreen(view: LinkPhoneView, back: () -> Unit, again: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.link_title), back = back) {
        when (view) {
            LinkPhoneView.Loading -> LoadingState(Modifier.heightIn(min = 240.dp))
            is LinkPhoneView.Failed -> {
                PageNote(stringResource(R.string.link_failed, view.message), bottom = 16)
                PrimaryButton(stringResource(R.string.link_again), again, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            is LinkPhoneView.Linked -> {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.link_done_title), color = LibraryColors.OnBackground, style = LibraryType.PageTitle, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.link_done_body), color = LibraryColors.Muted, style = LibraryType.Body, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
                PrimaryButton(stringResource(R.string.link_done), back, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp))
            }
            is LinkPhoneView.Showing -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.link_steps), color = LibraryColors.OnBackground, style = LibraryType.Body, textAlign = TextAlign.Center)
                QrImage(com.chencohen.ptt.AppLinks.web(view.code.code), stringResource(R.string.link_code_label), Modifier.padding(top = 8.dp))
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(view.code.expiresAt))
                Text(stringResource(R.string.link_until, time), color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center)
                Text(stringResource(R.string.link_note), color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center)
                SecondaryButton(stringResource(R.string.link_again), again, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Preview
@Composable
private fun LinkPhonePreview() {
    TalkTheme { LinkPhoneScreen(LinkPhoneView.Showing(ShownLinkCode("ptt://link?v=1&code=K7QM-4XPD", 1_800_000_000_000)), {}, {}) }
}

@Preview
@Composable
private fun LinkedPreview() {
    TalkTheme { LinkPhoneScreen(LinkPhoneView.Linked(2), {}, {}) }
}
