package com.chencohen.ptt.ui.groups

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.ui.style.EmptyState
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.PrimaryButton
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsScaffold
import kotlin.io.encoding.Base64

/** The paired contacts one can put in a group (not blocked), by name. */
internal fun pickable(contacts: List<Contact>, except: Set<String> = emptySet()) =
    contacts.filter { it.paired && !it.blocked && it.user !in except }.sortedBy { it.user.lowercase() }

/**
 * New group (docs/screens/groups.md): a picture (tap to pick one), a name, and the contacts to invite. Create makes the
 * group with this user as its owner and invites the ones ticked.
 */
@Composable
internal fun NewGroupScreen(contacts: List<Contact>, back: () -> Unit, create: (name: String, picture: ByteArray?, members: List<String>) -> Unit, busy: Boolean) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var picture by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) groupPicture(context, uri)?.let { picture = Base64.encode(it) }
    }
    SettingsScaffold(title = stringResource(R.string.group_new_title), back = back, imePadding = true) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.clickable(role = Role.Button) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GroupAvatar(name, picture, 80.dp)
                Text(stringResource(if (picture == null) R.string.group_picture_add else R.string.group_picture_change), color = LibraryColors.Muted, style = LibraryType.Caption, modifier = Modifier.padding(top = 6.dp))
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME) },
            label = { Text(stringResource(R.string.group_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        SectionTitle(stringResource(R.string.group_choose_members))
        val choices = remember(contacts) { pickable(contacts) }
        if (choices.isEmpty()) EmptyState(stringResource(R.string.group_no_contacts))
        choices.forEach { contact ->
            PickRow(contact, contact.user in picked) { picked = if (contact.user in picked) picked - contact.user else picked + contact.user }
        }
        PrimaryButton(
            stringResource(R.string.group_create),
            { create(name.trim(), picture?.let(Base64::decode), picked) },
            Modifier.fillMaxWidth().padding(16.dp),
            enabled = name.isNotBlank() && !busy,
            busy = busy,
        )
    }
}

/** Add members (the owner, from the group's page): the contacts not in the group yet. */
@Composable
internal fun AddMembersScreen(contacts: List<Contact>, inGroup: Set<String>, back: () -> Unit, add: (List<String>) -> Unit, busy: Boolean) {
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    SettingsScaffold(title = stringResource(R.string.group_add_members), back = back) {
        val choices = remember(contacts, inGroup) { pickable(contacts, inGroup) }
        if (choices.isEmpty()) EmptyState(stringResource(R.string.group_no_more_contacts))
        choices.forEach { contact ->
            PickRow(contact, contact.user in picked) { picked = if (contact.user in picked) picked - contact.user else picked + contact.user }
        }
        PrimaryButton(
            stringResource(R.string.group_invite),
            { add(picked) },
            Modifier.fillMaxWidth().padding(16.dp),
            enabled = picked.isNotEmpty() && !busy,
            busy = busy,
        )
    }
}

internal const val MAX_NAME = 64
