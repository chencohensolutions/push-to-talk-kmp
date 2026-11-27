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
    fun withChatColors(mine: Color?, theirs: Color?, mineLine: Color? = mine, theirLine: Color? = theirs, ink: Color? = null) = LibraryPalette(
        Background, Raised, OnBackground, Muted, Accent, Inverse, OnInverse, OnInverseMuted, Warning, Error,
        mine ?: Mine, mine?.let { ink ?: inkOn(it) } ?: OnMine, mineLine ?: MineLine,
        theirs ?: Theirs, theirs?.let { ink ?: inkOn(it) } ?: OnTheirs,
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
    return if (black > white * 1.15f) Color(0xFF17201F) else Color.White
}

/**
 * The chat colours one can pick (2026-11-27, hen): six hues spread evenly around the wheel (orange, lime, green, blue,
 * violet, pink), each shown by the theme: in the light theme its deep shade with white text (4.6:1), in the dark theme
 * its bright shade with black text ("in light theme white text on dark color, in dark theme black text on bright
 * color"). Used for the fill, the outlines and the writing box. -1 is the default (my green, their raised page colour).
 */
internal val ChatHuesDeep = listOf(
    Color(0xFFB85C00), Color(0xFF428500), Color(0xFF008744), Color(0xFF0073E5), Color(0xFF9E3DFF), Color(0xFFC30061),
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
    val base = if (lightPage) ChatHuesBright[h] else ChatHuesDeep[h]
    return shaded(base, lightPage, if (lightPage) ChatShade.light else ChatShade.dark)
}

/**
 * The bubbles' brightness (2026-11-27, hen: "make it from white to black slider, the brightness, default at center"),
 * each 0..1 for its theme, from Settings' chat colours: 0.5 the colour as it is, towards 0 lighter up to near white,
 * towards 1 darker down to near black. The text on it turns white or black, whichever reads.
 */
internal object ChatShade {
    /** TalkSettings.colorsFor's key for it: [light, dark] in hundredths. */
    const val KEY = "*bright"

    private var taken: List<Int>? = listOf(-1)

    fun take(saved: List<Int>?) {
        // Only when the saved values changed: a slider being moved isn't put back meanwhile.
        if (saved == taken) return
        taken = saved
        light = (saved?.getOrNull(0) ?: 50) / 100f
        dark = (saved?.getOrNull(1) ?: 50) / 100f
    }

    private val lightState = androidx.compose.runtime.mutableFloatStateOf(0.5f)
    private val darkState = androidx.compose.runtime.mutableFloatStateOf(0.5f)
    var light: Float
        get() = lightState.floatValue
        set(v) { lightState.floatValue = v }
    var dark: Float
        get() = darkState.floatValue
        set(v) { darkState.floatValue = v }
}

/** [base] lighter (t under 0.5, up to near white) or darker (over 0.5, down to near black), its hue and saturation kept. */
@Suppress("UNUSED_PARAMETER")
internal fun shaded(base: Color, lightPage: Boolean, t: Float): Color {
    val (h, s, l) = toHsl(base)
    val v = t.coerceIn(0f, 1f)
    val to = if (v < 0.5f) l + (0.95f - l) * (0.5f - v) * 2f else l - (l - 0.08f) * (v - 0.5f) * 2f
    return Color.hsl(h, s, to.coerceIn(0f, 1f))
}

private fun toHsl(c: Color): Triple<Float, Float, Float> {
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f))
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } * 60f
    return Triple(h, s, l)
}

/** Hue [i]'s thin lines (outlines, the writing box, rings): its deep shade in both themes, so a line stays seen. */
internal fun chatHueLine(i: Int): Color? = if (i < 0) null else ChatHuesDeep[i % ChatHuesDeep.size]

/** [content] with a chat's colours ([mine], [theirs]: chat hue indexes, -1 the default), in this theme's shade. */
@Composable
internal fun ChatColors(mine: Int, theirs: Int, content: @Composable () -> Unit) {
    if (mine < 0 && theirs < 0) return content()
    val base = LocalLibraryPalette.current
    val palette = androidx.compose.runtime.remember(base, mine, theirs, ChatShade.light, ChatShade.dark) {
        val light = base.Background.luminance() > 0.5f
        base.withChatColors(chatHue(mine, light), chatHue(theirs, light), chatHueLine(mine), chatHueLine(theirs))
    }
    CompositionLocalProvider(LocalLibraryPalette provides palette, content = content)
}

/** [content] with their colour as hue [i] (a group member's), in this theme's shade; mine as it is. */
@Composable
internal fun TheirHue(i: Int, content: @Composable () -> Unit) {
    val base = LocalLibraryPalette.current
    val palette = androidx.compose.runtime.remember(base, i, ChatShade.light, ChatShade.dark) {
        val light = base.Background.luminance() > 0.5f
        base.withChatColors(null, chatHue(i, light), null, chatHueLine(i))
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

