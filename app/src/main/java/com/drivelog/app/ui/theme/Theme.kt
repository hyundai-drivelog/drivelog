package com.drivelog.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DevelopmentColors = lightColorScheme(
    primary = Color(0xFF245FE5),
    onPrimary = Color.White,
    secondary = Color(0xFF087F78),
    secondaryContainer = Color(0xFFDDF6EF),
    onSecondaryContainer = Color(0xFF075F59),
    background = Color(0xFFF5F8FC),
    onBackground = Color(0xFF192B44),
    surface = Color.White,
    onSurface = Color(0xFF192B44),
    onSurfaceVariant = Color(0xFF52647B),
)

@Composable
fun DrivelogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DevelopmentColors, content = content)
}
