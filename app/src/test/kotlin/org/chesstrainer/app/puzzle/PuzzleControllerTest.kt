// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.puzzle

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random
import org.chesstrainer.app.content.ContentStatus
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.learning.content.ContentManifest
import org.chesstrainer.learning.content.ContentProblem
import org.chesstrainer.learning.content.InMemoryContentSource
import org.chesstrainer.learning.content.SchemaVersion
import org.chesstrainer.learning.puzzle.DefaultPuzzleService
import org.chesstrainer.learning.puzzle.Puzzle
import org.chesstrainer.learning.puzzle.PuzzleAttempt
import org.chesstrainer.learning.puzzle.PuzzleId
import org.chesstrainer.learning.puzzle.PuzzleMode
import org.chesstrainer.learning.puzzle.PuzzleProgressRepository
import org.chesstrainer.learning.puzzle.PuzzleResult
import org.chesstrainer.learning.puzzle.PuzzleState
import org.chesstrainer.learning.puzzle.PuzzleStats
import org.chesstrainer.learning.puzzle.ThemeId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логіка екрана задач на JVM: фейковий прогрес, `InMemoryContentSource` і `ChesslibRules` (без Android і Compose). */
class PuzzleControllerTest {

    private class FakeProgress : PuzzleProgressRepository {
        val attempts = ArrayList<PuzzleAttempt>()
        private val stats = MutableStateFlow(PuzzleStats(0, 0, 0))

        override suspend fun recordAttempt(attempt: PuzzleAttempt) {
            attempts += attempt
            stats.value = computeStats()
        }

        private fun lastPerPuzzle(): Map<PuzzleId, PuzzleResult> =
            attempts.associate { it.result.puzzleId to it.result } // останній запис перекриває попередні

        private fun computeStats(): PuzzleStats {
            val last = lastPerPuzzle().values
            return PuzzleStats(last.size, last.count { it.state == PuzzleState.SOLVED }, last.count { it.state == PuzzleState.FAILED })
        }

        override suspend fun lastResult(id: PuzzleId): PuzzleResult? = lastPerPuzzle()[id]

        override suspend fun failedPuzzleIds(limit: Int): List<PuzzleId> {
            val lastIndex = attempts.withIndex().groupBy({ it.value.result.puzzleId }, { it.index }).mapValues { it.value.max() }
            return lastPerPuzzle().filterValues { it.state == PuzzleState.FAILED }.keys.sortedBy { lastIndex.getValue(it) }.take(limit)
        }

        override suspend fun attemptedIds(): Set<PuzzleId> = attempts.map { it.result.puzzleId }.toSet()

        override fun stats(): Flow<PuzzleStats> = stats
    }

    private val rules = ChesslibRules()
    private val progress = FakeProgress()
    private val scopeJob = Job()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
    private val now = Instant.parse("2026-11-01T10:00:00Z")

    @After
    fun tearDown() = scopeJob.cancel()

    // a: фея з трьох ходів (гравець, відповідь, гравець), легка; b: мат в 1 хід; c: перетворення пішака, складна.
    private val a = puzzle("a", "4k3/8/8/8/8/8/4P3/4K2R w - - 0 1", listOf("e2e4", "e8d8", "e4e5"), setOf("fork"), 500)
    private val b = puzzle("b", "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", listOf("a1a8"), setOf("back_rank_mate", "mate_in_1"), 1500)
    private val c = puzzle("c", "8/P6k/8/8/8/8/8/K7 w - - 0 1", listOf("a7a8q"), setOf("promotion", "endgame"), 2000)

    private fun puzzle(id: String, fen: String, solution: List<String>, themes: Set<String>, rating: Int) = Puzzle(
        PuzzleId(id), Fen(fen), solution.map(::Uci), themes.mapTo(LinkedHashSet()) { ThemeId(it) }, rating, "CT-001",
    )

    private fun controller(
        puzzles: List<Puzzle> = listOf(a, b, c),
        status: ContentStatus = ContentStatus.Ready(puzzles.size, emptyList()),
    ): PuzzleController {
        val content = InMemoryContentSource(ContentManifest(SchemaVersion(1, 0), emptyList()), puzzles)
        val service = DefaultPuzzleService(content, progress, rules, Random(1))
        return PuzzleController(service, progress, rules, { status }, scope, clock = { now }, replyDelayMs = 0).also { it.start() }
    }

