package io.github.drlacheheb.kufus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalKufusPalette = staticCompositionLocalOf { DarkKufusPalette }

object KufusTheme {
    val palette: KufusPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalKufusPalette.current
}

private val DarkColorScheme =
    darkColorScheme(
        primary = KufusPrimary,
        onPrimary = KufusOnPrimary,
        primaryContainer = KufusPrimaryContainer,
        onPrimaryContainer = KufusOnPrimaryContainer,
        inversePrimary = KufusInversePrimary,
        secondary = KufusSecondary,
        onSecondary = KufusOnSecondary,
        secondaryContainer = KufusSecondaryContainer,
        tertiary = KufusTertiary,
        onTertiary = KufusOnTertiary,
        tertiaryContainer = KufusTertiaryContainer,
        background = DarkBackground,
        onBackground = DarkOnSurface,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceDim = DarkSurfaceDim,
        surfaceBright = DarkSurfaceBright,
        surfaceContainerLowest = DarkSurfaceContainerLowest,
        surfaceContainerLow = DarkSurfaceContainerLow,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceContainerHighest,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        inverseSurface = DarkInverseSurface,
        outline = DarkOutlineMuted,
        outlineVariant = DarkOutlineVariant,
        error = KufusError,
        onError = KufusOnError,
        errorContainer = KufusErrorContainer,
        onErrorContainer = KufusOnErrorContainer,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = LightPrimary,
        onPrimary = LightOnPrimary,
        primaryContainer = LightPrimaryContainer,
        onPrimaryContainer = LightOnPrimaryContainer,
        inversePrimary = KufusPrimary,
        secondary = LightSecondary,
        onSecondary = LightOnSecondary,
        secondaryContainer = LightSecondaryContainer,
        tertiary = LightTertiary,
        onTertiary = LightOnTertiary,
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceDim = LightSurfaceDim,
        surfaceBright = LightSurfaceBright,
        surfaceContainerLowest = LightSurfaceContainerLowest,
        surfaceContainerLow = LightSurfaceContainerLow,
        surfaceContainer = LightSurfaceContainer,
        surfaceContainerHigh = LightSurfaceContainerHigh,
        surfaceContainerHighest = LightSurfaceContainerHighest,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        error = LightError,
        onError = LightOnError,
        errorContainer = LightErrorContainer,
        onErrorContainer = LightOnErrorContainer,
    )

@Composable
fun KufusTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = kufusPalette(darkTheme)
    CompositionLocalProvider(LocalKufusPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = KufusTypography,
            content = content,
        )
    }
}
