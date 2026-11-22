package com.chencohen.ptt.ui.calls

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.chencohen.ptt.R
import com.chencohen.ptt.calls.KeptCallFile
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Where a kept call is, while it is made into a file on this phone and once it is there. */
sealed interface KeptLoad {
    /** Downloading and decrypting; [progress] 0 to 1. */
    data class Getting(val progress: Float) : KeptLoad

    data class Ready(
        val file: File,
        val people: List<KeptCallFile.Person>,
        val durationMs: Long,
        /** From the call's event logs: each device's camera rotation over time, and how each viewer showed each video. */
        val rotations: Map<String, List<Pair<Long, Int>>> = emptyMap(),
        val views: List<KeptCallFile.ViewAt> = emptyList(),
    ) : KeptLoad

    data class Failed(val nothingKept: Boolean) : KeptLoad
}

/**
 * Makes the kept call [message] into one Matroska file to play (docs/screens/calls.md, "Playing a kept call"), in the
 * app's cache only while the player is open (2026-11-22, hen: saved calls live in the folder chosen, not in the app).
 * [keptTracks] is callRecording, [read] reads a blob.
 */
suspend fun loadKeptCall(
    context: android.content.Context,
    message: TextMessage,
    keptTracks: suspend (TextMessage) -> com.chencohen.ptt.messaging.KeptCall,
    read: suspend (String, suspend (ByteArray) -> Unit) -> Unit,
    nameOf: (String) -> String,
    progress: (Float) -> Unit,
): KeptLoad = withContext(Dispatchers.IO) {
    val key = message.call?.keptKey ?: return@withContext KeptLoad.Failed(nothingKept = true)
    val scratch = File(context.cacheDir, "kept")
    val out = File(scratch, "play-${message.id}.mkv")
    try {
        val kept = keptTracks(message)
        if (kept.tracks.isEmpty()) return@withContext KeptLoad.Failed(nothingKept = true)
        scratch.mkdirs()
        val made = KeptCallFile(read, scratch).make(key, kept.tracks, nameOf, out.outputStream(), progress)
        KeptLoad.Ready(out, made.people, made.durationMs, made.rotations, made.views)
    } catch (e: kotlinx.coroutines.CancellationException) {
        out.delete()
        throw e
    } catch (e: Exception) {
        android.util.Log.w("KeptCall", "couldn't make the kept call", e)
        out.delete()
        KeptLoad.Failed(nothingKept = false)
    } finally {
        // Decrypted pieces never stay behind.
        scratch.listFiles()?.filter { it != out }?.forEach { it.delete() }
    }
}

/** Where Save as file put a kept call, or why it couldn't. */
sealed interface KeptSave {
    data class Saved(val uri: Uri, val where: String) : KeptSave

    /** The folder chosen in Settings › Calls isn't there any more (a card or drive taken out). */
    data class FolderGone(val folder: String) : KeptSave

    data object Failed : KeptSave
}

/**
 * Settings › Calls › Save calls to (2026-11-22, hen): a folder picked with Android's own picker (any storage, an SD
 * card or a USB drive too), kept with a lasting permission; none: Downloads › Talk.
 */
object CallsFolder {
    private const val PREFS = "calls_folder"
    private const val KEY = "tree"

    fun tree(context: android.content.Context): Uri? =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY, null)?.let(Uri::parse)

    /** [tree] from the picker (null: back to Downloads › Talk); the old one's permission is let go. */
    fun set(context: android.content.Context, tree: Uri?) {
        val resolver = context.contentResolver
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        tree(context)?.takeIf { it != tree }?.let { old -> runCatching { resolver.releasePersistableUriPermission(old, flags) } }
        if (tree != null) runCatching { resolver.takePersistableUriPermission(tree, flags) }
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(KEY, tree?.toString()).apply()
    }

    /** The folder's name as its storage shows it; null when it can't be read (gone). */
    fun name(context: android.content.Context, tree: Uri): String? = runCatching {
        val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** What Settings shows: the folder's name, or Downloads › Talk. */
    fun label(context: android.content.Context): String {
        val tree = tree(context) ?: return context.getString(R.string.calls_folder_default)
        return name(context, tree) ?: context.getString(R.string.calls_folder_gone, tree.lastPathSegment?.substringAfterLast(':').orEmpty())
    }
}

/**
 * Writes the kept call's file [file] (made for the player) as "<title> <date>.mkv" into the folder chosen in Settings
 * › Calls, or into Downloads › Talk: every person's audio and video, as made. It is copied straight there.
 */
