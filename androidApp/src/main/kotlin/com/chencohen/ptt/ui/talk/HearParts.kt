package com.chencohen.ptt.ui.talk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Clip
import com.chencohen.ptt.talk.Hear
import com.chencohen.ptt.talk.HeardStatus
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType

// Who can hear you (2026-11-25, hen; docs/screens/talk.md, "Who can hear you"): the face badges, the header line, the
// talker's bubble labels and the chips under my pill while I push.

/** Whether a user can hear a push now, from their presence (Hear.UNKNOWN when nobody says). */
internal val LocalHear = compositionLocalOf<(String) -> Hear> { { Hear.UNKNOWN } }

/** A face with its "can't hear" badge (Do Not Disturb, silent, in a call) at its bottom right on screen, in either direction. */
@Composable
internal fun HearFace(user: String, size: Dp, content: @Composable () -> Unit) {
    val hear = LocalHear.current(user)
    Box(Modifier.size(size)) {
        content()
        HearBadge(hear, (size * 0.42f).coerceIn(12.dp, 22.dp), Modifier.align(AbsoluteAlignment.BottomRight))
    }
}

/**
 * The badge (2026-11-25, hen): Do Not Disturb is Android's own sign, a red filled circle with a white bar (never a
 * moon); silent a crossed bell, in a call a phone. It comes and goes in one move, scaling with a fade.
 */
