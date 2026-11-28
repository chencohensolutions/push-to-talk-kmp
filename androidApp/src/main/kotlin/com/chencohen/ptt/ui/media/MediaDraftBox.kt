package com.chencohen.ptt.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.MediaKind
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryShapes
import com.chencohen.ptt.ui.style.LibraryType

/** Pictures picked for a chat, not sent yet: shown in the writing bubble, the caption written under them. */
class MediaDraft(
    val messages: List<TextMessage>,
    /** Sends them with [caption], less the items taken out ([removed], by item id). */
    val send: (caption: String, removed: Set<String>) -> Unit,
    /** Drops them all. */
    val drop: () -> Unit,
)

/**
 * The writing bubble with pictures in it (2026-11-28, hen: "feel inside the textbox, the image just above it"): one
 * outline in the writing colour round the pictures, each as wide as the box in its own shape with ✕ to take it out,
 * and under them the caption and Send. More than fit (about half the screen) scroll inside the box. Send sends them
 * as one message; ✕ on the last one drops the draft.
 */
@Composable
internal fun MediaDraftBox(draft: MediaDraft, preview: suspend (messageId: String, itemId: String) -> ByteArray?, modifier: Modifier = Modifier) {
    var caption by rememberSaveable(draft.messages.firstOrNull()?.id) { mutableStateOf("") }
    var removed by remember(draft.messages.firstOrNull()?.id) { mutableStateOf(emptySet<String>()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(draft.messages.firstOrNull()?.id) { runCatching { focus.requestFocus() } }
    val coral = LibraryColors.Writing
    val items = draft.messages.flatMap { m -> m.media?.items.orEmpty().map { m to it } }.filter { it.second.id !in removed }
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { draft.drop() }
        return
    }
    val maxPictures = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
            .clip(LibraryShapes.Card)
            .background(LibraryColors.Background)
            .border(1.5.dp, coral, LibraryShapes.Card),
    ) {
        Column(
            Modifier.heightIn(max = maxPictures).verticalScroll(rememberScrollState()).padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEach { (message, item) ->
                Box {
                    when (item.kind) {
                        MediaKind.IMAGE, MediaKind.VIDEO -> Picture(message, item, preview, select = null) {}
                        else -> Text(item.name, color = LibraryColors.OnBackground, style = LibraryType.Message, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).clip(CircleShape).background(Color(0x99000000))
                            .clickable { removed = removed + item.id },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(LibraryIcons.Close, stringResource(R.string.media_draft_remove), tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (caption.isEmpty()) Text(stringResource(R.string.media_caption_hint), color = LibraryColors.Muted, style = LibraryType.Message)
                BasicTextField(
                    caption, { caption = it }, Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = LibraryType.Message.copy(color = LibraryColors.OnBackground),
                    cursorBrush = SolidColor(LibraryColors.OnBackground),
                    maxLines = 6,
                )
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = draft.drop), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Trash, stringResource(R.string.media_draft_drop), tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(coral).clickable { draft.send(caption, removed) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(LibraryIcons.ArrowUp, stringResource(R.string.media_draft_send), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
