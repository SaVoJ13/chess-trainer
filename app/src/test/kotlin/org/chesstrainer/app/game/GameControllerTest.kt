// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.EngineMove
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.EngineState
import org.chesstrainer.core.engine.FakeEngineService
import org.chesstrainer.core.engine.Strength
import org.chesstrainer.core.game.AutoDrawReason
import org.chesstrainer.core.game.ChessGameFactory
import org.chesstrainer.core.game.ClaimReason
import org.chesstrainer.core.game.DefaultChessGameFactory
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логіка екрана гри на справжніх правилах (`ChesslibRules`) і `FakeEngineService`; без Android. */
class GameControllerTest {

    private class Env(
        val rules: ChesslibRules,
        val fake: FakeEngineService,
        val controller: GameController,
    ) {
        val state get() = controller.state.value

        fun sq(name: String) = Square.parse(name)!!

        /** Усі корутини контролера й фейка виконуються в потоці `runBlocking`: кілька `yield` вичерпують чергу. */
        suspend fun settle() = repeat(30) { yield() }

        suspend fun await(predicate: (GameUiState) -> Boolean): GameUiState =
            withTimeout(5_000) { controller.state.first(predicate) }

        /** Рушій думає й пошук справді дійшов до `bestMove` (а не стоїть у черзі на `start()`). */
        suspend fun awaitSearching() {
            await { it.engine == EngineStatus.Thinking }
            withTimeout(5_000) { while (fake.moveRequests.isEmpty()) yield() }
        }

        fun tap(from: String, to: String) {
            controller.onSquareTap(sq(from))
            controller.onSquareTap(sq(to))
        }
    }

    /** Рушій, що перший `start()` повертає помилкою. */
    private class FlakyStartEngine(private val delegate: EngineService) : EngineService by delegate {
        var failStart = true
        override suspend fun start(config: EngineConfig): ChessResult<Unit> =
            if (failStart) ChessResult.Err(ChessError.EngineUnavailable("test: cannot start")) else delegate.start(config)
    }

    private fun factoryFor(rules: ChesslibRules, fen: String?): ChessGameFactory {
        val base = DefaultChessGameFactory(rules)
        return object : ChessGameFactory by base {
            override fun new(start: Position?) = base.new(start ?: fen?.let { (rules.parseFen(it) as ChessResult.Ok).value })
        }
    }

