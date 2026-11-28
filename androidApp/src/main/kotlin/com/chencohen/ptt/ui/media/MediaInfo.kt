package com.chencohen.ptt.ui.media

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.media.MediaKeeping
import com.chencohen.ptt.media.OriginalState
import com.chencohen.ptt.messaging.AttachmentContent
import com.chencohen.ptt.messaging.HeldOriginal
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The viewer's Info (2026-11-28, hen: "reveal the original image exif information and what other cached my device
 * saved about that image and which peer holds what quality of the image"): a sheet over the picture with three parts.
 * The original: its size and type as shared, and every EXIF tag of the file once it is on this phone. On this phone:
 * each preview made for it (its size, and whether it is kept here; the one drawn here marked), and the original (the
 * file shared from here, or the copy fetched, or not here). Who has what: the one who shared it has the original; the
 * others the preview made for their screen, with whether it reached them when this phone knows.
 */
@Composable
internal fun MediaInfoSheet(
    message: TextMessage,
    item: AttachmentContent,
    original: OriginalState?,
    keeping: suspend (messageId: String, itemId: String) -> MediaKeeping?,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val ready = (original as? OriginalState.Ready)?.path
    // Read again a moment later: the others answer what they hold (asked as the sheet opens).
    val kept by produceState<MediaKeeping?>(null, message.id, item.id, ready) {
        value = keeping(message.id, item.id)
        kotlinx.coroutines.delay(INFO_REFRESH)
        value = keeping(message.id, item.id)
    }
    val path = ready ?: kept?.originalPath
    val exif by produceState<List<Pair<String, String>>?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { exifOf(context, it) } }
    }
    val names = LocalNames.current
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = close)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.75f).dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Color(0xF21B1F20))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.media_info), color = Color.White, style = LibraryType.Body, modifier = Modifier.weight(1f))
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = close), contentAlignment = Alignment.Center) {
                    Icon(LibraryIcons.Close, stringResource(R.string.media_info_close), tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }

            Heading(stringResource(R.string.media_info_original))
            Line(listOfNotNull(
                (item.width.takeIf { it > 0 }?.let { "$it × ${item.height}" }),
                sizeOf(item.size),
                item.mime.ifEmpty { null },
            ).joinToString(" · "))
            if (item.takenAt > 0) Line(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.takenAt)))
            if (item.hasPlace) Line("%.5f, %.5f".format(item.latE7 / 1e7, item.lonE7 / 1e7))
            when {
                exif != null && exif!!.isNotEmpty() -> exif!!.forEach { (tag, value) -> TagRow(tag, value) }
                path == null -> Muted(stringResource(R.string.media_info_exif_later))
                exif == null -> Muted(stringResource(R.string.media_info_reading))
                else -> Muted(stringResource(R.string.media_info_no_exif))
            }

            Heading(stringResource(R.string.media_info_here))
            kept?.parts?.forEach { part ->
                val what = stringResource(if (part.poster) R.string.media_info_poster else R.string.media_info_preview)
                val where = stringResource(if (part.here) R.string.media_info_kept_here else R.string.media_info_not_here)
                Line(listOfNotNull("$what ${part.width} × ${part.height}", part.mime.ifEmpty { null }, sizeOf(part.bytes), where).joinToString(" · ") +
                    if (part.shown) " · " + stringResource(R.string.media_info_drawn_here) else "")
            }
            kept?.let { k ->
                Line(
                    when {
                        k.originalIsSource -> stringResource(R.string.media_info_original_source)
                        k.originalPath != null -> stringResource(R.string.media_info_original_copy, sizeOf(fileSize(context, k.originalPath.orEmpty())) ?: "")
                        else -> stringResource(R.string.media_info_original_not_here)
                    },
                )
                if (k.onlyCopy) Line(stringResource(R.string.media_info_only_copy))
                if (k.sourceUnreadable) Muted(stringResource(R.string.media_info_source_unreadable))
                if (k.originalPath == null && !k.originalElsewhere) Muted(stringResource(R.string.media_info_nowhere))
                if (k.originalUploadedAt > 0) Line(
                    stringResource(
                        R.string.media_info_on_server,
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(k.originalUploadedAt + ORIGINAL_KEPT)),
                    ),
                )
            }

            Heading(stringResource(R.string.media_info_who))
            val phones = kept?.peers.orEmpty().groupingBy { it.user }.eachCount()
            kept?.peers?.forEach { peer ->
                val name = names(peer.user).let { n -> if ((phones[peer.user] ?: 0) > 1 && peer.device > 0) stringResource(R.string.media_info_peer_phone, n, peer.device) else n }
                if (peer.held >= 0) {
                    val what = when (peer.held) {
                        HeldOriginal.SOURCE -> stringResource(R.string.media_info_held_source, name)
                        HeldOriginal.COPY -> stringResource(R.string.media_info_held_copy, name)
                        HeldOriginal.KEPT -> stringResource(R.string.media_info_held_kept, name)
                        else -> if (peer.keptWidth > 0) stringResource(R.string.media_info_held_preview, name, peer.keptWidth) else stringResource(R.string.media_info_held_nothing, name)
                    }
                    val extra = listOfNotNull(
                        stringResource(R.string.media_info_held_journal).takeIf { peer.journal },
                        peer.toldAt.takeIf { it > 0 }?.let { stringResource(R.string.media_info_told, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) },
                    )
                    Line((listOf(what) + extra).joinToString(" · "))
                } else Line(
                    if (peer.original) stringResource(R.string.media_info_peer_original, name)
                    else stringResource(
                        when (peer.has) {
                            true -> R.string.media_info_peer_has
                            false -> R.string.media_info_peer_not_yet
                            null -> R.string.media_info_peer_unknown
                        },
                        name, peer.previewWidth,
                    ),
                )
            }
            Muted(stringResource(R.string.media_info_who_note))
        }
    }
}

