package com.chencohen.ptt.ui.style

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * The app's icons (2026-11-27, hen: "make all icons in the app with the fine details as the app icon"; the approved
 * sheet is docs/screens/mocks/ui-icons/ui-icons-fine.html on ux/fine-icons-spec). Material icons are not a dependency.
 *
 * Every icon is ONE tint, the one `Icon(tint = …)` gives it, drawn at a few strengths, as the app icon is a front
 * shape, a softer one behind, a thin light edge and one solid part:
 *  - lines 1.75 wide (a finer 1.5 for small marks), round ends and joins;
 *  - every closed body filled with the tint at a soft alpha ([IconStrength.soft]: 8% light, 10% dark, 14% on coral);
 *  - one small solid part (the mic's head, the camera's lens, the bell's clapper, the server's lights);
 *  - a 1-unit inner edge, top left, at [IconStrength.edge] (30% light, 35% dark, 40% on coral);
 *  - a crossed-out icon has no solid part and a gap around its bar.
 * Arrows, chevrons, Close, Plus, Check, Expand and Flip stay lines. Six icons can show their solid part in a second
 * colour where it means something ([FineIcon] with `key`): Mic, Video, Talk and Call teal while live, Bell coral for an
 * urgent push, Trash red while removing.
 */
internal object LibraryIcons {
    /** Search's orders: newest first (down), oldest first (up), most relevant (a sparkle). */
    val Mic: ImageVector @Composable @ReadOnlyComposable get() = icon("Mic")
    val MicOff: ImageVector @Composable @ReadOnlyComposable get() = icon("MicOff")
    val Video: ImageVector @Composable @ReadOnlyComposable get() = icon("Video")
    val VideoOff: ImageVector @Composable @ReadOnlyComposable get() = icon("VideoOff")
    val Call: ImageVector @Composable @ReadOnlyComposable get() = icon("Call")
    /** The Talk button: a walkie-talkie. */
    val Talk: ImageVector @Composable @ReadOnlyComposable get() = icon("Talk")
    val Speaker: ImageVector @Composable @ReadOnlyComposable get() = icon("Speaker")
    val SpeakerOff: ImageVector @Composable @ReadOnlyComposable get() = icon("SpeakerOff")
    val Play: ImageVector @Composable @ReadOnlyComposable get() = icon("Play")
    val Pause: ImageVector @Composable @ReadOnlyComposable get() = icon("Pause")
    val Chat: ImageVector @Composable @ReadOnlyComposable get() = icon("Chat")
    /** Urgent push (2026-11-25, hen): a bell. */
    val Bell: ImageVector @Composable @ReadOnlyComposable get() = icon("Bell")
    /** Silent (who can hear you; 2026-11-25, hen): a bell crossed out. */
    val BellOff: ImageVector @Composable @ReadOnlyComposable get() = icon("BellOff")
    /** Talk's own: a person (the profile circle), a chat bubble and a phone handset (the bottom tabs' empty states). */
    val Person: ImageVector @Composable @ReadOnlyComposable get() = icon("Person")
    val Group: ImageVector @Composable @ReadOnlyComposable get() = icon("Group")
    val Photo: ImageVector @Composable @ReadOnlyComposable get() = icon("Photo")
    val Share: ImageVector @Composable @ReadOnlyComposable get() = icon("Share")
    val Edit: ImageVector @Composable @ReadOnlyComposable get() = icon("Edit")
    val Copy: ImageVector @Composable @ReadOnlyComposable get() = icon("Copy")
    /** Paste an enrollment code: a clipboard. */
    val Paste: ImageVector @Composable @ReadOnlyComposable get() = icon("Paste")
    val Lock: ImageVector @Composable @ReadOnlyComposable get() = icon("Lock")
    /** The safety number: a shield with a check. */
    val Shield: ImageVector @Composable @ReadOnlyComposable get() = icon("Shield")
    /** The server: two racks with their lights. */
    val Server: ImageVector @Composable @ReadOnlyComposable get() = icon("Server")
    /** Scan a QR code: the four corners of a viewfinder and a line across. */
    val Scan: ImageVector @Composable @ReadOnlyComposable get() = icon("Scan")
    /** Scan an enrollment code. */
    val QrCode: ImageVector @Composable @ReadOnlyComposable get() = icon("QrCode")
    val Info: ImageVector @Composable @ReadOnlyComposable get() = icon("Info")
    /** Something went wrong: a circle with an exclamation mark. */
    val Error: ImageVector @Composable @ReadOnlyComposable get() = icon("Error")
    val CloudOff: ImageVector @Composable @ReadOnlyComposable get() = icon("CloudOff")
    val Trash: ImageVector @Composable @ReadOnlyComposable get() = icon("Trash")
    val Download: ImageVector @Composable @ReadOnlyComposable get() = icon("Download")
    val Flip: ImageVector @Composable @ReadOnlyComposable get() = icon("Flip")
    /** Full screen: four corners pointing out. */
    val Expand: ImageVector @Composable @ReadOnlyComposable get() = icon("Expand")
    val Plus: ImageVector @Composable @ReadOnlyComposable get() = icon("Plus")
    val Close: ImageVector @Composable @ReadOnlyComposable get() = icon("Close")
    val Check: ImageVector @Composable @ReadOnlyComposable get() = icon("Check")
    val Back: ImageVector @Composable @ReadOnlyComposable get() = icon("Back")
    /** The enrollment wizard's back arrow (a shorter head than [Back]). */
    val ArrowBack: ImageVector @Composable @ReadOnlyComposable get() = icon("ArrowBack")
    val ArrowUp: ImageVector @Composable @ReadOnlyComposable get() = icon("ArrowUp")
    val ChevronRight: ImageVector @Composable @ReadOnlyComposable get() = icon("ChevronRight")
    val ChevronDown: ImageVector @Composable @ReadOnlyComposable get() = icon("ChevronDown")
    val CloudUp: ImageVector @Composable @ReadOnlyComposable get() = icon("CloudUp")
    val Phone: ImageVector @Composable @ReadOnlyComposable get() = icon("Phone")
    /** A TV with Cast's waves in its corner: casting to a TV. */
    val Cast: ImageVector @Composable @ReadOnlyComposable get() = icon("Cast")
    /** [Cast] filled in, as Cast's own button is while connected: the screen solid beside its waves. */
    val CastOn: ImageVector @Composable @ReadOnlyComposable get() = icon("CastOn")
    val More: ImageVector @Composable @ReadOnlyComposable get() = icon("More")
    val Grid: ImageVector @Composable @ReadOnlyComposable get() = icon("Grid")
    val Calendar: ImageVector @Composable @ReadOnlyComposable get() = icon("Calendar")
    /** Do Not Disturb (2026-11-25, hen: Android's own sign, not a moon): a filled circle with a bar through it. */
    val DoNotDisturb: ImageVector @Composable @ReadOnlyComposable get() = icon("DoNotDisturb")
    val Minus: ImageVector @Composable @ReadOnlyComposable get() = icon("Minus")
    val Search: ImageVector @Composable @ReadOnlyComposable get() = icon("Search")
    val Tag: ImageVector @Composable @ReadOnlyComposable get() = icon("Tag")
    val Stack: ImageVector @Composable @ReadOnlyComposable get() = icon("Stack")
    val Settings: ImageVector @Composable @ReadOnlyComposable get() = icon("Settings")
    val Eye: ImageVector @Composable @ReadOnlyComposable get() = icon("Eye")
    /** Tucked away: an eye with a line through it. */
    val EyeOff: ImageVector @Composable @ReadOnlyComposable get() = icon("EyeOff")
    val Clock: ImageVector @Composable @ReadOnlyComposable get() = icon("Clock")
    val Crop: ImageVector @Composable @ReadOnlyComposable get() = icon("Crop")
    val Pin: ImageVector @Composable @ReadOnlyComposable get() = icon("Pin")
    val ArrowDown: ImageVector @Composable @ReadOnlyComposable get() = icon("ArrowDown")
    val Sparkle: ImageVector @Composable @ReadOnlyComposable get() = icon("Sparkle")
    val Map: ImageVector @Composable @ReadOnlyComposable get() = icon("Map")
    val Wifi: ImageVector @Composable @ReadOnlyComposable get() = icon("Wifi")
    /** Highlight: a star's outline, and [StarFilled] once highlighted. */
    val Star: ImageVector @Composable @ReadOnlyComposable get() = icon("Star")
    val StarFilled: ImageVector @Composable @ReadOnlyComposable get() = icon("StarFilled")
    val Block: ImageVector @Composable @ReadOnlyComposable get() = icon("Block")

