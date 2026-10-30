// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.chesstrainer.app.game.GameTermination
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.model.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Відображення між [SavedGame] і рядком таблиці та поведінка репозиторію над DAO. DAO тут підміняється
 * реалізацією в пам'яті: SQL самих запитів (`GameDao`) на JVM без пристрою не виконується, див. журнал.
 */
class RoomGameRepositoryTest {

    private class MemoryDao : GameDao {
        val rows = linkedMapOf<Long, GameEntity>()
        private var nextId = 1L

        override suspend fun upsert(entity: GameEntity): Long =
            if (entity.id == 0L || entity.id !in rows) {
                val id = if (entity.id == 0L) nextId++ else entity.id
                rows[id] = entity.copy(id = id)
                id
            } else {
                rows[entity.id] = entity
                -1
            }

        override suspend fun get(id: Long) = rows[id]

        override suspend fun delete(id: Long) {
            rows.remove(id)
        }

        override suspend fun lastUnfinished() =
            rows.values.filter { !it.finished }.maxWithOrNull(compareBy({ it.updatedAt }, { it.id }))

        override suspend fun markAbandoned(id: Long) {
            rows[id] = rows.getValue(id).copy(finished = true, termination = "ABANDONED")
        }
    }

    private val game = SavedGame(
        id = null,
        pgn = "[Event \"?\"]\n\n1. e4 *\n",
        mode = GameMode.VS_ENGINE,
        playerColor = Color.BLACK,
        difficultyId = null,
        finished = false,
        termination = null,
        createdAt = Instant.ofEpochMilli(1_000),
        updatedAt = Instant.ofEpochMilli(2_000),
    )

    @Test
    fun savedGameRoundTripsThroughTheEntity() = runBlocking {
        val repo = RoomGameRepository(MemoryDao())
        val id = repo.save(game)
        assertEquals(game.copy(id = id), repo.load(id))
    }

    @Test
    fun finishedGameKeepsTerminationAndDifficultyIsOptional() = runBlocking {
        val repo = RoomGameRepository(MemoryDao())
        val finished = game.copy(finished = true, termination = GameTermination.DRAW_CLAIMED, difficultyId = DifficultyId(7))
        val id = repo.save(finished)
        assertEquals(finished.copy(id = id), repo.load(id))
        assertNull(repo.lastUnfinished())
    }

    @Test
    fun savingAnExistingIdUpdatesInPlace() = runBlocking {
        val dao = MemoryDao()
        val repo = RoomGameRepository(dao)
        val id = repo.save(game)
        val same = repo.save(game.copy(id = id, pgn = "[Event \"?\"]\n\n1. e4 e5 *\n", updatedAt = Instant.ofEpochMilli(3_000)))
        assertEquals(id, same)
        assertEquals(1, dao.rows.size)
        assertTrue(repo.load(id)!!.pgn.contains("e5"))
    }

    @Test
    fun deleteRemovesTheRecord() = runBlocking {
        val repo = RoomGameRepository(MemoryDao())
        val id = repo.save(game)
        repo.delete(id)
        assertNull(repo.load(id))
    }

    @Test
    fun lastUnfinishedIsTheMostRecentlyUpdated() = runBlocking {
        val repo = RoomGameRepository(MemoryDao())
        repo.save(game.copy(updatedAt = Instant.ofEpochMilli(2_000)))
        val newest = repo.save(game.copy(updatedAt = Instant.ofEpochMilli(9_000)))
        repo.save(game.copy(updatedAt = Instant.ofEpochMilli(5_000)))
        repo.save(game.copy(updatedAt = Instant.ofEpochMilli(99_000), finished = true, termination = GameTermination.RESIGNATION))
        assertEquals(newest, repo.lastUnfinished()!!.id)
    }

    @Test
    fun unreadableRowIsClosedAndSkipped() = runBlocking {
        val dao = MemoryDao()
        val repo = RoomGameRepository(dao)
        val good = repo.save(game.copy(updatedAt = Instant.ofEpochMilli(2_000)))
        dao.upsert(
            GameEntity(
                pgn = "x", mode = "FROM_THE_FUTURE", playerColor = "WHITE", difficultyId = null,
                finished = false, termination = null, createdAt = 0, updatedAt = 9_000,
            ),
        )
        dao.upsert(
            GameEntity(
                pgn = "x", mode = "VS_ENGINE", playerColor = "GREEN", difficultyId = null,
                finished = false, termination = null, createdAt = 0, updatedAt = 8_000,
            ),
        )
        val found = repo.lastUnfinished()
        assertNotNull(found)
        assertEquals(good, found!!.id)
        assertEquals(2, dao.rows.values.count { it.finished && it.termination == "ABANDONED" })
    }
}
