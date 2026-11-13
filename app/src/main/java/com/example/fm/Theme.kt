package com.example.fm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF3A52A0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0DBFF), onPrimaryContainer = Color(0xFF304578),
    secondaryContainer = Color(0xFFDCE2F9), onSecondaryContainer = Color(0xFF404659),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1B21), onSurface = Color(0xFF1A1B21), onSurfaceVariant = Color(0xFF45464F),
    surfaceContainerLow = Color(0xFFF1F0F7), surfaceContainer = Color(0xFFF1F0F7),
    surfaceContainerHigh = Color(0xFFEEEDF4), surfaceContainerHighest = Color(0xFFE8E7EF),
    outline = Color(0xFF757780), outlineVariant = Color(0xFFC5C6D0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBFD0FF), onPrimary = Color(0xFF182E60),
    primaryContainer = Color(0xFF4A64AA), onPrimaryContainer = Color(0xFFE3E9FF),
    secondaryContainer = Color(0xFF4A5169), onSecondaryContainer = Color(0xFFE3E8FF),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF26272F), surface = Color(0xFF26272F),
    onBackground = Color(0xFFF4F3FA), onSurface = Color(0xFFF4F3FA), onSurfaceVariant = Color(0xFFDDDEEA),
    surfaceContainerLow = Color(0xFF343640), surfaceContainer = Color(0xFF343640),
    surfaceContainerHigh = Color(0xFF3E414C), surfaceContainerHighest = Color(0xFF4A4D59),
    outline = Color(0xFFAEAFBC), outlineVariant = Color(0xFF4F505B),
)

@Composable
fun FmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
