package com.taraang.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val TaraangColors = lightColorScheme(
    primary = Orange,
    onPrimary = White,
    primaryContainer = OrangeSoft,
    onPrimaryContainer = Graphite,
    secondary = Graphite,
    onSecondary = White,
    secondaryContainer = LightGray,
    onSecondaryContainer = Graphite,
    tertiary = Graphite2,
    onTertiary = White,
    background = White,
    onBackground = Graphite,
    surface = White,
    onSurface = Graphite,
    surfaceVariant = LightGray,
    onSurfaceVariant = Graphite2,
    surfaceContainerLowest = White,
    surfaceContainerLow = White,
    surfaceContainer = White,
    surfaceContainerHigh = White,
    surfaceContainerHighest = LightGray,
    outline = Divider,
    outlineVariant = Divider,
    error = WarningRed,
    onError = White,
    errorContainer = WarningRedSoft,
    onErrorContainer = WarningRed,
)

private val TaraangShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** 라이트 전용. 차량 화면에서 주황·흰색·흑연만 쓴다. */
@Composable
fun TaraangTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TaraangColors,
        typography = TaraangTypography,
        shapes = TaraangShapes,
        content = content,
    )
}