    private fun scenario(
        fen: String? = null,
        color: Color = Color.WHITE,
        wrap: (FakeEngineService) -> EngineService = { it },
        autoStart: Boolean = true,
        block: suspend Env.(FakeEngineService) -> Unit,
    ) = runBlocking {
        withTimeout(20_000) {
            val job = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + job)
            val rules = ChesslibRules()
            val fake = FakeEngineService(rules)
            val controller = GameController(
                rules = rules,
                games = factoryFor(rules, fen),
                engine = wrap(fake),
                scope = scope,
                levelFor = { caps -> fixedLevel(caps).copy(minReplyDelayMs = 0) },
                playerColor = color,
            )
            val env = Env(rules, fake, controller)
            try {
                if (autoStart) controller.start()
                env.block(fake)
            } finally {
                controller.shutdown()
                job.cancelAndJoin()
            }
        }
    }

    // --- вибір фігури й легальні ходи ---

    @Test
    fun selectingOwnPieceShowsItsLegalTargets() = scenario {
        controller.onSquareTap(sq("e2"))
        assertEquals(sq("e2"), state.selected)
        assertEquals(setOf(sq("e3"), sq("e4")), state.targets)
    }

    @Test
    fun knightTargetsComeFromRules() = scenario {
        controller.onSquareTap(sq("g1"))
        assertEquals(setOf(sq("f3"), sq("h3")), state.targets)
    }

    @Test
    fun tappingOpponentPieceOrEmptySquareClearsSelection() = scenario {
        controller.onSquareTap(sq("e2"))
        controller.onSquareTap(sq("e7"))
        assertNull(state.selected)
        assertTrue(state.targets.isEmpty())

        controller.onSquareTap(sq("e2"))
        controller.onSquareTap(sq("a5"))
        assertNull(state.selected)
    }

    @Test
    fun tappingSelectedPieceAgainDeselectsIt() = scenario {
        controller.onSquareTap(sq("e2"))
        controller.onSquareTap(sq("e2"))
        assertNull(state.selected)
    }

    @Test
    fun tappingAnotherOwnPieceMovesSelection() = scenario {
        controller.onSquareTap(sq("e2"))
        controller.onSquareTap(sq("g1"))
        assertEquals(sq("g1"), state.selected)
    }

    @Test
    fun illegalDestinationDoesNotMove() = scenario {
        controller.onSquareTap(sq("e2"))
        controller.onSquareTap(sq("e5"))
        assertEquals(0, state.game.moves.size)
        assertNull(state.selected)
    }

    @Test
    fun humanMoveIsPlayedAndEngineReplies() = scenario { fake ->
        tap("e2", "e4")
        val after = await { it.game.moves.size == 2 && it.engine == EngineStatus.Idle }
        assertEquals(listOf("e4"), listOf(after.game.moves[0].san))
        assertEquals(Color.WHITE, after.position.sideToMove)
        assertEquals(after.game.moves[1].move, after.lastMove)
        assertEquals(1, fake.moveRequests.size)
        assertEquals(listOf(Uci("e2e4")), fake.moveRequests.single().moves)
        // Поки що фіксований рівень: найслабший UCI_Elo з capabilities (TODO(difficulty) у FixedLevel).
        assertEquals(Strength.Elo(FakeEngineService.DEFAULT_CAPABILITIES.eloRange!!.first), fake.moveRequests.single().level.strength)
    }

    @Test
    fun humanCannotMoveWhileEngineThinking() = scenario { fake ->
        fake.holdSearches()
        tap("e2", "e4")
        awaitSearching()
        controller.onSquareTap(sq("d2"))
        assertNull(state.selected)
        assertFalse(controller.onDragStart(sq("d2")))
        fake.release()
        await { it.game.moves.size == 2 }
    }

    // --- перетягування ---

    @Test
    fun dragAndDropPlaysLegalMove() = scenario {
        assertTrue(controller.onDragStart(sq("g1")))
        assertEquals(sq("g1"), state.selected)
        controller.onDrop(sq("g1"), sq("f3"))
        await { it.game.moves.isNotEmpty() }
        assertEquals("Nf3", state.game.moves[0].san)
    }

    @Test
    fun dropOnIllegalSquareKeepsPieceSelected() = scenario {
        controller.onDragStart(sq("g1"))
        controller.onDrop(sq("g1"), sq("g5"))
        assertEquals(0, state.game.moves.size)
        assertEquals(sq("g1"), state.selected)
        controller.onDrop(sq("g1"), null)
        assertEquals(sq("g1"), state.selected)
    }

    @Test
    fun dragCannotStartFromOpponentPieceOrEmptySquare() = scenario {
        assertFalse(controller.onDragStart(sq("e7")))
        assertFalse(controller.onDragStart(sq("e4")))
    }

    // --- перетворення пішака ---

    private val promotionFen = "8/P6k/8/8/8/8/8/K7 w - - 0 1"

    @Test
    fun promotionAsksForPieceAndPlaysChosenOne() = scenario(promotionFen) {
        tap("a7", "a8")
        assertEquals(PendingPromotion(sq("a7"), sq("a8")), state.pendingPromotion)
        assertEquals(0, state.game.moves.size)

        controller.choosePromotion(PieceType.KNIGHT)
        val after = await { it.game.moves.isNotEmpty() }
        assertNull(after.pendingPromotion)
        assertEquals(PieceType.KNIGHT, after.game.moves[0].move.promotion)
    }

    @Test
    fun cancellingPromotionLeavesPositionUntouched() = scenario(promotionFen) {
        tap("a7", "a8")
        controller.cancelPromotion()
        assertNull(state.pendingPromotion)
        assertEquals(0, state.game.moves.size)
        assertEquals(Color.WHITE, state.position.sideToMove)
    }

    @Test
    fun boardIsLockedWhilePromotionPending() = scenario(promotionFen) {
        tap("a7", "a8")
        controller.onSquareTap(sq("a1"))
        assertEquals(sq("a7"), state.selected)
        assertEquals(PendingPromotion(sq("a7"), sq("a8")), state.pendingPromotion)
    }

    // --- скасування ходу ---

    @Test
    fun undoRevertsHumanMoveAndEngineReply() = scenario {
        tap("e2", "e4")
        await { it.game.moves.size == 2 && it.engine == EngineStatus.Idle }
        assertTrue(state.canUndo)

        controller.undo()
        assertEquals(0, state.game.moves.size)
        assertTrue(state.humanToMove)
        assertFalse(state.canUndo)
    }

    @Test
    fun undoWhileEngineThinkingCancelsSearchAndIgnoresLateReply() = scenario { fake ->
        fake.holdSearches()
        tap("e2", "e4")
        awaitSearching()

        controller.undo()
        assertEquals(0, state.game.moves.size)
        assertEquals(EngineStatus.Idle, state.engine)
        assertTrue(state.humanToMove)

        fake.release()
        settle()
        assertEquals(0, state.game.moves.size)
    }

    @Test
    fun undoIsUnavailableAtStartAndAfterGameEnds() = scenario("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1") {
        assertFalse(state.canUndo)
        tap("a1", "a8")
        assertTrue(state.isOver)
        assertFalse(state.canUndo)
    }

    @Test
    fun undoIsNotOfferedForEngineFirstMoveWhenPlayingBlack() = scenario(color = Color.BLACK) {
        await { it.game.moves.size == 1 && it.engine == EngineStatus.Idle }
        assertFalse(state.canUndo)
        controller.undo()
        assertEquals(1, state.game.moves.size)
    }

    // --- чорні, нова партія, переворот ---

    @Test
    fun engineMovesFirstWhenHumanPlaysBlack() = scenario(color = Color.BLACK) {
        val after = await { it.game.moves.size == 1 }
        assertEquals(Color.BLACK, after.position.sideToMove)
        assertEquals(Color.BLACK, after.bottomColor)
        assertTrue(after.humanToMove)
    }

    @Test
    fun newGameResetsPositionAndSwitchesColor() = scenario { fake ->
        tap("e2", "e4")
        await { it.game.moves.size == 2 }

        controller.newGame(Color.BLACK)
        val after = await { it.game.moves.size == 1 && it.playerColor == Color.BLACK }
        assertNull(after.endedBy)
        assertFalse(after.flipped)
        assertEquals(EngineState.Ready, fake.state.value)
    }

    @Test
    fun flipBoardTogglesBottomColor() = scenario {
        assertEquals(Color.WHITE, state.bottomColor)
        controller.flipBoard()
        assertEquals(Color.BLACK, state.bottomColor)
        controller.flipBoard()
        assertEquals(Color.WHITE, state.bottomColor)
    }

    // --- завершення партії ---

    @Test
    fun resignEndsGameAndBlocksMoves() = scenario {
        controller.resign()
        assertEquals(GameOutcome.Resigned(Color.BLACK), state.outcome)
        assertEquals(GameTermination.RESIGNATION, state.outcome!!.termination)
        controller.onSquareTap(sq("e2"))
        assertNull(state.selected)
    }

    @Test
    fun resignWhileEngineThinkingCancelsItsMove() = scenario { fake ->
        fake.holdSearches()
        tap("e2", "e4")
        awaitSearching()
        controller.resign()
        fake.release()
        settle()
        assertEquals(1, state.game.moves.size)
        assertTrue(state.isOver)
    }

    @Test
    fun checkmateEndsGameWithoutAskingEngine() = scenario("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1") { fake ->
        tap("a1", "a8")
        assertEquals(GameOutcome.Checkmate(Color.WHITE), state.outcome)
        assertEquals(GameTermination.CHECKMATE, state.outcome!!.termination)
        settle()
        assertTrue(fake.moveRequests.isEmpty())
    }

    @Test
    fun stalemateIsReported() = scenario("k7/2K5/8/8/8/8/8/1Q6 w - - 0 1") {
        tap("b1", "b6")
        assertEquals(GameOutcome.Stalemate, state.outcome)
    }

    @Test
    fun insufficientMaterialIsReported() = scenario("7k/8/8/8/8/8/1n6/K1B5 w - - 0 1") {
        tap("c1", "b2")
        assertEquals(GameOutcome.InsufficientMaterial, state.outcome)
    }

    @Test
    fun seventyFiveMoveRuleIsAutomaticDraw() = scenario("8/8/8/4k3/8/8/4K3/R7 w - - 149 80") {
        tap("a1", "a2")
        assertEquals(GameOutcome.DrawAutomatic(AutoDrawReason.SEVENTY_FIVE_MOVE_RULE), state.outcome)
        assertEquals(GameTermination.DRAW_AUTOMATIC, state.outcome!!.termination)
    }

    @Test
    fun kingInCheckIsMarked() = scenario("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1") {
        assertEquals(sq("e1"), state.checkSquare)
    }

    // --- нічия за вимогою (CONTRACTS 5) ---

    private val fiftyMoveFen = "8/8/8/4k3/8/8/4K3/R7 w - - 100 80"

    @Test
    fun claimBarAppearsOnHumanTurnAndClaimEndsGame() = scenario(fiftyMoveFen) { fake ->
        assertEquals(setOf(ClaimReason.FIFTY_MOVE_RULE), state.claimReasons)

        controller.claimDraw()
        assertEquals(GameOutcome.DrawClaimed, state.outcome)
        assertEquals(GameTermination.DRAW_CLAIMED, state.outcome!!.termination)
        assertTrue(state.claimReasons.isEmpty())
        settle()
        assertTrue(fake.moveRequests.isEmpty())
    }

    @Test
    fun claimBarIsHiddenWhileEngineToMove() = scenario(fiftyMoveFen) { fake ->
        fake.holdSearches()
        tap("a1", "a2")
        awaitSearching()
        assertTrue(state.game.status is org.chesstrainer.core.game.GameStatus.DrawClaimable)
        assertTrue(state.claimReasons.isEmpty())

        controller.claimDraw()
        assertFalse(state.isOver)
        fake.release()
    }

    @Test
    fun continueHidesBarUntilNextOccurrence() = scenario(fiftyMoveFen) {
        controller.dismissClaim()
        assertTrue(state.claimReasons.isEmpty())
        assertFalse(state.isOver)

        tap("a1", "a2")
        await { it.game.moves.size == 2 && it.engine == EngineStatus.Idle }
        assertEquals(setOf(ClaimReason.FIFTY_MOVE_RULE), state.claimReasons)
    }

    @Test
    fun noClaimBarInOrdinaryGame() = scenario {
        assertTrue(state.claimReasons.isEmpty())
        controller.claimDraw()
        assertFalse(state.isOver)
    }

    // --- рушій: життєвий цикл і помилки ---

    @Test
    fun engineStartsOnEnteringScreenWithoutAMove() = scenario { fake ->
        val ready = await { it.engine == EngineStatus.Idle }
        assertEquals(EngineStatus.Idle, ready.engine)
        assertEquals(EngineState.Ready, fake.state.value)
        assertEquals(1, fake.appliedConfigs.size)
    }

    @Test
    fun shutdownStopsEngine() = scenario { fake ->
        await { it.engine == EngineStatus.Idle }
        controller.shutdown()
        assertEquals(EngineState.Stopped, fake.state.value)
    }

    @Test
    fun engineFailingToStartShowsProblemAndRetryRecovers() {
        lateinit var flaky: FlakyStartEngine
        scenario(wrap = { FlakyStartEngine(it).also { engine -> flaky = engine } }) { fake ->
            val failed = await { it.engine is EngineStatus.Problem }
            assertEquals(EngineStatus.Problem(EngineProblem.UNAVAILABLE), failed.engine)
            // Партія жива: людина може ходити, застосунок не падає.
            tap("e2", "e4")
            await { it.engine is EngineStatus.Problem && it.game.moves.size == 1 }

            flaky.failStart = false
            controller.retryEngine()
            val recovered = await { it.game.moves.size == 2 && it.engine == EngineStatus.Idle }
            assertEquals(EngineState.Ready, fake.state.value)
            assertEquals(2, recovered.game.moves.size)
        }
    }

    @Test
    fun unavailableEngineDuringSearchShowsProblemAndRetryResumes() = scenario { fake ->
        fake.onBestMove { ChessResult.Err(ChessError.EngineUnavailable("test timeout")) }
        tap("e2", "e4")
        val failed = await { it.engine is EngineStatus.Problem }
        assertEquals(EngineStatus.Problem(EngineProblem.UNAVAILABLE), failed.engine)
        assertEquals(1, failed.game.moves.size)

        fake.onBestMove { ChessResult.Ok(EngineMove(Uci("e7e5"), null)) }
        controller.retryEngine()
        val after = await { it.game.moves.size == 2 }
        assertEquals("e5", after.game.moves[1].san)
        assertEquals(EngineStatus.Idle, await { it.engine == EngineStatus.Idle }.engine)
    }

    @Test
    fun engineCrashDuringSearchShowsCrashedProblem() = scenario { fake ->
        fake.holdSearches()
        tap("e2", "e4")
        awaitSearching()
        fake.crash("test crash")
        val failed = await { it.engine is EngineStatus.Problem }
        assertEquals(EngineStatus.Problem(EngineProblem.CRASHED), failed.engine)

        fake.release()
        controller.retryEngine()
        await { it.game.moves.size == 2 }
    }

    @Test
    fun illegalEngineMoveIsReportedNotApplied() = scenario { fake ->
        fake.onBestMove { ChessResult.Ok(EngineMove(Uci("a1a8"), null)) }
        tap("e2", "e4")
        val failed = await { it.engine is EngineStatus.Problem }
        assertEquals(EngineStatus.Problem(EngineProblem.UNEXPECTED), failed.engine)
        assertEquals(1, failed.game.moves.size)
    }

    @Test
    fun undoAfterEngineProblemReturnsMoveToHuman() = scenario { fake ->
        fake.onBestMove { ChessResult.Err(ChessError.EngineCrashed("test")) }
        tap("e2", "e4")
        await { it.engine is EngineStatus.Problem }
        controller.undo()
        assertEquals(0, state.game.moves.size)
        assertTrue(state.humanToMove)
    }

    @Test
    fun backgroundStopsEngineAndForegroundResumesSearch() = scenario { fake ->
        fake.holdSearches()
        tap("e2", "e4")
        awaitSearching()

        controller.onBackground()
        assertEquals(EngineStatus.Idle, state.engine)
        settle()
        assertEquals(EngineState.Stopped, fake.state.value)
        assertEquals(1, state.game.moves.size)

        controller.onForeground()
        awaitSearching()
        fake.release()
        await { it.game.moves.size == 2 }
        assertEquals(EngineState.Ready, fake.state.value)
    }

    @Test
    fun foregroundWithoutBackgroundDoesNothing() = scenario { fake ->
        await { it.engine == EngineStatus.Idle }
        controller.onForeground()
        assertEquals(1, fake.appliedConfigs.size)
    }

    @Test
    fun trimMemoryStopsIdleEngineAndNextMoveRestartsIt() = scenario { fake ->
        await { it.engine == EngineStatus.Idle }
        controller.onTrimMemory()
        settle()
        assertEquals(EngineState.Stopped, fake.state.value)

        tap("e2", "e4")
        await { it.game.moves.size == 2 }
        assertEquals(EngineState.Ready, fake.state.value)
        assertNotNull(fake.capabilities)
    }

    @Test
    fun trimMemoryDoesNotInterruptActiveSearch() = scenario { fake ->
        fake.holdSearches()
        tap("e2", "e4")
        awaitSearching()
        controller.onTrimMemory()
        settle()
        assertEquals(EngineStatus.Thinking, state.engine)
        fake.release()
        await { it.game.moves.size == 2 }
    }
}
