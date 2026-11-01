// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.chesstrainer.learning.puzzle.PuzzleAttempt
import org.chesstrainer.learning.puzzle.PuzzleId
import org.chesstrainer.learning.puzzle.PuzzleResult
import org.chesstrainer.learning.puzzle.PuzzleState
import org.chesstrainer.learning.puzzle.PuzzleStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Відображення й поведінка [RoomPuzzleProgressRepository] над DAO в пам'яті. SQL самих запитів перевіряє
 * інструментований `PuzzleProgressDaoTest` на пристрої (на JVM SQLite Room не виконується), а еквівалентні
 * запити звірено з локальним sqlite3 (журнал).
 */
class RoomPuzzleProgressRepositoryTest {

    /** Повторює семантику запитів [PuzzleProgressDao]: остання спроба задачі це запис із найбільшим id. */
    private class MemoryDao : PuzzleProgressDao {
        val rows = ArrayList<PuzzleAttemptEntity>()
        private val stats = MutableStateFlow(PuzzleStatsRow(0, 0, 0))

        override suspend fun insert(entity: PuzzleAttemptEntity): Long {
            val saved = entity.copy(id = rows.size + 1L)
            rows += saved
            val last = lastRows()
            stats.value = PuzzleStatsRow(last.size, last.count { it.state == "SOLVED" }, last.count { it.state == "FAILED" })
            return saved.id
        }

        private fun lastRows() = rows.groupBy { it.puzzleId }.values.map { it.maxBy { r -> r.id } }

        override suspend fun last(puzzleId: String) = rows.filter { it.puzzleId == puzzleId }.maxByOrNull { it.id }
        override suspend fun failedIds(limit: Int) =
            lastRows().filter { it.state == "FAILED" }.sortedBy { it.id }.take(limit).map { it.puzzleId }

        override suspend fun attemptedIds() = rows.map { it.puzzleId }.distinct()
        override fun stats(): Flow<PuzzleStatsRow> = stats
    }

    private val dao = MemoryDao()
    private val repo = RoomPuzzleProgressRepository(dao)

    private fun attempt(id: String, state: PuzzleState, wrong: Int = 0, hints: Int = 0, ms: Long = 1000, at: Long = 5) =
        PuzzleAttempt(PuzzleResult(PuzzleId(id), state, wrong, hints, ms), Instant.ofEpochMilli(at))

    @Test
    fun recordedAttemptIsMappedToRow() = runBlocking {
        repo.recordAttempt(attempt("p1", PuzzleState.FAILED, wrong = 2, hints = 1, ms = 4321, at = 77))
        val row = dao.rows.single()
        assertEquals(PuzzleAttemptEntity(1, "p1", "FAILED", 2, 1, 4321, 77), row)
    }

    @Test
    fun lastResultIsTheLatestAttempt() = runBlocking {
        repo.recordAttempt(attempt("p1", PuzzleState.FAILED, wrong = 1))
        repo.recordAttempt(attempt("p1", PuzzleState.SOLVED, hints = 2))
        assertEquals(PuzzleResult(PuzzleId("p1"), PuzzleState.SOLVED, 0, 2, 1000), repo.lastResult(PuzzleId("p1")))
        assertNull(repo.lastResult(PuzzleId("none")))
    }

    @Test
    fun failedIdsExcludeLaterSolvedOldestFirstWithLimit() = runBlocking {
        repo.recordAttempt(attempt("a", PuzzleState.FAILED))
        repo.recordAttempt(attempt("b", PuzzleState.FAILED))
        repo.recordAttempt(attempt("a", PuzzleState.SOLVED))
        repo.recordAttempt(attempt("c", PuzzleState.FAILED))
        repo.recordAttempt(attempt("b", PuzzleState.FAILED)) // повторна помилка: йде в кінець черги
        assertEquals(listOf("c", "b").map(::PuzzleId), repo.failedPuzzleIds(10))
        assertEquals(listOf(PuzzleId("c")), repo.failedPuzzleIds(1))
        assertEquals(emptyList<PuzzleId>(), repo.failedPuzzleIds(0))
    }

    @Test
    fun attemptedIdsAndStatsFollowLatestAttempts() = runBlocking {
        assertEquals(PuzzleStats(0, 0, 0), repo.stats().first())
        repo.recordAttempt(attempt("a", PuzzleState.FAILED))
        repo.recordAttempt(attempt("b", PuzzleState.SOLVED))
        repo.recordAttempt(attempt("a", PuzzleState.SOLVED))
        repo.recordAttempt(attempt("c", PuzzleState.FAILED))
        assertEquals(setOf("a", "b", "c").map(::PuzzleId).toSet(), repo.attemptedIds())
        assertEquals(PuzzleStats(attempted = 3, solved = 2, failed = 1), repo.stats().first())
    }

    @Test
    fun unfinishedAttemptIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.recordAttempt(attempt("a", PuzzleState.IN_PROGRESS)) }
        }
    }
}
