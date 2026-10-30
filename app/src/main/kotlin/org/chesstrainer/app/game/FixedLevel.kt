// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.EngineCapabilities
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.engine.Strength

/**
 * Тимчасовий фіксований рівень складності для екрана гри.
 *
 * TODO(difficulty): замінити на вибір рівня з `DifficultyPresets` і загорнути рушій у
 * `HandicapEngineService` у composition root, щойно Шахове ядро їх реалізує (CONTRACTS 3.5). Тоді ж
 * `DifficultyId` береться з пресетів і зберігається в партії; тут `DifficultyId(0)` лише заглушка.
 *
 * Діапазон сили береться з `capabilities`, числа Elo не хардкодяться (CONTRACTS 3.4): обираємо
 * найслабший `UCI_Elo`, який повідомив рушій.
 */
internal fun fixedLevel(capabilities: EngineCapabilities?): DifficultyLevel {
    val strength = capabilities?.eloRange?.let { Strength.Elo(it.first) } ?: Strength.Full
    return DifficultyLevel(
        id = DifficultyId(0),
        strength = strength,
        limit = SearchLimit(moveTimeMs = 500),
        minReplyDelayMs = 400,
        approxElo = null,
        group = DifficultyGroup.BEGINNER,
    )
}
