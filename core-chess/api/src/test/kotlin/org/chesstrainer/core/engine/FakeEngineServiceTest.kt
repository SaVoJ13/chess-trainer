// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import org.chesstrainer.core.testing.runTest
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci
import org.junit.Test
import org.junit.Assert.assertEquals
import org.chesstrainer.core.testing.assertFailsWith
import org.chesstrainer.core.testing.assertIs
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class FakeEngineServiceTest {
    private val start = Fen("start")
    private val mated = Fen("mated")
    private val rules = StubRules(
        mapOf(
            "start" to listOf("g1f3", "a2a3", "e2e4"),
            "start>a2a3" to listOf("h7h6"),
            "mated" to emptyList(),
        ),
    )
    private val level = DifficultyLevel(
        DifficultyId(1), Strength.Full, SearchLimit(depth = 1), 0, null, DifficultyGroup.MAX,
    )

    private fun fake() = FakeEngineService(rules)
    private fun moveRequest(fen: Fen = start, moves: List<Uci> = emptyList(), level: DifficultyLevel = this.level) =
        MoveRequest(fen, moves, level)
    private fun analysisRequest(fen: Fen = start, multiPv: Int = 1, moves: List<Uci> = emptyList()) =
        AnalysisRequest(fen, moves, SearchLimit(depth = 1), multiPv)

    @Test
    fun `start moves Stopped to Ready, publishes capabilities and clamps config`() = runTest {
        val engine = fake()
        assertEquals(EngineState.Stopped, engine.state.value)
        assertNull(engine.capabilities)

        assertEquals(ChessResult.Ok(Unit), engine.start(EngineConfig(threads = 99, hashMb = 99999)))

        assertEquals(EngineState.Ready, engine.state.value)
        assertEquals(FakeEngineService.DEFAULT_CAPABILITIES, engine.capabilities)
        assertEquals(listOf(EngineConfig(threads = 4, hashMb = 256)), engine.appliedConfigs)
    }

    @Test
    fun `config below range is clamped up`() = runTest {
        val engine = fake()
        engine.start(EngineConfig(threads = 0, hashMb = 0))
        assertEquals(listOf(EngineConfig(1, 1)), engine.appliedConfigs)
    }

    @Test
    fun `repeated start is ignored`() = runTest {
        val engine = fake()
        engine.start(EngineConfig(threads = 2))
        assertEquals(ChessResult.Ok(Unit), engine.start(EngineConfig(threads = 3)))
        assertEquals(listOf(EngineConfig(threads = 2)), engine.appliedConfigs)
    }

    @Test
    fun `requests before start and after stop are unavailable`() = runTest {
        val engine = fake()
        assertIs<ChessError.EngineUnavailable>((engine.bestMove(moveRequest()) as ChessResult.Err).error)
        assertIs<AnalysisEvent.Failed>(engine.analyse(analysisRequest()).toList().single())

        engine.start()
        engine.stop()
        assertEquals(EngineState.Stopped, engine.state.value)
        assertIs<ChessError.EngineUnavailable>((engine.bestMove(moveRequest()) as ChessResult.Err).error)
        val events = engine.analyse(analysisRequest()).toList()
        assertIs<ChessError.EngineUnavailable>((events.single() as AnalysisEvent.Failed).error)
        // capabilities лишаються після першого успішного start
        assertEquals(FakeEngineService.DEFAULT_CAPABILITIES, engine.capabilities)
    }

    @Test
    fun `bestMove returns first move sorted by UCI and goes back to Ready`() = runTest {
        val engine = fake().also { it.start() }
        val result = engine.bestMove(moveRequest())
        assertEquals(ChessResult.Ok(EngineMove(Uci("a2a3"), null)), result)
        assertEquals(EngineState.Ready, engine.state.value)
        assertEquals(listOf(moveRequest()), engine.moveRequests)
    }

    @Test
    fun `bestMove replays moves from start position`() = runTest {
        val engine = fake().also { it.start() }
        val result = engine.bestMove(moveRequest(moves = listOf(Uci("a2a3"))))
        assertEquals(ChessResult.Ok(EngineMove(Uci("h7h6"), null)), result)
    }

    @Test
    fun `bestMove without legal moves is NoLegalMoves and engine stays Ready`() = runTest {
        val engine = fake().also { it.start() }
        assertEquals(ChessResult.Err(ChessError.NoLegalMoves), engine.bestMove(moveRequest(mated)))
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `invalid FEN is reported as an error and engine stays Ready`() = runTest {
        val engine = fake().also { it.start() }
        assertIs<ChessError.InvalidFen>((engine.bestMove(moveRequest(Fen("nope"))) as ChessResult.Err).error)
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `analyse emits one Update per MultiPV line then Finished`() = runTest {
        val engine = fake().also { it.start() }
        val events = engine.analyse(analysisRequest(multiPv = 2)).toList()

        assertEquals(3, events.size)
        val first = (events[0] as AnalysisEvent.Update).update
        val second = (events[1] as AnalysisEvent.Update).update
        assertEquals(listOf(Uci("a2a3")), first.pv)
        assertEquals(1, first.multiPv)
        assertEquals(listOf(Uci("e2e4")), second.pv)
        assertEquals(2, second.multiPv)
        assertEquals(1, first.depth)
        assertEquals(Evaluation.Centipawns(0), first.evaluation)
        assertEquals(AnalysisEvent.Finished, events[2])
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `analyse MultiPV is limited by number of legal moves`() = runTest {
        val engine = fake().also { it.start() }
        val events = engine.analyse(analysisRequest(multiPv = 5)).toList()
        assertEquals(3 + 1, events.size)
    }

    @Test
    fun `analyse without legal moves fails with NoLegalMoves and engine stays Ready`() = runTest {
        val engine = fake().also { it.start() }
        val events = engine.analyse(analysisRequest(mated)).toList()
        assertEquals(listOf<AnalysisEvent>(AnalysisEvent.Failed(ChessError.NoLegalMoves)), events)
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `new bestMove cancels held bestMove`() = runTest {
        val engine = fake().also { it.start() }
        engine.holdSearches()
        val first = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(moveRequest()) }
        assertEquals(EngineState.Busy, engine.state.value)

        val second = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(moveRequest()) }
        assertEquals(ChessResult.Err(ChessError.Cancelled), first.await())

        engine.release()
        assertEquals(ChessResult.Ok(EngineMove(Uci("a2a3"), null)), second.await())
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `new request cancels held analyse with Failed Cancelled`() = runTest {
        val engine = fake().also { it.start() }
        engine.holdSearches()
        val analysis = async(start = CoroutineStart.UNDISPATCHED) { engine.analyse(analysisRequest()).toList() }
        val move = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(moveRequest()) }

        assertEquals(listOf<AnalysisEvent>(AnalysisEvent.Failed(ChessError.Cancelled)), analysis.await())
        engine.release()
        assertIs<ChessResult.Ok<EngineMove>>(move.await())
    }

    @Test
    fun `release lets held search finish normally`() = runTest {
        val engine = fake().also { it.start() }
        engine.holdSearches()
        val analysis = async(start = CoroutineStart.UNDISPATCHED) { engine.analyse(analysisRequest()).toList() }
        engine.release()
        assertEquals(AnalysisEvent.Finished, analysis.await().last())
    }

    @Test
    fun `collector cancellation frees the engine without producing events`() = runTest {
        val engine = fake().also { it.start() }
        engine.holdSearches()
        val job = async(start = CoroutineStart.UNDISPATCHED) { engine.analyse(analysisRequest()).toList() }
        assertEquals(EngineState.Busy, engine.state.value)
        job.cancelAndJoin()
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `taking only first event ends the search`() = runTest {
        val engine = fake().also { it.start() }
        val events = engine.analyse(analysisRequest(multiPv = 3)).take(1).toList()
        assertEquals(1, events.size)
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `crash fails active search, sets Failed state and allows restart`() = runTest {
        val engine = fake().also { it.start() }
        engine.holdSearches()
        val move = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(moveRequest()) }
        val analysis = async(start = CoroutineStart.UNDISPATCHED) { engine.analyse(analysisRequest()).toList() }
        // analyse скасував bestMove; тепер аварія під час analyse
        assertEquals(ChessResult.Err(ChessError.Cancelled), move.await())

        engine.crash("boom")
        val crashed = ChessError.EngineCrashed("boom")
        assertEquals(listOf<AnalysisEvent>(AnalysisEvent.Failed(crashed)), analysis.await())
        assertEquals(EngineState.Failed(crashed), engine.state.value)

        assertIs<ChessError.EngineUnavailable>((engine.bestMove(moveRequest()) as ChessResult.Err).error)

        engine.release()
        assertEquals(ChessResult.Ok(Unit), engine.start())
        assertEquals(EngineState.Ready, engine.state.value)
        assertIs<ChessResult.Ok<EngineMove>>(engine.bestMove(moveRequest()))
    }

    @Test
    fun `crash of held bestMove returns EngineCrashed`() = runTest {
        val engine = fake().also { it.start() }
        engine.holdSearches()
        val move = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(moveRequest()) }
        engine.crash()
        assertEquals(ChessResult.Err(ChessError.EngineCrashed("fake crash")), move.await())
    }

    @Test
    fun `Handicap strength is a contract violation for a raw engine`() = runTest {
        val engine = fake().also { it.start() }
        val handicap = level.copy(strength = Strength.Handicap(1))
        assertFailsWith<IllegalArgumentException> { engine.bestMove(moveRequest(level = handicap)) }
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `custom responders override defaults and are recorded`() = runTest {
        val engine = fake().also { it.start() }
        engine.onBestMove { ChessResult.Ok(EngineMove(Uci("e2e4"), Uci("e7e5"))) }
        val custom = listOf(AnalysisEvent.Failed(ChessError.EngineCrashed("scripted")))
        engine.onAnalyse { custom }

        assertEquals(ChessResult.Ok(EngineMove(Uci("e2e4"), Uci("e7e5"))), engine.bestMove(moveRequest()))
        assertEquals(custom, engine.analyse(analysisRequest()).toList())
        assertEquals(1, engine.moveRequests.size)
        assertEquals(1, engine.analysisRequests.size)
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `state flow exposes Busy during held search`() = runTest {
        val engine = fake().also { it.start() }
        engine.holdSearches()
        val job = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(moveRequest()) }
        assertTrue(engine.state.first() == EngineState.Busy)
        engine.release()
        job.await()
    }
}
