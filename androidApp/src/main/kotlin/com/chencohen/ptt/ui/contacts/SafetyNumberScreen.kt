package com.chencohen.ptt.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.SafetyNumberView
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

class SafetyNumberActions(
    val back: () -> Unit = {},
    val scan: () -> Unit = {},
    val setVerified: (Boolean) -> Unit = {},
)

/** What the safety number page has: still making it, the number, or none (no such contact). */
sealed interface SafetyNumberLoad {
    data object Loading : SafetyNumberLoad
    data class Shown(val number: SafetyNumberView) : SafetyNumberLoad
    data object Missing : SafetyNumberLoad
}

/**
 * The safety number with a contact (docs/screens/contacts.md), Signal's "Verify safety number": 60 digits in 12
 * groups of 5 and the code their phone scans, the same on both phones; Scan their code, and Mark as verified.
 */
@Composable
internal fun SafetyNumberScreen(user: String, load: SafetyNumberLoad, verified: Boolean, actions: SafetyNumberActions) {
    SettingsScaffold(title = stringResource(R.string.safety_title), back = actions.back) {
        when (load) {
            SafetyNumberLoad.Loading -> LoadingState()
            SafetyNumberLoad.Missing -> EmptyState(stringResource(R.string.safety_loading_failed))
            is SafetyNumberLoad.Shown -> {
                PageNote(stringResource(R.string.safety_intro, user), bottom = 20)
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    QrImage(load.number.code, stringResource(R.string.safety_title), size = 200.dp)
                    Digits(load.number.groups)
                    if (verified) {
                        Text(stringResource(R.string.safety_verified), color = LibraryColors.Accent, style = LibraryType.RowTitle, modifier = Modifier.padding(top = 16.dp))
                    }
                    PrimaryButton(stringResource(R.string.safety_scan), actions.scan, Modifier.fillMaxWidth().padding(top = 24.dp), icon = LibraryIcons.Scan)
                    SecondaryButton(
                        stringResource(if (verified) R.string.safety_unmark else R.string.safety_mark),
                        { actions.setVerified(!verified) },
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** The 12 groups of 5 digits, four to a row, in a monospace face so the columns line up when compared. */
@Composable
private fun Digits(groups: List<String>) {
    val spoken = groups.joinToString(" ")
    Column(
        Modifier.padding(top = 24.dp).semantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Numbers read left to right in Hebrew too: the groups keep their order on a right-to-left screen.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            groups.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { group ->
                        Text(group, color = LibraryColors.OnBackground, fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun SafetyNumberPreview() {
    TalkTheme {
        val groups = "123456789012345678901234567890123456789012345678901234567890".chunked(5)
        SafetyNumberScreen("dana", SafetyNumberLoad.Shown(SafetyNumberView(groups, "ptt://safety?v=1&n=AA")), verified = true, SafetyNumberActions())
    }
}
