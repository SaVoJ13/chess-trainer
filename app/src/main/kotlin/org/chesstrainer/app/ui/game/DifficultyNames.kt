// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.game

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.chesstrainer.app.R
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId

/** Ключі `difficulty_<id>` (CONTRACTS 3.5); `null` для id, якого цей застосунок ще не знає. */
@StringRes
internal fun difficultyNameRes(id: DifficultyId): Int? = when (id.value) {
    1 -> R.string.difficulty_1
    2 -> R.string.difficulty_2
    3 -> R.string.difficulty_3
    4 -> R.string.difficulty_4
    5 -> R.string.difficulty_5
    10 -> R.string.difficulty_10
    11 -> R.string.difficulty_11
    12 -> R.string.difficulty_12
    20 -> R.string.difficulty_20
    21 -> R.string.difficulty_21
    30 -> R.string.difficulty_30
    31 -> R.string.difficulty_31
    99 -> R.string.difficulty_99
    else -> null
}

@StringRes
internal fun difficultyGroupRes(group: DifficultyGroup): Int = when (group) {
    DifficultyGroup.BEGINNER -> R.string.difficulty_group_beginner
    DifficultyGroup.AMATEUR -> R.string.difficulty_group_amateur
    DifficultyGroup.CLUB -> R.string.difficulty_group_club
    DifficultyGroup.STRONG -> R.string.difficulty_group_strong
    DifficultyGroup.MAX -> R.string.difficulty_group_max
}

@Composable
internal fun difficultyName(id: DifficultyId): String =
    difficultyNameRes(id)?.let { stringResource(it) } ?: stringResource(R.string.difficulty_unknown, id.value)
