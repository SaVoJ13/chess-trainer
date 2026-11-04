// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.analysis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.chesstrainer.app.game.EngineProblem
import org.chesstrainer.core.engine.AnalysisEvent
import org.chesstrainer.core.engine.AnalysisUpdate
import org.chesstrainer.core.engine.EngineCapabilities
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.EngineState
import org.chesstrainer.core.engine.FakeEngineService
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.game.DefaultChessGameFactory
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логіка екрана аналізу на справжніх правилах (`ChesslibRules`) і `FakeEngineService`; без Android. */
class AnalysisControllerTest {
    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val mateInOne = "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1"
    private val whiteCheckmated = "R5k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1"

    private class Env(val rules: ChesslibRules, val fake: FakeEngineService, val controller: AnalysisController) {
        val state get() = controller.state.value
        val factory = DefaultChessGameFactory(rules)

        fun sq(name: String) = Square.parse(name)!!

        suspend fun await(predicate: (AnalysisState) -> Boolean): AnalysisState =
            withTimeout(5_000) { controller.state.first(predicate) }

        /** Пошук дійшов до рушія: запити зафіксовано. */
        suspend fun awaitRequests(count: Int) {
            withTimeout(5_000) { while (fake.analysisRequests.size < count) yield() }
        }

        suspend fun settle() = repeat(30) { yield() }

        fun tap(from: String, to: String) {
            controller.onSquareTap(sq(from))
            controller.onSquareTap(sq(to))
        }

        fun gameOf(vararg sans: String): ChessGame {
            var game = factory.new()
            for (san in sans) {
                val move = (rules.parseSan(game.position, san) as ChessResult.Ok).value
                game = (game.play(move) as ChessResult.Ok).value
            }
            return game
        }
    }

    /** Рушій, що `start()` повертає помилкою, доки `failStart`. */
    private class FlakyStartEngine(private val delegate: EngineService) : EngineService by delegate {
        @Volatile var failStart = true

        override suspend fun start(config: EngineConfig): ChessResult<Unit> =
            if (failStart) ChessResult.Err(ChessError.EngineUnavailable("test: cannot start")) else delegate.start(config)
    }

    private fun update(rank: Int, evaluation: Evaluation, vararg pv: String, depth: Int = 12) = AnalysisEvent.Update(
        AnalysisUpdate(depth, null, rank, evaluation, pv.map(::Uci), nodes = 1000, nps = 1000, timeMs = 10),
    )

