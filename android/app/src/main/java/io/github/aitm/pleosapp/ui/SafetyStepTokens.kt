package io.github.aitm.pleosapp.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Adapted from the existing Figma right panel. All actions share automotive hit areas. */
object SafetyStepTokens {
    val touch = 80.dp
    val primaryHeight = 112.dp
    val secondaryHeight = 104.dp
    val gutter = 48.dp
    val compactGutter = 28.dp
    val gap = 24.dp
    val smallGap = 12.dp
    val radius = 20.dp
    val hero = TextStyle(fontSize = 54.sp, lineHeight = 70.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
    val body = TextStyle(fontSize = 28.sp, lineHeight = 40.sp)
    val action = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontSize = 24.sp, lineHeight = 34.sp)
    val caption = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
    val typography = Typography(displaySmall = hero, headlineSmall = action, titleLarge = action,
        bodyLarge = body, bodyMedium = label, bodySmall = caption, labelLarge = label)
}
