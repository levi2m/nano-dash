package dev.nanodash.logger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Design tokens. One neutral graphite ramp carries every surface; one ember accent marks the primary action and the
 * one figure that matters; semantic colours only carry meaning (ok, wait, problem). The pairs below were checked for
 * contrast: text on its surface is at least 4.5:1 (primary text about 15:1), the accent on dark surfaces about 7:1.
 * Muted text is for meta on the page or card surface only, never on the raised surface (that pair is 4.3:1).
 */
@Immutable
class NanoColors(
    val bg: Color,
    val card: Color,
    val raised: Color,
    val hairline: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    /** Fill for selected and switched-on controls, with [onFill] on top. Light theme uses the darker ember: 5.88:1 against white. */
    val fill: Color,
    val onFill: Color,
    val danger: Color,
    val onDanger: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val isDark: Boolean,
)

val DarkColors = NanoColors(
    bg = Color(0xFF141415), card = Color(0xFF19191B), raised = Color(0xFF232325), hairline = Color(0x14EDEDED),
    text = Color(0xFFEDEDED), textSecondary = Color(0xFFA1A1A6), textMuted = Color(0xFF85858B),
    accent = Color(0xFFFF7A1A), onAccent = Color(0xFF141415), accentText = Color(0xFFFF7A1A),
    fill = Color(0xFFFF7A1A), onFill = Color(0xFF141415),
    danger = Color(0xFFC62E33), onDanger = Color(0xFFFFFFFF),
    ok = Color(0xFF4CC38A), warn = Color(0xFFE3B341), bad = Color(0xFFF2575C),
    isDark = true,
)

val LightColors = NanoColors(
    bg = Color(0xFFFAFAFA), card = Color(0xFFFFFFFF), raised = Color(0xFFF4F4F5), hairline = Color(0x14171717),
    text = Color(0xFF171717), textSecondary = Color(0xFF525252), textMuted = Color(0xFF737373),
    accent = Color(0xFFFF7A1A), onAccent = Color(0xFF141415), accentText = Color(0xFFB53A00),
    fill = Color(0xFFB53A00), onFill = Color(0xFFFFFFFF),
    danger = Color(0xFFC62E33), onDanger = Color(0xFFFFFFFF),
    ok = Color(0xFF15803D), warn = Color(0xFF8A6100), bad = Color(0xFFC62E33),
    isDark = false,
)

val LocalNanoColors = staticCompositionLocalOf { DarkColors }

/** Spacing steps on a 4 dp grid. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

object Radius {
    val small = 8.dp
    val control = 10.dp
    val card = 16.dp
}

/** Text styles: two weights (400 and 500), sentence case, tabular figures wherever numbers change. */
object NanoText {
    private const val TABULAR = "tnum"
    private val face = FontFamily.SansSerif

    /** The speed readout. */
    val hero = TextStyle(fontFamily = face, fontSize = 88.sp, lineHeight = 88.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1.5).sp, fontFeatureSettings = TABULAR)
    val figure = TextStyle(fontFamily = face, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR)
    val title = TextStyle(fontFamily = face, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val heading = TextStyle(fontFamily = face, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = face, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val bodyNumbers = body.copy(fontFeatureSettings = TABULAR)
    val label = TextStyle(fontFamily = face, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp)
    val button = TextStyle(fontFamily = face, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun NanoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text, surface = c.card, onSurface = c.text,
            surfaceVariant = c.raised, onSurfaceVariant = c.textSecondary, outline = c.textMuted, outlineVariant = c.hairline, error = c.bad,
        )
    } else {
        lightColorScheme(
            primary = c.accentText, onPrimary = c.card, background = c.bg, onBackground = c.text, surface = c.card, onSurface = c.text,
            surfaceVariant = c.raised, onSurfaceVariant = c.textSecondary, outline = c.textMuted, outlineVariant = c.hairline, error = c.bad,
        )
    }
    val type = Typography(
        headlineSmall = NanoText.title, titleLarge = NanoText.title, titleMedium = NanoText.heading, titleSmall = NanoText.heading,
        bodyLarge = NanoText.body, bodyMedium = NanoText.body, bodySmall = NanoText.label, labelLarge = NanoText.button,
        labelMedium = NanoText.label, labelSmall = NanoText.label,
    )
    val shapes = Shapes(
        small = androidx.compose.foundation.shape.RoundedCornerShape(Radius.small),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(Radius.control),
        large = androidx.compose.foundation.shape.RoundedCornerShape(Radius.card),
    )
    CompositionLocalProvider(LocalNanoColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
    }
}

val nanoColors: NanoColors
    @Composable get() = LocalNanoColors.current
