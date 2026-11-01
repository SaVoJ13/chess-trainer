// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.chesstrainer.learning.puzzle.PuzzleAttempt
import org.chesstrainer.learning.puzzle.PuzzleId
import org.chesstrainer.learning.puzzle.PuzzleProgressRepository
import org.chesstrainer.learning.puzzle.PuzzleResult
import org.chesstrainer.learning.puzzle.PuzzleState
import org.chesstrainer.learning.puzzle.PuzzleStats

/** Реалізація порту `learning` над [PuzzleProgressDao] (CONTRACTS 4.2). Тестується на фейковому DAO. */
class RoomPuzzleProgressRepository(private val dao: PuzzleProgressDao) : PuzzleProgressRepository {

    override suspend fun recordAttempt(attempt: PuzzleAttempt) {
        val r = attempt.result
        require(r.state != PuzzleState.IN_PROGRESS) { "Only finished attempts are recorded" }
        dao.insert(
            PuzzleAttemptEntity(
                puzzleId = r.puzzleId.value,
                state = r.state.name,
                wrongMoves = r.wrongMoves,
                hintsUsed = r.hintsUsed,
                timeMs = r.timeMs,
                at = attempt.at.toEpochMilli(),
            ),
        )
    }

    override suspend fun lastResult(id: PuzzleId): PuzzleResult? = dao.last(id.value)?.toResult()

    override suspend fun failedPuzzleIds(limit: Int): List<PuzzleId> =
        if (limit <= 0) emptyList() else dao.failedIds(limit).map(::PuzzleId)

    override suspend fun attemptedIds(): Set<PuzzleId> = dao.attemptedIds().mapTo(HashSet(), ::PuzzleId)

    override fun stats(): Flow<PuzzleStats> = dao.stats().map { PuzzleStats(it.attempted, it.solved, it.failed) }
}

private fun PuzzleAttemptEntity.toResult() = PuzzleResult(
    puzzleId = PuzzleId(puzzleId),
    state = PuzzleState.valueOf(state),
    wrongMoves = wrongMoves,
    hintsUsed = hintsUsed,
    timeMs = timeMs,
)

