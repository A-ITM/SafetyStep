package io.github.aitm.pleosapp.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Adapted from the existing Figma right panel. All actions share automotive hit areas. */
object SafetyStepTokens {
    val touch = 80.dp
    val primaryHeight = touch
    val secondaryHeight = touch
    val gutter = 48.dp
    val compactGutter = 28.dp
    val gap = 24.dp
    val smallGap = 12.dp
    val radius = 20.dp
    val badgeRadius = 8.dp
    val dialogRadius = 28.dp
    val cardPadding = 24.dp
    val brandSymbol = 32.dp
    val homeSymbol = 64.dp
    val warningSymbol = 48.dp
    val hero = TextStyle(fontSize = 42.sp, lineHeight = 54.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
    val distance = TextStyle(fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
    val body = TextStyle(fontSize = 26.sp, lineHeight = 36.sp)
    val action = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontSize = 24.sp, lineHeight = 32.sp)
    val caption = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
    val typography = Typography(displaySmall = hero, headlineSmall = action, titleLarge = action,
        bodyLarge = body, bodyMedium = label, bodySmall = caption, labelLarge = label)
}
