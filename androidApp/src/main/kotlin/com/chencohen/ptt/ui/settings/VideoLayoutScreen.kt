package com.chencohen.ptt.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.calls.CallGrid
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.Pink
import com.chencohen.ptt.ui.style.RowDivider
import com.chencohen.ptt.ui.style.SectionTitle
import com.chencohen.ptt.ui.style.SettingsCard
import com.chencohen.ptt.ui.style.SettingsScaffold
import com.chencohen.ptt.ui.talk.SwitchRow

/**
 * Settings › Call settings › Video call layout (2026-11-23, hen, from the approved mockup): a small call screen that
 * shows the choices as they change, then Layout (Maximized | Normal), Follow face, Face height, Zoom (Whole to Close)
 * and where Your video rests (six places), and the gestures that change the same values in a call. The values are the
 * ones the call screen keeps ("call_view"), so the two always agree.
 */
@Composable
internal fun VideoLayoutScreen(back: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("call_view", android.content.Context.MODE_PRIVATE) }
    var maximized by remember { mutableStateOf(prefs.getBoolean("fill", true)) }
    var follow by remember { mutableStateOf(prefs.getBoolean("follow_remote", false)) }
    var faceAt by remember { mutableFloatStateOf(prefs.getFloat("face_at", 0.33f)) }
    var slot by remember { mutableIntStateOf(prefs.getInt("own_slot", 5)) }
    // The zoom as kept by the call screen (1.0: the whole picture); 0: never pinched (Maximized fills, Normal whole).
    val config = LocalConfiguration.current
    val screenW = config.screenWidthDp.toFloat().coerceAtLeast(1f)
    val screenH = config.screenHeightDp.toFloat().coerceAtLeast(1f)
    val picture = 3f / 4f
    val fit = CallGrid.content(screenW, screenH, picture, false)
    val coverRatio = maxOf(screenW / fit.first, screenH / fit.second)
    val zoomKey = if (maximized) "zoom_maximized" else "zoom_normal"
    val most = if (maximized) coverRatio * 1.5f else 1.8f
    var zoom by remember(zoomKey) {
        mutableFloatStateOf(prefs.getFloat(zoomKey, 0f).takeIf { it > 0f } ?: (if (maximized) coverRatio else 1f))
    }

    SettingsScaffold(stringResource(R.string.layout_title), back) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            MockCall(maximized, follow, faceAt, zoom.coerceIn(1f, most), slot, screenW, screenH, picture)
        }
        SectionTitle(stringResource(R.string.layout_section))
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.layout_layout), color = LibraryColors.OnBackground, style = LibraryType.RowTitle, modifier = Modifier.weight(1f))
                Segments(
                    listOf(stringResource(R.string.call_layout_maximized), stringResource(R.string.call_layout_normal)),
                    if (maximized) 0 else 1,
                ) { i ->
                    maximized = i == 0
                    prefs.edit().putBoolean("fill", maximized).apply()
                }
            }
            RowDivider()
            SwitchRow(stringResource(R.string.call_follow_them), stringResource(R.string.layout_follow_body), follow) { on ->
                follow = on
                prefs.edit().putBoolean("follow_remote", on).apply()
            }
            RowDivider()
            SliderRow(
                stringResource(R.string.layout_face_height), stringResource(R.string.layout_higher), stringResource(R.string.layout_lower),
                faceAt, 0.15f..0.85f,
            ) { v ->
                faceAt = v
                prefs.edit().putFloat("face_at", v).apply()
            }
            RowDivider()
            SliderRow(
                stringResource(R.string.layout_zoom), stringResource(R.string.layout_whole), stringResource(R.string.layout_close),
                zoom.coerceIn(1f, most), 1f..most,
            ) { v ->
                zoom = v
                prefs.edit().putFloat(zoomKey, v).apply()
            }
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.layout_your_video), color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
                    Text(stringResource(if (maximized) R.string.layout_your_video_body else R.string.layout_your_video_normal), color = LibraryColors.Muted, style = LibraryType.Caption)
                }
                PlacePicker(slot) { s ->
                    slot = s
                    prefs.edit().putInt("own_slot", s).apply()
                }
            }
        }
        SectionTitle(stringResource(R.string.layout_in_call))
        SettingsCard {
            Gesture(stringResource(R.string.layout_tap), stringResource(R.string.layout_tap_body))
            RowDivider()
            Gesture(stringResource(R.string.layout_pinch), stringResource(R.string.layout_pinch_body))
            RowDivider()
            Gesture(stringResource(R.string.layout_drag), stringResource(R.string.layout_drag_body))
            RowDivider()
            Gesture(stringResource(R.string.layout_drag_own), stringResource(R.string.layout_drag_own_body))
        }
        Box(Modifier.size(24.dp))
    }
}

