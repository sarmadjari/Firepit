package com.getfirepit.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val LightScheme = lightColorScheme(
    primary = EmberLight.primary,
    onPrimary = EmberLight.onPrimary,
    primaryContainer = EmberLight.bubbleOut,
    onPrimaryContainer = EmberLight.text,
    secondary = EmberLight.infra,
    onSecondary = EmberLight.onPrimary,
    secondaryContainer = EmberLight.bubbleOut,      // selected tab / chip indicator
    onSecondaryContainer = EmberLight.primary,
    surfaceVariant = EmberLight.surface2,
    tertiary = EmberLight.warn,
    error = EmberLight.danger,
    onError = EmberLight.onPrimary,
    background = EmberLight.surface,
    onBackground = EmberLight.text,
    surface = EmberLight.surface,
    onSurface = EmberLight.text,
    surfaceContainer = EmberLight.surface2,
    surfaceContainerLow = EmberLight.surface2,
    surfaceContainerHigh = EmberLight.surface2,
    onSurfaceVariant = EmberLight.text2,
    outline = EmberLight.outline,
    outlineVariant = EmberLight.outline,
)

private val DarkScheme = darkColorScheme(
    primary = EmberDark.primary,
    onPrimary = EmberDark.onPrimary,
    primaryContainer = EmberDark.bubbleOut,
    onPrimaryContainer = EmberDark.text,
    secondary = EmberDark.infra,
    onSecondary = EmberDark.surface,
    secondaryContainer = EmberDark.bubbleOut,
    onSecondaryContainer = EmberDark.primary,
    surfaceVariant = EmberDark.surface2,
    tertiary = EmberDark.warn,
    error = EmberDark.danger,
    onError = EmberDark.surface,
    background = EmberDark.surface,
    onBackground = EmberDark.text,
    surface = EmberDark.surface,
    onSurface = EmberDark.text,
    surfaceContainer = EmberDark.surface2,
    surfaceContainerLow = EmberDark.surface2,
    surfaceContainerHigh = EmberDark.surface2,
    onSurfaceVariant = EmberDark.text2,
    outline = EmberDark.outline,
    outlineVariant = EmberDark.outline,
)

private val LightExtras = FirepitColors(
    bubbleOut = EmberLight.bubbleOut, bubbleIn = EmberLight.bubbleIn, surface2 = EmberLight.surface2,
    text2 = EmberLight.text2, outline = EmberLight.outline, live = EmberLight.live, stale = EmberLight.stale,
    warn = EmberLight.warn, danger = EmberLight.danger, infra = EmberLight.infra, calloutWarn = EmberLight.calloutWarn,
    identity = identityPalette(0.42f),
)

private val DarkExtras = FirepitColors(
    bubbleOut = EmberDark.bubbleOut, bubbleIn = EmberDark.bubbleIn, surface2 = EmberDark.surface2,
    text2 = EmberDark.text2, outline = EmberDark.outline, live = EmberDark.live, stale = EmberDark.stale,
    warn = EmberDark.warn, danger = EmberDark.danger, infra = EmberDark.infra, calloutWarn = EmberDark.calloutWarn,
    identity = identityPalette(0.64f),
)

@Composable
fun FirepitTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFirepitColors provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = FirepitTypography,
            content = content,
        )
    }
}

object FirepitTheme {
    val colors: FirepitColors
        @Composable get() = LocalFirepitColors.current
}
