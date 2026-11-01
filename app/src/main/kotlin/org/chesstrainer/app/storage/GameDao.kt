// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    /** Вставляє нову (`id == 0`) або замінює наявну; повертає rowId вставки чи -1 при оновленні. */
    @Upsert
    suspend fun upsert(entity: GameEntity): Long

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun get(id: Long): GameEntity?

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM games WHERE finished = 0 ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun lastUnfinished(): GameEntity?

    /** Партії для списку, найновіші першими; PGN потрібен для кількості півходів і результату. */
    @Query("SELECT id, mode, finished, updatedAt, pgn FROM games ORDER BY updatedAt DESC, id DESC")
    fun observeRows(): Flow<List<GameSummaryRow>>

    /** Закриває запис, який не вдається прочитати, щоб він не пропонувався знову. */
    @Query("UPDATE games SET finished = 1, termination = 'ABANDONED' WHERE id = :id")
    suspend fun markAbandoned(id: Long)
}