    private fun scenario(
        capabilities: EngineCapabilities = FakeEngineService.DEFAULT_CAPABILITIES,
        wrap: (FakeEngineService) -> EngineService = { it },
        block: suspend Env.(FakeEngineService) -> Unit,
    ) = runBlocking {
        withTimeout(20_000) {
            val job = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + job)
            val rules = ChesslibRules()
            val fake = FakeEngineService(rules, capabilities)
            val controller = AnalysisController(rules, DefaultChessGameFactory(rules), wrap(fake), scope)
            val env = Env(rules, fake, controller)
            try {
                env.block(fake)
            } finally {
                controller.close()
                job.cancelAndJoin()
            }
        }
    }

    // --- FEN ---

    @Test
    fun validFenLoadsPositionAndStartsAnalysisAtFullStrength() = scenario { fake ->
        controller.openEmpty()
        controller.onFenTextChange(mateInOne)
        controller.loadFen()
        assertEquals(0, state.ply)
        assertEquals(mateInOne, state.position.fen.value)
        assertNull(state.fenProblem)
        awaitRequests(1)
        val request = fake.analysisRequests.single()
        assertEquals(mateInOne, request.startFen.value)
        assertTrue(request.moves.isEmpty())
        assertNull("аналіз без ліміту, до скасування", request.limit)
        assertEquals(1, request.multiPv)
    }

    @Test
    fun invalidFenReportsAProblemAndKeepsTheBoard() = scenario { fake ->
        controller.openEmpty()
        val before = state.position
        controller.onFenTextChange("це не FEN")
        controller.loadFen()
        assertEquals(FenProblem.INVALID, state.fenProblem)
        assertEquals(before, state.position)
        settle()
        assertTrue("рушій не запитано", fake.analysisRequests.isEmpty())
        controller.onFenTextChange("8/8/8/8/8/8/8/8 w - - 0 1")
        assertNull("редагування прибирає помилку", state.fenProblem)
        controller.loadFen()
        assertEquals("позиція без королів нелегальна", FenProblem.INVALID, state.fenProblem)
    }

    @Test
    fun emptyFenIsReportedSeparately() = scenario {
        controller.openEmpty()
        controller.onFenTextChange("   \n ")
        controller.loadFen()
        assertEquals(FenProblem.EMPTY, state.fenProblem)
    }

    @Test
    fun pastedFenWithLineBreaksAndExtraSpacesIsAccepted() = scenario {
        controller.openEmpty()
        controller.onFenTextChange("  rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR\n b   KQkq e3 0 1 ")
        controller.loadFen()
        assertNull(state.fenProblem)
        assertEquals(Color.BLACK, state.position.sideToMove)
    }

    @Test
    fun fourFieldFenIsAccepted() = scenario {
        controller.openEmpty()
        controller.onFenTextChange("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -")
        controller.loadFen()
        assertNull(state.fenProblem)
        assertEquals(start, state.position.fen.value)
    }

    @Test
    fun fenFieldFollowsThePositionOnTheBoard() = scenario {
        controller.openEmpty()
        tap("e2", "e4")
        assertEquals(state.position.fen.value, state.fenText)
        controller.previous()
        assertEquals(start, state.fenText)
    }

    @Test
    fun engineUpdatesDoNotTouchTheFenFieldOrItsError() = scenario { fake ->
        fake.onAnalyse {
            listOf(
                update(1, Evaluation.Centipawns(10), "e2e4", depth = 10),
                update(1, Evaluation.Centipawns(12), "e2e4", depth = 11),
                update(1, Evaluation.Centipawns(15), "e2e4", depth = 12),
                AnalysisEvent.Finished,
            )
        }
        controller.openEmpty()
        controller.onFenTextChange("це не FEN")
        controller.loadFen()
        assertEquals(FenProblem.INVALID, state.fenProblem)
        controller.onFenTextChange("rnbqkbnr/pppppppp/8/8/8 w")
        controller.startAnalysis()
        await { it.lines.firstOrNull()?.depth == 12 }
        assertEquals("текст, який вводить користувач, не скидається оновленнями оцінки", "rnbqkbnr/pppppppp/8/8/8 w", state.fenText)
        controller.loadFen()
        assertEquals(FenProblem.INVALID, state.fenProblem)
        controller.startAnalysis()
        await { it.lines.firstOrNull()?.depth == 12 }
        assertEquals("помилка FEN теж лишається, доки користувач не змінить текст", FenProblem.INVALID, state.fenProblem)
        assertEquals("rnbqkbnr/pppppppp/8/8/8 w", state.fenText)
    }

    // --- оцінка ---

    @Test
    fun engineLinesAppearWithEvaluationAndSanPvFromWhitesPointOfView() = scenario { fake ->
        fake.onAnalyse {
            listOf(update(1, Evaluation.Centipawns(35), "e2e4", "e7e5", "g1f3", depth = 14), AnalysisEvent.Finished)
        }
        controller.open(factory.new(), 0)
        val line = await { it.lines.isNotEmpty() }.lines.single()
        assertEquals(Evaluation.Centipawns(35), line.evaluation)
        assertEquals(14, line.depth)
        assertEquals("1. e4 e5 2. Nf3", line.pv)
    }

    @Test
    fun blackToMovePvStartsWithEllipsis() = scenario { fake ->
        fake.onAnalyse { listOf(update(1, Evaluation.Centipawns(-20), "e7e5", "g1f3", "b8c6"), AnalysisEvent.Finished) }
        controller.openEmpty()
        controller.onFenTextChange("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        controller.loadFen()
        assertEquals("1... e5 2. Nf3 Nc6", await { it.lines.isNotEmpty() }.lines.single().pv)
    }

    @Test
    fun mateInOneIsShownWithItsMove() = scenario { fake ->
        fake.onAnalyse { listOf(update(1, Evaluation.Mate(1), "a1a8"), AnalysisEvent.Finished) }
        controller.openEmpty()
        controller.onFenTextChange(mateInOne)
        controller.loadFen()
        val line = await { it.lines.isNotEmpty() }.lines.single()
        assertEquals(Evaluation.Mate(1), line.evaluation)
        assertEquals("1. Ra8#", line.pv)
    }

    @Test
    fun pvStopsAtAnIllegalMoveInsteadOfCrashing() = scenario { fake ->
        fake.onAnalyse { listOf(update(1, Evaluation.Centipawns(0), "e2e4", "e2e4", "g1f3"), AnalysisEvent.Finished) }
        controller.open(factory.new(), 0)
        assertEquals("1. e4", await { it.lines.isNotEmpty() }.lines.single().pv)
    }

    @Test
    fun multiPvLinesAreKeptInRankOrder() = scenario { fake ->
        fake.onAnalyse {
            listOf(
                update(2, Evaluation.Centipawns(10), "d2d4"),
                update(1, Evaluation.Centipawns(30), "e2e4"),
                update(3, Evaluation.Centipawns(5), "g1f3"),
                AnalysisEvent.Finished,
            )
        }
        controller.openEmpty()
        controller.setMultiPv(3)
        controller.startAnalysis()
        val s = await { it.lines.size == 3 }
        assertEquals(listOf(1, 2, 3), s.lines.map { it.rank })
        assertEquals(3, fake.analysisRequests.last().multiPv)
    }

    @Test
    fun multiPvIsClampedToWhatTheEngineSupports() = scenario(
        capabilities = FakeEngineService.DEFAULT_CAPABILITIES.copy(maxMultiPv = 2),
    ) { fake ->
        controller.openEmpty()
        controller.setMultiPv(3)
        controller.startAnalysis()
        awaitRequests(1)
        assertEquals(2, fake.analysisRequests.last().multiPv)
        assertEquals(2, state.multiPv)
        assertEquals(2, state.maxMultiPv)
    }

    @Test
    fun multiPvOutsideOneToThreeIsClamped() = scenario {
        controller.setMultiPv(0)
        assertEquals(1, state.multiPv)
        controller.setMultiPv(9)
        assertEquals(3, state.multiPv)
    }

    // --- ходи за обидві сторони ---

    @Test
    fun movesCanBePlayedForBothSidesAndTheEngineIsAskedAgain() = scenario { fake ->
        fake.holdSearches()
        controller.openEmpty()
        controller.startAnalysis()
        awaitRequests(1)
        tap("e2", "e4")
        awaitRequests(2)
        assertEquals(Color.BLACK, state.position.sideToMove)
        tap("e7", "e5")
        awaitRequests(3)
        assertEquals(2, state.ply)
        assertEquals(listOf("e2e4", "e7e5"), fake.analysisRequests.last().moves.map { it.value })
        assertEquals(start, fake.analysisRequests.last().startFen.value)
    }

    @Test
    fun onlyThePiecesOfTheSideToMoveCanBeSelected() = scenario {
        controller.openEmpty()
        controller.onSquareTap(sq("e7"))
        assertNull(state.selected)
        controller.onSquareTap(sq("e2"))
        assertEquals(setOf(sq("e3"), sq("e4")), state.targets)
        assertTrue(controller.onDragStart(sq("g1")))
        assertFalse(controller.onDragStart(sq("g8")))
    }

    @Test
    fun dragAndDropPlaysAMove() = scenario {
        controller.openEmpty()
        assertTrue(controller.onDragStart(sq("g1")))
        controller.onDrop(sq("g1"), sq("f3"))
        assertEquals(1, state.ply)
        assertEquals("Nf3", state.game.moves.single().san)
    }

    @Test
    fun goingBackAndPlayingADifferentMoveReplacesTheRestOfTheLine() = scenario {
        controller.openEmpty()
        tap("e2", "e4")
        tap("e7", "e5")
        tap("g1", "f3")
        controller.goTo(1)
        assertEquals(Color.BLACK, state.position.sideToMove)
        tap("c7", "c5")
        assertEquals(2, state.ply)
        assertEquals(2, state.plyCount)
        assertEquals(listOf("e4", "c5"), state.game.moves.map { it.san })
    }

    @Test
    fun playingTheNextMoveOfTheLineJustAdvancesWithoutLosingTheTail() = scenario {
        controller.openEmpty()
        tap("e2", "e4")
        tap("e7", "e5")
        controller.first()
        tap("e2", "e4")
        assertEquals(1, state.ply)
        assertEquals("хвіст лінії збережено", 2, state.plyCount)
        assertTrue(state.canGoForward)
    }

    @Test
    fun navigationStopsAtTheEnds() = scenario {
        controller.openEmpty()
        tap("e2", "e4")
        controller.previous()
        controller.previous()
        assertEquals(0, state.ply)
        assertFalse(state.canGoBack)
        controller.last()
        controller.next()
        assertEquals(1, state.ply)
        assertFalse(state.canGoForward)
    }

    @Test
    fun promotionAsksForAPieceAndThenPlaysIt() = scenario {
        controller.openEmpty()
        controller.onFenTextChange("8/P7/8/8/8/8/k6K/8 w - - 0 1")
        controller.loadFen()
        tap("a7", "a8")
        assertNotNull(state.pendingPromotion)
        assertEquals(0, state.ply)
        controller.choosePromotion(PieceType.QUEEN)
        assertNull(state.pendingPromotion)
        assertEquals("a8=Q+", state.game.moves.single().san)
    }

    @Test
    fun cancelledPromotionLeavesTheBoardAlone() = scenario {
        controller.openEmpty()
        controller.onFenTextChange("8/P7/8/8/8/8/k6K/8 w - - 0 1")
        controller.loadFen()
        tap("a7", "a8")
        controller.cancelPromotion()
        assertNull(state.pendingPromotion)
        assertEquals(0, state.ply)
    }

    // --- запуск і зупинка ---

    @Test
    fun stopCancelsTheSearchKeepsTheLinesAndDoesNotRestartOnMoves() = scenario { fake ->
        fake.onAnalyse { listOf(update(1, Evaluation.Centipawns(20), "e2e4")) }
        controller.open(factory.new(), 0)
        await { it.lines.isNotEmpty() }
        assertTrue(state.running)
        controller.stopAnalysis()
        assertEquals(AnalysisEngine.Idle, state.engine)
        assertEquals("останні лінії лишаються", 1, state.lines.size)
        val requests = fake.analysisRequests.size
        tap("e2", "e4")
        settle()
        assertEquals("після зупинки хід не запускає аналіз", requests, fake.analysisRequests.size)
        assertTrue("лінії застаріли й прибрані", state.lines.isEmpty())
        controller.startAnalysis()
        awaitRequests(requests + 1)
    }

    @Test
    fun checkmatePositionIsNotSentToTheEngine() = scenario { fake ->
        controller.openEmpty()
        controller.onFenTextChange(whiteCheckmated)
        controller.loadFen()
        assertEquals(PositionVerdict.Checkmate(winner = Color.WHITE), state.verdict)
        assertFalse(state.canAnalyse)
        controller.startAnalysis()
        settle()
        assertTrue(fake.analysisRequests.isEmpty())
        assertEquals(AnalysisEngine.Idle, state.engine)
    }

    @Test
    fun stalemateAndBareKingsAreRecognisedWithoutTheEngine() = scenario { fake ->
        controller.openEmpty()
        controller.onFenTextChange("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        controller.loadFen()
        assertEquals(PositionVerdict.Stalemate, state.verdict)
        controller.onFenTextChange("8/8/4k3/8/8/3K4/8/8 w - - 0 1")
        controller.loadFen()
        assertEquals(PositionVerdict.InsufficientMaterial, state.verdict)
        settle()
        assertTrue(fake.analysisRequests.isEmpty())
    }

    @Test
    fun goingBackFromACheckmateAllowsAnalysisAgain() = scenario { fake ->
        val game = gameOf("f3", "e5", "g4", "Qh4#")
        controller.open(game, game.moves.size)
        assertEquals(PositionVerdict.Checkmate(winner = Color.BLACK), state.verdict)
        settle()
        assertTrue(fake.analysisRequests.isEmpty())
        controller.previous()
        assertEquals(PositionVerdict.Playable, state.verdict)
        controller.startAnalysis()
        awaitRequests(1)
        assertEquals(listOf("f2f3", "e7e5", "g2g4"), fake.analysisRequests.single().moves.map { it.value })
    }

    // --- відкриття партії ---

    @Test
    fun openingASavedGameShowsThePositionAtTheRequestedPly() = scenario { fake ->
        val game = gameOf("e4", "e5", "Nf3", "Nc6")
        controller.open(game, 3)
        assertEquals(3, state.ply)
        assertEquals(4, state.plyCount)
        assertEquals(game.positionAt(3), state.position)
        awaitRequests(1)
        assertEquals(listOf("e2e4", "e7e5", "g1f3"), fake.analysisRequests.single().moves.map { it.value })
        controller.next()
        assertEquals(4, state.ply)
        awaitRequests(2)
        assertEquals(4, fake.analysisRequests.last().moves.size)
    }

    // --- помилки рушія ---

    @Test
    fun engineThatCannotStartShowsAProblemAndCanBeRetried() {
        lateinit var flaky: FlakyStartEngine
        scenario(wrap = { fake -> FlakyStartEngine(fake).also { flaky = it } }) { _ ->
            controller.open(factory.new(), 0)
            val problem = await { it.engine is AnalysisEngine.Problem }.engine as AnalysisEngine.Problem
            assertEquals(EngineProblem.UNAVAILABLE, problem.kind)
            flaky.failStart = false
            controller.startAnalysis()
            await { it.lines.isNotEmpty() }
        }
    }

    @Test
    fun engineCrashDuringAnalysisShowsAProblem() = scenario { fake ->
        fake.onAnalyse { listOf(AnalysisEvent.Failed(ChessError.EngineCrashed("boom"))) }
        controller.open(factory.new(), 0)
        val problem = await { it.engine is AnalysisEngine.Problem }.engine as AnalysisEngine.Problem
        assertEquals(EngineProblem.CRASHED, problem.kind)
    }

    // --- життєвий цикл (CONTRACTS 3.4) ---

    @Test
    fun backgroundStopsTheEngineAndForegroundResumesTheAnalysis() = scenario { fake ->
        fake.holdSearches()
        controller.open(factory.new(), 0)
        awaitRequests(1)
        controller.onBackground()
        withTimeout(5_000) { while (fake.state.value != EngineState.Stopped) yield() }
        assertEquals(AnalysisEngine.Idle, state.engine)
        controller.onForeground()
        awaitRequests(2)
        await { it.running }
    }

    @Test
    fun backgroundDoesNotRestartAnAnalysisTheUserStopped() = scenario { fake ->
        controller.open(factory.new(), 0)
        awaitRequests(1)
        controller.stopAnalysis()
        controller.onBackground()
        controller.onForeground()
        settle()
        assertEquals(1, fake.analysisRequests.size)
    }

    @Test
    fun trimMemoryStopsTheEngineAndDoesNotResumeByItself() = scenario { fake ->
        fake.holdSearches()
        controller.open(factory.new(), 0)
        awaitRequests(1)
        controller.onTrimMemory()
        withTimeout(5_000) { while (fake.state.value != EngineState.Stopped) yield() }
        controller.onForeground()
        settle()
        assertEquals(1, fake.analysisRequests.size)
        assertFalse(state.running)
    }

    @Test
    fun closeStopsTheEngineAndTheClosedBoardDoesNotWakeUp() = scenario { fake ->
        fake.holdSearches()
        controller.open(factory.new(), 0)
        awaitRequests(1)
        controller.close()
        assertEquals(EngineState.Stopped, fake.state.value)
        assertEquals(AnalysisEngine.Idle, state.engine)
        controller.onForeground()
        settle()
        assertEquals(1, fake.analysisRequests.size)
    }

    @Test
    fun staleEventsFromAnOldSearchAreIgnoredAfterThePositionChanged() = scenario { fake ->
        fake.onAnalyse { request ->
            val evaluation = if (request.moves.isEmpty()) 11 else 22
            listOf(update(1, Evaluation.Centipawns(evaluation), "e7e5"), AnalysisEvent.Finished)
        }
        controller.openEmpty()
        controller.startAnalysis()
        tap("e2", "e4")
        val s = await { it.lines.firstOrNull()?.evaluation == Evaluation.Centipawns(22) }
        assertEquals(1, s.lines.size)
    }
}
