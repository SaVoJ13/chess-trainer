// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.puzzle

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.learning.puzzle.PuzzleTestSupport.mv
import org.chesstrainer.learning.puzzle.PuzzleTestSupport.puzzle
import org.chesstrainer.learning.puzzle.PuzzleTestSupport.rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PuzzleSessionTest {
    private val clock = TestTimeSource()

    private fun session(p: Puzzle) = DefaultPuzzleSession(p, rules, clock)

    /** Реальна задача з трьох півходів із закоміченого пакета. */
    private fun threePly(): Puzzle = PuzzleTestSupport.loadCommitted().puzzles.first { it.solution.size == 3 }

    @Test
    fun correctSingleMoveSolves() = runBlocking {
        val s = session(puzzle("a"))
        assertEquals(PuzzleState.IN_PROGRESS, s.state)
        assertEquals(PuzzleStep.Correct(null, lineCompleted = true), s.submit(mv("a1a8")))
        assertEquals(PuzzleState.SOLVED, s.state)
        assertEquals(PuzzleStep.Finished, s.submit(mv("a1a8")))
        assertEquals(PuzzleState.SOLVED, s.result().state)
    }

    @Test
    fun correctSequenceReturnsOpponentReply() = runBlocking {
        val p = threePly()
        val s = session(p)
        val start = s.position
        val step = s.submit(mv(p.solution[0].value))
        assertEquals(PuzzleStep.Correct(mv(p.solution[1].value), lineCompleted = false), step)
        assertTrue("position advanced", s.position != start)
        assertEquals(PuzzleState.IN_PROGRESS, s.state)
        assertEquals(PuzzleStep.Correct(null, lineCompleted = true), s.submit(mv(p.solution[2].value)))
        assertEquals(PuzzleState.SOLVED, s.state)
    }

    @Test
    fun wrongMoveFailsButPlayCanContinue() = runBlocking {
        val p = threePly()
        val s = session(p)
        val before = s.position
        val wrong = rules.legalMoves(before).sortedBy { it.from.index * 64 + it.to.index }
            .first { rules.toUci(it).value != p.solution[0].value }
        assertEquals(PuzzleStep.Wrong, s.submit(wrong))
        assertEquals(before, s.position)
        assertEquals(PuzzleState.FAILED, s.state)
        assertEquals(PuzzleStep.Wrong, s.submit(wrong))
        // Далі можна довести розв'язок до кінця, результат лишається FAILED.
        assertTrue(s.submit(mv(p.solution[0].value)) is PuzzleStep.Correct)
        assertEquals(PuzzleStep.Correct(null, lineCompleted = true), s.submit(mv(p.solution[2].value)))
        val r = s.result()
        assertEquals(PuzzleState.FAILED, r.state)
        assertEquals(2, r.wrongMoves)
        assertEquals(PuzzleStep.Finished, s.submit(wrong))
    }

    @Test
    fun illegalMoveCountsAsWrong() = runBlocking {
        val s = session(puzzle("a"))
        assertEquals(PuzzleStep.Wrong, s.submit(mv("a1h8")))
        assertEquals(1, s.result().wrongMoves)
    }

    @Test
    fun alternativeMateIsAccepted() = runBlocking {
        val s = session(puzzle("a", fen = PuzzleTestSupport.TWO_ROOKS_FEN, solution = listOf("a1a8")))
        assertEquals(PuzzleStep.Correct(null, lineCompleted = true), s.submit(mv("b1b8")))
        assertEquals(PuzzleState.SOLVED, s.state)
        assertTrue(rules.isCheckmate(s.position))
    }

    @Test
    fun alternativeNonMatingMoveIsRejected() = runBlocking {
        val s = session(puzzle("a", fen = PuzzleTestSupport.TWO_ROOKS_FEN, solution = listOf("a1a8")))
        assertEquals(PuzzleStep.Wrong, s.submit(mv("b1b7")))
        assertEquals(PuzzleState.FAILED, s.state)
    }

    @Test
    fun alternativeMateInMiddleEndsPuzzle() = runBlocking {
        // Розв'язок із трьох півходів, але перший хід гравця дає мат іншим способом.
        val s = session(
            puzzle("a", fen = PuzzleTestSupport.TWO_ROOKS_FEN, solution = listOf("b1b2", "g8f8", "a1a8")),
        )
        assertEquals(PuzzleStep.Correct(null, lineCompleted = true), s.submit(mv("a1a8")))
        assertEquals(PuzzleState.SOLVED, s.state)
        assertEquals(PuzzleStep.Finished, s.submit(mv("b1b2")))
    }

    @Test
    fun hintsGoNudgeDirectionFullMove() = runBlocking {
        val s = session(puzzle("a", themes = listOf("middlegame", "pin", "fork", "mate_in_1")))
        // Тактичні теми за пріоритетом: fork перед pin; фаза middlegame ігнорується.
        assertEquals(Hint.Nudge(ThemeId("fork")), s.hint())
        assertEquals(PuzzleState.IN_PROGRESS, s.state)
        assertEquals(Hint.Direction(Square.parse("a1")!!), s.hint())
        assertEquals(PuzzleState.IN_PROGRESS, s.state)
        assertEquals(Hint.FullMove(mv("a1a8")), s.hint())
        assertEquals(PuzzleState.FAILED, s.state)
        assertEquals(Hint.FullMove(mv("a1a8")), s.hint()) // повтор не рахується
        assertEquals(3, s.result().hintsUsed)
        // Задачу можна довести до кінця; результат FAILED.
        assertEquals(PuzzleStep.Correct(null, lineCompleted = true), s.submit(mv("a1a8")))
        assertEquals(PuzzleState.FAILED, s.result().state)
    }

    @Test
    fun phaseThemeIsNeverUsedForNudge() {
        val s = session(puzzle("a", themes = listOf("opening", "endgame", "mate_in_1")))
        assertEquals(Hint.Nudge(ThemeId("mate_in_1")), s.hint())
    }

    @Test
    fun withoutTacticThemeNudgeIsSkipped() {
        val s = session(puzzle("a", themes = listOf("middlegame")))
        assertEquals(Hint.Direction(Square.parse("a1")!!), s.hint())
        assertEquals(1, s.result().hintsUsed)
        assertTrue(s.hint() is Hint.FullMove)
        assertEquals(2, s.result().hintsUsed)
    }

    @Test
    fun hintsRestartPerMoveButNudgeOnlyOnce() = runBlocking {
        val p = threePly()
        val s = session(p)
        assertTrue(s.hint() is Hint.Nudge)
        assertEquals(Hint.Direction(mv(p.solution[0].value).from), s.hint())
        s.submit(mv(p.solution[0].value))
        // Новий хід гравця: тему вже показано, починаємо з фігури.
        assertEquals(Hint.Direction(mv(p.solution[2].value).from), s.hint())
        assertEquals(PuzzleState.IN_PROGRESS, s.state)
    }

    @Test
    fun revealSolutionFailsAndEnds() = runBlocking {
        val s = session(puzzle("a", solution = listOf("a1a8")))
        assertEquals(listOf(Uci("a1a8")), s.revealSolution())
        assertEquals(PuzzleState.FAILED, s.state)
        assertEquals(PuzzleStep.Finished, s.submit(mv("a1a8")))
        assertEquals(PuzzleState.FAILED, s.result().state)
    }

    @Test
    fun hintAfterFinishIsProgrammerError() {
        val s = session(puzzle("a"))
        s.revealSolution()
        try {
            s.hint()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun hintAfterLineCompletedIsProgrammerError() = runBlocking {
        val s = session(puzzle("a"))
        assertEquals(PuzzleStep.Correct(null, lineCompleted = true), s.submit(mv("a1a8")))
        try {
            s.hint()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, s.result().hintsUsed)
    }

    @Test
    fun lineCompletedAfterWrongKeepsFailed() = runBlocking {
        val s = session(puzzle("a"))
        assertEquals(PuzzleStep.Wrong, s.submit(mv("a1a7")))
        assertEquals(PuzzleStep.Correct(null, lineCompleted = true), s.submit(mv("a1a8")))
        assertEquals(PuzzleState.FAILED, s.state)
        assertEquals(PuzzleStep.Finished, s.submit(mv("a1a8")))
    }

    @Test
    fun revealSolutionMidwayFailsAndFinishes() = runBlocking {
        val p = threePly()
        val s = session(p)
        s.submit(mv(p.solution[0].value))
        assertEquals(PuzzleState.IN_PROGRESS, s.state)
        assertEquals(p.solution, s.revealSolution())
        assertEquals(PuzzleState.FAILED, s.state)
        assertEquals(PuzzleStep.Finished, s.submit(mv(p.solution[2].value)))
    }

    @Test
    fun resultCountsTimeAndStopsAtFinish()= runBlocking {
        val s = session(puzzle("a"))
        clock += 1500.milliseconds
        assertEquals(1500L, s.result().timeMs)
        s.submit(mv("a1a8"))
        clock += 5000.milliseconds
        assertEquals(1500L, s.result().timeMs)
        assertEquals(PuzzleId("a"), s.result().puzzleId)
    }

    @Test
    fun resultCountsWrongMovesAndHints() = runBlocking {
        val s = session(puzzle("a"))
        s.submit(mv("a1a7"))
        s.hint()
        s.hint()
        assertEquals(PuzzleResult(PuzzleId("a"), PuzzleState.FAILED, 1, 2, 0), s.result())
    }

    @Test
    fun invalidStartFenIsContractViolation() {
        try {
            session(puzzle("a", fen = "broken"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