    private fun sq(name: String) = Square.parse(name)!!
    private fun PuzzleController.tapMove(from: String, to: String) {
        onSquareTap(sq(from))
        onSquareTap(sq(to))
    }
    private val PuzzleController.play get() = state.value.play!!

    private fun PuzzleController.startWith(theme: String? = null, level: PuzzleLevel = PuzzleLevel.ANY, mode: PuzzleMode = PuzzleMode.NEW) {
        selectTheme(theme?.let(::ThemeId))
        selectLevel(level)
        selectMode(mode)
        startPuzzle()
    }

    // --- вибір ---

    @Test
    fun themeSelectionPicksPuzzleWithThatTheme() {
        val ctl = controller()
        ctl.startWith(theme = "promotion")
        assertEquals("c", ctl.play.puzzle.id.value)
        assertEquals(ThemeId("promotion"), ctl.state.value.setup.theme)
    }

    @Test
    fun levelSelectionPicksPuzzleInRange() {
        val easy = controller().also { it.startWith(level = PuzzleLevel.EASY) }
        assertEquals("a", easy.play.puzzle.id.value)
        val medium = controller().also { it.startWith(level = PuzzleLevel.MEDIUM) }
        assertEquals("b", medium.play.puzzle.id.value)
        val hard = controller().also { it.startWith(level = PuzzleLevel.HARD) }
        assertEquals("c", hard.play.puzzle.id.value)
    }

    @Test
    fun newModeSkipsAttemptedAndReportsWhenExhausted() {
        val ctl = controller(listOf(b))
        ctl.startWith()
        ctl.tapMove("a1", "a8") // мат: розв'язано
        assertEquals(PuzzleState.SOLVED, ctl.play.state)
        ctl.nextPuzzle()
        assertNull(ctl.state.value.play)
        assertEquals(MenuNotice.NO_NEW_PUZZLES, ctl.state.value.notice)
        assertFalse(ctl.state.value.loading)
    }

    @Test
    fun retryFailedOffersOnlyFailedAndSaysSoWhenNone() {
        val ctl = controller()
        ctl.startWith(mode = PuzzleMode.RETRY_FAILED)
        assertNull(ctl.state.value.play)
        assertEquals(MenuNotice.NO_FAILED_PUZZLES, ctl.state.value.notice)

        // Проваливаємо задачу "c" (показ розв'язку), після цього вона з'являється в повторенні.
        ctl.selectMode(PuzzleMode.NEW)
        ctl.selectTheme(ThemeId("promotion"))
        ctl.startPuzzle()
        ctl.revealSolution()
        ctl.backToMenu()
        ctl.selectTheme(null)
        ctl.selectMode(PuzzleMode.RETRY_FAILED)
        ctl.startPuzzle()
        assertEquals("c", ctl.play.puzzle.id.value)
    }

    @Test
    fun setupCannotChangeDuringPuzzle() {
        val ctl = controller()
        ctl.startWith(theme = "fork")
        ctl.selectTheme(ThemeId("pin"))
        assertEquals(ThemeId("fork"), ctl.state.value.setup.theme)
    }

    // --- ходи ---

    @Test
    fun correctMoveShowsOpponentReplyThenSolves() {
        val ctl = controller()
        ctl.startWith(theme = "fork")
        assertEquals(org.chesstrainer.core.model.Color.WHITE, ctl.play.playerColor)

        ctl.tapMove("e2", "e4")
        assertEquals(sq("d8"), ctl.play.lastMove!!.to) // відповідь суперника e8d8
        assertEquals(PuzzleState.IN_PROGRESS, ctl.play.state)
        assertFalse(ctl.play.over)
        assertEquals(PieceType.KING, ctl.play.position.pieceAt(sq("d8"))!!.type)

        ctl.tapMove("e4", "e5")
        assertTrue(ctl.play.over)
        assertEquals(PuzzleState.SOLVED, ctl.play.state)
        val saved = progress.attempts.single()
        assertEquals(PuzzleState.SOLVED, saved.result.state)
        assertEquals(PuzzleId("a"), saved.result.puzzleId)
        assertEquals(now, saved.at)
        assertEquals(PuzzleStats(1, 1, 0), ctl.state.value.stats)
    }

    @Test
    fun dragMoveWorksLikeTaps() {
        val ctl = controller()
        ctl.startWith(theme = "fork")
        assertTrue(ctl.onDragStart(sq("e2")))
        ctl.onDrop(sq("e2"), sq("e4"))
        assertEquals(sq("d8"), ctl.play.lastMove!!.to)
        assertFalse(ctl.onDragStart(sq("e8"))) // чужа фігура
    }