suspend fun saveKeptCall(context: android.content.Context, file: File, title: String, at: Long): KeptSave = withContext(Dispatchers.IO) {
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.ROOT).format(java.util.Date(at))
    val name = "${title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)} $stamp.mkv"
    val resolver = context.contentResolver
    val tree = CallsFolder.tree(context)
    if (tree != null) {
        val folder = CallsFolder.name(context, tree) ?: return@withContext KeptSave.FolderGone(tree.lastPathSegment?.substringAfterLast(':').orEmpty())
        return@withContext try {
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val uri = android.provider.DocumentsContract.createDocument(resolver, parent, "video/x-matroska", name)
                ?: return@withContext KeptSave.FolderGone(folder)
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
                throw e
            }
            KeptSave.Saved(uri, folder)
        } catch (e: Exception) {
            android.util.Log.w("KeptCall", "couldn't save to the chosen folder", e)
            if (CallsFolder.name(context, tree) == null) KeptSave.FolderGone(folder) else KeptSave.Failed
        }
    }
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/x-matroska")
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Talk")
        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext KeptSave.Failed
    try {
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        KeptSave.Saved(uri, context.getString(R.string.calls_folder_default))
    } catch (e: Exception) {
        android.util.Log.w("KeptCall", "couldn't save to Downloads", e)
        runCatching { resolver.delete(uri, null, null) }
        KeptSave.Failed
    }
}

/** Shares [uri] (a saved kept call). */
fun shareKeptCall(context: android.content.Context, uri: Uri) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("video/x-matroska")
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, context.getString(R.string.call_share)))
}

/**
 * A kept call played back: everyone's picture in a grid (their name on it; an avatar for one without video), each
 * with their own voice, kept together by one set of controls. One player per person over the same file, each on that
 * person's tracks; the first leads and the others follow it.
 */
