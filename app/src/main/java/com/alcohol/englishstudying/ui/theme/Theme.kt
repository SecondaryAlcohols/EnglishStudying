package com.alcohol.englishstudying.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = BlueGray40,
    onPrimary = Neutral99,
    primaryContainer = BlueGray80,
    onPrimaryContainer = Neutral10,
    secondary = Teal40,
    onSecondary = Neutral99,
    secondaryContainer = Teal80,
    onSecondaryContainer = Neutral10,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Neutral95,
    onSurfaceVariant = Neutral20,
    outline = Neutral90
)

private val DarkColors = darkColorScheme(
    primary = BlueGray80,
    onPrimary = Neutral10,
    primaryContainer = BlueGray40,
    onPrimaryContainer = Neutral95,
    secondary = Teal80,
    onSecondary = Neutral10,
    secondaryContainer = Teal40,
    onSecondaryContainer = Neutral95,
    background = Neutral10,
    onBackground = Neutral95,
    surface = Neutral10,
    onSurface = Neutral95,
    surfaceVariant = Neutral20,
    onSurfaceVariant = Neutral90,
    outline = Neutral20
)

/**
 * 外观不开放自定义（见计划里的范围界定），只跟随系统深浅色。
 */
@Composable
fun EnglishStudyingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