@Composable
internal fun HearBadge(hear: Hear, size: Dp, modifier: Modifier = Modifier) {
    val shown = hear == Hear.DND || hear == Hear.SILENT || hear == Hear.IN_CALL
    // The last one shown, kept while it goes.
    val last = remember { arrayOf(hear) }
    if (shown) last[0] = hear
    AnimatedVisibility(shown, modifier, enter = scaleIn(tween(220)) + fadeIn(tween(220)), exit = scaleOut(tween(220)) + fadeOut(tween(220))) {
        val ring = LibraryColors.Background
        when (last[0]) {
            Hear.DND -> Box(
                Modifier.size(size).drawBehind {
                    val r = this.size.minDimension / 2
                    drawCircle(ring, r)
                    drawCircle(Color(0xFFD93025), r - 1.5.dp.toPx())
                    val barW = r * 1.1f
                    val barH = r * 0.32f
                    drawRoundRect(Color.White, Offset(center.x - barW / 2, center.y - barH / 2), Size(barW, barH), CornerRadius(barH / 2))
                },
            )
            Hear.IN_CALL -> Box(Modifier.size(size).background(ring, CircleShape).padding(1.5.dp).background(LibraryColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Call, null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
            else -> Box(Modifier.size(size).background(ring, CircleShape).padding(1.5.dp).background(LibraryColors.Muted, CircleShape), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.BellOff, null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
            }
        }
    }
}

/** A chat header's line for a contact who can't hear now; null when they can (or nobody says). */
@Composable
internal fun hearLine(hear: Hear): String? = when (hear) {
    Hear.DND -> stringResource(R.string.hear_dnd)
    Hear.SILENT -> stringResource(R.string.hear_silent_line)
    Hear.IN_CALL -> stringResource(R.string.hear_in_call)
    else -> null
}

/** Why they can't hear, short ("Do Not Disturb", "Silent", "In a call"): from what they told of this push, else their presence. */
@Composable
internal fun hearReason(status: Int, hear: Hear): String? = when (status) {
    HeardStatus.SAVED_DND -> stringResource(R.string.hear_dnd)
    HeardStatus.SAVED_SILENT -> stringResource(R.string.hear_silent)
    HeardStatus.SAVED_IN_CALL -> stringResource(R.string.hear_in_call)
    HeardStatus.SAVED_URGENT_NOT_ALLOWED -> stringResource(R.string.heard_saved_urgent)
    else -> when (hear) {
        Hear.DND -> stringResource(R.string.hear_dnd)
        Hear.SILENT -> stringResource(R.string.hear_silent)
        Hear.IN_CALL -> stringResource(R.string.hear_in_call)
        else -> null
    }
}

/** A push of mine, as one listener told what became of it: the bubble's label (none for a plain push heard). */
@Composable
internal fun heardLabel(status: Int, urgent: Boolean): String? = when (status) {
    HeardStatus.SAVED_DND -> stringResource(R.string.heard_saved_dnd)
    HeardStatus.SAVED_SILENT -> stringResource(R.string.heard_saved_silent)
    HeardStatus.SAVED_IN_CALL -> stringResource(R.string.heard_saved_call)
    HeardStatus.SAVED_URGENT_NOT_ALLOWED -> stringResource(R.string.heard_saved_urgent)
    HeardStatus.HEARD_URGENT_DND -> stringResource(R.string.heard_urgent_dnd)
    HeardStatus.HEARD_URGENT_SILENT -> stringResource(R.string.heard_urgent_silent)
    HeardStatus.HEARING -> if (urgent) stringResource(R.string.heard_urgent) else null
    else -> null
}

/**
 * Under a push of mine (2026-11-25, hen): "Saved: Do Not Disturb", "URGENT ✓ Heard through Do Not Disturb", and so
 * on; in a group each with the member's name. Red for urgent, muted otherwise.
 */
@Composable
internal fun HeardLabels(clip: Clip, group: Boolean, nameOf: (String) -> String, onInverse: Boolean) {
    val lines = clip.heardBy.entries.sortedBy { it.key }.mapNotNull { (user, status) ->
        heardLabel(status, clip.urgent)?.let { label -> (if (group) "${nameOf(user)} · $label" else label) to (clip.urgent || status == HeardStatus.SAVED_URGENT_NOT_ALLOWED) }
    }
    lines.forEach { (line, red) ->
        Text(
            line,
            color = if (red) LibraryColors.Error else if (onInverse) LibraryColors.OnMineMuted else LibraryColors.Muted,
            style = LibraryType.Tiny.copy(fontWeight = if (red) FontWeight.SemiBold else FontWeight.Normal),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** One listener of my push now: who, their name, whether their presence says they can hear, and what they told of it. */
internal data class PushListener(val user: String, val name: String, val hear: Hear, val status: Int)

/** Whether a listener can't hear my push now: they said it was saved, or their presence says they can't. */
internal fun PushListener.cantHear(): Boolean = status in SAVED_STATUSES || (status == 0 && (hear == Hear.DND || hear == Hear.SILENT || hear == Hear.IN_CALL))

/** They said it reaches their ears. */
internal fun PushListener.hearing(): Boolean = HeardStatus.heard(status)

private val SAVED_STATUSES = setOf(HeardStatus.SAVED_DND, HeardStatus.SAVED_SILENT, HeardStatus.SAVED_IN_CALL, HeardStatus.SAVED_URGENT_NOT_ALLOWED)

/**
 * The chip under my pill while I push (2026-11-25, hen): a greyed face and "Sharon can't hear now · Do Not Disturb ·
 * saved" with one short buzz, or a teal glowing face and "Sharon is hearing you" only once their phone said it plays.
 * It changes in place, in one move; nothing shows until there is something true to say. In a group: a row of the
 * members' faces, lit or grey, and "2 hearing · 2 get it later".
 */
@Composable
internal fun PushHearChip(pushing: String?, listeners: List<PushListener>, group: Boolean, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val anyCant = pushing != null && listeners.any { it.cantHear() }
    val anyHearing = pushing != null && listeners.any { it.hearing() }
    // One short buzz the first time this push meets someone who can't hear it.
    LaunchedEffect(pushing, anyCant) {
        if (pushing != null && anyCant) view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }
    val shown = pushing != null && (anyCant || anyHearing)
    val last = remember { arrayOf(listeners) }
    if (shown) last[0] = listeners
    AnimatedVisibility(
        shown, modifier.fillMaxWidth(),
        enter = fadeIn(tween(250)) + expandVertically(tween(250)), exit = fadeOut(tween(250)) + shrinkVertically(tween(250)),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            val people = last[0]
            val shape = RoundedCornerShape(20.dp)
            if (!group && people.size == 1) {
                val p = people[0]
                val cant = p.cantHear()
                val tint by animateColorAsState(if (cant) LibraryColors.Muted else LibraryColors.Accent, tween(250), label = "chip tint")
                Row(
                    Modifier.shadow(4.dp, shape).background(LibraryColors.Raised, shape).border(1.dp, tint.copy(alpha = 0.6f), shape).padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChipFace(p, cant)
                    val words = if (cant) {
                        com.chencohen.ptt.genderedString(R.string.push_cant_hear, p.user, android.text.BidiFormatter.getInstance().unicodeWrap(p.name), hearReason(p.status, p.hear) ?: "")
                    } else {
                        com.chencohen.ptt.genderedString(R.string.push_hearing, p.user, android.text.BidiFormatter.getInstance().unicodeWrap(p.name))
                    }
                    Text(words, color = if (cant) LibraryColors.Muted else LibraryColors.OnBackground, style = LibraryType.Small, maxLines = 1)
                }
            } else {
                val hearing = people.count { it.hearing() }
                // Offline ones get it later too.
                val later = people.count { it.cantHear() || (it.status == 0 && it.hear == Hear.OFFLINE) }
                Row(
                    Modifier.shadow(4.dp, shape).background(LibraryColors.Raised, shape).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    people.take(6).forEach { p -> ChipFace(p, p.cantHear(), small = true) }
                    Text(stringResource(R.string.push_group_hear, hearing, later), color = LibraryColors.OnBackground, style = LibraryType.Small, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/** A listener's face in the chip: teal glowing while they hear, greyed (with its badge) when they can't, plain until known. */
@Composable
private fun ChipFace(p: PushListener, cant: Boolean, small: Boolean = false) {
    val size = if (small) 26.dp else 30.dp
    val lit = p.hearing()
    val grey by animateFloatAsState(if (cant) 0.45f else 1f, tween(250), label = "face grey")
    val glow by animateFloatAsState(if (lit) 1f else 0f, tween(250), label = "face glow")
    val teal = LibraryColors.Accent
    Box(
        Modifier.size(size + 6.dp).drawBehind {
            if (glow > 0f) {
                val r = size.toPx() / 2 + 1.dp.toPx()
                drawCircle(teal.copy(alpha = 0.3f * glow), r + 3.dp.toPx())
                drawCircle(teal.copy(alpha = glow), r, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).graphicsLayer { alpha = grey }) { com.chencohen.ptt.ui.contacts.Avatar(p.user, size, name = p.name) }
        val reason = when (p.status) {
            HeardStatus.SAVED_DND -> Hear.DND
            HeardStatus.SAVED_SILENT -> Hear.SILENT
            HeardStatus.SAVED_IN_CALL -> Hear.IN_CALL
            else -> if (p.status == 0) p.hear else Hear.OK
        }
        HearBadge(if (cant) reason else Hear.OK, if (small) 11.dp else 13.dp, Modifier.align(AbsoluteAlignment.BottomRight))
    }
}

/**
 * A contact's "Let urgent pushes through Do Not Disturb" (2026-11-25, hen; off by default). Turned on the first time
 * without Do Not Disturb access, it asks for it; without it the note says urgent pushes still arrive, silently.
 */
@Composable
internal fun UrgentThroughDndCard(on: Boolean, set: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifications = remember { context.getSystemService(android.app.NotificationManager::class.java) }
    var access by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(notifications.isNotificationPolicyAccessGranted) }
    var asking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Back from Android's settings: looked at again.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) access = notifications.isNotificationPolicyAccessGranted }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    com.chencohen.ptt.ui.style.SectionTitle(stringResource(R.string.urgent_dnd_section))
    com.chencohen.ptt.ui.style.SettingsCard {
        SwitchRow(stringResource(R.string.urgent_dnd_title), stringResource(R.string.urgent_dnd_body), on) { now ->
            set(now)
            if (now && !access) asking = true
        }
    }
    if (on && !access) com.chencohen.ptt.ui.style.PageNote(stringResource(R.string.urgent_dnd_no_access), top = 6)
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.urgent_dnd_ask_title)) },
            text = { Text(stringResource(R.string.urgent_dnd_ask_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    asking = false
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                }) { Text(stringResource(R.string.urgent_dnd_ask_open)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text(stringResource(R.string.urgent_dnd_ask_later)) } },
        )
    }
}
