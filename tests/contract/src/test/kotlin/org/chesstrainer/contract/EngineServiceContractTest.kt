// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.contract

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.chesstrainer.core.engine.AnalysisEvent
import org.chesstrainer.core.engine.AnalysisRequest
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.EngineMove
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.EngineState
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.engine.Strength
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Одна реалізація під тестом. «Довгий» пошук для перевірок скасування: Fake тримає його через [hold],
 * справжній рушій виконує нескінченний аналіз (`limit = null`), тож для нього [hold]/[release] порожні.
 */
interface EngineHarness {
    val engine: EngineService

    /** true: «сира» реалізація, що відхиляє `Strength.Handicap` як порушення контракту (CONTRACTS 3.5). */
    val isRaw: Boolean

    /** true: [crash] імітує падіння рушія (Fake). Для справжнього рушія падіння перевіряє сценарій `crash` на пристрої. */
    val canCrash: Boolean get() = false

    fun hold() {}
    fun release() {}
    fun crash() {}
}

/**
 * Контрактні тести `EngineService` (CONTRACTS 3.4, v0.6–v0.20). Реалізацію постачає підклас через [newHarness].
 * Кожен тест виконується з таймаутом [TIMEOUT_MS]: зависання виклику стає провалом, а не безкінечним очікуванням.
 * Ідентифікатори `C-nn` потрапляють у `tests/PLAN.md`, розд. 3.
 */
abstract class EngineServiceContractTest {
    protected abstract fun newHarness(): EngineHarness

    private lateinit var h: EngineHarness
    private val engine get() = h.engine
    private val rules = ChesslibRules()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val startFen = Fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    private val matedFen = Fen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
    private val stalematedFen = Fen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
    private val full = DifficultyLevel(DifficultyId(1), Strength.Full, SearchLimit(depth = 4), 0, null, DifficultyGroup.MAX)

    private fun move(fen: Fen = startFen, moves: List<Uci> = emptyList(), level: DifficultyLevel = full) = MoveRequest(fen, moves, level)

    private fun analysis(fen: Fen = startFen, moves: List<Uci> = emptyList(), limit: SearchLimit? = SearchLimit(depth = 4), multiPv: Int = 1) =
        AnalysisRequest(fen, moves, limit, multiPv)

    private fun err(r: ChessResult<EngineMove>) = (r as ChessResult.Err).error

