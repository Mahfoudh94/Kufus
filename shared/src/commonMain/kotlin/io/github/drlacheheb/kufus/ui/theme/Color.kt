package io.github.drlacheheb.kufus.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

val CanvasBackground = Color(0xFF09090B)
val ModalSurface = Color(0xFF121215)
val SidebarBackground = Color(0xFF18181B)
val InputBackground = Color(0xFF09090B)
val FooterBackground = Color(0xFF18181B)
val DarkBorder = Color(0xFF27272A)
val DarkBorderHover = Color(0xFF3F3F46)

val DarkBackground = Color(0xFF131316)
val DarkSurface = Color(0xFF131316)
val DarkSurfaceDim = Color(0xFF131316)
val DarkSurfaceContainerLowest = Color(0xFF0E0E11)
val DarkSurfaceContainerLow = Color(0xFF1B1B1E)
val DarkSurfaceContainer = Color(0xFF1F1F22)
val DarkSurfaceContainerHigh = Color(0xFF2A2A2D)
val DarkSurfaceContainerHighest = Color(0xFF353437)
val DarkSurfaceVariant = Color(0xFF353437)
val DarkSurfaceBright = Color(0xFF39393C)

val DarkOutline = Color(0xFF908FA0)
val DarkOutlineMuted = Color(0xFF908FA0)
val DarkOutlineVariant = Color(0xFF464554)
val DarkOutlineHover = Color(0xFF5A5969)

val KufusPrimary = Color(0xFFC0C1FF)
val KufusOnPrimary = Color(0xFF1000A9)
val KufusPrimaryContainer = Color(0xFF8083FF)
val KufusOnPrimaryContainer = Color(0xFF0D0096)
val KufusPrimaryFixed = Color(0xFFE1E0FF)
val KufusPrimaryFixedDim = Color(0xFFC0C1FF)
val KufusInversePrimary = Color(0xFF494BD6)

val KufusSecondary = Color(0xFF4EDEA3)
val KufusOnSecondary = Color(0xFF003824)
val KufusSecondaryContainer = Color(0xFF00A572)

val KufusTertiary = Color(0xFFFFB95F)
val KufusOnTertiary = Color(0xFF472A00)
val KufusTertiaryContainer = Color(0xFFCA8100)

val KufusError = Color(0xFFFFB4AB)
val KufusOnError = Color(0xFF690005)
val KufusErrorContainer = Color(0xFF93000A)
val KufusOnErrorContainer = Color(0xFFFFDAD6)
val KufusDestructiveAccent = Color(0xFFF7768E)

val DarkOnSurface = Color(0xFFE4E1E5)
val DarkOnSurfaceVariant = Color(0xFFC7C4D7)
val DarkInverseSurface = Color(0xFFE4E1E5)

val LightBackground = Color(0xFFF7F7FA)
val LightSurface = Color(0xFFF7F7FA)
val LightSurfaceDim = Color(0xFFEEEEF3)
val LightSurfaceContainerLowest = Color(0xFFEEEEF3)
val LightSurfaceContainerLow = Color(0xFFF0F0F5)
val LightSurfaceContainer = Color(0xFFFFFFFF)
val LightSurfaceContainerHigh = Color(0xFFE4E4EA)
val LightSurfaceContainerHighest = Color(0xFFDDDDE5)
val LightSurfaceVariant = Color(0xFFE4E4EA)
val LightSurfaceBright = Color(0xFFFFFFFF)
val LightInputBackground = Color(0xFFFFFFFF)
val LightBorder = Color(0xFFD4D4DC)
val LightBorderHover = Color(0xFFB4B4C2)
val LightOutline = Color(0xFF71717A)
val LightOutlineVariant = Color(0xFFD4D4DC)
val LightOnSurface = Color(0xFF18181B)
val LightOnSurfaceVariant = Color(0xFF52525B)
val LightPrimary = Color(0xFF494BD6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE1E0FF)
val LightOnPrimaryContainer = Color(0xFF1000A9)
val LightSecondary = Color(0xFF00875A)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFF00A572)
val LightTertiary = Color(0xFFB45309)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)
val LightDestructiveAccent = Color(0xFFDC2626)

@Immutable
data class KufusPalette(
    val isDark: Boolean,
    val background: Color,
    val surfaceDim: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceBright: Color,
    val inputBackground: Color,
    val border: Color,
    val borderHover: Color,
    val outline: Color,
    val outlineVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val destructiveAccent: Color,
    val contextMenuBorder: Color,
    val windowControlHover: Color,
)

val DarkKufusPalette =
    KufusPalette(
        isDark = true,
        background = DarkBackground,
        surfaceDim = DarkSurfaceDim,
        surfaceContainerLowest = DarkSurfaceContainerLowest,
        surfaceContainerLow = DarkSurfaceContainerLow,
        surfaceContainer = DarkSurfaceContainer,
        surfaceBright = DarkSurfaceBright,
        inputBackground = InputBackground,
        border = DarkBorder,
        borderHover = DarkBorderHover,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        onSurface = DarkOnSurface,
        onSurfaceVariant = DarkOnSurfaceVariant,
        primary = KufusPrimary,
        onPrimary = KufusOnPrimary,
        secondary = KufusSecondary,
        secondaryContainer = KufusSecondaryContainer,
        onSecondaryContainer = Color.White,
        tertiary = KufusTertiary,
        error = KufusError,
        onError = KufusOnError,
        errorContainer = KufusErrorContainer,
        onErrorContainer = KufusOnErrorContainer,
        destructiveAccent = KufusDestructiveAccent,
        contextMenuBorder = Color(0xFF2E2E35),
        windowControlHover = DarkSurfaceVariant,
    )

val LightKufusPalette =
    KufusPalette(
        isDark = false,
        background = LightBackground,
        surfaceDim = LightSurfaceDim,
        surfaceContainerLowest = LightSurfaceContainerLowest,
        surfaceContainerLow = LightSurfaceContainerLow,
        surfaceContainer = LightSurfaceContainer,
        surfaceBright = LightSurfaceContainerHigh,
        inputBackground = LightInputBackground,
        border = LightBorder,
        borderHover = LightBorderHover,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        onSurface = LightOnSurface,
        onSurfaceVariant = LightOnSurfaceVariant,
        primary = LightPrimary,
        onPrimary = LightOnPrimary,
        secondary = LightSecondary,
        secondaryContainer = LightSecondaryContainer,
        onSecondaryContainer = Color.White,
        tertiary = LightTertiary,
        error = LightError,
        onError = LightOnError,
        errorContainer = LightErrorContainer,
        onErrorContainer = LightOnErrorContainer,
        destructiveAccent = LightDestructiveAccent,
        contextMenuBorder = LightBorder,
        windowControlHover = LightSurfaceContainerHigh,
    )

fun kufusPalette(isDark: Boolean): KufusPalette = if (isDark) DarkKufusPalette else LightKufusPalette
