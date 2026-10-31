// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.time.Instant
import org.chesstrainer.app.game.GameTermination
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.model.Color

class RoomGameRepository(private val dao: GameDao) : GameRepository {

    override suspend fun save(game: SavedGame): GameId {
        val rowId = dao.upsert(game.toEntity())
        return game.id ?: GameId(rowId)
    }

    override suspend fun load(id: GameId): SavedGame? = dao.get(id.value)?.toSavedGame()

    override suspend fun delete(id: GameId) = dao.delete(id.value)

    /** Рядок, який не вдається прочитати (невідомі значення), закривається й пропускається. */
    override suspend fun lastUnfinished(): SavedGame? {
        while (true) {
            val entity = dao.lastUnfinished() ?: return null
            entity.toSavedGame()?.let { return it }
            dao.markAbandoned(entity.id)
        }
    }
}

private fun SavedGame.toEntity() = GameEntity(
    id = id?.value ?: 0,
    pgn = pgn,
    mode = mode.name,
    playerColor = playerColor?.name,
    difficultyId = difficultyId?.value,
    finished = finished,
    termination = termination?.name,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

private fun GameEntity.toSavedGame(): SavedGame? {
    val mode = GameMode.entries.firstOrNull { it.name == mode } ?: return null
    val color = playerColor?.let { name -> Color.entries.firstOrNull { it.name == name } ?: return null }
    val termination = termination?.let { name -> GameTermination.entries.firstOrNull { it.name == name } ?: return null }
    return SavedGame(
        id = GameId(id),
        pgn = pgn,
        mode = mode,
        playerColor = color,
        difficultyId = difficultyId?.let(::DifficultyId),
        finished = finished,
        termination = termination,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )
}
