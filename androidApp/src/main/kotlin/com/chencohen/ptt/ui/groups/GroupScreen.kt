package com.chencohen.ptt.ui.groups

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.style.EmptyState
import com.chencohen.ptt.ui.style.InfoRow
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.PrimaryButton
import com.chencohen.ptt.ui.style.RowDivider
import com.chencohen.ptt.ui.style.SecondaryButton
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsCard
import com.chencohen.ptt.ui.style.SettingsScaffold

class GroupActions(
    val back: () -> Unit = {},
    val accept: () -> Unit = {},
    val decline: () -> Unit = {},
    /** The owner: a new name, and a new picture (null keeps it). */
    val rename: (String, ByteArray?) -> Unit = { _, _ -> },
    val addMembers: () -> Unit = {},
    /** The owner takes a member out; asks first. */
    val remove: (String) -> Unit = {},
    /** A member's own contact page, or Add contact for one who isn't a contact. */
    val openMember: (String) -> Unit = {},
    val leave: () -> Unit = {},
    val delete: () -> Unit = {},
    /** When I'm away: Notify only (true) or Play aloud. */
    val setNotifyOnly: (Boolean) -> Unit = {},
    /** Quick talk on or off: a page of the Talk tab (docs/screens/quick-talk.md). */
    val setQuickTalk: (Boolean) -> Unit = {},
)

/**
 * A group's page (docs/screens/groups.md), Signal's group settings: the picture and name (the owner changes them), an
 * invitation to answer, the members with how each is known to this phone, and leave or delete in red.
 */
@Composable
internal fun GroupScreen(me: String, group: Group?, contacts: List<Contact>, actions: GroupActions, notifyOnly: Boolean = false, quickTalk: Boolean = false) {
    val context = LocalContext.current
    SettingsScaffold(title = group?.let { groupName(it) } ?: "", back = actions.back, imePadding = true) {
        if (group == null) {
            EmptyState(stringResource(R.string.group_missing))
            return@SettingsScaffold
        }
        val owner = group.owner == me && !group.gone
        var editing by rememberSaveable(group.id) { mutableStateOf(false) }
        var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) groupPicture(context, uri)?.let { actions.rename(group.name, it) }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.then(if (owner) Modifier.clickable(role = Role.Button) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GroupAvatar(group.name, group.picture, 80.dp)
                if (owner) Text(stringResource(R.string.group_picture_change), color = LibraryColors.Muted, style = LibraryType.Caption, modifier = Modifier.padding(top = 6.dp))
            }
            Text(groupName(group), color = LibraryColors.OnBackground, style = LibraryType.PageTitle, modifier = Modifier.padding(top = 12.dp))
            Text(
                when {
                    group.gone -> stringResource(R.string.group_gone)
                    group.isInvitation(me) -> stringResource(R.string.group_invited_by, group.owner)
                    else -> stringResource(R.string.group_members_count, group.readers.size)
                },
                color = LibraryColors.Muted,
                style = LibraryType.Caption,
                textAlign = TextAlign.Center,
            )
        }
        if (group.isInvitation(me)) {
            PageNoteText(stringResource(R.string.group_invitation_body, group.owner))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SecondaryButton(stringResource(R.string.group_decline), actions.decline, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                PrimaryButton(stringResource(R.string.group_accept), actions.accept, Modifier.weight(1f))
            }
        }
        if (owner) {
            if (editing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                PrimaryButton(
                    stringResource(R.string.group_save_name),
                    {
                        actions.rename(name.trim(), null)
                        editing = false
                    },
                    Modifier.fillMaxWidth().padding(16.dp),
                    enabled = name.isNotBlank(),
                )
            } else {
                SettingsCard { InfoRow(stringResource(R.string.group_rename), null, onClick = { name = group.name; editing = true }) }
            }
        }
        if (!group.gone && group.isReader(me)) {
            com.chencohen.ptt.ui.talk.QuickTalkCard(quickTalk, actions.setQuickTalk)
            com.chencohen.ptt.ui.talk.WhenAwayCard(notifyOnly, actions.setNotifyOnly)
        }
        SectionTitle(stringResource(R.string.group_members))
        SettingsCard {
            val rows = group.readers.map { it.user to false } + group.invited.filter { !group.isReader(it.user) }.map { it.user to true }
            rows.forEachIndexed { i, (user, invited) ->
                if (i > 0) RowDivider()
                MemberRow(me, user, group, invited, contacts.firstOrNull { it.user == user }, owner, actions)
            }
            if (owner) {
                if (rows.isNotEmpty()) RowDivider()
                InfoRow(stringResource(R.string.group_add_members), null, onClick = actions.addMembers)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (!group.gone && !group.isInvitation(me)) {
            SettingsCard {
                if (owner) {
                    InfoRow(stringResource(R.string.group_delete), stringResource(R.string.group_delete_body), onClick = actions.delete, destructive = true)
                } else {
                    InfoRow(stringResource(R.string.group_leave), stringResource(R.string.group_leave_body), onClick = actions.leave, destructive = true)
                }
            }
        }
    }
}

@Composable
private fun PageNoteText(text: String) {
    Text(text, color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
}

/**
 * A member: the name, and how this phone knows them: you, the owner, verified, a contact not verified, or (never paired
 * with this user) added by the owner, not verified by you.
 */
@Composable
private fun MemberRow(me: String, user: String, group: Group, invited: Boolean, contact: Contact?, owner: Boolean, actions: GroupActions) {
    val labels = buildList {
        if (user == group.owner) add(stringResource(R.string.group_owner))
        if (invited) add(stringResource(R.string.group_invited))
        add(
            when {
                user == me -> stringResource(R.string.group_you)
                contact?.verified == true -> stringResource(R.string.contact_safety_verified)
                contact != null -> stringResource(R.string.group_contact_unverified)
                else -> stringResource(R.string.group_not_verified_by_you)
            },
        )
    }
    Row(
        Modifier.fillMaxWidth()
            .then(if (user != me) Modifier.clickable(role = Role.Button) { actions.openMember(user) } else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user, 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(contact?.displayName?.takeIf { it.isNotBlank() }?.let { "$it · $user" } ?: user, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
            Text(labels.joinToString(" · "), color = LibraryColors.Muted, style = LibraryType.Caption)
        }
        if (owner && user != me) {
            Text(
                stringResource(R.string.group_remove),
                color = LibraryColors.Error,
                style = LibraryType.Caption,
                modifier = Modifier.clickable(role = Role.Button) { actions.remove(user) }.padding(8.dp),
            )
        }
    }
}
