// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Результат однієї спроби розв'язати задачу (схема v2). Лише в `app`, у контракти не потрапляє (CONTRACTS 5).
 * Записи лише додаються; порядок спроб визначає зростання [id] (час [at] лише для показу й діагностики).
 * [state] це ім'я `PuzzleState` (SOLVED або FAILED), [at] це мілісекунди Unix.
 */
@Entity(tableName = "puzzle_attempts", indices = [Index(value = ["puzzleId", "id"])])
data class PuzzleAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val puzzleId: String,
    val state: String,
    val wrongMoves: Int,
    val hintsUsed: Int,
    val timeMs: Long,
    val at: Long,
)

/** Підсумок за останніми спробами кожної задачі. */
data class PuzzleStatsRow(val attempted: Int, val solved: Int, val failed: Int)
