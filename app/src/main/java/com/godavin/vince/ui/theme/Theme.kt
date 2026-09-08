package com.godavin.vince.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Matches the existing VINCE HUD palette: teal (Vince), pink (Clara), purple (Davina)
val VinceTeal = Color(0xFF22D3EE)
val ClaraPink = Color(0xFFEC4899)
val DavinaPurple = Color(0xFFA855F7)

private val VinceDarkColors = darkColorScheme(
    primary = VinceTeal,
    secondary = ClaraPink,
    tertiary = DavinaPurple,
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
)

@Composable
fun VinceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VinceDarkColors,
        content = content
    )
}
