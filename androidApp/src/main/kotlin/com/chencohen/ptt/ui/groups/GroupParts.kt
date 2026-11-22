package com.chencohen.ptt.ui.groups

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64

// The pieces the group screens share (docs/screens/groups.md): the group's picture, its row in Chats, and a contact
// to tick.

/** The group's picture in a circle, or its first letter with the group icon's look when it has none. */
@Composable
internal fun GroupAvatar(name: String, picture: String?, size: Dp = 48.dp) {
    val bitmap = remember(picture) { picture?.let { runCatching { Base64.decode(it) }.getOrNull() }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Box(Modifier.size(size).clip(CircleShape).background(LibraryColors.Raised), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            name.isNotBlank() -> Text(name.first().uppercaseChar().toString(), color = LibraryColors.OnBackground, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
            else -> Icon(LibraryIcons.Group, null, tint = LibraryColors.Muted, modifier = Modifier.size(size / 2))
        }
    }
}

/** The name a group shows: its own, or "A group" while the owner's change hasn't come. */
@Composable
internal fun groupName(group: Group): String = group.name.ifBlank { stringResource(R.string.group_unnamed) }

/** A group in Chats: its picture, its name, and the last message with who wrote it. */
@Composable
internal fun GroupRow(group: Group, last: TextMessage?, unread: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(group.name, group.picture)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(groupName(group), color = LibraryColors.OnBackground, style = LibraryType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when {
                group.gone -> stringResource(R.string.group_gone_short)
                last != null -> com.chencohen.ptt.ui.contacts.previewOf(last).let { if (last.outgoing) stringResource(R.string.chats_you, it) else "${last.author}: $it" }
                else -> stringResource(R.string.group_members_count, group.readers.size)
            }
            Text(line, color = LibraryColors.Muted, style = LibraryType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (unread > 0) {
            Box(Modifier.padding(end = 8.dp).background(LibraryColors.Inverse, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(if (unread > 99) "99+" else unread.toString(), color = LibraryColors.OnInverse, style = LibraryType.Small)
            }
        }
    }
}

/** A contact with a tick box, for choosing members. */
@Composable
internal fun PickRow(contact: Contact, picked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onToggle).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.user, 40.dp)
        Spacer(Modifier.width(14.dp))
        val title = contact.name.takeIf { it != contact.user }?.let { "$it · ${contact.user}" } ?: contact.user
        Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Checkbox(checked = picked, onCheckedChange = { onToggle() })
    }
}

/** A picked photo made small for a group: at most 192 pixels a side, JPEG. Null when it can't be read. */
internal fun groupPicture(context: android.content.Context, uri: Uri): ByteArray? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val side = maxOf(info.size.width, info.size.height)
        if (side > MAX_SIDE) decoder.setTargetSampleSize(side / MAX_SIDE)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
    val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    ByteArrayOutputStream().use { out ->
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }
}.getOrNull()

private const val MAX_SIDE = 192