    @Test
    fun wrongMoveKeepsPositionAndResultIsFailedEvenWhenLineIsFinished() {
        val ctl = controller()
        ctl.startWith(theme = "fork")
        val before = ctl.play.position

        ctl.tapMove("e2", "e3") // легальний, але не розв'язок
        assertTrue(ctl.play.wrongMove)
        assertEquals(before, ctl.play.position)
        assertEquals(1, ctl.play.wrongMoves)
        assertEquals(PuzzleState.FAILED, ctl.play.state)
        assertFalse(ctl.play.over)

        // Можна пробувати далі й довести лінію до кінця: lineCompleted не робить задачу «розв'язаною».
        ctl.tapMove("e2", "e4")
        assertFalse(ctl.play.wrongMove)
        ctl.tapMove("e4", "e5")
        assertTrue(ctl.play.over)
        assertEquals(PuzzleState.FAILED, ctl.play.state)
        val saved = progress.attempts.single().result
        assertEquals(PuzzleState.FAILED, saved.state)
        assertEquals(1, saved.wrongMoves)
        assertEquals(PuzzleStats(1, 0, 1), ctl.state.value.stats)
    }

    @Test
    fun promotionAsksForPieceThenSubmits() {
        val ctl = controller()
        ctl.startWith(theme = "promotion")
        ctl.tapMove("a7", "a8")
        assertNotNull(ctl.play.pendingPromotion)
        assertFalse(ctl.play.canMove)
        ctl.choosePromotion(PieceType.QUEEN)
        assertNull(ctl.play.pendingPromotion)
        assertTrue(ctl.play.over)
        assertEquals(PuzzleState.SOLVED, ctl.play.state)
    }

    @Test
    fun cancelPromotionLeavesPuzzleUntouched() {
        val ctl = controller()
        ctl.startWith(theme = "promotion")
        ctl.tapMove("a7", "a8")
        ctl.cancelPromotion()
        assertNull(ctl.play.pendingPromotion)
        assertFalse(ctl.play.over)
        assertTrue(progress.attempts.isEmpty())
    }

    // --- підказки ---

    @Test
    fun threeHintStepsEndInFailedAndShowMoveOnBoard() {
        val ctl = controller()
        ctl.startWith(theme = "fork")

        ctl.hint()
        assertEquals(1, ctl.play.hint!!.step)
        assertEquals(ThemeId("fork"), ctl.play.hint!!.theme)
        assertEquals(PuzzleState.IN_PROGRESS, ctl.play.state)

        ctl.hint()
        assertEquals(2, ctl.play.hint!!.step)
        assertEquals(sq("e2"), ctl.play.hint!!.from)

        ctl.hint()
        val third = ctl.play.hint!!
        assertEquals(3, third.step)
        assertEquals(sq("e2"), third.move!!.from)
        assertEquals(sq("e4"), third.move!!.to)
        assertEquals(PuzzleState.FAILED, ctl.play.state)
        assertEquals(3, ctl.play.hintsUsed)

        // Гравець робить підказаний хід і доводить лінію: результат FAILED.
        ctl.tapMove("e2", "e4")
        assertNull(ctl.play.hint)
        ctl.tapMove("e4", "e5")
        assertEquals(PuzzleState.FAILED, progress.attempts.single().result.state)
    }

    @Test
    fun hintIsBlockedAfterFinishAndDoesNotThrow() {
        val ctl = controller()
        ctl.startWith(theme = "back_rank_mate")
        ctl.tapMove("a1", "a8")
        assertTrue(ctl.play.over)
        assertFalse(ctl.play.canHint)
        ctl.hint() // не кидає IllegalStateException: кнопка заблокована
        assertNull(ctl.play.hint)
    }

    // --- розв'язок ---