    /** The part of [icon] that takes the second colour ([FineIcon]), or null when it has none. */
    @Composable @ReadOnlyComposable
    fun keyOf(icon: ImageVector): ImageVector? {
        val spec = specs[icon.name] ?: return null
        if (spec.layers.none { it.key }) return null
        return build(spec, LocalIconStrength.current ?: defaultStrength(), keyOnly = true)
    }

    @Composable @ReadOnlyComposable
    private fun icon(name: String): ImageVector = build(specs.getValue(name), LocalIconStrength.current ?: defaultStrength(), keyOnly = false)

    @Composable @ReadOnlyComposable
    private fun defaultStrength() = if (LibraryColors.Background.luminance() < 0.5f) IconStrength.Dark else IconStrength.Light

    private val built = HashMap<Triple<String, IconStrength, Boolean>, ImageVector>()

    private fun build(spec: Spec, strength: IconStrength, keyOnly: Boolean): ImageVector = synchronized(built) {
        built.getOrPut(Triple(spec.name, strength, keyOnly)) { draw(spec, strength, keyOnly) }
    }

    private val specs: Map<String, Spec> = listOf(
        // From the approved sheet's generator (build.js), layer for layer.
        spec("Mic", K("M12 2.25a3.25 3.25 0 0 1 3.25 3.25v5.5a3.25 3.25 0 0 1-6.5 0V5.5A3.25 3.25 0 0 1 12 2.25z", hi = Hi(10.7f, 5.4f, 9.2f)), L("M5.5 11.25a6.5 6.5 0 0 0 13 0"), L("M12 17.75v3.25"), L("M9 21h6")),
        spec("MicOff", S("M12 2.25a3.25 3.25 0 0 1 3.25 3.25v5.5a3.25 3.25 0 0 1-6.5 0V5.5A3.25 3.25 0 0 1 12 2.25z"), L("M12 2.25a3.25 3.25 0 0 1 3.25 3.25v5.5a3.25 3.25 0 0 1-6.5 0V5.5A3.25 3.25 0 0 1 12 2.25z"), L("M5.5 11.25a6.5 6.5 0 0 0 13 0"), L("M12 17.75v3.25"), L("M9 21h6"), Slash(4f, 4f, 20f, 20f)),
        spec("Video", S("M5 6h8.5a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-8.5a2.5 2.5 0 0 1-2.5-2.5v-7a2.5 2.5 0 0 1 2.5-2.5z"), L("M5 6h8.5a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-8.5a2.5 2.5 0 0 1-2.5-2.5v-7a2.5 2.5 0 0 1 2.5-2.5z"), H("M4.5 10.6V9.4A1.4 1.4 0 0 1 5.9 8h2.5"), K("M17.9 10.5l3.2-2.1a.6.6 0 0 1 .9.5v6.2a.6.6 0 0 1-.9.5l-3.2-2.1z", round = true)),
        spec("VideoOff", S("M5 6h8.5a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-8.5a2.5 2.5 0 0 1-2.5-2.5v-7a2.5 2.5 0 0 1 2.5-2.5z"), L("M5 6h8.5a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-8.5a2.5 2.5 0 0 1-2.5-2.5v-7a2.5 2.5 0 0 1 2.5-2.5z"), L("M17.9 10.5l3.2-2.1a.6.6 0 0 1 .9.5v6.2a.6.6 0 0 1-.9.5l-3.2-2.1z"), Slash(3.5f, 3.5f, 20.5f, 20.5f)),
        spec("Call", S("M5 4h3.5l1.5 4.5-2.2 1.4a11 11 0 0 0 6.3 6.3l1.4-2.2L20 15.5V19a1.5 1.5 0 0 1-1.6 1.5A16.5 16.5 0 0 1 3.5 5.6 1.5 1.5 0 0 1 5 4z"), L("M5 4h3.5l1.5 4.5-2.2 1.4a11 11 0 0 0 6.3 6.3l1.4-2.2L20 15.5V19a1.5 1.5 0 0 1-1.6 1.5A16.5 16.5 0 0 1 3.5 5.6 1.5 1.5 0 0 1 5 4z"), KL("M14.6 3.6a5.8 5.8 0 0 1 5.8 5.8"), KL("M14.6 7.1a2.3 2.3 0 0 1 2.3 2.3")),
        spec("Talk", S("M8.5 7.5h7a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1-2.5 2.5h-7a2.5 2.5 0 0 1-2.5-2.5v-9a2.5 2.5 0 0 1 2.5-2.5z"), L("M8.5 7.5h7a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1-2.5 2.5h-7a2.5 2.5 0 0 1-2.5-2.5v-9a2.5 2.5 0 0 1 2.5-2.5z"), L("M9.25 2.5v5"), K("M9.7 10.1h4.6a1.2 1.2 0 0 1 1.2 1.2v1.2a1.2 1.2 0 0 1-1.2 1.2h-4.6a1.2 1.2 0 0 1-1.2-1.2v-1.2a1.2 1.2 0 0 1 1.2-1.2z"), l("M9.2 16.5h5.6"), l("M9.2 18.8h3.2")),
        spec("Speaker", S("M11 5.3L6.4 9H3.6a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h2.8l4.6 3.7a.6.6 0 0 0 1-.47V5.77a.6.6 0 0 0-1-.47z"), L("M11 5.3L6.4 9H3.6a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h2.8l4.6 3.7a.6.6 0 0 0 1-.47V5.77a.6.6 0 0 0-1-.47z"), L("M15.6 8.9a4.6 4.6 0 0 1 0 6.2"), L("M18.6 6a8.6 8.6 0 0 1 0 12")),
        spec("SpeakerOff", S("M11 5.3L6.4 9H3.6a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h2.8l4.6 3.7a.6.6 0 0 0 1-.47V5.77a.6.6 0 0 0-1-.47z"), L("M11 5.3L6.4 9H3.6a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h2.8l4.6 3.7a.6.6 0 0 0 1-.47V5.77a.6.6 0 0 0-1-.47z"), L("M16.2 9.4l5.2 5.2"), L("M21.4 9.4l-5.2 5.2")),
        spec("Play", F("M8 5.2v13.6a1 1 0 0 0 1.53.85l10.6-6.8a1 1 0 0 0 0-1.7L9.53 4.35A1 1 0 0 0 8 5.2z", hi = Hi(10f, 8.3f, 12.9f))),
        spec("Pause", F("M7.9 4.75h0.95a1.4 1.4 0 0 1 1.4 1.4v11.7a1.4 1.4 0 0 1-1.4 1.4h-0.95a1.4 1.4 0 0 1-1.4-1.4v-11.7a1.4 1.4 0 0 1 1.4-1.4z", hi = Hi(8f, 7.3f, 11.9f)), F("M15.15 4.75h0.95a1.4 1.4 0 0 1 1.4 1.4v11.7a1.4 1.4 0 0 1-1.4 1.4h-0.95a1.4 1.4 0 0 1-1.4-1.4v-11.7a1.4 1.4 0 0 1 1.4-1.4z", hi = Hi(15.25f, 7.3f, 11.9f))),
        spec("Chat", S("M13.84 4.44A4.8 4.8 0 1 1 19.21 12.39A8.7 8.7 0 0 0 13.84 4.44Z"), L2("M13.84 4.44A4.8 4.8 0 1 1 19.21 12.39"), S("M17.5 12.5a7 7 0 0 1-10.2 6.2L3 20l1.25-4.1A7 7 0 1 1 17.5 12.5z"), L("M17.5 12.5a7 7 0 0 1-10.2 6.2L3 20l1.25-4.1A7 7 0 1 1 17.5 12.5z"), H("M5.6 11.6a5 5 0 0 1 2.9-3.9")),
        spec("Bell", S("M18 8.5a6 6 0 0 0-12 0c0 5.6-2 7.4-2.6 8a.3.3 0 0 0 .2.5h16.8a.3.3 0 0 0 .2-.5c-.6-.6-2.6-2.4-2.6-8z"), L("M18 8.5a6 6 0 0 0-12 0c0 5.6-2 7.4-2.6 8a.3.3 0 0 0 .2.5h16.8a.3.3 0 0 0 .2-.5c-.6-.6-2.6-2.4-2.6-8z"), H("M8.2 8.5a3.9 3.9 0 0 1 2.2-3.4"), K("M9.6 19.3h4.8a2.45 2.45 0 0 1-4.8 0z", round = true)),
        spec("BellOff", S("M18 8.5a6 6 0 0 0-12 0c0 5.6-2 7.4-2.6 8a.3.3 0 0 0 .2.5h16.8a.3.3 0 0 0 .2-.5c-.6-.6-2.6-2.4-2.6-8z"), L("M18 8.5a6 6 0 0 0-12 0c0 5.6-2 7.4-2.6 8a.3.3 0 0 0 .2.5h16.8a.3.3 0 0 0 .2-.5c-.6-.6-2.6-2.4-2.6-8z"), L("M10 19.6a2.2 2.2 0 0 0 4 0"), Slash(3.5f, 3.5f, 20.5f, 20.5f)),
        spec("Person", S("M12 3.85a3.9 3.9 0 1 0 0 7.8a3.9 3.9 0 1 0 0-7.8z"), L("M12 3.85a3.9 3.9 0 1 0 0 7.8a3.9 3.9 0 1 0 0-7.8z"), H("M9.9 7a2.4 2.4 0 0 1 1.5-1.4"), S("M4.4 20.5c.9-3.7 3.9-5.9 7.6-5.9s6.7 2.2 7.6 5.9z"), L("M4.4 20.5c.9-3.7 3.9-5.9 7.6-5.9s6.7 2.2 7.6 5.9")),
        spec("Group", L2("M15.6 4.5a3.6 3.6 0 0 1 0 7"), L2("M18.2 15.3c1.9.9 3 2.7 3.4 5.2"), S("M9 4.4a3.6 3.6 0 1 0 0 7.2a3.6 3.6 0 1 0 0-7.2z"), L("M9 4.4a3.6 3.6 0 1 0 0 7.2a3.6 3.6 0 1 0 0-7.2z"), H("M7.1 7.3a2.2 2.2 0 0 1 1.4-1.3"), S("M2.4 20.5c.7-3.6 3.3-5.6 6.6-5.6s5.9 2 6.6 5.6z"), L("M2.4 20.5c.7-3.6 3.3-5.6 6.6-5.6s5.9 2 6.6 5.6")),
        spec("Photo", L("M5.5 3.5h13a2.5 2.5 0 0 1 2.5 2.5v12a2.5 2.5 0 0 1-2.5 2.5h-13a2.5 2.5 0 0 1-2.5-2.5v-12a2.5 2.5 0 0 1 2.5-2.5z"), S("M3.3 17.7l5-5a1 1 0 0 1 1.4 0l3.8 3.8 2-2a1 1 0 0 1 1.4 0l3.8 3.8v.2a2.5 2.5 0 0 1-2.5 2.5h-13a2.5 2.5 0 0 1-2.2-1.3z"), L("M3.4 17.6l4.9-4.9a1 1 0 0 1 1.4 0l5.6 5.6"), L("M13.6 16.4l1.9-1.9a1 1 0 0 1 1.4 0l3.8 3.8"), F("M15.9 6.75a1.55 1.55 0 1 0 0 3.1a1.55 1.55 0 1 0 0-3.1z")),
        spec("Share", L("M8.5 13.4l7 4.1"), L("M15.5 6.5l-7 4.1"), F("M6 9.1a2.9 2.9 0 1 0 0 5.8a2.9 2.9 0 1 0 0-5.8z"), S("M18 2.25a2.75 2.75 0 1 0 0 5.5a2.75 2.75 0 1 0 0-5.5z"), L("M18 2.25a2.75 2.75 0 1 0 0 5.5a2.75 2.75 0 1 0 0-5.5z"), S("M18 16.25a2.75 2.75 0 1 0 0 5.5a2.75 2.75 0 1 0 0-5.5z"), L("M18 16.25a2.75 2.75 0 1 0 0 5.5a2.75 2.75 0 1 0 0-5.5z")),
        spec("Edit", S("M16.2 3.9a2.05 2.05 0 0 1 2.9 2.9L7.4 18.5l-3.9 1 1-3.9z"), L("M16.2 3.9a2.05 2.05 0 0 1 2.9 2.9L7.4 18.5l-3.9 1 1-3.9z"), L("M14.2 5.9l2.9 2.9"), F("M3.5 19.5l1-3.9 2.9 2.9z", round = true), L("M12.5 20.5h8")),
        spec("Copy", L2("M5.2 15h-.7A1.5 1.5 0 0 1 3 13.5v-9A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5v.7"), S("M10 8.5h9a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-8.5a2 2 0 0 1 2-2z"), L("M10 8.5h9a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-8.5a2 2 0 0 1 2-2z"), H("M9.9 12.6v-1a1.2 1.2 0 0 1 1.2-1.2h2")),
        spec("Paste", S("M7.5 5.5h9a2.5 2.5 0 0 1 2.5 2.5v10.5a2.5 2.5 0 0 1-2.5 2.5h-9a2.5 2.5 0 0 1-2.5-2.5v-10.5a2.5 2.5 0 0 1 2.5-2.5z"), L("M8.5 5.5h-1A2.5 2.5 0 0 0 5 8v10.5A2.5 2.5 0 0 0 7.5 21h9a2.5 2.5 0 0 0 2.5-2.5V8a2.5 2.5 0 0 0-2.5-2.5h-1"), F("M9.7 3h4.6a1.2 1.2 0 0 1 1.2 1.2v1.6a1.2 1.2 0 0 1-1.2 1.2h-4.6a1.2 1.2 0 0 1-1.2-1.2v-1.6a1.2 1.2 0 0 1 1.2-1.2z"), l("M9 12.3h6"), l("M9 16h3.6")),
        spec("Lock", L("M7.75 10.5V7.25a4.25 4.25 0 0 1 8.5 0v3.25"), S("M6.5 10.5h11a2.5 2.5 0 0 1 2.5 2.5v5.5a2.5 2.5 0 0 1-2.5 2.5h-11a2.5 2.5 0 0 1-2.5-2.5v-5.5a2.5 2.5 0 0 1 2.5-2.5z"), L("M6.5 10.5h11a2.5 2.5 0 0 1 2.5 2.5v5.5a2.5 2.5 0 0 1-2.5 2.5h-11a2.5 2.5 0 0 1-2.5-2.5v-5.5a2.5 2.5 0 0 1 2.5-2.5z"), H("M5.9 14.8v-1.1a1.3 1.3 0 0 1 1.3-1.3h2"), F("M12 13.4a1.3 1.3 0 1 0 0 2.6a1.3 1.3 0 1 0 0-2.6z"), l("M12 15.6v2.1")),
        spec("Shield", S("M12 2.75l7.75 2.9v5.85c0 4.9-3.3 8-7.75 9.25-4.45-1.25-7.75-4.35-7.75-9.25V5.65z"), L("M12 2.75l7.75 2.9v5.85c0 4.9-3.3 8-7.75 9.25-4.45-1.25-7.75-4.35-7.75-9.25V5.65z"), H("M6.2 10.2V7l2.9-1.1"), L("M8.6 11.9l2.5 2.5 4.4-4.9")),
        spec("Server", S("M5.5 3.5h13a2.5 2.5 0 0 1 2.5 2.5v2a2.5 2.5 0 0 1-2.5 2.5h-13a2.5 2.5 0 0 1-2.5-2.5v-2a2.5 2.5 0 0 1 2.5-2.5z"), L("M5.5 3.5h13a2.5 2.5 0 0 1 2.5 2.5v2a2.5 2.5 0 0 1-2.5 2.5h-13a2.5 2.5 0 0 1-2.5-2.5v-2a2.5 2.5 0 0 1 2.5-2.5z"), S("M5.5 13.5h13a2.5 2.5 0 0 1 2.5 2.5v2a2.5 2.5 0 0 1-2.5 2.5h-13a2.5 2.5 0 0 1-2.5-2.5v-2a2.5 2.5 0 0 1 2.5-2.5z"), L("M5.5 13.5h13a2.5 2.5 0 0 1 2.5 2.5v2a2.5 2.5 0 0 1-2.5 2.5h-13a2.5 2.5 0 0 1-2.5-2.5v-2a2.5 2.5 0 0 1 2.5-2.5z"), F("M7 5.7a1.3 1.3 0 1 0 0 2.6a1.3 1.3 0 1 0 0-2.6z"), F("M7 15.7a1.3 1.3 0 1 0 0 2.6a1.3 1.3 0 1 0 0-2.6z"), l("M11.5 7h5.5"), l("M11.5 17h3.5")),
        spec("Scan", L("M3 8V5.5A2.5 2.5 0 0 1 5.5 3H8"), L("M16 3h2.5A2.5 2.5 0 0 1 21 5.5V8"), L("M21 16v2.5a2.5 2.5 0 0 1-2.5 2.5H16"), L("M8 21H5.5A2.5 2.5 0 0 1 3 18.5V16"), S("M6.5 12h11v3.2a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z"), L("M6 12h12")),
        spec("QrCode", S("M5.1 3.5h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1-1.6 1.6h-3.3a1.6 1.6 0 0 1-1.6-1.6v-3.3a1.6 1.6 0 0 1 1.6-1.6z"), L("M5.1 3.5h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1-1.6 1.6h-3.3a1.6 1.6 0 0 1-1.6-1.6v-3.3a1.6 1.6 0 0 1 1.6-1.6z"), S("M15.6 3.5h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1-1.6 1.6h-3.3a1.6 1.6 0 0 1-1.6-1.6v-3.3a1.6 1.6 0 0 1 1.6-1.6z"), L("M15.6 3.5h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1-1.6 1.6h-3.3a1.6 1.6 0 0 1-1.6-1.6v-3.3a1.6 1.6 0 0 1 1.6-1.6z"), S("M5.1 14h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1-1.6 1.6h-3.3a1.6 1.6 0 0 1-1.6-1.6v-3.3a1.6 1.6 0 0 1 1.6-1.6z"), L("M5.1 14h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1-1.6 1.6h-3.3a1.6 1.6 0 0 1-1.6-1.6v-3.3a1.6 1.6 0 0 1 1.6-1.6z"), F("M6.35 5.85h0.8a0.5 0.5 0 0 1 0.5 0.5v0.8a0.5 0.5 0 0 1-0.5 0.5h-0.8a0.5 0.5 0 0 1-0.5-0.5v-0.8a0.5 0.5 0 0 1 0.5-0.5z"), F("M16.85 5.85h0.8a0.5 0.5 0 0 1 0.5 0.5v0.8a0.5 0.5 0 0 1-0.5 0.5h-0.8a0.5 0.5 0 0 1-0.5-0.5v-0.8a0.5 0.5 0 0 1 0.5-0.5z"), F("M6.35 16.35h0.8a0.5 0.5 0 0 1 0.5 0.5v0.8a0.5 0.5 0 0 1-0.5 0.5h-0.8a0.5 0.5 0 0 1-0.5-0.5v-0.8a0.5 0.5 0 0 1 0.5-0.5z"), F("M14.200000000000001 13.4h1.4a0.8 0.8 0 0 1 0.8 0.8v1.4a0.8 0.8 0 0 1-0.8 0.8h-1.4a0.8 0.8 0 0 1-0.8-0.8v-1.4a0.8 0.8 0 0 1 0.8-0.8z"), F("M18.8 18h1.4a0.8 0.8 0 0 1 0.8 0.8v1.4a0.8 0.8 0 0 1-0.8 0.8h-1.4a0.8 0.8 0 0 1-0.8-0.8v-1.4a0.8 0.8 0 0 1 0.8-0.8z"), F("M19 13.6h0.8a0.7 0.7 0 0 1 0.7 0.7v0.8a0.7 0.7 0 0 1-0.7 0.7h-0.8a0.7 0.7 0 0 1-0.7-0.7v-0.8a0.7 0.7 0 0 1 0.7-0.7z"), F("M14.299999999999999 18.3h0.8a0.7 0.7 0 0 1 0.7 0.7v0.8a0.7 0.7 0 0 1-0.7 0.7h-0.8a0.7 0.7 0 0 1-0.7-0.7v-0.8a0.7 0.7 0 0 1 0.7-0.7z")),
        spec("Info", S("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"), L("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"), H("M5.9 10.4a6.4 6.4 0 0 1 3.4-4.1"), L("M12 11.2v5.2"), F("M12 6.75a1.15 1.15 0 1 0 0 2.3a1.15 1.15 0 1 0 0-2.3z")),
        spec("Error", S("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"), L("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"), H("M5.9 10.4a6.4 6.4 0 0 1 3.4-4.1"), L("M12 7.4v5.6"), F("M12 15.25a1.15 1.15 0 1 0 0 2.3a1.15 1.15 0 1 0 0-2.3z")),
        spec("CloudOff", S("M7 19a5 5 0 0 1-.9-9.92A6 6 0 0 1 17.7 9.3 4.85 4.85 0 0 1 17.2 19z"), L("M7 19a5 5 0 0 1-.9-9.92A6 6 0 0 1 17.7 9.3 4.85 4.85 0 0 1 17.2 19z"), Slash(3.5f, 3.5f, 20.5f, 20.5f)),
        spec("Trash", S("M6.2 8.2l.75 10.5a2 2 0 0 0 2 1.8h6.1a2 2 0 0 0 2-1.8l.75-10.5z"), L("M6.2 8.2l.75 10.5a2 2 0 0 0 2 1.8h6.1a2 2 0 0 0 2-1.8l.75-10.5"), l("M10.2 11.6v5.4"), l("M13.8 11.6v5.4"), KL("M4 6.8h16"), KL("M9.3 6.8V5.2A1.2 1.2 0 0 1 10.5 4h3a1.2 1.2 0 0 1 1.2 1.2v1.6")),
        spec("Download", S("M4 16h16v2a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 18z"), L("M4 16v2a2.5 2.5 0 0 0 2.5 2.5h11A2.5 2.5 0 0 0 20 18v-2"), L("M12 3.5v11"), L("M7.5 10.5l4.5 4.5 4.5-4.5")),
        spec("Flip", L("M3.5 12a8.5 8.5 0 0 1 14.4-6.1L20.5 8.4"), L("M20.5 3.6v4.8h-4.8"), L("M20.5 12a8.5 8.5 0 0 1-14.4 6.1L3.5 15.6"), L("M3.5 20.4v-4.8h4.8")),
        spec("Expand", L("M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9"), L("M20 9V5.5A1.5 1.5 0 0 0 18.5 4H15"), L("M4 15v3.5A1.5 1.5 0 0 0 5.5 20H9"), L("M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15")),
        spec("Plus", L("M12 5v14"), L("M5 12h14")),
        spec("Close", L("M6 6l12 12"), L("M18 6L6 18")),
        spec("Check", L("M5 12.5l4.5 4.5L19 7")),
        spec("Back", L("M19 12H5"), L("M12 19l-7-7 7-7"), mirrored = true),
        spec("ArrowBack", L("M19 12H5"), L("M11 6l-6 6 6 6"), mirrored = true),
        spec("ArrowUp", L("M12 19V5"), L("M6 11l6-6 6 6")),
        spec("ChevronRight", L("M9 6l6 6-6 6"), mirrored = true),
        spec("ChevronDown", L("M6 9l6 6 6-6")),

        // The rest, by the same rules.
        spec("CloudUp", S("M17.5 19H7a5 5 0 1 1 1.1-9.9A6 6 0 0 1 19.5 11a4 4 0 0 1-2 8z"), L("M17.5 19H7a5 5 0 1 1 1.1-9.9A6 6 0 0 1 19.5 11a4 4 0 0 1-2 8z"), L("M12 16v-5"), L("M9.5 13.5L12 11l2.5 2.5")),
        spec("Phone", S("M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"), L("M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"), H("M8.2 7.5V5.4A1.2 1.2 0 0 1 9.4 4.2h1.6"), L("M11 18h2")),
        spec("Cast", L("M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"), L("M2 12a8 8 0 0 1 8 8"), L("M2 16a4 4 0 0 1 4 4"), F(circ(2.6f, 19.4f, 1.1f))),
        spec("CastOn", L("M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"), L("M2 12a8 8 0 0 1 8 8"), L("M2 16a4 4 0 0 1 4 4"), F(circ(2.6f, 19.4f, 1.1f)), F("M6 8H18V16H12.25A11 11 0 0 0 6 9.75Z")),
        spec("More", F("M12 3a2 2 0 1 0 0.01 0z"), F("M12 10a2 2 0 1 0 0.01 0z"), F("M12 17a2 2 0 1 0 0.01 0z")),
        spec("Grid", S(rr(3f, 3f, 8f, 8f, 2f)), L(rr(3f, 3f, 8f, 8f, 2f)), S(rr(13f, 3f, 8f, 8f, 2f)), L(rr(13f, 3f, 8f, 8f, 2f)), S(rr(3f, 13f, 8f, 8f, 2f)), L(rr(3f, 13f, 8f, 8f, 2f)), F(rr(13f, 13f, 8f, 8f, 2f))),
        spec("Calendar", S(rr(3f, 4.5f, 18f, 16.5f, 2.5f)), L(rr(3f, 4.5f, 18f, 16.5f, 2.5f)), L("M3 9.5h18"), L("M8 2.5v4"), L("M16 2.5v4"), F(rr(13.5f, 13.5f, 3.5f, 3.5f, 1f))),
        spec("DoNotDisturb", F("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm5 11H7v-2h10v2z")),
        spec("Minus", L("M5 12h14")),
        spec("Search", S(circ(11f, 11f, 7f)), L(circ(11f, 11f, 7f)), H("M7.2 10.2a4 4 0 0 1 2.9-3"), L("M20 20l-3.9-3.9")),
        spec("Tag", S("M3 12V5.5A2.5 2.5 0 0 1 5.5 3H12l9 9-9 9z"), L("M3 12V5.5A2.5 2.5 0 0 1 5.5 3H12l9 9-9 9z"), F(circ(7.6f, 7.6f, 1.4f))),
        spec("Stack", L2("M4 16V5.5A1.5 1.5 0 0 1 5.5 4H16"), S(rr(8f, 7f, 13f, 14f, 2f)), L(rr(8f, 7f, 13f, 14f, 2f)), H("M9.9 11.2v-1a1.2 1.2 0 0 1 1.2-1.2h2")),
        spec("Settings", L("M4 7h9"), L("M17 7h3"), F(rr(13.6f, 4.4f, 2.8f, 5.2f, 1.2f)), L("M4 17h3"), L("M11 17h9"), L(rr(7.6f, 14.4f, 2.8f, 5.2f, 1.2f))),
        spec("Eye", S("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"), L("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"), F(circ(12f, 12f, 3f), hi = Hi(11f, 10.8f, 11.6f))),
        spec("EyeOff", L("M9.9 5.2A10.5 10.5 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-2.6 3.5"), L("M6.6 6.6C3.8 8.4 2 12 2 12s3.5 7 10 7c1.8 0 3.4-.5 4.8-1.3"), L("M9.9 9.9a3 3 0 0 0 4.2 4.2"), Slash(3f, 3f, 21f, 21f)),
        spec("Clock", S(circ(12f, 12f, 9f)), L(circ(12f, 12f, 9f)), H("M5.9 10.4a6.4 6.4 0 0 1 3.4-4.1"), L("M12 7.5V12l3 2"), F(circ(12f, 12f, 1.1f))),
        spec("Crop", L("M6 2v13.5A2.5 2.5 0 0 0 8.5 18H22"), L("M18 22V8.5A2.5 2.5 0 0 0 15.5 6H2"), S("M8 8h8v8H8z")),
        spec("Pin", S("M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z"), L("M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z"), H("M7.6 8.6a4.6 4.6 0 0 1 2.6-3.2"), F(circ(12f, 9.5f, 2.3f))),
        spec("ArrowDown", L("M12 5v14"), L("M6 13l6 6 6-6")),
        spec("Sparkle", S("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"), L("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"), F("M19 16l.7 1.8 1.8.7-1.8.7L19 21l-.7-1.8-1.8-.7 1.8-.7z", round = true)),
        spec("Map", S("M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z"), L("M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z"), S("M9 4l6 2v14l-6-2z"), L("M9 4v14"), L("M15 6v14")),
        spec("Wifi", L("M2 8.8a15 15 0 0 1 20 0"), L("M5 12.5a10 10 0 0 1 14 0"), L("M8.5 16a5 5 0 0 1 7 0"), F(circ(12f, 19.4f, 1.3f))),
        spec("Star", S("M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"), L("M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z")),
        spec("StarFilled", F("M12 2.5l2.9 6 6.6 1-4.8 4.6 1.1 6.6L12 17.6l-5.8 3.1 1.1-6.6L2.5 9.5l6.6-1z", round = true)),
        spec("Block", S(circ(12f, 12f, 9f)), L(circ(12f, 12f, 9f)), L("M5.6 5.6l12.8 12.8")),

    ).associateBy { it.name }
}

