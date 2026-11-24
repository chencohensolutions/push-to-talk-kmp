package com.chencohen.ptt.ui.face

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.chencohen.ptt.messagingState
import com.chencohen.ptt.pictureOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

// People's pictures (2026-11-24, hen): an illustrated face cut out on a clear background, drawn free-standing wherever
// the letter circle was. Decoded once per picture id and kept in a small cache.
internal object Faces {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ids: StateFlow<Map<String, String>>? = null
    private val cache = LruCache<String, ImageBitmap>(96)

    /** Who has a picture, with its id (MessagingState.pictures). */
    fun ids(context: Context): StateFlow<Map<String, String>> = ids ?: synchronized(this) {
        ids ?: messagingState(context.applicationContext).map { it.pictures }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, messagingState(context.applicationContext).value.pictures).also { ids = it }
    }

    fun cached(user: String, id: String): ImageBitmap? = cache.get("$user/$id")

    suspend fun loadFace(context: Context, user: String, code: Int, id: String): ImageBitmap? {
        cached("$user#$code", id)?.let { return it }
        val bytes = com.chencohen.ptt.faceImageOf(context, user, code) ?: return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            ?.also { cache.put("$user#$code/$id", it) }
    }

    suspend fun load(context: Context, user: String, id: String): ImageBitmap? {
        cached(user, id)?.let { return it }
        val bytes = pictureOf(context, user) ?: return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            ?.also { cache.put("$user/$id", it) }
    }
}

/** [user]'s face in expression [code] (this user's own: ""), or null while it loads or when there is none. */
@Composable
internal fun faceOf(user: String, code: Int): ImageBitmap? {
    if (code == com.chencohen.ptt.face.Expression.NEUTRAL.code) return faceOf(user)
    val context = LocalContext.current
    val ids by Faces.ids(context).collectAsState()
    val id = ids[user] ?: return null
    val face by produceState(Faces.cached("$user#$code", id), user, id, code) { value = Faces.loadFace(context, user, code, id) }
    return face
}

/** [user]'s face (this user's own: ""), or null while it loads or when they have none. */
@Composable
internal fun faceOf(user: String): ImageBitmap? {
    val context = LocalContext.current
    val ids by Faces.ids(context).collectAsState()
    val id = ids[user] ?: return null
    val face by produceState(Faces.cached(user, id), user, id) { value = Faces.load(context, user, id) }
    return face
}
