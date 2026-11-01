// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Запити прогресу задач. «Остання спроба» задачі це запис із найбільшим `id`.
 * Задача вважається невдалою, якщо її остання спроба FAILED (тобто після останньої FAILED ще не було SOLVED).
 */
@Dao
interface PuzzleProgressDao {
    @Insert
    suspend fun insert(entity: PuzzleAttemptEntity): Long

    @Query("SELECT * FROM puzzle_attempts WHERE puzzleId = :puzzleId ORDER BY id DESC LIMIT 1")
    suspend fun last(puzzleId: String): PuzzleAttemptEntity?

    /** Невдалі задачі, найстаріші (за останньою спробою) першими. */
    @Query(
        "SELECT puzzleId FROM puzzle_attempts a WHERE state = 'FAILED' " +
            "AND id = (SELECT MAX(id) FROM puzzle_attempts b WHERE b.puzzleId = a.puzzleId) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun failedIds(limit: Int): List<String>

    @Query("SELECT DISTINCT puzzleId FROM puzzle_attempts")
    suspend fun attemptedIds(): List<String>

    @Query(
        "SELECT COUNT(*) AS attempted, " +
            "COALESCE(SUM(CASE WHEN state = 'SOLVED' THEN 1 ELSE 0 END), 0) AS solved, " +
            "COALESCE(SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed " +
            "FROM puzzle_attempts a WHERE id = (SELECT MAX(id) FROM puzzle_attempts b WHERE b.puzzleId = a.puzzleId)",
    )
    fun stats(): Flow<PuzzleStatsRow>
}
