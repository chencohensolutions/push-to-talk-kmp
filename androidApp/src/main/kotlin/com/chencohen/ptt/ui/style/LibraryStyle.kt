package com.chencohen.ptt.ui.style

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Journal's library colors (journal-client-kmp, names kept so docs/screens/style.md reads the same), light or dark with
 * the system theme. Things drawn over photos (the
 * Photo* colors, scrims and LabelFade) are the same in both: white on a dark scrim reads on any photo.
 */
@Immutable
internal class LibraryPalette(
    val Background: Color,
    val Raised: Color,
    /** Text and icons on Background and Raised. */
    val OnBackground: Color,
    val Muted: Color,
    val Accent: Color,
    /** The main color, a surface that stands out from the page: pink in both themes (buttons, the selected tab, Undo). */
    val Inverse: Color,
    val OnInverse: Color,
    val OnInverseMuted: Color,
    val Warning: Color,
    val Error: Color,
    /**
     * This phone's own chat bubbles, their text and their time (2026-11-26, hen: green; the mock's "B"): the fill of
     * one every participant's phone has. [MineLine]: its outline before that, and the ring of a face that read; the
     * same green, brighter on the dark page so a thin line reads as the fill does.
     */
    val Mine: Color = Color(0xFF23774A),
    val OnMine: Color = Color(0xFFF2FBF5),
    val MineLine: Color = Color(0xFF32A066),
    /** Their chat bubbles and the text on them (2026-11-27, hen: a chat's two colours can be chosen; the page's raised by default). */
    val Theirs: Color = Raised,
    val OnTheirs: Color = OnBackground,
    /** The writing bubble's outline, caret and Send, and my draft's (coral by default; a chat's own colour for mine). */
    val Writing: Color = Color(0xFFE9504F),
    /** Their live draft's outline and its "writing" label (teal by default; a chat's own colour for theirs). */
    val TheirLine: Color = Accent,
) {
    /** The same palette with [ink] as the text on my bubbles (an outline bubble's text is the page's). */
    fun withOnMine(ink: Color) = LibraryPalette(Background, Raised, OnBackground, Muted, Accent, Inverse, OnInverse, OnInverseMuted, Warning, Error, Mine, ink, MineLine, Theirs, OnTheirs, Writing, TheirLine)

    /**
     * A chat's own colours ([mine], [theirs]; null keeps the default), the whole way (2026-11-27, hen: "respect the
     * sending rectangle border colors and also the new message textbox border colors"): my bubble's outline (dashed,
     * then whole), its fill, the writing bubble's outline and my draft's in [mine]; their bubbles and their live draft's
     * outline in [theirs]. Text on them is light or dark by the colour.
     */
    fun withChatColors(mine: Color?, theirs: Color?, mineLine: Color? = mine, theirLine: Color? = theirs) = LibraryPalette(
        Background, Raised, OnBackground, Muted, Accent, Inverse, OnInverse, OnInverseMuted, Warning, Error,
        mine ?: Mine, mine?.let { inkOn(it) } ?: OnMine, mineLine ?: MineLine,
        theirs ?: Theirs, theirs?.let { inkOn(it) } ?: OnTheirs,
        mineLine ?: Writing, theirLine ?: TheirLine,
    )

    val OnTheirsMuted: Color get() = OnTheirs.copy(alpha = 0.6f)

    val OnMineMuted: Color get() = OnMine.copy(alpha = 0.6f)
    val BadgeScrim = Color(0x990F1413)
    val PillScrim = Color(0xB30F1413)
    val PhotoText = Color.White
    val PhotoTextMuted = Color(0xFFE3E8E6)

    /** Light chips over photos (the calendar block, "Cover"). */
    val Paper = Color(0xFFF5F6F4)
    val PaperInk = Color(0xFF17201F)

    /** Behind the floating top bar: the page color at the edge, fading into the content. */
    val TopFade = Brush.verticalGradient(listOf(Background.copy(alpha = 0.95f), Background.copy(alpha = 0.75f), Color.Transparent))

    /** Under text written over the top or bottom of a photo. */
    val LabelFade = Brush.verticalGradient(listOf(Color(0x8C0F1413), Color.Transparent))
    val PhotoFade = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60F1413)))
}