    private fun isLegal(m: Uci, fen: Fen = startFen, moves: List<Uci> = emptyList()): Boolean {
        var p = (rules.parseFen(fen.value) as ChessResult.Ok).value
        for (u in moves) p = (rules.apply(p, (rules.parseUci(p, u.value) as ChessResult.Ok).value) as ChessResult.Ok).value
        return rules.parseUci(p, m.value) is ChessResult.Ok
    }

    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(TIMEOUT_MS) { block() } }

    private suspend fun ready() {
        assertEquals(ChessResult.Ok(Unit), engine.start())
        assertEquals(EngineState.Ready, engine.state.value)
    }

    /** Довгий пошук: чекає, поки рушій справді зайнятий. Повертає події, коли пошук закінчено. */
    private suspend fun longAnalyse(): Deferred<List<AnalysisEvent>> {
        h.hold()
        val job = scope.async { engine.analyse(analysis(limit = null)).toList() }
        withTimeout(WAIT_MS) { engine.state.first { it == EngineState.Busy } }
        return job
    }

    @Before
    fun setUp() {
        h = newHarness()
    }

    @After
    fun tearDown() {
        runBlocking {
            h.release()
            engine.stop()
        }
    }

    // --- C-01 … C-05: життєвий цикл ---

    @Test
    fun `C-01 capabilities are null before first start and set after`() = run {
        assertNull(engine.capabilities)
        assertEquals(EngineState.Stopped, engine.state.value)
        ready()
        val c = engine.capabilities
        assertNotNull(c)
        assertTrue(c!!.name.isNotEmpty() && c.maxMultiPv >= 1)
    }

    @Test
    fun `C-02 repeated start in Ready is Ok and keeps the engine Ready`() = run {
        ready()
        assertEquals(ChessResult.Ok(Unit), engine.start())
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `C-03 concurrent start calls return the same successful result`() = run {
        val results = listOf(scope.async { engine.start() }, scope.async { engine.start() }, scope.async { engine.start() }).awaitAll()
        assertTrue(results.toString(), results.all { it == ChessResult.Ok(Unit) })
        assertEquals(EngineState.Ready, engine.state.value)
        assertTrue(engine.bestMove(move()) is ChessResult.Ok)
    }

    @Test
    fun `C-04 requests before start are EngineUnavailable and do not start the engine`() = run {
        assertTrue(err(engine.bestMove(move())) is ChessError.EngineUnavailable)
        val events = engine.analyse(analysis()).toList()
        assertEquals(1, events.size)
        assertTrue(events.toString(), (events[0] as AnalysisEvent.Failed).error is ChessError.EngineUnavailable)
        assertEquals(EngineState.Stopped, engine.state.value)
    }

    @Test
    fun `C-05 stop then start again gives a working engine`() = run {
        ready()
        engine.stop()
        assertEquals(EngineState.Stopped, engine.state.value)
        assertTrue(err(engine.bestMove(move())) is ChessError.EngineUnavailable)
        ready()
        assertTrue(engine.bestMove(move()) is ChessResult.Ok)
    }

    // --- C-06 … C-10: запити ---

    @Test
    fun `C-06 bestMove returns a legal move and the engine goes back to Ready`() = run {
        ready()
        val r = engine.bestMove(move())
        assertTrue(r.toString(), r is ChessResult.Ok && isLegal(r.value.best))
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `C-07 bestMove replays the moves list`() = run {
        ready()
        val moves = listOf(Uci("e2e4"), Uci("e7e5"), Uci("g1f3"))
        val r = engine.bestMove(move(moves = moves))
        assertTrue(r.toString(), r is ChessResult.Ok && isLegal(r.value.best, moves = moves))
    }

    @Test
    fun `C-08 positions without legal moves give NoLegalMoves and keep the engine Ready`() = run {
        ready()
        for (fen in listOf(matedFen, stalematedFen)) {
            assertEquals(fen.value, ChessResult.Err(ChessError.NoLegalMoves), engine.bestMove(move(fen)))
            assertEquals(fen.value, listOf<AnalysisEvent>(AnalysisEvent.Failed(ChessError.NoLegalMoves)), engine.analyse(analysis(fen)).toList())
            assertEquals(EngineState.Ready, engine.state.value)
        }
    }

    @Test
    fun `C-09 invalid FEN and illegal move are request errors and keep the engine Ready`() = run {
        ready()
        assertTrue(err(engine.bestMove(move(Fen("not a fen")))) is ChessError.InvalidFen)
        assertTrue(err(engine.bestMove(move(moves = listOf(Uci("e2e5"))))) is ChessError.IllegalMove)
        val a1 = engine.analyse(analysis(Fen("not a fen"))).toList()
        val a2 = engine.analyse(analysis(moves = listOf(Uci("e2e5")))).toList()
        assertTrue(a1.toString(), a1.size == 1 && (a1[0] as AnalysisEvent.Failed).error is ChessError.InvalidFen)
        assertTrue(a2.toString(), a2.size == 1 && (a2[0] as AnalysisEvent.Failed).error is ChessError.IllegalMove)
        assertEquals(EngineState.Ready, engine.state.value)
        assertTrue(engine.bestMove(move()) is ChessResult.Ok)
    }

    @Test
    fun `C-10 analyse gives Updates for MultiPV lines and ends with Finished`() = run {
        ready()
        val events = engine.analyse(analysis(multiPv = 3)).toList()
        assertEquals(AnalysisEvent.Finished, events.last())
        val updates = events.filterIsInstance<AnalysisEvent.Update>()
        assertEquals(setOf(1, 2, 3), updates.map { it.update.multiPv }.toSet())
        assertTrue(updates.all { it.update.pv.isNotEmpty() && isLegal(it.update.pv.first()) })
        assertEquals(EngineState.Ready, engine.state.value)
    }

    // --- C-11 … C-15: скасування (3.4: «останній запит перемагає») ---

    private suspend fun CoroutineScope.lastRequestWins(second: suspend () -> Any?) {
        ready()
        val first = longAnalyse()
        val next = async { second() }
        val events = first.await()
        assertEquals("перший пошук має завершитися Failed(Cancelled): $events", AnalysisEvent.Failed(ChessError.Cancelled), events.last())
        h.release()
        next.await()
        withTimeout(WAIT_MS) { engine.state.first { it == EngineState.Ready } }
    }

    @Test
    fun `C-11 a new bestMove cancels a running analyse with Failed Cancelled`() = run {
        lastRequestWins { engine.bestMove(move()) }
    }

    @Test
    fun `C-12 a request that fails with NoLegalMoves still cancels the previous search`() = run {
        lastRequestWins { assertEquals(ChessResult.Err(ChessError.NoLegalMoves), engine.bestMove(move(matedFen))) }
    }

    @Test
    fun `C-13 a request that fails with InvalidFen or IllegalMove still cancels the previous search`() = run {
        lastRequestWins { assertTrue(err(engine.bestMove(move(Fen("not a fen")))) is ChessError.InvalidFen) }
        engine.stop()
        lastRequestWins { assertTrue(err(engine.bestMove(move(moves = listOf(Uci("e2e5"))))) is ChessError.IllegalMove) }
    }

    @Test
    fun `C-14 an analyse that fails with NoLegalMoves still cancels the previous search`() = run {
        lastRequestWins { assertEquals(listOf<AnalysisEvent>(AnalysisEvent.Failed(ChessError.NoLegalMoves)), engine.analyse(analysis(matedFen)).toList()) }
    }

    @Test
    fun `C-15 a new bestMove cancels a running bestMove with Err Cancelled`() = run {
        ready()
        h.hold()
        val slow = DifficultyLevel(DifficultyId(1), Strength.Full, SearchLimit(depth = 40), 0, null, DifficultyGroup.MAX)
        val first = scope.async { engine.bestMove(move(level = slow)) }
        withTimeout(WAIT_MS) { engine.state.first { it == EngineState.Busy } }
        val second = scope.async { engine.bestMove(move()) }
        assertEquals(ChessResult.Err(ChessError.Cancelled), first.await())
        h.release()
        assertTrue(second.await() is ChessResult.Ok)
    }

    // --- C-16 … C-19: stop, скасування споживачем, newGame, збій ---

    @Test
    fun `C-16 stop during a search gives Cancelled and state Stopped`() = run {
        ready()
        val search = longAnalyse()
        engine.stop()
        assertEquals(AnalysisEvent.Failed(ChessError.Cancelled), search.await().last())
        assertEquals(EngineState.Stopped, engine.state.value)
        assertTrue(err(engine.bestMove(move())) is ChessError.EngineUnavailable)
    }

    @Test
    fun `C-17 cancelling the collector frees the engine without a Failed event`() = run {
        ready()
        h.hold()
        val seen = mutableListOf<AnalysisEvent>()
        val job: Job = scope.launch { engine.analyse(analysis(limit = null)).collect { seen += it } }
        withTimeout(WAIT_MS) { engine.state.first { it == EngineState.Busy } }
        job.cancelAndJoin()
        h.release()
        withTimeout(WAIT_MS) { engine.state.first { it == EngineState.Ready } }
        assertTrue(seen.toString(), seen.none { it is AnalysisEvent.Failed })
        assertTrue(engine.bestMove(move()) is ChessResult.Ok)
    }

    @Test
    fun `C-18 newGame keeps the engine usable`() = run {
        ready()
        engine.newGame()
        assertEquals(EngineState.Ready, engine.state.value)
        assertTrue(engine.bestMove(move()) is ChessResult.Ok)
    }

    @Test
    fun `C-19 a crash gives Failed EngineCrashed and start recovers`() = run {
        assumeTrue("реалізація не вміє імітувати падіння (справжній рушій: сценарій crash на пристрої)", h.canCrash)
        ready()
        val search = longAnalyse()
        h.crash()
        val last = search.await().last()
        assertTrue(last.toString(), last is AnalysisEvent.Failed && last.error is ChessError.EngineCrashed)
        val state = engine.state.value
        assertTrue(state.toString(), state is EngineState.Failed && state.error is ChessError.EngineCrashed)
        assertTrue(err(engine.bestMove(move())) is ChessError.EngineUnavailable)
        h.release()
        ready()
        assertTrue(engine.bestMove(move()) is ChessResult.Ok)
    }

    // --- C-20 … C-22: передумови та Handicap ---

    @Test
    fun `C-20 SearchLimit without any limit violates the precondition`() {
        try {
            SearchLimit()
            org.junit.Assert.fail("очікувався IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `C-21 a raw engine rejects Strength Handicap and does not cancel the running search`() = run {
        assumeTrue("лише для сирої реалізації", h.isRaw)
        ready()
        val search = longAnalyse()
        val handicap = DifficultyLevel(DifficultyId(2), Strength.Handicap(2), SearchLimit(depth = 2), 0, null, DifficultyGroup.BEGINNER)
        try {
            engine.bestMove(move(level = handicap))
            org.junit.Assert.fail("очікувався IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("передумова порушена до прийняття запиту: пошук не скасовується", EngineState.Busy, engine.state.value)
        h.release()
        engine.stop()
        search.await()
    }

    @Test
    fun `C-22 a decorated engine returns legal Handicap moves at every step and keeps analyse at full strength`() = run {
        assumeTrue("лише для HandicapEngineService", !h.isRaw)
        ready()
        for (step in 1..5) {
            val level = DifficultyLevel(DifficultyId(step), Strength.Handicap(step), SearchLimit(depth = 2), 0, null, DifficultyGroup.BEGINNER)
            val r = engine.bestMove(move(level = level))
            assertTrue("step $step: $r", r is ChessResult.Ok && isLegal(r.value.best))
            assertEquals(EngineState.Ready, engine.state.value)
        }
        assertEquals(AnalysisEvent.Finished, engine.analyse(analysis()).toList().last())
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val WAIT_MS = 5_000L
    }
}
