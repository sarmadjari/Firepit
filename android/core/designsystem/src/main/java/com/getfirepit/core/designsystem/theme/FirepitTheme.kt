package com.getfirepit.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LightColorScheme = lightColorScheme(
    primary = EmberPalette.PrimaryLight,
    onPrimary = EmberPalette.OnPrimaryLight,
    background = EmberPalette.SurfaceLight,
    onBackground = EmberLightColors.textPrimary,
    surface = EmberPalette.SurfaceLight,
    onSurface = EmberLightColors.textPrimary,
    surfaceContainer = EmberPalette.Surface2Light,
    surfaceContainerHigh = EmberPalette.Surface2Light,
    onSurfaceVariant = EmberLightColors.textSecondary,
    outline = EmberPalette.OutlineLight,
    outlineVariant = EmberPalette.OutlineLight,
    error = EmberLightColors.danger,
)

private val DarkColorScheme = darkColorScheme(
    primary = EmberPalette.PrimaryDark,
    onPrimary = EmberPalette.OnPrimaryDark,
    background = EmberPalette.SurfaceDark,
    onBackground = EmberDarkColors.textPrimary,
    surface = EmberPalette.SurfaceDark,
    onSurface = EmberDarkColors.textPrimary,
    surfaceContainer = EmberPalette.Surface2Dark,
    surfaceContainerHigh = EmberPalette.Surface2Dark,
    onSurfaceVariant = EmberDarkColors.textSecondary,
    outline = EmberPalette.OutlineDark,
    outlineVariant = EmberPalette.OutlineDark,
    error = EmberDarkColors.danger,
)

val LocalFirepitColors = staticCompositionLocalOf { EmberLightColors }

/**
 * Deliberately no Material You dynamic colour: Ember's hues carry meaning
 * (live green, warn gold, danger crimson, infra slate) and recolouring them
 * from the wallpaper would break both the brand and the status language.
 */
@Composable
fun FirepitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val firepitColors = if (darkTheme) EmberDarkColors else EmberLightColors

    CompositionLocalProvider(LocalFirepitColors provides firepitColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = FirepitTypography,
            shapes = FirepitShapes,
            content = content,
        )
    }
}

object FirepitTheme {
    val colors: FirepitColors
        @Composable @ReadOnlyComposable get() = LocalFirepitColors.current
}
