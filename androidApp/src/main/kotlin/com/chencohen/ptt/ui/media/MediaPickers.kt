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
import androidx.compose.runtime.setValue
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

/**
 * The phone's own camera, for as many shots as the user takes (2026-11-28, hen: "can the app open the native camera app
 * to take an image? and that image is also be saved on the device outside the app"; "many in one go"). Every shot is a
 * photo in the gallery, kept after it is sent. With access to all photos, the camera opens as itself and, back in Talk,
 * everything taken since it opened joins the draft. Without it, Talk asks the camera for one shot at a time into a
 * gallery entry Talk makes (DCIM/Camera, so Talk can read it again later), opening it again after each one until Back.
 * [taken] gets the shots.
 */
@Composable
internal fun rememberCamera(taken: (List<Uri>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val onTaken by rememberUpdatedState(taken)
    // When the camera was opened as itself (ms), 0: it wasn't.
    var since by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableLongStateOf(0L) }
    // The gallery entry of the shot being taken, and the shots of this round.
    var shot by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var round by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(listOf<String>()) }
    lateinit var next: () -> Unit
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = shot?.let(Uri::parse)
        shot = null
        if (uri != null && ok) {
            runCatching { context.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
            round = round + uri.toString()
            next()
        } else {
            if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
            // Back in the camera: the round ends, its shots go to the draft.
            val done = round.map(Uri::parse)
            round = emptyList()
            if (done.isNotEmpty()) onTaken(done)
        }
    }
    next = {
        val uri = newShot(context)
        if (uri == null) {
            val done = round.map(Uri::parse)
            round = emptyList()
            if (done.isNotEmpty()) onTaken(done)
        } else {
            shot = uri.toString()
            runCatching { capture.launch(uri) }.onFailure { runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event != androidx.lifecycle.Lifecycle.Event.ON_RESUME || since == 0L) return@LifecycleEventObserver
            val from = since
            since = 0L
            val shots = takenSince(context, from)
            if (shots.isNotEmpty()) onTaken(shots)
        }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch) }
    }
    val still = {
        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (intent.resolveActivity(context.packageManager) == null) next()
        else {
            since = System.currentTimeMillis()
            runCatching { context.startActivity(intent) }.onFailure { since = 0L; next() }
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasAllPhotos(context)) still() else next()
    }
    return {
        when {
            hasAllPhotos(context) -> still()
            else -> runCatching { ask.launch(ALL_PHOTOS) }.onFailure { next() }
        }
    }
}

/** A new photo's entry in the gallery (DCIM/Camera, pending until it is taken); null when it can't be made. */
private fun newShot(context: Context): Uri? = runCatching {
    val name = "IMG_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date()) + ".jpg"
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DCIM + "/Camera")
        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    context.contentResolver.insert(android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
}.getOrNull()

/**
 * The pictures and videos the camera saved since [since] (ms), oldest first: only what is in DCIM/Camera, so a
 * screenshot, a download or a synced picture added meanwhile doesn't join the draft (2026-11-28, hen: back from the
 * camera without a shot, a bank app's screenshot was added).
 */
private fun takenSince(context: Context, since: Long): List<Uri> = runCatching {
    val files = android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
    val columns = arrayOf(android.provider.MediaStore.Files.FileColumns._ID, android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE)
    val where = "${android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND ${android.provider.MediaStore.MediaColumns.DATE_ADDED} >= ? AND " +
        "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    val args = arrayOf(
        android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        (since / 1000).toString(),
        android.os.Environment.DIRECTORY_DCIM + "/Camera/%",
    )
    val out = mutableListOf<Uri>()
    context.contentResolver.query(files, columns, where, args, "${android.provider.MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val base = if (c.getInt(1) == android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            out += android.content.ContentUris.withAppendedId(base, id)
        }
    }
    out.take(30)
}.getOrDefault(emptyList())
