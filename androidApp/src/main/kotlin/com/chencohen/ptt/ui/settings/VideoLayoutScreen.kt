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
import androidx.compose.foundation.layout.safeDrawing
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
    // The whole screen in pixels, as a Maximized call has it (under the bars and the cutout).
    val config = LocalConfiguration.current
    val activity = context as? android.app.Activity
    val bounds = remember(config) { activity?.windowManager?.currentWindowMetrics?.bounds }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenW = (bounds?.width() ?: with(density) { config.screenWidthDp.dp.roundToPx() }).toFloat().coerceAtLeast(1f)
    val screenH = (bounds?.height() ?: with(density) { config.screenHeightDp.dp.roundToPx() }).toFloat().coerceAtLeast(1f)
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
            MockCall(maximized, follow, faceAt, zoom.coerceIn(1f, most), slot, screenW, screenH, picture, prefs.getFloat("face_h", TYPICAL_FACE))
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

/** A face's height in a selfie as the face finder sees it (a share of the picture), until a call measures one. */
private const val TYPICAL_FACE = 0.33f

/**
 * The little call screen, to scale (2026-11-23, hen: "the face illustration and the real face size are not accurate"):
 * this phone's screen at its real shape, cutout and safe edges; their camera as a 3:4 picture with a face box as the
 * face finder sees one ([faceH] of the picture tall, the size measured in the last call, or a third), placed by the
 * call screen's own code (CallGrid.frame, windowSlots, ownWindowDp) at the zoom, face height and window place chosen;
 * this phone's window at its size in a call; everything scaled down. The controls are hidden, as after a tap.
 */
@Composable
private fun MockCall(maximized: Boolean, follow: Boolean, faceAt: Float, zoom: Float, slot: Int, screenW: Float, screenH: Float, picture: Float, faceH: Float) {
    val label = stringResource(R.string.layout_preview)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    val safe = androidx.compose.foundation.layout.WindowInsets.safeDrawing
    val safeL = safe.getLeft(density, dir).toFloat()
    val safeT = safe.getTop(density).toFloat()
    val safeR = safe.getRight(density, dir).toFloat()
    val safeB = safe.getBottom(density).toFloat()
    val context = LocalContext.current
    val cutouts = remember {
        (context as? android.app.Activity)?.windowManager?.currentWindowMetrics?.windowInsets?.displayCutout?.boundingRects.orEmpty()
    }
    val px = with(density) { 1.dp.toPx() }
    Canvas(
        Modifier.width(170.dp).aspectRatio(screenW / screenH).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0F1413))
            .semantics { contentDescription = label },
    ) {
        // Worked out in the phone's own pixels, drawn at this scale.
        val k = size.width / screenW
        val (winWdp, winHdp) = com.chencohen.ptt.ui.calls.ownWindowDp(picture)
        val ww = winWdp * px
        val wh = winHdp * px
        val face = CallGrid.Face(0.5f, 0.4f, faceH * 0.75f / picture * picture, faceH)
        if (maximized) {
            val fit = CallGrid.content(screenW, screenH, picture, false)
            val slots = CallGrid.windowSlots(screenW, screenH, ww, wh, safeL, safeT, safeR, safeB, 16 * px)
            val rest = slots[slot.coerceIn(0, 5)]
            val area = CallGrid.Area(rest.first / screenW, rest.second / screenH, (rest.first + ww) / screenW, (rest.second + wh) / screenH)
            val p = CallGrid.frame(screenW, screenH, fit.first, fit.second, if (follow) face else null, faceAt, zoom, 1.5f, area, pan = true)
            picture(0f, 0f, screenW, screenH, fit, p, face, k)
            window(Offset(rest.first * k, rest.second * k), ww * k, wh * k)
        } else {
            val (them, me) = CallGrid.normal(screenW, screenH, picture, picture, false)
            val p = CallGrid.frame(them.width, them.height, them.width, them.height, if (follow) face else null, 0.4f, zoom, 1.8f)
            picture((screenW - them.width) / 2, 0f, them.width, them.height, them.width to them.height, p, face, k)
            val top = them.height + (screenH - them.height - me.height) / 2
            window(Offset((screenW - me.width) / 2 * k, top * k), me.width * k, me.height * k)
        }
        // The camera cutout, where it really is.
        cutouts.forEach { r ->
            drawRoundRect(Color.Black, Offset(r.left * k, r.top * k), Size(r.width() * k, r.height() * k), CornerRadius(r.height() * k / 2, r.height() * k / 2))
        }
        if (follow) {
            drawRoundRect(Pink, Offset(3f, 3f), Size(size.width - 6f, size.height - 6f), CornerRadius(14f, 14f), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/**
 * Their 3:4 picture ([content], laid out centred in the box at [bx], [by], [bw]×[bh]) as placed ([p]; null: as laid
 * out), with a figure drawn to the [face] box, all in the phone's pixels times [k].
 */
private fun DrawScope.picture(bx: Float, by: Float, bw: Float, bh: Float, content: Pair<Float, Float>, p: CallGrid.Placement?, face: CallGrid.Face, k: Float) {
    val (cw, ch) = content
    val s = p?.scale ?: 1f
    val ox = bx + (bw - cw) / 2 + (p?.x ?: 0f)
    val oy = by + (bh - ch) / 2 + (p?.y ?: 0f)
    clipRect(bx * k, by * k, (bx + bw) * k, (by + bh) * k) {
        drawRect(Color(0xFF3A4442), Offset(ox * k, oy * k), Size(cw * s * k, ch * s * k))
        val fx = (ox + face.x * cw * s) * k
        val fy = (oy + face.y * ch * s) * k
        val fh = face.height * ch * s * k
        val fw = face.width * cw * s * k
        val ink = Color(0xFF8FA19D)
        // Shoulders below the face, then the face box as the finder sees it (forehead to chin), hair above it.
        drawRoundRect(ink, Offset(fx - fw * 1.6f, fy + fh * 0.75f), Size(fw * 3.2f, fh * 2f), CornerRadius(fw * 0.9f, fw * 0.9f))
        drawOval(ink.copy(alpha = 0.75f), Offset(fx - fw * 0.6f, fy - fh * 0.68f), Size(fw * 1.2f, fh * 0.75f))
        drawOval(Color(0xFFB9C7C3), Offset(fx - fw / 2, fy - fh / 2), Size(fw, fh))
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
