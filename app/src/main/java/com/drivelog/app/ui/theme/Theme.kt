package com.drivelog.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Day = lightColorScheme(
    primary = Color(0xFF0E0E0F), onPrimary = Color.White,
    background = Color.White, onBackground = Color(0xFF0E0E0F),
    surface = Color.White, onSurface = Color(0xFF0E0E0F),
    surfaceVariant = Color(0xFFF1F1F2), onSurfaceVariant = Color(0xFF66666B),
    outline = Color(0xFFD4D4D6), error = Color(0xFFB3261E),
)

private val Night = darkColorScheme(
    primary = Color.White, onPrimary = Color(0xFF0E0E0F),
    background = Color(0xFF0E0E0F), onBackground = Color.White,
    surface = Color(0xFF0E0E0F), onSurface = Color.White,
    surfaceVariant = Color(0xFF1F1F21), onSurfaceVariant = Color(0xFFB0B0B5),
    outline = Color(0xFF3A3A3D), error = Color(0xFFFF8A80),
)

/** Monochrome per the mockups. Day and night follow the vehicle's system setting. */
@Composable
fun DrivelogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Night else Day, content = content)
}
