// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.puzzle

import androidx.annotation.StringRes
import org.chesstrainer.app.R
import org.chesstrainer.learning.puzzle.ThemeId

/** Рядок назви теми (`puzzle_theme_<id>`, CONTRACTS 4.1). Список повний для 20 тем каталогу (перевіряє тест). */
@StringRes
fun themeNameRes(theme: ThemeId): Int? = when (theme.value) {
    "smothered_mate" -> R.string.puzzle_theme_smothered_mate
    "back_rank_mate" -> R.string.puzzle_theme_back_rank_mate
    "double_check" -> R.string.puzzle_theme_double_check
    "discovered_check" -> R.string.puzzle_theme_discovered_check
    "fork" -> R.string.puzzle_theme_fork
    "pin" -> R.string.puzzle_theme_pin
    "skewer" -> R.string.puzzle_theme_skewer
    "discovered_attack" -> R.string.puzzle_theme_discovered_attack
    "deflection" -> R.string.puzzle_theme_deflection
    "attraction" -> R.string.puzzle_theme_attraction
    "trapped_piece" -> R.string.puzzle_theme_trapped_piece
    "hanging_piece" -> R.string.puzzle_theme_hanging_piece
    "promotion" -> R.string.puzzle_theme_promotion
    "mate_in_1" -> R.string.puzzle_theme_mate_in_1
    "mate_in_2" -> R.string.puzzle_theme_mate_in_2
    "mate_in_3" -> R.string.puzzle_theme_mate_in_3
    "sacrifice" -> R.string.puzzle_theme_sacrifice
    "opening" -> R.string.puzzle_theme_opening
    "middlegame" -> R.string.puzzle_theme_middlegame
    "endgame" -> R.string.puzzle_theme_endgame
    else -> null
}
