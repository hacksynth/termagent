package com.termagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import com.termagent.data.ThemeMode
import com.termagent.data.ThemeStyle

/**
 * The chat-shell palette.
 *
 * Miuix supplies the control look (buttons, switches, dialogs); these extra roles are what
 * the ChatGPT-desktop-style shell needs — a darker canvas than Miuix's own `surface`, a
 * distinct sidebar, message bubbles and the GitHub-dark terminal card.
 */
@Immutable
data class TermAgentPalette(
    val background: Color,
    val sidebar: Color,
    val sidebarSelected: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val accent: Color,
    val onAccent: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    val divider: Color,
    val terminalBackground: Color,
    val terminalHeader: Color,
    val terminalBorder: Color,
    val terminalText: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
)

private val DarkPalette = TermAgentPalette(
    background = Color(0xFF0B0F14),
    sidebar = Color(0xFF11161C),
    sidebarSelected = Color(0xFF1C232B),
    surface = Color(0xFF161C23),
    surfaceElevated = Color(0xFF1E252E),
    accent = Color(0xFF4C9AFF),
    onAccent = Color(0xFFFFFFFF),
    userBubble = Color(0xFF222B36),
    onUserBubble = Color(0xFFE6EDF3),
    textPrimary = Color(0xFFE6EDF3),
    textSecondary = Color(0xFF9BA7B4),
    textFaint = Color(0xFF6B7684),
    divider = Color(0xFF222A33),
    terminalBackground = Color(0xFF0D1117),
    terminalHeader = Color(0xFF161B22),
    terminalBorder = Color(0xFF30363D),
    terminalText = Color(0xFFC9D1D9),
    success = Color(0xFF3FB950),
    danger = Color(0xFFF85149),
    warning = Color(0xFFD29922),
)

private val LightPalette = TermAgentPalette(
    background = Color(0xFFF7F8FA),
    sidebar = Color(0xFFEFEFEF),
    sidebarSelected = Color(0xFFE0E3E8),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    accent = Color(0xFF1A73E8),
    onAccent = Color(0xFFFFFFFF),
    userBubble = Color(0xFFE8EAED),
    onUserBubble = Color(0xFF1F1F1F),
    textPrimary = Color(0xFF1F1F1F),
    textSecondary = Color(0xFF5F6368),
    textFaint = Color(0xFF80868B),
    divider = Color(0xFFDDDFE3),
    terminalBackground = Color(0xFF1E1E1E),
    terminalHeader = Color(0xFF2D2D2D),
    terminalBorder = Color(0xFF3C3C3C),
    terminalText = Color(0xFFD4D4D4),
    success = Color(0xFF1A7F37),
    danger = Color(0xFFCF222E),
    warning = Color(0xFF9A6700),
)

// ---------------------------------------------------------------------- DeepSeek

/**
 * DeepSeek's brand blue (`#4D6BFE`, the "whale" accent). In dark mode the accent is lifted
 * to `#6B84FF` so it keeps its punch against the ink canvas without glowing.
 */
private val DeepSeekBrand = Color(0xFF4D6BFE)
private val DeepSeekBrandDark = Color(0xFF6B84FF)

/**
 * DeepSeek light: white canvas, a barely-there blue-grey chrome, and the brand blue used
 * for the user bubble tint — the look of the DeepSeek web/desktop chat in light mode.
 */
private val DeepSeekLightPalette = TermAgentPalette(
    background = Color(0xFFF7F8FC),
    sidebar = Color(0xFFEFF1F7),
    sidebarSelected = Color(0xFFDCE3F8),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF4F6FB),
    accent = DeepSeekBrand,
    onAccent = Color(0xFFFFFFFF),
    userBubble = Color(0xFFE7EDFF),
    onUserBubble = Color(0xFF14171F),
    textPrimary = Color(0xFF14171F),
    textSecondary = Color(0xFF5A6070),
    textFaint = Color(0xFF878D9B),
    divider = Color(0xFFE2E5EF),
    terminalBackground = Color(0xFF12151B),
    terminalHeader = Color(0xFF1C2029),
    terminalBorder = Color(0xFF2C323D),
    terminalText = Color(0xFFD2D8E2),
    success = Color(0xFF17803D),
    danger = Color(0xFFD93036),
    warning = Color(0xFF9A6700),
)

/**
 * DeepSeek dark: a blue-tinged ink canvas (`#0E1116`) instead of pure black, which is what
 * the official dark theme uses — brand blue then reads as a highlight, not a glare.
 */
private val DeepSeekDarkPalette = TermAgentPalette(
    background = Color(0xFF0E1116),
    sidebar = Color(0xFF131820),
    sidebarSelected = Color(0xFF1F2733),
    surface = Color(0xFF171C24),
    surfaceElevated = Color(0xFF212836),
    accent = DeepSeekBrandDark,
    onAccent = Color(0xFFFFFFFF),
    userBubble = Color(0xFF23304F),
    onUserBubble = Color(0xFFE3E9F5),
    textPrimary = Color(0xFFE7EAF0),
    textSecondary = Color(0xFF9AA4B6),
    textFaint = Color(0xFF6B7688),
    divider = Color(0xFF232A36),
    terminalBackground = Color(0xFF0B0F14),
    terminalHeader = Color(0xFF141A22),
    terminalBorder = Color(0xFF2A3240),
    terminalText = Color(0xFFC9D3E0),
    success = Color(0xFF3FB950),
    danger = Color(0xFFF2555A),
    warning = Color(0xFFD9A83A),
)

