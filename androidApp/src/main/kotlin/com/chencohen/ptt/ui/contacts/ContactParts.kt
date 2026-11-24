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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.PairingError
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType

// The pieces the contact screens share (docs/screens/contacts.md): the letter circle and a contact's row.

/**
 * [user]'s face: their picture when they have one (2026-11-24, hen: an illustrated face on a clear background, drawn
 * free-standing, no disc and no crop), else [name]'s first letter in a Raised circle.
 */
@Composable
internal fun Avatar(user: String, size: Dp = 48.dp, name: String = user) {
    val face = com.chencohen.ptt.ui.face.faceOf(user)
    if (face != null) {
        androidx.compose.foundation.Image(face, name, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.size(size))
        return
    }
    Box(Modifier.size(size).clip(CircleShape).background(LibraryColors.Raised), contentAlignment = Alignment.Center) {
        val letter = name.firstOrNull()?.uppercaseChar()
        if (letter != null) {
            Text(letter.toString(), color = LibraryColors.OnBackground, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
        } else {
            Icon(LibraryIcons.Person, null, tint = LibraryColors.Muted, modifier = Modifier.size(size / 2))
        }
    }
}

/**
 * A contact in Chats or New chat: the letter, the name, and one line under it: what is wrong first (blocked, a
 * changed safety number in orange, not confirmed yet), else the last test message, else "Paired".
 */
@Composable
internal fun ContactRow(contact: Contact, last: TextMessage?, unread: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.user)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // The name they gave, with the user name the server knows them by: two people may both be "Dana".
            val title = contact.name.takeIf { it != contact.user }?.let { "$it · ${contact.user}" } ?: contact.user
            Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (line, warn) = when {
                contact.blocked -> stringResource(R.string.chats_blocked) to false
                contact.changedKey != null -> stringResource(R.string.chats_key_changed) to true
                !contact.paired -> stringResource(R.string.chats_waiting) to false
                last != null -> (if (last.outgoing) stringResource(R.string.chats_you, previewOf(last)) else previewOf(last)) to false
                else -> stringResource(R.string.chats_paired) to false
            }
            Text(
                line,
                color = if (warn) LibraryColors.Warning else LibraryColors.Muted,
                style = LibraryType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unread > 0) {
            // Unread texts: Ink's count, Signal's dot.
            Box(Modifier.padding(end = 8.dp).background(LibraryColors.Inverse, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(if (unread > 99) "99+" else unread.toString(), color = LibraryColors.OnInverse, style = LibraryType.Small)
            }
        }
        if (contact.verified && contact.changedKey == null) {
            Icon(LibraryIcons.Shield, stringResource(R.string.safety_verified), tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** What a pairing error says, in the screens' words. */
@Composable
internal fun pairingErrorText(error: PairingError): String = stringResource(
    when (error) {
        PairingError.NOT_A_CODE -> R.string.pair_error_not_a_code
        PairingError.NEWER_VERSION -> R.string.pair_error_newer_version
        PairingError.OWN_CODE -> R.string.pair_error_own_code
        PairingError.OTHER_SERVER -> R.string.pair_error_other_server
        PairingError.EXPIRED -> R.string.pair_error_expired
        PairingError.KEYS_DIFFER -> R.string.pair_error_keys_differ
        PairingError.NO_DEVICES -> R.string.pair_error_no_devices
        PairingError.BLOCKED -> R.string.pair_error_blocked
        PairingError.NOT_FOUND -> R.string.pair_error_not_found
        PairingError.RATE_LIMITED -> R.string.pair_error_rate_limited
        PairingError.NOT_READY -> R.string.pair_error_not_ready
        PairingError.UNREACHABLE -> R.string.pair_error_unreachable
        PairingError.INVITE_FOR_SOMEONE_ELSE -> R.string.pair_error_invite_someone_else
        PairingError.INVALID_EMAIL -> R.string.pair_error_invalid_email
        PairingError.INVITES_OFF -> R.string.pair_error_invites_off
    },
)

/** The preview line of a message in Chats: its text, or what kind of clip it is. */
@Composable
internal fun previewOf(message: com.chencohen.ptt.messaging.TextMessage): String {
    if (message.call != null) return com.chencohen.ptt.ui.calls.callLine(message)
    val clip = message.clip ?: return message.body
    val length = clip.durationMs.takeIf { it > 0 }?.let { com.chencohen.ptt.ui.talk.clipLength(it) }
    return when (clip.kind) {
        com.chencohen.ptt.messaging.ClipKind.BURST -> if (length != null) stringResource(R.string.chats_burst_length, length) else stringResource(R.string.chats_burst)
        com.chencohen.ptt.messaging.ClipKind.VOICE -> if (length != null) stringResource(R.string.chats_voice_length, length) else stringResource(R.string.chats_voice)
    }
}
