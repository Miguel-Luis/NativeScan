package com.lordprogrammer.nativescan2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = md_theme_primary,
    onPrimary = md_theme_onPrimary,
    primaryContainer = md_theme_primaryContainer,
    onPrimaryContainer = md_theme_onPrimaryContainer,
    surface = md_theme_surface,
    background = md_theme_surface,
    surfaceVariant = md_theme_surfaceVariant,
    error = md_theme_error,
    onSurfaceVariant = md_theme_onSurfaceVariant,
    outline = md_theme_outline,
    secondaryContainer = md_theme_secondaryContainer,
    surfaceContainerLow = md_theme_surfaceContainerLow,
    surfaceContainerLowest = md_theme_surfaceContainerLowest,
    tertiary = md_theme_tertiary,
    surfaceContainer = md_theme_surfaceContainer,
    errorContainer = md_theme_errorContainer,
    outlineVariant = md_theme_outlineVariant,
    onSurface = md_theme_onSurface,
)

private val DarkColors = darkColorScheme(
    primary = md_theme_primary_dark,
    onPrimary = md_theme_onPrimary_dark,
    primaryContainer = md_theme_primaryContainer_dark,
    onPrimaryContainer = md_theme_onPrimaryContainer_dark,
    surface = md_theme_surface_dark,
    background = md_theme_surface_dark,
    surfaceVariant = md_theme_surfaceVariant_dark,
    error = md_theme_error_dark,
)

@Composable
fun NativeScanTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = NativeScanTypography,
        shapes = NativeScanShapes,
        content = content,
    )
}
