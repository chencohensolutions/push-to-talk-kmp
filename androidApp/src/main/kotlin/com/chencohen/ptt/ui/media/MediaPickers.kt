package com.chencohen.ptt.ui.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** Samsung's Gallery, whose picker picks many (2026-11-28, hen: "can i call galaxy gallery app to pick images?"). */
private const val SAMSUNG_GALLERY = "com.sec.android.gallery3d"

/**
 * The composer's + (2026-11-28, hen): [open] with true picks pictures and videos in Samsung's Gallery (Android's photo
 * picker where there is none), with false files (Open document, kept readable: the original is read again when
 * someone opens it). [picked] gets what was chosen.
 */
@Composable
internal fun rememberMediaPicker(picked: (List<Uri>) -> Unit): (gallery: Boolean) -> Unit {
    val context = LocalContext.current
    val onPicked by rememberUpdatedState(picked)
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onPicked(urisOf(result.data))
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { keep(context, it) }
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return { pickGallery ->
        if (pickGallery) {
            val samsung = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                setPackage(SAMSUNG_GALLERY)
            }
            if (samsung.resolveActivity(context.packageManager) != null) {
                runCatching { gallery.launch(samsung) }.onFailure { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            } else {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        } else {
            files.launch(arrayOf("*/*"))
        }
    }
}

/** The URIs a picker or a Share gave: its data, or each of its clip's. */
internal fun urisOf(intent: Intent?): List<Uri> {
    intent ?: return emptyList()
    val clip = intent.clipData
    val fromClip = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
    @Suppress("DEPRECATION")
    val streams = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }
    return (streams + fromClip + listOfNotNull(intent.data)).distinct()
}

/** Keeps [uri] readable after this run (a document's grant), where the provider lets it be kept. */
internal fun keep(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}
