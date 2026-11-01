// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.chesstrainer.learning.puzzle.PuzzleAttempt
import org.chesstrainer.learning.puzzle.PuzzleId
import org.chesstrainer.learning.puzzle.PuzzleResult
import org.chesstrainer.learning.puzzle.PuzzleState
import org.chesstrainer.learning.puzzle.PuzzleStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SQL запитів прогресу задач на справжньому SQLite (JVM-тести працюють із фейковим DAO). Лише на пристрої. */
@RunWith(AndroidJUnit4::class)
class PuzzleProgressDaoTest {
    private lateinit var db: GameDatabase
    private lateinit var repo: RoomPuzzleProgressRepository

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, GameDatabase::class.java).build()
        repo = RoomPuzzleProgressRepository(db.puzzleProgress())
    }

    @After
    fun close() = db.close()

    private suspend fun record(id: String, state: PuzzleState, t: Long) = repo.recordAttempt(
        PuzzleAttempt(PuzzleResult(PuzzleId(id), state, 0, 0, 100), Instant.ofEpochMilli(t)),
    )

    @Test
    fun failedAttemptedLastResultAndStats() = runBlocking {
        record("a", PuzzleState.FAILED, 1)
        record("b", PuzzleState.FAILED, 2)
        record("c", PuzzleState.SOLVED, 3)
        record("a", PuzzleState.SOLVED, 4)
        record("b", PuzzleState.FAILED, 5)
        record("d", PuzzleState.FAILED, 6)
        record("c", PuzzleState.FAILED, 7)

        assertEquals(listOf("b", "d", "c").map(::PuzzleId), repo.failedPuzzleIds(10))
        assertEquals(listOf("b", "d").map(::PuzzleId), repo.failedPuzzleIds(2))
        assertEquals(setOf("a", "b", "c", "d").map(::PuzzleId).toSet(), repo.attemptedIds())
        assertEquals(PuzzleState.SOLVED, repo.lastResult(PuzzleId("a"))!!.state)
        assertNull(repo.lastResult(PuzzleId("zzz")))
        assertEquals(PuzzleStats(attempted = 4, solved = 1, failed = 3), repo.stats().first())
    }

    @Test
    fun emptyStats() = runBlocking {
        assertEquals(PuzzleStats(0, 0, 0), repo.stats().first())
    }
}