@Composable
fun KeptCallScreen(title: String, load: KeptLoad, back: () -> Unit, save: () -> Unit, saving: Boolean = false) {
    // The file to play is the app's only while the player is open.
    DisposableEffect(load) { onDispose { (load as? KeptLoad.Ready)?.file?.delete() } }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val backLabel = stringResource(R.string.kept_back)
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = backLabel, onClick = back), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Back, backLabel, tint = Color.White)
            }
            Text(title, color = Color.White, style = LibraryType.RowTitle, modifier = Modifier.weight(1f))
            if (load is KeptLoad.Ready) {
                Text(
                    stringResource(if (saving) R.string.kept_saving else R.string.kept_save), color = Color.White, style = LibraryType.Small,
                    modifier = Modifier.padding(horizontal = 4.dp).background(Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable(enabled = !saving, role = Role.Button, onClick = save).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        when (load) {
            is KeptLoad.Getting -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.kept_getting), color = Color.White, style = LibraryType.Body, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { load.progress }, modifier = Modifier.fillMaxWidth())
            }
            is KeptLoad.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (load.nothingKept) R.string.kept_nothing else R.string.kept_failed),
                    color = Color.White, style = LibraryType.Body, textAlign = TextAlign.Center,
                )
            }
            is KeptLoad.Ready -> KeptCallPlayer(load)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun KeptCallPlayer(ready: KeptLoad.Ready) {
    val context = LocalContext.current
    val people = ready.people
    val players = remember(ready.file) {
        people.map { person ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(ready.file)))
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) = choose(this@apply, tracks, person, people)
                })
                prepare()
            }
        }
    }
    val sizes = remember(ready.file) { mutableStateMapOf<Int, Float>() }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(players) {
        players.forEachIndexed { i, p ->
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) sizes[i] = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (i == 0 && playbackState == Player.STATE_ENDED) {
                        players.forEach { it.pause() }
                        playing = false
                    }
                }
            })
        }
        onDispose { players.forEach { it.release() } }
    }
    // The first player leads: the others follow its position.
    LaunchedEffect(players) {
        while (isActive) {
            val lead = players.first()
            if (dragging == null) position = lead.currentPosition
            if (playing) players.drop(1).forEach { p -> if (abs(p.currentPosition - lead.currentPosition) > 200) p.seekTo(lead.currentPosition) }
            delay(250)
        }
    }
    // Whole pictures, or each as the others saw it (their Fill crop and tile shape, from the call's log).
    var asSeen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (ready.views.isNotEmpty()) {
            Text(
                stringResource(if (asSeen) R.string.kept_whole else R.string.kept_as_seen), color = Color.White, style = LibraryType.Small,
                modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 4.dp)
                    .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable(role = Role.Button) { asSeen = !asSeen }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        val columns = if (people.size <= 2) 1 else 2
        val rows = people.chunked(columns)
        Column(Modifier.weight(1f).fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { c, person ->
                        val i = r * columns + c
                        Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
                            if (person.video != null) {
                                // The sender's camera rotation at this moment, from their log (the frames come as captured).
                                // The file starts the track turned by its first rotation, which the player applies itself (and
                                // reports the turned size); only the turns after it are left to do here.
                                val turns = ready.rotations[person.identity].orEmpty()
                                val first = turns.firstOrNull()?.second ?: 0
                                val rotation = ((turns.lastOrNull { it.first <= position }?.second ?: first) - first + 360) % 360
                                val view = if (asSeen) {
                                    ready.views.lastOrNull { it.of == person.identity.substringBeforeLast('.') && it.ms <= position }
                                        ?: ready.views.firstOrNull { it.of == person.identity.substringBeforeLast('.') }
                                } else {
                                    null
                                }
                                VideoTile(players[i], sizes[i] ?: (4f / 3f), rotation, view?.takeIf { it.fill }?.aspect)
                            } else {
                                Avatar(person.name, size = 96.dp)
                            }
                            Text(
                                person.name, color = Color.White, style = LibraryType.Small,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                                    .background(LibraryColors.PillScrim, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = stringResource(if (playing) R.string.kept_pause else R.string.kept_play)
            Box(
                Modifier.size(48.dp).background(Color.White.copy(alpha = 0.18f), CircleShape).clickable(role = Role.Button, onClickLabel = label) {
                    if (playing) {
                        players.forEach { it.pause() }
                    } else {
                        val lead = players.first()
                        if (lead.playbackState == Player.STATE_ENDED) players.forEach { it.seekTo(0) }
                        players.forEach { it.seekTo(lead.currentPosition) }
                        players.forEach { it.play() }
                    }
                    playing = !playing
                },
                contentAlignment = Alignment.Center,
            ) { Icon(if (playing) LibraryIcons.Pause else LibraryIcons.Play, label, tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            val duration = ready.durationMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val to = ((dragging ?: 0f) * duration).toLong()
                    players.forEach { it.seekTo(to) }
                    position = to
                    dragging = null
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text("${clock(position)} / ${clock(ready.durationMs)}", color = Color.White, style = LibraryType.Small)
        }
    }
}

/** [player] plays only [person]'s picture and voice. */
@OptIn(UnstableApi::class)
private fun choose(player: ExoPlayer, tracks: Tracks, person: KeptCallFile.Person, people: List<KeptCallFile.Person>) {
    fun group(type: Int, number: Int?, order: Int): Tracks.Group? {
        if (number == null) return null
        val all = tracks.groups.filter { it.type == type }
        return all.firstOrNull { g -> g.mediaTrackGroup.getFormat(0).id?.substringAfterLast(':') == number.toString() }
            ?: all.getOrNull(order)
    }
    val video = group(C.TRACK_TYPE_VIDEO, person.video, people.filter { it.video != null }.indexOf(person))
    val audio = group(C.TRACK_TYPE_AUDIO, person.audio, people.filter { it.audio != null }.indexOf(person))
    val params = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, video == null)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audio == null)
        .clearOverrides()
    video?.let { params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
    audio?.let { params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
    val built = params.build()
    if (built != player.trackSelectionParameters) player.trackSelectionParameters = built
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** A progress bar's value for [KeptLoad.Getting], kept between recompositions. */
@Composable
fun rememberKeptProgress() = remember { mutableFloatStateOf(0f) }

/**
 * One person's video in the player: turned upright by [rotation] (clockwise degrees), whole in its tile; with [crop]
 * (a tile's shape, width for 1 high) cut to that shape as a viewer saw it with Fill.
 */
@Composable
private fun VideoTile(player: ExoPlayer, frameAspect: Float, rotation: Int, crop: Float?) {
    val turned = rotation % 180 != 0
    val shown = if (turned) 1f / frameAspect else frameAspect
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val box = crop ?: shown
        // The box (the picture's own shape, or the viewer's tile) fitted in the tile...
        val (bw, bh) = if (w / h > box) (h * box) to h else w to (w / box)
        // ...and the picture covering it.
        val (cw, ch) = if (shown > box) (bh * shown) to bh else bw to (bw / shown)
        val (vw, vh) = if (turned) ch to cw else cw to ch
        with(density) {
            Box(Modifier.size(bw.toDp(), bh.toDp()).clipToBounds(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { android.view.TextureView(it).also { view -> player.setVideoTextureView(view) } },
                    modifier = Modifier.requiredSize(vw.toDp(), vh.toDp()).graphicsLayer { rotationZ = rotation.toFloat() },
                )
            }
        }
    }
}