/** How strong an icon's soft fill and inner edge are: by the page (light, dark), and stronger in white on coral. */
internal enum class IconStrength(val soft: Float, val edge: Float) {
    Light(0.08f, 0.30f),
    Dark(0.10f, 0.35f),
    OnAccent(0.14f, 0.40f),
}

/** Null: by the page's theme. */
internal val LocalIconStrength = staticCompositionLocalOf<IconStrength?> { null }

/** Icons in [content] sit white on a coral (or other strong) fill: their soft parts a little stronger so they still read. */
@Composable
internal fun IconsOnAccent(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalIconStrength provides IconStrength.OnAccent, content = content)

/**
 * [icon] in [tint], and its solid part in [key] when given: the second colour of the six that have one (teal while live,
 * coral for an urgent push, red while removing). Without [key] it is exactly `Icon(icon, tint = tint)`.
 */
@Composable
internal fun FineIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    key: Color? = null,
) {
    val part = if (key != null) LibraryIcons.keyOf(icon) else null
    if (part == null) {
        Icon(icon, contentDescription, modifier, tint)
        return
    }
    val painter = rememberVectorPainter(part)
    Icon(
        icon, contentDescription,
        modifier.drawWithContent {
            drawContent()
            with(painter) { draw(size, colorFilter = ColorFilter.tint(key!!)) }
        },
        tint,
    )
}

