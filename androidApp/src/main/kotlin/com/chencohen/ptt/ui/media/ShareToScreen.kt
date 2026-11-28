package com.chencohen.ptt.ui.media

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.contacts.ContactRow
import com.chencohen.ptt.ui.groups.GroupRow
import com.chencohen.ptt.ui.style.EmptyState
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.SettingsScaffold

/**
 * Send to (docs/screens/share.md; 2026-11-28, hen): what was shared to Talk from another app's Share menu goes to the
 * chat picked here, the latest chats first, as Signal's share sheet. A tap sends it and opens that chat.
 */
@Composable
internal fun ShareToScreen(
    count: Int,
    contacts: List<Contact>,
    groups: List<Group>,
    me: String,
    conversations: Map<String, List<TextMessage>>,
    back: () -> Unit,
    pick: (chat: String) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.share_title), back = back) {
        Text(stringResource(R.string.share_count, count), color = LibraryColors.Muted, style = LibraryType.Caption, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        val people = contacts.filter { it.paired && !it.blocked }.map { it.user to it }
        val teams = groups.filter { !it.gone && it.isReader(me) }.map { it.chat to it }
        val chats = (people + teams).sortedByDescending { (chat, _) -> conversations[chat]?.lastOrNull()?.at ?: 0L }
        if (chats.isEmpty()) {
            EmptyState(stringResource(R.string.share_none))
            return@SettingsScaffold
        }
        for ((chat, what) in chats) {
            val last = conversations[chat]?.lastOrNull()
            when (what) {
                is Contact -> ContactRow(what, last) { pick(chat) }
                is Group -> GroupRow(what, last, unread = 0) { pick(chat) }
            }
        }
    }
}