/**
 * Talk's main color, an orange-pink coral (2026-11-22, hen), the same in light and dark: white bold text on it reads (3.8:1), and it stands out
 * from both page colors.
 */
internal val Pink = Color(0xFFE9504F)

internal val DarkLibraryPalette = LibraryPalette(
    Background = Color(0xFF0F1413),
    Raised = Color(0xFF1E2725),
    OnBackground = Color.White,
    Muted = Color(0xFFA9B4B1),
    Accent = Color(0xFF1F5F5B),
    Inverse = Pink,
    OnInverse = Color.White,
    OnInverseMuted = Color(0xFFFFD1E8),
    Warning = Color(0xFFF2B84B),
    Error = Color(0xFFC2410C),
)

internal val LightLibraryPalette = LibraryPalette(
    Background = Color(0xFFF4F5F3),
    Raised = Color.White,
    OnBackground = Color(0xFF17201F),
    Muted = Color(0xFF5E6866),
    Accent = Color(0xFF1F5F5B),
    Inverse = Pink,
    OnInverse = Color.White,
    OnInverseMuted = Color(0xFFFFD1E8),
    Warning = Color(0xFFB45309),
    Error = Color(0xFFC2410C),
    MineLine = Color(0xFF23774A),
)

private val LocalLibraryPalette = staticCompositionLocalOf { DarkLibraryPalette }

/** [content] with [ink] as the text on my bubbles: what a bubble of mine holds reads on its outline and on its fill. */
@Composable
internal fun MineInk(ink: Color, content: @Composable () -> Unit) {
    val base = LocalLibraryPalette.current
    val palette = androidx.compose.runtime.remember(base, ink) { if (ink == base.OnMine) base else base.withOnMine(ink) }
    CompositionLocalProvider(LocalLibraryPalette provides palette, content = content)
}

/**
 * Text on [c]: white on a dark colour, near-black on a light one (2026-11-27, hen: "white on dark or dark on white").
 * The deep chat colours are made for white text (4.6:1), where black reads about the same; white is kept unless black
 * clearly stands out more (the bright ones: 11:1 against about 2).
 */
internal fun inkOn(c: Color): Color {
    val l = c.luminance()
    val black = (l + 0.05f) / 0.05f
    val white = 1.05f / (l + 0.05f)
    return if (black > white * 2f) Color(0xFF17201F) else Color.White
}

/**
 * The chat colours one can pick (2026-11-27, hen): six hues spread evenly around the wheel (orange, lime, green, blue,
 * violet, pink), each shown by the theme: in the light theme its deep shade with white text (4.6:1), in the dark theme
 * its bright shade with black text ("in light theme white text on dark color, in dark theme black text on bright
 * color"). Used for the fill, the outlines and the writing box. -1 is the default (my green, their raised page colour).
 */
internal val ChatHuesDeep = listOf(
    Color(0xFFB85C00), Color(0xFF428500), Color(0xFF008744), Color(0xFF0073E5), Color(0xFF9E3DFF), Color(0xFFE50072),
)
internal val ChatHuesBright = listOf(
    // Orange, blue, violet and pink at black 8:1 (hen: "the red is too close to white"); lime and green at 11:1.
    Color(0xFFFA7D00), Color(0xFF6AD400), Color(0xFF00D86C), Color(0xFF48A4FF), Color(0xFFC284FF), Color(0xFFFF6BB5),
)

/**
 * Chat hue [i] as this theme shows it (2026-11-27, hen: "reverse the colors of light and dark"): in the light theme its
 * bright shade with black text, in the dark theme its deep shade with white text. A stored pick from the twelve maps to
 * its hue.
 */
internal fun chatHue(i: Int, lightPage: Boolean): Color? {
    if (i < 0) return null
    val h = i % ChatHuesDeep.size
    return if (lightPage) ChatHuesBright[h] else ChatHuesDeep[h]
}

