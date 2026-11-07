// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.chesstrainer.app.settings.BoardThemeId

/** Колір підсвітки, який може відрізнятися на світлій і темній клітинці (потрібно темі `high_contrast`). */
data class SquareTint(val onLight: Color, val onDark: Color) {
    constructor(both: Color) : this(both, both)

    fun on(isLightSquare: Boolean): Color = if (isLightSquare) onLight else onDark
}

/** Власна палітра дошки (CONTRACTS 5.1): без текстур і зображень. */
data class BoardPalette(
    val lightSquare: Color,
    val darkSquare: Color,
    val lastMove: SquareTint,
    val selected: SquareTint,
    val check: SquareTint,
    val targetHint: Color,
    val coordinateOnLight: Color,
    val coordinateOnDark: Color,
)

data class BoardTheme(val id: BoardThemeId, val palette: BoardPalette)

/**
 * Теми дошки (CONTRACTS 5.1). Значення `id` стабільні й не перевикористовуються; назва в UI має ключ `board_theme_<id>`.
 * Палітра однакова у світлій і темній темі застосунку: дошка завжди лежить на власному тлі.
 */
object BoardThemes {
    /** Нинішні кольори дошки; тема за замовчуванням. */
    val slate = BoardTheme(
        BoardThemeId("slate"),
        BoardPalette(
            lightSquare = Color(0xFFDDE4EB),
            darkSquare = Color(0xFF7F97AE),
            lastMove = SquareTint(Color(0x66E8C547)),
            selected = SquareTint(Color(0x8855A67A)),
            check = SquareTint(Color(0xCCD9453D)),
            targetHint = Color(0x55233545),
            coordinateOnLight = Color(0xFF5E7388),
            coordinateOnDark = Color(0xFFEAF0F5),
        ),
    )

    val brown = BoardTheme(
        BoardThemeId("brown"),
        BoardPalette(
            lightSquare = Color(0xFFEEDCBF),
            darkSquare = Color(0xFFA9774F),
            lastMove = SquareTint(Color(0x77E8C547)),
            selected = SquareTint(Color(0x8855A67A)),
            check = SquareTint(Color(0xCCD9453D)),
            targetHint = Color(0x66302010),
            coordinateOnLight = Color(0xFF7A5232),
            coordinateOnDark = Color(0xFFF6E9D6),
        ),
    )

    val green = BoardTheme(
        BoardThemeId("green"),
        BoardPalette(
            lightSquare = Color(0xFFE6EFD8),
            darkSquare = Color(0xFF6F9A5E),
            lastMove = SquareTint(Color(0x88F2D13B)),
            selected = SquareTint(Color(0x883F7FCF)),
            check = SquareTint(Color(0xCCD9453D)),
            targetHint = Color(0x66173012),
            coordinateOnLight = Color(0xFF4F7A40),
            coordinateOnDark = Color(0xFFF1F7E8),
        ),
    )

    /**
     * Контраст ≥ 3:1 (WCAG 2.x, нетекстові елементи): світла проти темної клітинки, а кожна підсвітка проти клітинки,
     * на яку вона лягає, окремо для світлої й темної. Тому підсвітки непрозорі й різні для двох кольорів клітинки.
     */
    val highContrast = BoardTheme(
        BoardThemeId("high_contrast"),
        BoardPalette(
            lightSquare = Color(0xFFFFFFFF),
            darkSquare = Color(0xFF6E6E6E),
            lastMove = SquareTint(onLight = Color(0xFF3A78D8), onDark = Color(0xFFFFF2A0)),
            selected = SquareTint(onLight = Color(0xFF1E7B34), onDark = Color(0xFFA6F0B0)),
            check = SquareTint(onLight = Color(0xFFC4001A), onDark = Color(0xFFFFC2B8)),
            targetHint = Color(0xFF101820),
            coordinateOnLight = Color(0xFF000000),
            coordinateOnDark = Color(0xFF000000),
        ),
    )

    val default = slate

    /** Порядок це порядок у налаштуваннях. */
    val all: List<BoardTheme> = listOf(slate, brown, green, highContrast)

    /** Невідомий `id` дає [default]. */
    fun palette(id: BoardThemeId): BoardPalette = all.firstOrNull { it.id == id }?.palette ?: default.palette
}

/** Палітра, якою малюються всі дошки застосунку; значення задає `MainActivity` із налаштувань. */
val LocalBoardPalette = staticCompositionLocalOf { BoardThemes.default.palette }
