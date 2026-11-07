// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5D7C),
    onPrimary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE8F2),
    onSecondaryContainer = Color(0xFF17303F),
    background = Color(0xFFF5F6F8),
    surface = Color(0xFFF5F6F8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC4E0),
    onPrimary = Color(0xFF0E2433),
    secondaryContainer = Color(0xFF29404F),
    onSecondaryContainer = Color(0xFFDCE8F2),
    background = Color(0xFF15181B),
    surface = Color(0xFF15181B),
)

@Composable
fun ChessTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
