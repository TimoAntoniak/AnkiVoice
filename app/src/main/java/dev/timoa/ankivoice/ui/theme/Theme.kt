package dev.timoa.ankivoice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val OrangePrimary = Color(0xFFE66A1A)
private val OrangeOnPrimary = Color(0xFFFFFFFF)
private val OrangeContainer = Color(0xFFFFE0CB)
private val OrangeOnContainer = Color(0xFF2B1405)

private val LightColors = lightColorScheme(
    primary = OrangePrimary,
    onPrimary = OrangeOnPrimary,
    primaryContainer = OrangeContainer,
    onPrimaryContainer = OrangeOnContainer,
    secondary = Color(0xFF7A5C4A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE8D9),
    onSecondaryContainer = Color(0xFF2D1A10),
    tertiary = Color(0xFF8A4B00),
    onTertiary = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF8EFE9),
    background = Color(0xFFFFFBF8),
    onBackground = Color(0xFF1F1B18),
    outline = Color(0xFF8A817A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB685),
    onPrimary = Color(0xFF4A2100),
    primaryContainer = Color(0xFF643200),
    onPrimaryContainer = Color(0xFFFFDDC4),
    secondary = Color(0xFFECC2A8),
    onSecondary = Color(0xFF452B1B),
    secondaryContainer = Color(0xFF5D412F),
    onSecondaryContainer = Color(0xFFFFDDC7),
    tertiary = Color(0xFFFFB868),
    onTertiary = Color(0xFF4B2800),
    surface = Color(0xFF141311),
    surfaceVariant = Color(0xFF2A2521),
    background = Color(0xFF141311),
    onBackground = Color(0xFFECE1DA),
    outline = Color(0xFF9F9187),
)

private val AppShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

private val AppTypography = Typography()

@Composable
fun AnkiVoiceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