/**
 * Recolour the Miuix controls (switch, text button, dialog chrome) to the brand.
 *
 * [Colors] is a 53-field immutable data class, and Miuix already exposes the extension
 * point we want: `ThemeController(lightColors = …, darkColors = …)` is consulted for the
 * `Light`/`Dark`/`System` modes. So instead of restating every role we `copy` only the ones
 * that carry the brand, and everything else keeps Miuix's own tuned value.
 *
 * NOTE: the `Monet*` modes deliberately ignore these — Miuix derives them from the wallpaper
 * seed, so a brand palette genuinely cannot apply there.
 */
private fun Colors.rebranded(p: TermAgentPalette): Colors = copy(
    primary = p.accent,
    onPrimary = p.onAccent,
    primaryVariant = p.accent,
    onPrimaryVariant = p.onAccent,
    primaryContainer = p.userBubble,
    onPrimaryContainer = p.onUserBubble,
    error = p.danger,
    onError = p.onAccent,
    background = p.background,
    onBackground = p.textPrimary,
    onBackgroundVariant = p.textSecondary,
    surface = p.surface,
    onSurface = p.textPrimary,
    surfaceVariant = p.surfaceElevated,
    onSurfaceSecondary = p.textSecondary,
    onSurfaceVariantSummary = p.textSecondary,
    onSurfaceVariantActions = p.textPrimary,
    onSurfaceContainer = p.textPrimary,
    onSurfaceContainerVariant = p.textSecondary,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.surfaceElevated,
    onSurfaceContainerHigh = p.textPrimary,
    surfaceContainerHighest = p.sidebarSelected,
    onSurfaceContainerHighest = p.textPrimary,
    outline = p.divider,
    dividerLine = p.divider,
)

private val DeepSeekLightScheme: Colors = lightColorScheme().rebranded(DeepSeekLightPalette)
private val DeepSeekDarkScheme: Colors = darkColorScheme().rebranded(DeepSeekDarkPalette)

/**
 * The shell palette a [ThemeStyle] paints with, per light/dark.
 *
 * Public because the settings screen draws a preview swatch per style — and it must show the
 * style's *own* colours, not the ones currently installed in the composition local.
 */
fun paletteFor(style: ThemeStyle, dark: Boolean): TermAgentPalette = when (style) {
    ThemeStyle.Default -> if (dark) DarkPalette else LightPalette
    ThemeStyle.DeepSeek -> if (dark) DeepSeekDarkPalette else DeepSeekLightPalette
}

/** Monospace face used for terminal cards and inline code. */
val TerminalFontFamily: FontFamily = FontFamily.Monospace

val TerminalTextSize = 12.sp
val TerminalLineHeight = 18.sp

/** Standard padding steps, so the shell stays visually consistent. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

val LocalTermAgentPalette = compositionLocalOf { DarkPalette }

/** Accessor: `TermAgentTheme.colors.accent`. */
object TermAgentTheme {
    val colors: TermAgentPalette
        @Composable get() = LocalTermAgentPalette.current
}

/** Maps our persisted [ThemeMode] onto Miuix's [ColorSchemeMode]. */
private fun ThemeMode.toColorSchemeMode(): ColorSchemeMode = when (this) {
    ThemeMode.Dark -> ColorSchemeMode.Dark
    ThemeMode.Light -> ColorSchemeMode.Light
    ThemeMode.System -> ColorSchemeMode.System
    ThemeMode.MonetDark -> ColorSchemeMode.MonetDark
    ThemeMode.MonetLight -> ColorSchemeMode.MonetLight
}

/** Whether a mode resolves to a dark canvas (Monet variants follow their suffix). */
private fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.Dark, ThemeMode.MonetDark -> true
    ThemeMode.Light, ThemeMode.MonetLight -> false
    ThemeMode.System -> systemDark
}

/**
 * Root theme: Miuix for the controls, [TermAgentPalette] for the shell.
 *
 * [mode] picks light/dark (or wallpaper), [style] picks the brand. They are independent, so
 * "DeepSeek · follow system" works without enumerating every combination — and a future
 * brand is one enum entry plus one palette pair.
 *
 * `ThemeController.colorSchemeMode` is a read-only `mutableStateOf` property (there is no
 * setter), so the controller is **recreated when [mode] or [style] changes** rather than
 * mutated — hence the `remember(mode, style)` key.
 */
@Composable
fun TermAgentTheme(
    mode: ThemeMode = ThemeMode.Dark,
    style: ThemeStyle = ThemeStyle.Default,
    content: @Composable () -> Unit,
) {
    val controller = remember(mode, style) {
        if (style == ThemeStyle.DeepSeek) {
            ThemeController(
                colorSchemeMode = mode.toColorSchemeMode(),
                lightColors = DeepSeekLightScheme,
                darkColors = DeepSeekDarkScheme,
            )
        } else {
            ThemeController(colorSchemeMode = mode.toColorSchemeMode())
        }
    }

    val palette = paletteFor(style, mode.isDark(isSystemInDarkTheme()))

    MiuixTheme(controller = controller) {
        CompositionLocalProvider(LocalTermAgentPalette provides palette, content = content)
    }
}

/**
 * True when [ThemeMode] currently renders a dark canvas — system-aware, so a style preview
 * swatch can match what the user would actually get.
 */
@Composable
fun ThemeMode.resolvesDark(): Boolean = isDark(isSystemInDarkTheme())

/** Small helper for terminal-card status colours. */
fun TermAgentPalette.statusColor(succeeded: Boolean): Color = if (succeeded) success else danger

/** Wordmark styling shared by the title bar and the drawer header. */
val WordmarkWeight = FontWeight.SemiBold
