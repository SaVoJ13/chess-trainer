// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.settings

import org.chesstrainer.app.R
import org.chesstrainer.app.settings.BoardThemeId

/** Ключ назви `board_theme_<id>` у `strings.xml`; невідомий `id` дає назву теми за замовчуванням. */
internal fun boardThemeNameRes(id: BoardThemeId): Int = when (id.value) {
    "brown" -> R.string.board_theme_brown
    "green" -> R.string.board_theme_green
    "high_contrast" -> R.string.board_theme_high_contrast
    else -> R.string.board_theme_slate
}
