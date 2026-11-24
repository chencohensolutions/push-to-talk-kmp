package com.chencohen.ptt.ui.face

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohen.ptt.face.Expression
import com.chencohen.ptt.ui.style.LibraryColors

/** One of [author]'s faces sent as a message (2026-11-24, hen): free-standing; [fallback] (an emoji) while it has none. */
@Composable
internal fun FaceSticker(author: String, code: Int, fallback: String) {
    val face = faceOf(author, code)
    if (face != null) {
        Image(face, Expression.of(code)?.let { stringResource(expressionName(it)) }, contentScale = ContentScale.Fit, modifier = Modifier.size(112.dp))
    } else {
        Text(fallback, fontSize = 48.sp)
    }
}

/** My faces in a row: a tap picks one ([current]: the one picked before, ringed). */
@Composable
internal fun FaceTray(current: Int?, pick: (Int) -> Unit) {
    val context = LocalContext.current
    val ids by Faces.ids(context).collectAsState()
    var codes by remember { mutableStateOf(listOf(Expression.NEUTRAL.code)) }
    LaunchedEffect(ids[""]) { codes = (listOf(Expression.NEUTRAL.code) + com.chencohen.ptt.myFaceCodes(context)).distinct() }
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(codes, key = { it }) { code ->
            val face = faceOf("", code)
            val name = Expression.of(code)?.let { stringResource(expressionName(it)) }
            Box(
                Modifier.size(60.dp)
                    .background(if (current == code) LibraryColors.Accent.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = name) { pick(code) },
                contentAlignment = Alignment.Center,
            ) {
                if (face != null) Image(face, name, contentScale = ContentScale.Fit, modifier = Modifier.size(54.dp))
                else Text(Expression.of(code)?.emoji.orEmpty(), fontSize = 28.sp)
            }
        }
    }
}

/** Who reacted and with which of their faces, small and overlapping a little. */
@Composable
internal fun Reactions(reactions: Map<String, Int>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        reactions.entries.sortedBy { it.key }.forEach { (who, face) ->
            val image = faceOf(who, face - 1)
            if (image != null) Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.size(26.dp))
            else Text(Expression.of(face - 1)?.emoji.orEmpty(), fontSize = 16.sp)
        }
    }
}
