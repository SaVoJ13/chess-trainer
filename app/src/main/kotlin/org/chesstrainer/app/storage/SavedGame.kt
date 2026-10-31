// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.time.Instant
import org.chesstrainer.app.game.GameTermination
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.model.Color

@JvmInline
value class GameId(val value: Long)

enum class GameMode { VS_ENGINE, ANALYSIS_BOARD, IMPORTED }

/**
 * Збережена партія (CONTRACTS 5). Ходи й початкова позиція зберігаються лише в [pgn]; усе інше
 * (позицію, статус, список ходів) відновлюють `ChessGame` і `PgnCodec`.
 *
 * [difficultyId] до появи `DifficultyPresets` завжди `null` (заглушка `DifficultyId(0)` не зберігається).
 */
data class SavedGame(
    val id: GameId?,
    val pgn: String,
    val mode: GameMode,
    val playerColor: Color?,
    val difficultyId: DifficultyId?,
    val finished: Boolean,
    val termination: GameTermination?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
