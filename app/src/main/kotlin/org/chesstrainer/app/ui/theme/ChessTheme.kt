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

/** Кольори дошки: власна приглушена синьо-сіра палітра, однакова у світлій і темній темі. */
object BoardColors {
    val lightSquare = Color(0xFFDDE4EB)
    val darkSquare = Color(0xFF7F97AE)
    val lastMove = Color(0x66E8C547)
    val selected = Color(0x8855A67A)
    val targetHint = Color(0x55233545)
    val check = Color(0xCCD9453D)
    val coordinateOnLight = Color(0xFF5E7388)
    val coordinateOnDark = Color(0xFFEAF0F5)
}

@Composable
fun ChessTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
