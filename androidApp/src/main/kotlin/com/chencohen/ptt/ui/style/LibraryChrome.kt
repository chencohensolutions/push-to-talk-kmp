package com.chencohen.ptt.ui.style

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohen.ptt.R

// Journal's chrome (LibraryChrome.kt, HomeCard, SettingsScreen's cards and rows; docs/screens/style.md), the pieces
// the phase 1 screens use.

/** Shown only while the server can't be reached; nothing is shown when it can. */
@Composable
internal fun OfflinePill(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(LibraryColors.PillScrim, RoundedCornerShape(50))
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.CloudOff, null, tint = LibraryColors.Warning, modifier = Modifier.size(15.dp))
        Text(stringResource(R.string.library_offline), color = Color.White, style = LibraryType.Caption, fontWeight = FontWeight.SemiBold)
    }
}

/** The floating segmented bar at the bottom of the home screens (Chats, Calls). */
@Composable
internal fun <T> SegmentedBar(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(12.dp, RoundedCornerShape(50))
            .background(LibraryColors.Raised.copy(alpha = 0.95f), RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            TextButton(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (isSelected) LibraryColors.Inverse else Color.Transparent,
                    contentColor = if (isSelected) LibraryColors.OnInverse else LibraryColors.OnBackground,
                ),
                modifier = Modifier
                    .height(40.dp)
                    .semantics {
                        role = Role.Tab
                        this.selected = isSelected
                    },
            ) {
                Text(label, style = LibraryType.Body, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * A card at the top of home (Register again): Raised, a title, one line and one ink action. [warning] puts a small
 * warning mark before the title.
 */
@Composable
internal fun HomeCard(title: String, body: String?, action: String, onAction: () -> Unit, warning: Boolean = false) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, LibraryShapes.Card)
            .padding(start = 16.dp, end = 8.dp, top = if (body == null) 4.dp else 14.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (warning) Icon(LibraryIcons.Info, null, tint = LibraryColors.Warning, modifier = Modifier.padding(end = 8.dp).size(18.dp))
            Text(
                title,
                color = LibraryColors.OnBackground,
                fontSize = if (body == null) 14.sp else 16.sp,
                fontWeight = if (body == null) FontWeight.Normal else FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (body == null) TextAction(action, onAction)
        }
        if (body != null) {
            Text(body, color = LibraryColors.Muted, style = LibraryType.Body, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
            PrimaryButton(action, onAction, Modifier.padding(top = 10.dp, bottom = 10.dp))
        }
    }
}

/** A Settings card: Raised, 16 dp from the sides. */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(LibraryColors.Raised, LibraryShapes.Card)) { content() }
}

/** The label over a settings card, in Muted. */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        color = LibraryColors.Muted,
        style = LibraryType.SectionLabel,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A row with a title and a line under it; with [onClick], a chevron and a tap. */
@Composable
internal fun InfoRow(title: String, body: String?, onClick: (() -> Unit)? = null, destructive: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (destructive) LibraryColors.Error else LibraryColors.OnBackground,
                style = LibraryType.RowTitle,
                fontWeight = FontWeight.SemiBold,
            )
            if (body != null) Text(body, color = LibraryColors.Muted, style = LibraryType.Caption)
        }
        if (onClick != null && !destructive) {
            Icon(LibraryIcons.ChevronRight, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun RowDivider() = HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
