package com.chencohen.ptt.ui.face

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.chencohen.ptt.R
import com.chencohen.ptt.face.FaceMaker
import com.chencohen.ptt.face.FaceStyle
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.PrimaryButton
import com.chencohen.ptt.ui.style.SecondaryButton
import com.chencohen.ptt.ui.style.SettingsScaffold
import com.chencohen.ptt.ui.style.TextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Once after enrolling (or updating to the version that has pictures): the Your picture step was shown. */
internal object PictureAsked {
    private fun prefs(context: Context) = context.getSharedPreferences("ptt_prefs", Context.MODE_PRIVATE)
    fun asked(context: Context): Boolean = prefs(context).getString("picture_asked", null) == "true"
    fun mark(context: Context) = prefs(context).edit().putString("picture_asked", "true").apply()
}

private fun styleName(style: FaceStyle): Int = when (style) {
    FaceStyle.SOFT -> R.string.face_style_soft
    FaceStyle.PAINTED -> R.string.face_style_painted
    FaceStyle.HAND_DRAWN -> R.string.face_style_hand_drawn
    FaceStyle.INK -> R.string.face_style_ink
}

/**
 * Your picture (2026-11-24, hen; docs/screens/picture.md): a selfie or a photo, drawn on the phone in every style side
 * by side, each on light and dark; the person keeps one. The photo is never kept or sent. [onboarding]: shown once
 * after enrolling, with Skip (it never blocks, also without a network).
 */
