package io.github.aitm.pleosapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import io.github.aitm.pleosapp.prototype.ThemePreference

data class SafetyColors(
    val background: Color, val panel: Color, val inset: Color, val line: Color,
    val text: Color, val secondary: Color, val blue: Color, val mint: Color,
    val amber: Color, val red: Color, val mapRoad: Color, val dark: Boolean,
)

private val Night = SafetyColors(
    Color(0xFF0B1222), Color(0xFF171F34), Color(0xFF232E45), Color(0xFF39445C),
    Color(0xFFFFFFFF), Color(0xFFB4BDCE), Color(0xFF75B7FF), Color(0xFF73D8A5),
    Color(0xFFFFCD80), Color(0xFFFFA8A3), Color(0xFF25344C), true,
)
private val Day = SafetyColors(
    Color(0xFFF0F4FA), Color.White, Color(0xFFEAF0F8), Color(0xFFCDD8E7),
    Color(0xFF14243D), Color(0xFF526680), Color(0xFF205AB6), Color(0xFF16705B),
    Color(0xFF895100), Color(0xFFAC292E), Color(0xFFCFDAE8), false,
)
val LocalSafetyColors = staticCompositionLocalOf { Day }

@Composable
fun SafetyStepTheme(preference: ThemePreference, content: @Composable () -> Unit) {
    val dark = when (preference) { ThemePreference.SYSTEM -> isSystemInDarkTheme(); ThemePreference.DARK -> true; ThemePreference.LIGHT -> false }
    val c = if (dark) Night else Day
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF91B8FF), onPrimary = Color(0xFF092857), primaryContainer = Color(0xFF214D91),
        background = c.background, surface = c.panel, onSurface = c.text, onBackground = c.text,
        secondary = c.mint, outline = c.line, surfaceVariant = c.inset, onSurfaceVariant = c.secondary,
        error = c.red, errorContainer = Color(0xFF58262E), onErrorContainer = Color(0xFFFFDAD7),
    ) else lightColorScheme(
        primary = Color(0xFF245FC1), onPrimary = Color.White, primaryContainer = Color(0xFFDCE8FF),
        background = c.background, surface = c.panel, onSurface = c.text, onBackground = c.text,
        secondary = c.mint, outline = c.line, surfaceVariant = c.inset, onSurfaceVariant = c.secondary,
        error = c.red, errorContainer = Color(0xFFFFE6E4), onErrorContainer = Color(0xFF8C1D26),
    )
    CompositionLocalProvider(LocalSafetyColors provides c) { MaterialTheme(colorScheme = scheme, typography = SafetyStepTokens.typography, content = content) }
}
