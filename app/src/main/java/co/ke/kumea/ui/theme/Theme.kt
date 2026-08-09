package co.ke.kumea.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Build-3 Soil Paper scheme. One palette, always: the notebook is paper in
 * daylight — the spec defines no dark variant, so dark mode and dynamic color
 * are retired rather than shipping an off-spec guess. Revisit post-Build-3 if
 * a dark spec lands.
 */
private val SoilPaperColors = lightColorScheme(
    primary = LeafGreen,
    onPrimary = Color.White,
    primaryContainer = LeafWash,
    onPrimaryContainer = DeepLeaf,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = LeafWash,
    onSecondaryContainer = DeepLeaf,
    tertiary = Clay,
    onTertiary = Color.White,
    tertiaryContainer = ClayWash,
    onTertiaryContainer = Ink,
    background = SoilPaper,
    onBackground = Ink,
    surface = SoilPaper,
    onSurface = Ink,
    surfaceVariant = CardPaper,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = CardPaper,
    surfaceContainerLow = CardPaper,
    surfaceContainer = CardPaper,
    surfaceContainerHigh = CardPaper,
    surfaceContainerHighest = CardPaper,
    outline = ClayLine,
    outlineVariant = ClayLine,
    error = LossRed,
    onError = Color.White,
)

// Geometry: cards & buttons 12dp (broad, slightly squared reads sturdy —
// full-pill reads fintech), chips 8dp.
private val SoilPaperShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

/** 12dp button shape — M3 buttons default to full-pill, which the spec retires. */
val KumeaButtonShape = RoundedCornerShape(12.dp)

@Composable
fun KumeaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SoilPaperColors,
        typography = KumeaTypography,
        shapes = SoilPaperShapes,
        content = content,
    )
}