@Composable
internal fun FacePickerScreen(onboarding: Boolean, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prepared by remember { mutableStateOf<FaceMaker.Prepared?>(null) }
    var working by remember { mutableStateOf(false) }
    var noFace by remember { mutableStateOf(false) }
    val drawn = remember { mutableStateMapOf<FaceStyle, Bitmap>() }
    var chosen by remember { mutableStateOf<FaceStyle?>(null) }
    var saving by remember { mutableStateOf(false) }
    // The chosen style's faces: each expression, for the emoticons (2026-11-24, hen).
    var set by remember { mutableStateOf<Map<com.chencohen.ptt.face.Expression, Bitmap>?>(null) }
    // Expressions taken from a real selfie instead of made (2026-11-24, hen: "also guided"), in the same style.
    val real = remember { mutableStateMapOf<com.chencohen.ptt.face.Expression, Bitmap>() }
    var asking by remember { mutableStateOf<com.chencohen.ptt.face.Expression?>(null) }
    var taking by remember { mutableStateOf<com.chencohen.ptt.face.Expression?>(null) }
    var notQuite by remember { mutableStateOf<com.chencohen.ptt.face.Expression?>(null) }
    LaunchedEffect(prepared, chosen) {
        set = null
        val p = prepared ?: return@LaunchedEffect
        val s = chosen ?: return@LaunchedEffect
        set = runCatching { FaceMaker.drawSet(context, p, s) }.onFailure { android.util.Log.w("FacePicker", "no expressions", it) }.getOrNull()
    }
    val hasPicture = faceOf("") != null

    // The first style's model starts downloading as the step opens; the rest follow, one by one.
    LaunchedEffect(Unit) { for (style in FaceStyle.entries) FaceMaker.download(context, style) }

    fun use(photo: Bitmap?) {
        if (photo == null) return
        working = true
        noFace = false
        drawn.clear()
        chosen = null
        scope.launch {
            val p = runCatching { FaceMaker.prepare(context, photo) }.onFailure { android.util.Log.w("FacePicker", "couldn't prepare the photo", it) }.getOrNull()
            photo.recycle()
            prepared = p
            working = false
            if (p == null) {
                noFace = true
                return@launch
            }
            for (style in FaceStyle.entries) {
                runCatching { FaceMaker.draw(context, p, style) }.onFailure { android.util.Log.w("FacePicker", "couldn't draw $style", it) }.getOrNull()?.let { drawn[style] = it; if (chosen == null) chosen = style }
            }
        }
    }

    val selfie = remember { File(File(context.cacheDir, "selfie").apply { mkdirs() }, "selfie.jpg") }
    val selfieUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.calls", selfie) }
    // A real selfie for one expression: found, cut out and drawn in the chosen style; the expression checked by the
    // face detector (smiling, an eye closed), and kept either way with a word when it doesn't look like it.
    val takeOne = rememberLauncherForActivityResult(FrontCamera()) { ok ->
        val e = taking ?: return@rememberLauncherForActivityResult
        taking = null
        scope.launch {
            val photo = if (ok) decode(context, Uri.fromFile(selfie)) else null
            selfie.delete()
            val style = chosen ?: return@launch
            if (photo == null) return@launch
            working = true
            val p = runCatching { FaceMaker.prepare(context, photo) }.getOrNull()
            val looks = runCatching { FaceMaker.looks(photo, e) }.getOrDefault(true)
            photo.recycle()
            val b = p?.let { runCatching { FaceMaker.draw(context, it, style) }.getOrNull() }
            working = false
            if (b == null) { noFace = true; return@launch }
            real[e] = b
            notQuite = if (looks) null else e
        }
    }
    val take = rememberLauncherForActivityResult(FrontCamera()) { ok ->
        scope.launch {
            val photo = if (ok) decode(context, Uri.fromFile(selfie)) else null
            selfie.delete()
            use(photo)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { use(decode(context, uri)) }
    }
    fun finish() {
        PictureAsked.mark(context)
        done()
    }

    SettingsScaffold(stringResource(R.string.face_title), back = ::finish) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (prepared == null) {
                Box(Modifier.padding(top = 16.dp)) { Avatar("", 120.dp, name = "?") }
                Text(stringResource(R.string.face_body), color = LibraryColors.Muted, style = LibraryType.Body, textAlign = TextAlign.Center)
            }
            if (working) CircularProgressIndicator(Modifier.padding(16.dp).size(32.dp), color = LibraryColors.OnBackground, strokeWidth = 3.dp)
            if (noFace) Text(stringResource(R.string.face_no_face), color = LibraryColors.Error, style = LibraryType.Body, textAlign = TextAlign.Center)
            prepared?.let {
                Text(stringResource(R.string.face_pick_style), color = LibraryColors.Muted, style = LibraryType.Body, textAlign = TextAlign.Center)
                FaceStyle.entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { style -> StyleTile(style, drawn[style], chosen == style, Modifier.weight(1f)) { if (drawn[style] != null) chosen = style } }
                    }
                }
            }
            set?.let { faces ->
                Text(stringResource(R.string.face_expressions), color = LibraryColors.Muted, style = LibraryType.Body, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                FacesRow(faces + real) { e -> asking = e }
                notQuite?.let { e ->
                    Text(stringResource(R.string.face_not_quite, stringResource(expressionName(e))), color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center)
                }
                asking?.let { e ->
                    // One expression: a real selfie for it, or back to the made one.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextAction(stringResource(R.string.face_real_selfie, stringResource(expressionName(e))), {
                            asking = null
                            taking = e
                            takeOne.launch(selfieUri)
                        }, icon = LibraryIcons.Person)
                        if (e in real) TextAction(stringResource(R.string.face_made_one), { real.remove(e); asking = null; if (notQuite == e) notQuite = null })
                    }
                }
            }
            val pickedNow = chosen?.let { drawn[it] }
            if (pickedNow != null) {
                PrimaryButton(stringResource(R.string.face_use), {
                    saving = true
                    scope.launch {
                        val bytes = withContext(Dispatchers.Default) { FaceMaker.encode(pickedNow) }
                        // The expressions too, for the emoticons (each about 12 KB).
                        val faces = withContext(Dispatchers.Default) {
                            (set.orEmpty() + real).filterKeys { it != com.chencohen.ptt.face.Expression.NEUTRAL }.map { (e, b) -> e.code to FaceMaker.encode(b) }.toMap()
                        }
                        com.chencohen.ptt.setMyFaces(context, bytes, faces)
                        saving = false
                        finish()
                    }
                }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = set != null && !saving, busy = saving || set == null)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The selfie is the main action until a picture is drawn; then Use this picture is.
                if (pickedNow == null) PrimaryButton(stringResource(R.string.face_take_selfie), { take.launch(selfieUri) }, Modifier.weight(1f), icon = LibraryIcons.Person)
                else SecondaryButton(stringResource(R.string.face_take_selfie), { take.launch(selfieUri) }, Modifier.weight(1f), icon = LibraryIcons.Person)
                SecondaryButton(stringResource(R.string.face_pick_photo), { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f), icon = LibraryIcons.Photo)
            }
            Text(stringResource(R.string.face_private), color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center)
            if (onboarding) TextAction(stringResource(R.string.face_skip), ::finish)
            else if (hasPicture) TextAction(stringResource(R.string.face_remove), { com.chencohen.ptt.setMyPicture(context, null); finish() }, destructive = true)
        }
    }
}