/** Hue [i]'s thin lines (outlines, the writing box, rings): its deep shade in both themes, so a line stays seen. */
internal fun chatHueLine(i: Int): Color? = if (i < 0) null else ChatHuesDeep[i % ChatHuesDeep.size]

/** [content] with a chat's colours ([mine], [theirs]: chat hue indexes, -1 the default), in this theme's shade. */
@Composable
internal fun ChatColors(mine: Int, theirs: Int, content: @Composable () -> Unit) {
    if (mine < 0 && theirs < 0) return content()
    val base = LocalLibraryPalette.current
    val palette = androidx.compose.runtime.remember(base, mine, theirs) {
        val light = base.Background.luminance() > 0.5f
        base.withChatColors(chatHue(mine, light), chatHue(theirs, light), chatHueLine(mine), chatHueLine(theirs))
    }
    CompositionLocalProvider(LocalLibraryPalette provides palette, content = content)
}

/** [content] with their colour as hue [i] (a group member's), in this theme's shade; mine as it is. */
@Composable
internal fun TheirHue(i: Int, content: @Composable () -> Unit) {
    val base = LocalLibraryPalette.current
    val palette = androidx.compose.runtime.remember(base, i) {
        base.withChatColors(null, chatHue(i, base.Background.luminance() > 0.5f), null, chatHueLine(i))
    }
    CompositionLocalProvider(LocalLibraryPalette provides palette, content = content)
}

internal val LibraryColors: LibraryPalette
    @Composable @ReadOnlyComposable get() = LocalLibraryPalette.current

/**
 * The library palette for [content]: the system theme's, or dark when [dark] says so (the viewer). Material's colors
 * come from the same palette ([materialColors]), so dialogs, text fields, sliders and default buttons match the
 * library instead of the wallpaper.
 */
@Composable
internal fun LibraryTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkLibraryPalette else LightLibraryPalette
    CompositionLocalProvider(LocalLibraryPalette provides palette) {
        MaterialTheme(colorScheme = if (dark) DarkMaterialColors else LightMaterialColors, typography = MaterialTheme.typography, content = content)
    }
}

/**
 * Material's color scheme from a library palette: pink (Inverse) is the main color, teal (Accent) the secondary for
 * "on" states and progress, surfaces from Background and Raised, Muted for secondary text and outlines.
 */
internal fun materialColors(p: LibraryPalette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val tealContainer = if (dark) Color(0xFF16403D) else Color(0xFFD3E6E3)
    val onTealContainer = if (dark) Color(0xFFCDE7E3) else Color(0xFF0E3431)
    return base.copy(
        primary = p.Inverse,
        onPrimary = p.OnInverse,
        primaryContainer = p.Raised,
        onPrimaryContainer = p.OnBackground,
        inversePrimary = p.Accent,
        secondary = p.Accent,
        onSecondary = Color.White,
        secondaryContainer = tealContainer,
        onSecondaryContainer = onTealContainer,
        tertiary = p.Accent,
        onTertiary = Color.White,
        tertiaryContainer = tealContainer,
        onTertiaryContainer = onTealContainer,
        background = p.Background,
        onBackground = p.OnBackground,
        surface = p.Background,
        onSurface = p.OnBackground,
        surfaceVariant = p.Raised,
        onSurfaceVariant = p.Muted,
        surfaceTint = Color.Transparent,
        inverseSurface = p.Inverse,
        inverseOnSurface = p.OnInverse,
        error = p.Error,
        onError = Color.White,
        errorContainer = p.Error.copy(alpha = 0.15f),
        onErrorContainer = p.Error,
        outline = p.Muted,
        outlineVariant = p.Muted.copy(alpha = 0.35f),
        scrim = Color.Black,
        surfaceBright = p.Raised,
        surfaceDim = p.Background,
        surfaceContainerLowest = p.Background,
        surfaceContainerLow = p.Raised,
        surfaceContainer = p.Raised,
        surfaceContainerHigh = p.Raised,
        surfaceContainerHighest = p.Raised,
    )
}

internal val LightMaterialColors = materialColors(LightLibraryPalette, dark = false)
internal val DarkMaterialColors = materialColors(DarkLibraryPalette, dark = true)

