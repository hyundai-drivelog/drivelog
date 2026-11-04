package com.drivelog.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Values from the Pleos Colors page: the basic_* grayscale, which swaps between light and dark, and the
// button_filled_enabled token (basic_600). Surface variant is the 5% ink tint of button_basic_enabled.
private val Day = lightColorScheme(
    primary = Color(0xFF44464E), onPrimary = Color.White,
    background = Color.White, onBackground = Color(0xFF131417),
    surface = Color.White, onSurface = Color(0xFF131417),
    surfaceVariant = Color(0xFFF3F3F3), onSurfaceVariant = Color(0xFF686A72),
    outline = Color(0xFFE0E1E6), error = Color(0xFFB3261E),
)

private val Night = darkColorScheme(
    primary = Color(0xFFE0E1E6), onPrimary = Color(0xFF131417),
    background = Color(0xFF131417), onBackground = Color.White,
    surface = Color(0xFF131417), onSurface = Color.White,
    surfaceVariant = Color(0xFF1F2023), onSurfaceVariant = Color(0xFFC7C9CE),
    outline = Color(0xFF44464E), error = Color(0xFFFF8A80),
)

/** Monochrome per the mockups, on Pleos's own grays. Day and night follow the vehicle's system setting. */
@Composable
fun DrivelogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Night else Day, content = content)
}