    @Test
    fun revealSolutionFailsPuzzleAndAllowsStepping() {
        val ctl = controller()
        ctl.startWith(theme = "fork")
        ctl.revealSolution()

        val play = ctl.play
        assertTrue(play.over)
        assertEquals(PuzzleState.FAILED, play.state)
        val review = play.review!!
        assertEquals(3, review.moves.size)
        assertEquals(4, review.positions.size)
        assertEquals(listOf("e4", "Kd8", "e5"), review.san)
        assertEquals(0, review.index)

        ctl.reviewStep(1)
        assertEquals(1, ctl.play.review!!.index)
        assertEquals(sq("e4"), ctl.play.review!!.lastMove!!.to)
        ctl.reviewStep(10)
        assertEquals(3, ctl.play.review!!.index)
        assertFalse(ctl.play.review!!.canForward)
        ctl.reviewStep(-10)
        assertEquals(0, ctl.play.review!!.index)

        // Ходи після показу ігноруються.
        ctl.tapMove("e2", "e4")
        assertEquals(PuzzleState.FAILED, ctl.play.state)
        assertEquals(1, progress.attempts.size)
        assertEquals(PuzzleState.FAILED, progress.attempts.single().result.state)
    }

    // --- запис спроби ---

    @Test
    fun attemptIsRecordedOnlyOnceAcrossNextAndBack() {
        val ctl = controller()
        ctl.startWith(theme = "back_rank_mate")
        ctl.tapMove("a1", "a8")
        ctl.backToMenu()
        assertEquals(1, progress.attempts.size)
        assertNull(ctl.state.value.play)
    }

    @Test
    fun leavingAfterWrongMoveRecordsFailedButCleanLeaveRecordsNothing() {
        val ctl = controller()
        ctl.startWith(theme = "fork")
        ctl.backToMenu() // чиста незавершена задача
        assertTrue(progress.attempts.isEmpty())

        ctl.startPuzzle()
        ctl.tapMove("e2", "e3") // помилка
        ctl.backToMenu()
        assertEquals(PuzzleState.FAILED, progress.attempts.single().result.state)
    }

    @Test
    fun nextPuzzleLoadsAnotherAndKeepsSetup() {
        val ctl = controller()
        ctl.startWith(theme = "back_rank_mate")
        ctl.tapMove("a1", "a8")
        ctl.nextPuzzle() // b вже зіграна, інших за темою немає
        assertEquals(MenuNotice.NO_NEW_PUZZLES, ctl.state.value.notice)
        assertEquals(ThemeId("back_rank_mate"), ctl.state.value.setup.theme)
    }

    @Test
    fun recordingFailureDoesNotCrashThePuzzle() {
        val errors = ArrayList<Throwable>()
        val failing = object : PuzzleProgressRepository by progress {
            override suspend fun recordAttempt(attempt: PuzzleAttempt) = throw IllegalStateException("disk full")
        }
        val content = InMemoryContentSource(ContentManifest(SchemaVersion(1, 0), emptyList()), listOf(b))
        val ctl = PuzzleController(
            DefaultPuzzleService(content, failing, rules, Random(1)), failing, rules,
            { ContentStatus.Ready(1, emptyList()) }, scope, replyDelayMs = 0, onError = { errors += it },
        ).also { it.start() }
        ctl.startPuzzle()
        ctl.tapMove("a1", "a8")
        assertTrue(ctl.play.over)
        assertEquals(1, errors.size)
    }

    // --- контент ---

    @Test
    fun unavailableContentBlocksStartAndSaysSo() {
        val problem = ContentProblem.UnsupportedSchema("manifest.json", "2.0")
        val ctl = controller(status = ContentStatus.Unavailable(problem))
        assertEquals(ContentAvailability.UNAVAILABLE, ctl.state.value.availability)
        ctl.startPuzzle()
        assertNull(ctl.state.value.play)
        assertFalse(ctl.state.value.loading)
    }

    @Test
    fun readyContentIsAvailable() {
        assertEquals(ContentAvailability.READY, controller().state.value.availability)
    }

    @Test
    fun unreadablePuzzleGivesNoticeInsteadOfCrash() {
        val broken = puzzle("x", "not a fen", listOf("e2e4"), setOf("fork"), 500)
        val ctl = controller(listOf(broken))
        ctl.startPuzzle()
        assertNull(ctl.state.value.play)
        assertEquals(MenuNotice.PUZZLE_UNREADABLE, ctl.state.value.notice)
        assertFalse(ctl.state.value.loading)
    }

    @Test
    fun themesCoverTheWholeCatalogInOrder() {
        val ctl = controller()
        assertEquals(20, ctl.themes.size)
        assertEquals(ThemeId("smothered_mate"), ctl.themes.first())
        assertEquals(ThemeId("endgame"), ctl.themes.last())
    }

    @Test
    fun parseResultSanity() {
        assertTrue(rules.parseFen(a.startFen.value) is ChessResult.Ok)
    }
}
