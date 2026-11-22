package com.chencohen.ptt.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.style.EmptyState
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsScaffold

/**
 * New chat (docs/screens/contacts.md), Signal's "New message": Add contact first, then the contacts by name. A contact
 * opens their page (the conversation itself is phase 3).
 */
@Composable
internal fun NewChatScreen(contacts: List<Contact>, back: () -> Unit, addContact: () -> Unit, openContact: (String) -> Unit, newGroup: () -> Unit = {}) {
    SettingsScaffold(title = stringResource(R.string.new_chat_title), back = back) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = newGroup)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).background(LibraryColors.Raised, CircleShape), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Group, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.new_chat_group), color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                Text(stringResource(R.string.new_chat_group_body), color = LibraryColors.Muted, style = LibraryType.Caption)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = addContact)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).background(LibraryColors.Inverse, CircleShape), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Scan, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.new_chat_add), color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                Text(stringResource(R.string.new_chat_add_body), color = LibraryColors.Muted, style = LibraryType.Caption)
            }
        }
        SectionTitle(stringResource(R.string.new_chat_contacts))
        if (contacts.isEmpty()) {
            EmptyState(stringResource(R.string.new_chat_none))
        } else {
            contacts.sortedBy { it.user.lowercase() }.forEach { contact ->
                ContactRow(contact, null) { openContact(contact.user) }
            }
        }
    }
}

@Preview
@Composable
private fun NewChatPreview() {
    TalkTheme { NewChatScreen(listOf(Contact("dana", "", paired = true), Contact("avi", "", paired = false)), {}, {}, {}) }
}