// ---- how an icon is drawn ----------------------------------------------------------------------------------------

/** A layer: [key] marks the part that takes the second colour. */
internal sealed class Layer(val key: Boolean = false)
/** A line: full strength 1.75, a finer 1.5, or a line of the layer behind at 55%. */
private class Line(val d: String, val width: Float, val alpha: Float, key: Boolean = false) : Layer(key)
/** A body's soft fill. */
private class Soft(val d: String) : Layer()
/** The 1-unit inner edge. */
private class Edge(val d: String) : Layer()
/** A solid part; [hi] is its inner edge, cut out of it and drawn at the edge's strength. [round] softens its corners. */
private class Solid(val d: String, val hi: Hi?, val round: Boolean, key: Boolean) : Layer(key)
/** The bar of an "off" icon: everything before it gets a gap where it passes. */
private class Slash(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Layer()
/** A short vertical inner edge on a solid part, at x from y1 to y2. */
internal class Hi(val x: Float, val y1: Float, val y2: Float)

internal class Spec(val name: String, val mirrored: Boolean, val layers: List<Layer>)

private fun spec(name: String, vararg layers: Layer, mirrored: Boolean = false) = Spec(name, mirrored, layers.toList())
private fun L(d: String): Layer = Line(d, 1.75f, 1f)
private fun l(d: String): Layer = Line(d, 1.5f, 1f)
private fun L2(d: String): Layer = Line(d, 1.75f, 0.55f)
private fun KL(d: String): Layer = Line(d, 1.75f, 1f, key = true)
private fun S(d: String): Layer = Soft(d)
private fun H(d: String): Layer = Edge(d)
private fun F(d: String, hi: Hi? = null, round: Boolean = false): Layer = Solid(d, hi, round, key = false)
private fun K(d: String, hi: Hi? = null, round: Boolean = false): Layer = Solid(d, hi, round, key = true)

private fun n(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

/** A rounded rectangle. */
private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${n(x + r)} ${n(y)}h${n(w - 2 * r)}a${n(r)} ${n(r)} 0 0 1 ${n(r)} ${n(r)}v${n(h - 2 * r)}a${n(r)} ${n(r)} 0 0 1-${n(r)} ${n(r)}" +
        "h-${n(w - 2 * r)}a${n(r)} ${n(r)} 0 0 1-${n(r)}-${n(r)}v-${n(h - 2 * r)}a${n(r)} ${n(r)} 0 0 1 ${n(r)}-${n(r)}z"

/** A circle. */
private fun circ(cx: Float, cy: Float, r: Float) =
    "M${n(cx)} ${n(cy - r)}a${n(r)} ${n(r)} 0 1 0 0 ${n(2 * r)}a${n(r)} ${n(r)} 0 1 0 0-${n(2 * r)}z"

/** The 1-unit cut of an inner edge on a solid part. */
private fun capsule(h: Hi) = "M${n(h.x - 0.5f)} ${n(h.y1)}a.5 .5 0 0 1 1 0V${n(h.y2)}a.5 .5 0 0 1-1 0z"

/** The whole square less a 5-unit band along the slash, wound the other way so the band is left out. */
private fun slashClip(s: Slash): String {
    val dx = s.x2 - s.x1
    val dy = s.y2 - s.y1
    val len = kotlin.math.hypot(dx, dy)
    val ux = dx / len * 2.5f
    val uy = dy / len * 2.5f
    // a, b along one side, c, d back along the other; the square runs clockwise, so this runs counter-clockwise
    var pts = listOf(
        (s.x1 - ux - uy) to (s.y1 - uy + ux), (s.x2 + ux - uy) to (s.y2 + uy + ux),
        (s.x2 + ux + uy) to (s.y2 + uy - ux), (s.x1 - ux + uy) to (s.y1 - uy - ux),
    )
    val area = pts.indices.sumOf { i -> val (x0, y0) = pts[i]; val (x1, y1) = pts[(i + 1) % pts.size]; (x0 * y1 - x1 * y0).toDouble() }
    if (area > 0) pts = pts.reversed()
    return "M0 0H24V24H0Z" + pts.mapIndexed { i, (x, y) -> "${if (i == 0) "M" else "L"}${n(x)} ${n(y)}" }.joinToString("") + "Z"
}

private fun nodes(d: String) = PathParser().parsePathString(d).toNodes()

private fun ImageVector.Builder.stroke(d: String, width: Float, alpha: Float) = addPath(
    pathData = nodes(d), stroke = SolidColor(Color.Black), strokeAlpha = alpha, strokeLineWidth = width,
    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
)

private fun ImageVector.Builder.fill(d: String, alpha: Float, evenOdd: Boolean = false) = addPath(
    pathData = nodes(d), fill = SolidColor(Color.Black), fillAlpha = alpha,
    pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
)

/** [spec] at [strength]; [keyOnly]: only the part that takes the second colour, to lay over the icon. */
private fun draw(spec: Spec, strength: IconStrength, keyOnly: Boolean): ImageVector {
    val b = ImageVector.Builder(if (keyOnly) "${spec.name}.key" else spec.name, 24.dp, 24.dp, 24f, 24f, autoMirror = spec.mirrored)
    val slash = spec.layers.filterIsInstance<Slash>().firstOrNull()
    if (slash != null && !keyOnly) b.addGroup(clipPathData = nodes(slashClip(slash)))
    for (layer in spec.layers) {
        if (keyOnly && !layer.key) continue
        when (layer) {
            is Line -> b.stroke(layer.d, layer.width, layer.alpha)
            is Soft -> b.fill(layer.d, strength.soft)
            is Edge -> b.stroke(layer.d, 1f, strength.edge)
            is Solid -> {
                val hole = layer.hi?.let(::capsule)
                b.fill(layer.d + (hole ?: ""), 1f, evenOdd = hole != null)
                if (layer.round) b.stroke(layer.d, 1f, 1f)
                if (hole != null) b.fill(hole, strength.edge)
            }
            is Slash -> Unit
        }
    }
    if (slash != null && !keyOnly) {
        b.clearGroup()
        b.stroke("M${n(slash.x1)} ${n(slash.y1)}L${n(slash.x2)} ${n(slash.y2)}", 1.75f, 1f)
    }
    return b.build()
}

/** The second colour of a live part (a call's open microphone, its camera): teal, brighter on the dark page. */
internal val IconLive: Color
    @Composable @ReadOnlyComposable get() = if (LibraryColors.Background.luminance() < 0.5f) Color(0xFF5CC1B5) else Color(0xFF1F5F5B)