/** The expressions in a row, each on light above and dark below. */
@Composable
private fun FacesRow(faces: Map<com.chencohen.ptt.face.Expression, Bitmap>, pick: (com.chencohen.ptt.face.Expression) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(faces.entries.filter { it.key != com.chencohen.ptt.face.Expression.NEUTRAL }.toList(), key = { it.key.code }) { (e, b) ->
            val image = remember(b) { b.asImageBitmap() }
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(0.5f to Color(0xFFF4F4F2), 0.5f to Color(0xFF1C1E20)))
                    .clickable(role = Role.Button) { pick(e) }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(image, stringResource(expressionName(e)), contentScale = ContentScale.Fit, modifier = Modifier.size(72.dp))
            }
        }
    }
}

internal fun expressionName(e: com.chencohen.ptt.face.Expression): Int = when (e) {
    com.chencohen.ptt.face.Expression.NEUTRAL -> R.string.face_expr_neutral
    com.chencohen.ptt.face.Expression.SMILE -> R.string.face_expr_smile
    com.chencohen.ptt.face.Expression.LAUGH -> R.string.face_expr_laugh
    com.chencohen.ptt.face.Expression.SAD -> R.string.face_expr_sad
    com.chencohen.ptt.face.Expression.SURPRISED -> R.string.face_expr_surprised
    com.chencohen.ptt.face.Expression.WINK -> R.string.face_expr_wink
    com.chencohen.ptt.face.Expression.ANGRY -> R.string.face_expr_angry
}

/** One style: the face on light and dark at once (split down the middle), its name, a ring while its model comes. */
@Composable
private fun StyleTile(style: FaceStyle, picture: Bitmap?, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val progress by FaceMaker.progressOf(style).collectAsState()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(0.5f to Color(0xFFF4F4F2), 0.5f to Color(0xFF1C1E20)))
                .border(if (chosen) 3.dp else 1.dp, if (chosen) LibraryColors.OnBackground else LibraryColors.Muted.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .clickable(role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) {
                val image = remember(picture) { picture.asImageBitmap() }
                Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            } else {
                val ring = if (progress.total > 0 && !progress.ready) progress.done.toFloat() / progress.total else null
                if (progress.failed) Text(stringResource(R.string.face_offline), color = LibraryColors.Muted, style = LibraryType.Caption, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
                else if (ring != null) CircularProgressIndicator({ ring }, Modifier.size(32.dp), color = LibraryColors.Muted, strokeWidth = 3.dp)
                else CircularProgressIndicator(Modifier.size(24.dp), color = LibraryColors.Muted, strokeWidth = 2.dp)
            }
        }
        Text(stringResource(styleName(style)), color = LibraryColors.OnBackground, style = LibraryType.Caption, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The camera app, asked to start on the front camera (a hint most camera apps follow). */
private class FrontCamera : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        putExtra("android.intent.extras.CAMERA_FACING", 1)
        putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
        putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
    }
}

/** A photo upright (its EXIF turn applied), at most about 1600 px, software pixels. */
private suspend fun decode(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 1600) decoder.setTargetSampleSize(longest / 1600)
        }
    }.getOrNull()
}