@Composable
private fun Segments(labels: List<String>, chosen: Int, choose: (Int) -> Unit) {
    Row(Modifier.background(LibraryColors.Background, CircleShape).padding(3.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == chosen
            Text(
                label,
                color = if (on) Color.White else LibraryColors.OnBackground,
                style = LibraryType.Small,
                modifier = Modifier.clip(CircleShape).background(if (on) Pink else Color.Transparent)
                    .semantics { selected = on }
                    .clickable(role = Role.Tab) { choose(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SliderRow(title: String, start: String, end: String, value: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
        Slider(
            value = value, onValueChange = set, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Pink),
            modifier = Modifier.semantics { contentDescription = title },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(start, color = LibraryColors.Muted, style = LibraryType.Small, modifier = Modifier.weight(1f))
            Text(end, color = LibraryColors.Muted, style = LibraryType.Small)
        }
    }
}

/** Six places, three across and two down, as on the screen; mirrored in Hebrew so its sides read with the words. */
@Composable
private fun PlacePicker(slot: Int, choose: (Int) -> Unit) {
    val names = listOf(
        R.string.layout_top_left, R.string.layout_top_centre, R.string.layout_top_right,
        R.string.layout_bottom_left, R.string.layout_bottom_centre, R.string.layout_bottom_right,
    ).map { stringResource(it) }
    // The places are the screen's own (left is left); in Hebrew the picker is drawn mirrored, so it reads start to end
    // and each cell still stands for its side of the screen.
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == LayoutDirection.Rtl
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier.width(96.dp).aspectRatio(3f / 2.4f).border(1.dp, LibraryColors.Muted.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).padding(4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            for (row in 0..1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (cell in 0..2) {
                        val column = if (rtl) 2 - cell else cell
                        val s = row * 3 + column
                        val on = s == slot
                        Box(
                            Modifier.size(width = 26.dp, height = 18.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (on) Pink else LibraryColors.Muted.copy(alpha = 0.25f))
                                .semantics { selected = on; contentDescription = names[s] }
                                .clickable(role = Role.RadioButton) { choose(s) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Gesture(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = LibraryColors.OnBackground, style = LibraryType.RowTitle)
        Text(body, color = LibraryColors.Muted, style = LibraryType.Caption)
    }
}

/**
 * The little call screen: their picture (a figure) as the layout, zoom and face height place it, kept clear of this
 * phone's window as in a call, and this phone's window (a pink figure) where it rests.
 */
@Composable
private fun MockCall(maximized: Boolean, follow: Boolean, faceAt: Float, zoom: Float, slot: Int, screenW: Float, screenH: Float, picture: Float) {
    val label = stringResource(R.string.layout_preview)
    Canvas(
        Modifier.width(150.dp).aspectRatio(screenW / screenH).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0F1413))
            .semantics { contentDescription = label },
    ) {
        val w = size.width
        val h = size.height
        val k = w / screenW
        // This phone's window: 160 dp on its long side, 16 dp in from the edges, below the bar and above the buttons.
        val ww = 120f * k
        val wh = 160f * k
        val m = 16f * k
        val top = 40f * k
        val bottom = h - wh - 40f * k
        val xs = listOf(m, (w - ww) / 2, w - ww - m)
        val own = if (maximized) Offset(xs[slot % 3], if (slot < 3) top else bottom) else null
        if (maximized) {
            val fit = CallGrid.content(w, h, picture, false)
            val area = own?.let { CallGrid.Area(it.x / w, it.y / h, (it.x + ww) / w, (it.y + wh) / h) }
            figure(0f, 0f, w, h, fit, faceAt, zoom, area, Color(0xFF3A4442), Color(0xFF8FA19D))
            own?.let { o -> window(o, ww, wh) }
        } else {
            val ph = w / picture
            figure(0f, 0f, w, ph, w to ph, faceAt, zoom, null, Color(0xFF3A4442), Color(0xFF8FA19D))
            val rest = h - ph
            val sh = minOf(rest * 0.85f, (w * 0.6f) / picture)
            val sw = sh * picture
            window(Offset((w - sw) / 2, ph + (rest - sh) / 2), sw, sh)
        }
        if (follow) {
            // Follow face: a pink frame round the screen says it keeps the face in view.
            drawRoundRect(Pink, Offset(6f, 6f), Size(w - 12f, h - 12f), CornerRadius(14f, 14f), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** Their picture ([cw]×[ch] laid out whole, centred in the box) zoomed and placed, with a figure in it. */
private fun DrawScope.figure(
    bx: Float,
    by: Float,
    bw: Float,
    bh: Float,
    content: Pair<Float, Float>,
    faceAt: Float,
    zoom: Float,
    avoid: CallGrid.Area?,
    back: Color,
    ink: Color,
) {
    val (cw, ch) = content
    val headH = 0.22f
    val fh = headH * ch * zoom / bh
    val ty = CallGrid.faceClearOf(faceAt, fh * 0.75f * bh / bw, fh, avoid)
    val p = CallGrid.place(bw, bh, cw, ch, 0.5f, 0.35f, headH, zoom, targetY = ty, fixedZoom = zoom)
    val ox = bx + (bw - cw) / 2 + p.x
    val oy = by + (bh - ch) / 2 + p.y
    val s = p.scale
    clipRect(bx, by, bx + bw, by + bh) {
        drawRect(back, Offset(ox, oy), Size(cw * s, ch * s))
        val head = Offset(ox + 0.5f * cw * s, oy + 0.35f * ch * s)
        val r = headH * ch * s / 2
        drawCircle(ink, r * 0.85f, head)
        drawRoundRect(ink, Offset(head.x - r * 1.9f, head.y + r * 1.05f), Size(r * 3.8f, r * 3f), CornerRadius(r * 1.4f, r * 1.4f))
    }
}

private fun DrawScope.window(at: Offset, w: Float, h: Float) {
    drawRoundRect(Color(0xFF1E2524), at, Size(w, h), CornerRadius(8f, 8f))
    clipRect(at.x, at.y, at.x + w, at.y + h) {
        val head = Offset(at.x + w / 2, at.y + h * 0.42f)
        val r = w * 0.16f
        drawCircle(Pink, r, head)
        drawRoundRect(Pink, Offset(head.x - r * 2f, head.y + r * 1.2f), Size(r * 4f, r * 3f), CornerRadius(r * 1.4f, r * 1.4f))
    }
    drawRoundRect(Color.White.copy(alpha = 0.7f), at, Size(w, h), CornerRadius(8f, 8f), style = Stroke(width = 1.5f))
}
