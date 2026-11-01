// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.chesstrainer.app.game.GameTermination
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.pgn.DefaultPgnCodec
import org.chesstrainer.core.pgn.PgnCodec
import org.chesstrainer.core.pgn.PgnResult
import org.chesstrainer.core.rules.ChesslibRules

class RoomGameRepository(
    private val dao: GameDao,
    private val codec: PgnCodec = DefaultPgnCodec(ChesslibRules()),
) : GameRepository {

    /** Розібране зведення PGN: повторний розбір потрібен лише для партій, що змінилися. */
    private class Parsed(val updatedAt: Long, val pgnHash: Int, val plyCount: Int, val result: PgnResult)

    private val parsedById = ConcurrentHashMap<Long, Parsed>()

    override suspend fun save(game: SavedGame): GameId {
        val rowId = dao.upsert(game.toEntity())
        return game.id ?: GameId(rowId)
    }

    override suspend fun load(id: GameId): SavedGame? = dao.get(id.value)?.toSavedGame()

    override suspend fun delete(id: GameId) = dao.delete(id.value)

    /**
     * Кількість півходів і результат дає `PgnCodec`, власного розбору PGN тут немає. Розбір іде поза головним
     * потоком. Рядок з невідомим режимом (запис новішої версії) не показується.
     */
    override fun observeAll(): Flow<List<SavedGameSummary>> =
        dao.observeRows().map { rows ->
            parsedById.keys.retainAll(rows.map { it.id }.toSet())
            rows.mapNotNull { it.toSummary() }
        }.flowOn(Dispatchers.Default)

    /** Рядок, який не вдається прочитати (невідомі значення), закривається й пропускається. */
    override suspend fun lastUnfinished(): SavedGame? {
        while (true) {
            val entity = dao.lastUnfinished() ?: return null
            entity.toSavedGame()?.let { return it }
            dao.markAbandoned(entity.id)
        }
    }

    private fun GameSummaryRow.toSummary(): SavedGameSummary? {
        val mode = GameMode.entries.firstOrNull { it.name == mode } ?: return null
        val hash = pgn.hashCode()
        val cached = parsedById[id]?.takeIf { it.updatedAt == updatedAt && it.pgnHash == hash }
        val parsed = cached ?: parse(pgn).let { (plies, result) -> Parsed(updatedAt, hash, plies, result) }
            .also { parsedById[id] = it }
        return SavedGameSummary(GameId(id), mode, parsed.result, finished, Instant.ofEpochMilli(updatedAt), parsed.plyCount)
    }

    private fun parse(pgn: String): Pair<Int, PgnResult> = try {
        val first = codec.parse(pgn).firstOrNull()
        if (first is ChessResult.Ok) first.value.moves.size to first.value.result else 0 to PgnResult.UNKNOWN
    } catch (e: RuntimeException) {
        0 to PgnResult.UNKNOWN
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
