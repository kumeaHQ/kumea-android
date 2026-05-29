package co.ke.kumea.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = KumeaGreen,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = KumeaGreenLight,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = KumeaGreenDark,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    error = Error,
)

private val DarkColors = darkColorScheme(
    primary = KumeaGreenLight,
    onPrimary = Charcoal,
    primaryContainer = KumeaGreenDark,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = KumeaGreen,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    error = Error,
)

@Composable
fun KumeaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You dynamic color on Android 12+. Falls back to brand palette otherwise.
    // Off by default in Sprint 0 so the brand green is visible; can be flipped later.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KumeaTypography,
        content = content,
    )
}