/** Display names in the viewer's Info (the app sets it: a contact's name, or the user). */
internal val LocalNames = androidx.compose.runtime.staticCompositionLocalOf<(String) -> String> { { it } }

@Composable
private fun Heading(text: String) = Text(text, color = Color(0xFFFF8A80), style = LibraryType.Small, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun Line(text: String) = Text(text, color = Color.White, style = LibraryType.Small)

@Composable
private fun Muted(text: String) = Text(text, color = Color(0x99FFFFFF), style = LibraryType.Small)

@Composable
private fun TagRow(tag: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(tag, color = Color(0x99FFFFFF), style = LibraryType.Tiny, modifier = Modifier.weight(0.45f))
    Text(value, color = Color.White, style = LibraryType.Tiny, modifier = Modifier.weight(0.55f))
}

private fun sizeOf(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes < 1_000_000 -> "${(bytes + 999) / 1000} KB"
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}

private fun fileSize(context: Context, path: String): Long = runCatching {
    if (path.startsWith("content:")) context.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")?.use { it.length } ?: 0L
    else File(path.removePrefix("file://")).length()
}.getOrDefault(0L)

/** Every EXIF tag of the file at [path] that has a value, by its name. */
private fun exifOf(context: Context, path: String): List<kotlin.Pair<String, String>> = runCatching {
    val exif = if (path.startsWith("content:")) {
        context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { ExifInterface(it.fileDescriptor).let { e -> read(e) } } ?: emptyList()
    } else read(ExifInterface(path.removePrefix("file://")))
    exif
}.getOrDefault(emptyList())

private fun read(exif: ExifInterface): List<kotlin.Pair<String, String>> =
    ExifInterface::class.java.fields
        .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
        .mapNotNull { field -> (field.get(null) as? String) }
        .distinct()
        .mapNotNull { tag -> exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() && it.length < 200 }?.let { tag to it } }
        .sortedBy { it.first }

/** How long after opening the sheet it reads again, once the others have answered what they hold. */
private const val INFO_REFRESH = 4_000L

/** How long the server keeps an original after it is uploaded (MediaSharing's, which this module can't see). */
private const val ORIGINAL_KEPT = 29L * 24 * 60 * 60 * 1000
