// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import kotlinx.coroutines.flow.Flow

/** Сховище партій (CONTRACTS 5). */
interface GameRepository {
    /** Вставка (`id == null`) або оновлення; повертає ідентифікатор запису. */
    suspend fun save(game: SavedGame): GameId

    suspend fun load(id: GameId): SavedGame?

    suspend fun delete(id: GameId)

    /** Усі партії для списку за `updatedAt` спаданням; нове значення при кожній зміні таблиці. */
    fun observeAll(): Flow<List<SavedGameSummary>>

    /** Остання за часом оновлення незавершена партія, придатна для відновлення. */
    suspend fun lastUnfinished(): SavedGame?
}