/**
 * The library's text sizes (docs/screens/style.md). Each carries its size and weight; a call may still set its own
 * weight or color. Nothing is drawn below [Tiny] (11 sp) except digits inside an icon.
 */
internal object LibraryType {
    /** The home screen's title: "Chats", "Calls". */
    val ScreenTitle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold)
    /** A page's or sheet's title. */
    val PageTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
    /** A section's label over a card ("Account", "Your server"), in Muted. */
    val SectionLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    /** A row's title in a card or list. */
    val RowTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 14.sp)

    /**
     * A message's words (2026-11-24, hen: English written live on a right-to-left phone showed its caret at the line's
     * start): their own direction from their first strong letter, English left to right and Hebrew right to left,
     * whatever the screen's.
     */
    val Message = Body.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content)
    /** Notes and second lines under a title. */
    val Caption = TextStyle(fontSize = 13.sp)
    /** Badges and counts over photos. */
    val Small = TextStyle(fontSize = 12.sp)
    val Tiny = TextStyle(fontSize = 11.sp)
}

/** Corners (docs/screens/style.md): cards and sheets, tiles inside a card, and full pills for controls. */
internal object LibraryShapes {
    val Card = RoundedCornerShape(16.dp)
    val Sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val Tile = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(50)
}

/** Line icons (Material icons are not a dependency of this app). */
internal object LibraryIcons {
    /** [mirrored] icons point along the reading direction, so they flip in right-to-left layouts (Hebrew). */
    private fun icon(name: String, filled: Boolean, vararg paths: String, mirrored: Boolean = false) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored).apply {
            paths.forEach { data ->
                val nodes = PathParser().parsePathString(data).toNodes()
                if (filled) {
                    addPath(pathData = nodes, fill = SolidColor(Color.Black))
                } else {
                    addPath(
                        pathData = nodes,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }.build()

    val CloudOff = icon("CloudOff", false, "M17.5 19H7a5 5 0 0 1-2.7-9.2", "M8.6 5.6A6 6 0 0 1 19.5 11a4 4 0 0 1 1.6 6.3", "M2 2l20 20")
    val CloudUp = icon("CloudUp", false, "M17.5 19H7a5 5 0 1 1 1.1-9.9A6 6 0 0 1 19.5 11a4 4 0 0 1-2 8z", "M12 16v-5", "M9.5 13.5L12 11l2.5 2.5")
    val Check = icon("Check", false, "M5 12l5 5 9-10")
    val Phone = icon("Phone", false, "M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2")
    val Play = icon("Play", true, "M7 4v16l13-8z")
    val Pause = icon("Pause", true, "M7 5h3.5v14H7z", "M13.5 5H17v14h-3.5z")
    /** A TV with Cast's waves in its corner: casting to a TV. */
    val Cast = icon("Cast", false, "M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M2 12a8 8 0 0 1 8 8", "M2 16a4 4 0 0 1 4 4", "M2 20h0.01")
    /** [Cast] filled in, as Cast's own button is while connected: the screen solid beside its waves. */
    val CastOn = ImageVector.Builder("CastOn", 24.dp, 24.dp, 24f, 24f).apply {
        listOf("M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M2 12a8 8 0 0 1 8 8", "M2 16a4 4 0 0 1 4 4", "M2 20h0.01").forEach {
            addPath(
                pathData = PathParser().parsePathString(it).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        // The screen's inside, clear of the outer wave.
        addPath(pathData = PathParser().parsePathString("M6 8H18V16H12.25A11 11 0 0 0 6 9.75Z").toNodes(), fill = SolidColor(Color.Black))
    }.build()
    val More = icon("More", true, "M12 3a2 2 0 1 0 0.01 0z", "M12 10a2 2 0 1 0 0.01 0z", "M12 17a2 2 0 1 0 0.01 0z")
    val Back = icon("Back", false, "M19 12H5", "M12 19l-7-7 7-7", mirrored = true)
    val Info = icon("Info", false, "M12 3a9 9 0 1 0 0.01 0", "M12 11v5", "M12 8h0.01")
    val Grid = icon("Grid", false, "M4 3h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M14 3h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M4 13h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z", "M14 13h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z")
    val Calendar = icon("Calendar", false, "M5 4h14a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 9h18", "M8 2v4", "M16 2v4")
    /** Full screen: four corners pointing out. */
    val Expand = icon("Expand", false, "M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5")
    val Lock = icon("Lock", false, "M5 11h14a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")
    val Plus = icon("Plus", false, "M12 5v14", "M5 12h14")
    /** Urgent push (2026-11-25, hen): a bell. */
    val Bell = icon("Bell", false, "M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9", "M13.73 21a2 2 0 0 1-3.46 0")
    /** Silent (who can hear you; 2026-11-25, hen): a bell crossed out. */
    val BellOff = icon("BellOff", false, "M13.73 21a2 2 0 0 1-3.46 0", "M18.63 13A17.9 17.9 0 0 1 18 8", "M6.26 6.26A5.9 5.9 0 0 0 6 8c0 7-3 9-3 9h14", "M18 8a6 6 0 0 0-9.33-5", "M2 2l20 20")
    /** Do Not Disturb (2026-11-25, hen: Android's own sign, not a moon): a filled circle with a bar through it. */
    val DoNotDisturb = icon("DoNotDisturb", true, "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm5 11H7v-2h10v2z")
    val Group = icon("Group", false, "M9 4a4 4 0 1 0 0.01 0", "M2 21v-1a6 6 0 0 1 6-6h2a6 6 0 0 1 6 6v1", "M16 3.2a4 4 0 0 1 0 7.6", "M19 14.2a6 6 0 0 1 3 5.8v1")
    val Edit = icon("Edit", false, "M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z")
    val Minus = icon("Minus", false, "M5 12h14")
    val Search = icon("Search", false, "M11 4a7 7 0 1 0 0.01 0", "M20 20l-3.5-3.5")
    val Tag = icon("Tag", false, "M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z", "M7.5 7.5h0.01")
    val Close = icon("Close", false, "M6 6l12 12", "M18 6L6 18")
    val Trash = icon("Trash", false, "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13", "M10 11v6", "M14 11v6")
    val Stack = icon("Stack", false, "M9 7h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", "M4 16V5a1 1 0 0 1 1-1h11")
    val Settings = icon("Settings", false, "M4 7h9", "M17 7h3", "M15 5v4", "M4 17h3", "M11 17h9", "M9 15v4")
    val Eye = icon("Eye", false, "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M12 9a3 3 0 1 0 0.01 0")
    /** Tucked away: an eye with a line through it. */
    val EyeOff = icon("EyeOff", false, "M9.9 5.2A10.5 10.5 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-2.6 3.5", "M6.6 6.6C3.8 8.4 2 12 2 12s3.5 7 10 7c1.8 0 3.4-.5 4.8-1.3", "M9.9 9.9a3 3 0 0 0 4.2 4.2", "M3 3l18 18")
    val Clock = icon("Clock", false, "M12 3a9 9 0 1 0 0.01 0", "M12 7v5l3 2")
    val Crop = icon("Crop", false, "M6 2v14a2 2 0 0 0 2 2h14", "M18 22V8a2 2 0 0 0-2-2H2")
    val ChevronRight = icon("ChevronRight", false, "M9 6l6 6-6 6", mirrored = true)
    val ChevronDown = icon("ChevronDown", false, "M6 9l6 6 6-6")
    val Download = icon("Download", false, "M12 3v12", "M7 10l5 5 5-5", "M5 21h14")
    val Share = icon(
        "Share", false, "M18 2a3 3 0 1 0 0.01 0", "M6 9a3 3 0 1 0 0.01 0", "M18 16a3 3 0 1 0 0.01 0", "M8.6 13.5l6.8 4", "M15.4 6.5l-6.8 4",
    )
    val Pin = icon("Pin", false, "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z", "M12 7.5a2 2 0 1 0 0.01 0")
    /** Search's orders: newest first (down), oldest first (up), most relevant (a sparkle). */
    val Mic = icon("Mic", false, "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    /** The Talk button: a walkie-talkie. */
    val Talk = icon("Talk", false, "M8 7h8a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M9 2v5", "M9 11h6", "M9 14h6", "M12 18h0.01")
    val Video = icon("Video", false, "M4 6h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M17 10l5-3v10l-5-3")
    val VideoOff = icon("VideoOff", false, "M17 10l5-3v10l-5-3", "M10 6h5a2 2 0 0 1 2 2v5", "M15 18H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2", "M2 2l20 20")
    val MicOff = icon("MicOff", false, "M9 9v3a3 3 0 0 0 5.1 2.1", "M15 9.3V6a3 3 0 0 0-5.9-.7", "M17 16.9A7 7 0 0 1 5 12", "M19 11a7 7 0 0 1-.4 2.3", "M12 18v3", "M2 2l20 20")
    val Speaker = icon("Speaker", false, "M11 5L6 9H2v6h4l5 4z", "M15.5 8.5a5 5 0 0 1 0 7", "M19 5a10 10 0 0 1 0 14")
    val SpeakerOff = icon("SpeakerOff", false, "M11 5L6 9H2v6h4l5 4z", "M22 9l-6 6", "M16 9l6 6")
    val Flip = icon("Flip", false, "M3 12a9 9 0 0 1 15-6.7L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-15 6.7L3 16", "M3 21v-5h5")
    val ArrowDown = icon("ArrowDown", false, "M12 5v14", "M6 13l6 6 6-6")
    val ArrowUp = icon("ArrowUp", false, "M12 19V5", "M6 11l6-6 6 6")
    val Sparkle = icon("Sparkle", false, "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z", "M19 16l.7 1.8 1.8.7-1.8.7L19 21l-.7-1.8-1.8-.7 1.8-.7z")
    val Map = icon("Map", false, "M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z", "M9 4v14", "M15 6v14")
    val Copy = icon("Copy", false, "M9 9h11a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z", "M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1")
    val Wifi = icon("Wifi", false, "M2 8.8a15 15 0 0 1 20 0", "M5 12.5a10 10 0 0 1 14 0", "M8.5 16a5 5 0 0 1 7 0", "M12 19.5h0.01")
    /** Highlight: a star's outline, and [StarFilled] once highlighted. */
    val Star = icon("Star", false, "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z")
    val StarFilled = icon("StarFilled", true, "M12 2.5l2.9 6 6.6 1-4.8 4.6 1.1 6.6L12 17.6l-5.8 3.1 1.1-6.6L2.5 9.5l6.6-1z")
    /** Talk's own: a person (the profile circle), a chat bubble and a phone handset (the bottom tabs' empty states). */
    val Person = icon("Person", false, "M12 4a4 4 0 1 0 0.01 0", "M4 20c1.5-3.5 4.5-5 8-5s6.5 1.5 8 5")
    val Chat = icon("Chat", false, "M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-4.6A8 8 0 1 1 21 12z")
    val Call = icon("Call", false, "M5 4h3.5l1.5 4.5-2.2 1.4a11 11 0 0 0 6.3 6.3l1.4-2.2L20 15.5V19a1.5 1.5 0 0 1-1.6 1.5A16.5 16.5 0 0 1 3.5 5.6 1.5 1.5 0 0 1 5 4z")
    val Photo = icon("Photo", false, "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8.5 10a1.5 1.5 0 1 0 0.01 0", "M21 15l-5-5L5 21")
    /** Scan a QR code: the four corners of a viewfinder and a line across. */
    val Scan = icon("Scan", false, "M3 8V5a2 2 0 0 1 2-2h3", "M16 3h3a2 2 0 0 1 2 2v3", "M21 16v3a2 2 0 0 1-2 2h-3", "M8 21H5a2 2 0 0 1-2-2v-3", "M7 12h10")
    /** The safety number: a shield with a check. */
    val Shield = icon("Shield", false, "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", "M8.5 12l2.5 2.5 4.5-5")
    val Block = icon("Block", false, "M12 3a9 9 0 1 0 0.01 0", "M5.6 5.6l12.8 12.8")
}
