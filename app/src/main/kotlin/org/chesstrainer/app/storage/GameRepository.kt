// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

/**
 * Сховище партій (CONTRACTS 5). Частина контракту (`delete`, `observeAll`, `SavedGameSummary`) додається
 * разом зі списком збережених партій; зараз потрібне лише автозбереження.
 */
interface GameRepository {
    /** Вставка (`id == null`) або оновлення; повертає ідентифікатор запису. */
    suspend fun save(game: SavedGame): GameId

    suspend fun load(id: GameId): SavedGame?

    suspend fun delete(id: GameId)

    /** Остання за часом оновлення незавершена партія, придатна для відновлення. */
    suspend fun lastUnfinished(): SavedGame?
}
