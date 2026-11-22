package com.chencohen.ptt.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.OwnDevice
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.LoadingState
import com.chencohen.ptt.ui.style.PageNote
import com.chencohen.ptt.ui.style.PrimaryButton
import com.chencohen.ptt.ui.style.RowDivider
import com.chencohen.ptt.ui.style.SecondaryButton
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsCard
import com.chencohen.ptt.ui.style.SettingsScaffold
import java.text.DateFormat
import java.util.Date

/** What Your devices shows: loading, the phones, or why they couldn't be read. */
sealed interface DevicesView {
    data object Loading : DevicesView
    data class Showing(val devices: List<OwnDevice>, val revoking: Int? = null) : DevicesView
    data class Failed(val message: String) : DevicesView
}

/**
 * Your devices (docs/screens/devices.md; 2026-11-22, hen): this user's phones on the server, with when each was
 * enrolled and last reached the server, this phone marked; Remove on another one revokes it, after asking. Link a
 * phone adds one.
 */
@Composable
internal fun DevicesScreen(
    view: DevicesView,
    now: Long,
    back: () -> Unit,
    again: () -> Unit,
    revoke: (OwnDevice) -> Unit,
    linkPhone: () -> Unit,
) {
    var asking by remember { mutableStateOf<OwnDevice?>(null) }
    SettingsScaffold(title = stringResource(R.string.devices_title), back = back) {
        when (view) {
            DevicesView.Loading -> LoadingState(Modifier.heightIn(min = 240.dp))
            is DevicesView.Failed -> {
                PageNote(stringResource(R.string.devices_failed, view.message), bottom = 16)
                PrimaryButton(stringResource(R.string.devices_again), again, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            is DevicesView.Showing -> {
                PageNote(stringResource(R.string.devices_intro), bottom = 8)
                SectionTitle(stringResource(R.string.devices_section))
                SettingsCard {
                    view.devices.forEachIndexed { i, device ->
                        if (i > 0) RowDivider()
                        DeviceRow(device, now, removing = view.revoking == device.deviceNumber, remove = { asking = device })
                    }
                }
                SecondaryButton(stringResource(R.string.devices_link), linkPhone, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp))
            }
        }
    }
    asking?.let { device ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.devices_remove_title, device.name)) },
            text = { Text(stringResource(R.string.devices_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    revoke(device)
                }) { Text(stringResource(R.string.devices_remove), color = LibraryColors.Error) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.devices_cancel)) } },
        )
    }
}

@Composable
private fun DeviceRow(device: OwnDevice, now: Long, removing: Boolean, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (device.current) stringResource(R.string.devices_this_phone, device.name) else device.name,
                color = LibraryColors.OnBackground,
                style = LibraryType.RowTitle,
                fontWeight = FontWeight.SemiBold,
            )
            Text(lastSeen(device, now), color = if (device.current) LibraryColors.Accent else LibraryColors.Muted, style = LibraryType.Caption)
            val registered = device.registeredAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
            Text(
                listOfNotNull(
                    registered?.let { stringResource(R.string.devices_registered, it) },
                    keyWords(device.keyProtection),
                ).joinToString(" · ").replaceFirstChar { it.uppercase() },
                color = LibraryColors.Muted,
                style = LibraryType.Caption,
            )
        }
        if (!device.current) {
            TextButton(onClick = remove, enabled = !removing) {
                Text(stringResource(if (removing) R.string.devices_removing else R.string.devices_remove), color = LibraryColors.Error)
            }
        }
    }
}

/** "Active now" for this phone and one seen in the last two minutes; else how long ago, or not known yet. */
@Composable
private fun lastSeen(device: OwnDevice, now: Long): String {
    val at = device.lastSeenAt
    return when {
        device.current -> stringResource(R.string.devices_active_now)
        at == null -> stringResource(R.string.devices_seen_unknown)
        now - at < 2 * DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.devices_active_now)
        now - at < DateUtils.DAY_IN_MILLIS ->
            stringResource(R.string.devices_seen, DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString())
        else -> stringResource(
            R.string.devices_seen,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at)),
        )
    }
}

@Composable
private fun keyWords(protection: String): String? = when (protection) {
    "STRONGBOX" -> stringResource(R.string.devices_key_strongbox)
    "TEE", "SECURE_ENCLAVE" -> stringResource(R.string.devices_key_tee)
    "SOFTWARE" -> stringResource(R.string.devices_key_software)
    else -> null
}

@Preview
@Composable
private fun DevicesPreview() {
    val now = 1_800_000_000_000
    TalkTheme {
        DevicesScreen(
            DevicesView.Showing(
                listOf(
                    OwnDevice(3, "SM-S931B", now - 86_400_000, now, true, "STRONGBOX"),
                    OwnDevice(4, "Pixel 9", now - 3 * 86_400_000, now - 25 * 60_000, false, "TEE"),
                    OwnDevice(5, "sdk_gphone64", now - 9 * 86_400_000, null, false, "SOFTWARE"),
                ),
            ),
            now, {}, {}, {}, {},
        )
    }
}
