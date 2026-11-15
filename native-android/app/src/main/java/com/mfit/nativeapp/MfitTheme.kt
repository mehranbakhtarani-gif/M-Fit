package com.mfit.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * M-Fit visual language, ported from the existing web app:
 * soft mint/ice-blue atmosphere, translucent white surfaces and generous rounded corners.
 */
internal object MfitPalette {
    val ink = Color(0xFF183B3B)
    val muted = Color(0xFF77918D)
    val mint = Color(0xFF8EE2C6)
    val blue = Color(0xFFB9E8F5)
    val line = Color(0xD9FFFFFF)
    val glass = Color(0xBFFFFFFF)
    val navGlass = Color(0xEFFFFFFF)
    val green = Color(0xFF62BFA2)
    val pageBrush = Brush.linearGradient(
        colors = listOf(Color(0xFFF5FCF8), Color(0xFFEDF8FB), Color(0xFFF8FBF9))
    )
    val pageRadialBrush = Brush.radialGradient(
        colors = listOf(Color(0xFFD7F5E9), Color.Transparent),
        radius = 900f
    )
}

private val MfitLightColors = lightColorScheme(
    primary = Color(0xFF62BFA2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7F2E2),
    onPrimaryContainer = Color(0xFF28594E),
    secondary = Color(0xFF83CFE4),
    onSecondary = Color(0xFF183B3B),
    secondaryContainer = Color(0xFFD9F2FA),
    onSecondaryContainer = Color(0xFF28594E),
    background = Color(0xFFF2FAF7),
    onBackground = Color(0xFF183B3B),
    surface = Color(0xF2FFFFFF),
    onSurface = Color(0xFF183B3B),
    surfaceVariant = Color(0xFFE8F5F0),
    onSurfaceVariant = Color(0xFF77918D),
    outline = Color(0xFFDCEEE8)
)

@Composable
internal fun MfitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MfitLightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(15.dp),
            medium = RoundedCornerShape(23.dp),
            large = RoundedCornerShape(30.dp)
        ),
        content = content
    )
}
