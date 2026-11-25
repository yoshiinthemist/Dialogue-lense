package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DialogueLensDarkColorScheme = darkColorScheme(
    primary = SoftJade,
    onPrimary = CinemaObsidian,
    primaryContainer = SoftJadeContainer,
    onPrimaryContainer = InkPrimary,
    secondary = AmethystHeal,
    onSecondary = CinemaObsidian,
    secondaryContainer = AmethystContainer,
    onSecondaryContainer = InkPrimary,
    tertiary = WarmAmber,
    onTertiary = CinemaObsidian,
    tertiaryContainer = WarmAmberContainer,
    onTertiaryContainer = InkPrimary,
    background = CinemaObsidian,
    onBackground = InkPrimary,
    surface = CinemaSurface,
    onSurface = InkPrimary,
    surfaceVariant = CinemaGraphite,
    onSurfaceVariant = InkSecondary,
    surfaceContainerHigh = CinemaSurfaceHigh,
    outline = CinemaBorder,
    outlineVariant = CinemaBorder.copy(alpha = 0.55f),
    error = LensRose,
    onError = CinemaObsidian,
)

private val DialogueLensShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun DialogueLensTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DialogueLensDarkColorScheme,
        typography = DialogueLensTypography,
        shapes = DialogueLensShapes,
        content = content,
    )
}
