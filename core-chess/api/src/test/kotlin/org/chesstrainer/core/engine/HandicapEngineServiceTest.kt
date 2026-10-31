// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.core.testing.assertFailsWith
import org.chesstrainer.core.testing.assertIs
import org.chesstrainer.core.testing.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandicapEngineServiceTest {
    private val rules = ChesslibRules()
    private val startFen = Fen(rules.startPosition().fen.value)
    private val matedFen = Fen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")

    private fun handicap(step: Int, delay: Long = 0) = DifficultyLevel(
        DifficultyId(step), Strength.Handicap(step), SearchLimit(depth = 1), delay, null, DifficultyGroup.BEGINNER,
    )

    private val full = DifficultyLevel(DifficultyId(99), Strength.Full, SearchLimit(depth = 1), 0, null, DifficultyGroup.MAX)

    private fun request(level: DifficultyLevel, fen: Fen = startFen, moves: List<Uci> = emptyList()) =
        MoveRequest(fen, moves, level)

    /** Рушій, що оцінює перші n ходів (за UCI) значеннями [scores]; решта не повертається. */
    private fun scripted(scores: List<Int>, fake: FakeEngineService = FakeEngineService(rules)): FakeEngineService {
        fake.onAnalyse { req ->
            val position = (rules.parseFen(req.startFen.value) as ChessResult.Ok).value
            val moves = rules.legalMoves(position).map { rules.toUci(it).value }.sorted()
            val lines = minOf(req.multiPv, scores.size, moves.size)
            (0 until lines).map { i ->
                AnalysisEvent.Update(
                    AnalysisUpdate(
                        depth = 1, selDepth = null, multiPv = i + 1,
                        evaluation = Evaluation.Centipawns(scores[i]), // білі ходять: оцінка з їх боку
                        pv = listOf(Uci(moves[i])), nodes = 1, nps = 0, timeMs = 0,
                    ),
                )
            } + AnalysisEvent.Finished
        }
        return fake
    }

    private suspend fun service(fake: FakeEngineService, seed: Int = 1): HandicapEngineService {
        val s = HandicapEngineService(fake, rules, Random(seed))
        s.start()
        return s
    }

    // --- вибір ходу ---

    @Test
    fun `handicap move is always legal and not delegated as a bestMove`() = runTest {
        val fake = scripted(listOf(30, 20, 0, -50, -300))
        val engine = service(fake)
        val legal = rules.legalMoves(rules.startPosition()).map { rules.toUci(it).value }.toSet()
        repeat(50) {
            val result = engine.bestMove(request(handicap(1))) as ChessResult.Ok
            assertTrue(result.value.best.value in legal)
            assertNull(result.value.ponder)
        }
        assertTrue(fake.moveRequests.isEmpty())
        assertEquals(50, fake.analysisRequests.size)
        val sent = fake.analysisRequests.first()
        assertEquals(HandicapPolicy.CANDIDATES, sent.multiPv)
        assertEquals(SearchLimit(depth = 1), sent.limit)
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `same seed gives the same moves and different seeds can differ`() = runTest {
        suspend fun moves(seed: Int): List<String> {
            val engine = service(scripted(listOf(30, 20, 0, -50, -300)), seed)
            return (1..40).map { (engine.bestMove(request(handicap(1))) as ChessResult.Ok).value.best.value }
        }
        assertEquals(moves(7), moves(7))
        assertTrue(moves(7) != moves(8))
    }

    private class CountingRandom(seed: Int) : Random() {
        private val inner = Random(seed)
        var draws = 0
        override fun nextBits(bitCount: Int): Int = inner.nextBits(bitCount)
        override fun nextDouble(): Double { draws++; return inner.nextDouble() }
    }

    @Test
    fun `random is consumed exactly twice per handicap move, also with a single candidate`() = runTest {
        val random = CountingRandom(5)
        val many = HandicapEngineService(scripted(listOf(30, 20, 0)), rules, random).also { it.start() }
        repeat(10) { many.bestMove(request(handicap(2))) }
        assertEquals(20, random.draws)

        val single = HandicapEngineService(scripted(listOf(0)), rules, random).also { it.start() }
        repeat(5) { single.bestMove(request(handicap(5))) }
        assertEquals(30, random.draws)

        // Відхилені запити Random не витрачають.
        many.bestMove(request(handicap(2), matedFen))
        assertEquals(30, random.draws)
    }

    @Test
    fun `strength mapping uses side to move perspective and mate scores`() = runTest {
        // Чорні ходять: оцінка білих -500 означає, що перший кандидат для чорних на +500.
        val fake = FakeEngineService(rules)
        fake.onAnalyse { req ->
            listOf(
                AnalysisEvent.Update(
                    AnalysisUpdate(1, null, 1, Evaluation.Mate(-1), listOf(Uci("d8h4")), 1, 0, 0),
                ),
                AnalysisEvent.Update(
                    AnalysisUpdate(1, null, 2, Evaluation.Centipawns(400), listOf(Uci("a7a6")), 1, 0, 0),
                ),
                AnalysisEvent.Finished,
            )
        }
        val afterG4 = Fen("rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2")
        val engine = service(fake)
        // Найсильніший крок майже завжди бачить мат (blind 2,5 %): у 200 спробах мат обирається в переважній більшості.
        val picks = (1..200).map { (engine.bestMove(request(handicap(5), afterG4)) as ChessResult.Ok).value.best.value }
        assertTrue(picks.count { it == "d8h4" } > 180)
    }

    @Test
    fun `share of the best move grows with step over seeded games`() = runTest {
        val fake = scripted(listOf(80, 40, 0, -60, -400))
        val best = "a2a3"
        val shares = (1..HandicapPolicy.STEPS).map { step ->
            val engine = HandicapEngineService(fake, rules, Random(100 + step)).also { it.start() }
            val picks = (1..1500).map { (engine.bestMove(request(handicap(step))) as ChessResult.Ok).value.best.value }
            picks.count { it == best } / 1500.0
        }
        for (i in 1 until shares.size) assertTrue("shares $shares", shares[i] >= shares[i - 1] - 0.03)
        assertTrue("shares $shares", shares.last() > shares.first() + 0.2)
    }

    // --- семантика EngineService ---

    @Test
    fun `no legal moves invalid fen and illegal move never reach the engine while Ready`() = runTest {
        val fake = scripted(listOf(0))
        val engine = service(fake)

        assertEquals(ChessResult.Err(ChessError.NoLegalMoves), engine.bestMove(request(handicap(1), matedFen)))
        assertIs<ChessError.InvalidFen>((engine.bestMove(request(handicap(1), Fen("nope"))) as ChessResult.Err).error)
        assertIs<ChessError.IllegalMove>(
            (engine.bestMove(request(handicap(1), moves = listOf(Uci("e2e5")))) as ChessResult.Err).error,
        )
        assertTrue(fake.analysisRequests.isEmpty())
        assertTrue(fake.moveRequests.isEmpty())
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `requests before start are unavailable`() = runTest {
        val engine = HandicapEngineService(FakeEngineService(rules), rules, Random(1))
        val error = (engine.bestMove(request(handicap(1))) as ChessResult.Err).error
        assertIs<ChessError.EngineUnavailable>(error)
        engine.start()
        engine.stop()
        assertIs<ChessError.EngineUnavailable>((engine.bestMove(request(handicap(1))) as ChessResult.Err).error)
    }

    @Test
    fun `new request cancels the previous search even when it is rejected`() = runTest {
        val fake = scripted(listOf(0, 0, 0))
        val engine = service(fake)
        fake.holdSearches()
        val first = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request(handicap(1))) }
        assertEquals(EngineState.Busy, engine.state.value)

        // Фейк під holdSearches тримає й запити з помилкою до release(); справжній рушій відповів би одразу.
        val rejected = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request(handicap(1), matedFen)) }

        assertEquals(ChessResult.Err(ChessError.Cancelled), first.await())
        fake.release()
        assertEquals(ChessResult.Err(ChessError.NoLegalMoves), rejected.await())
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `new handicap request cancels the previous handicap request`() = runTest {
        val fake = scripted(listOf(0, 0, 0))
        val engine = service(fake)
        fake.holdSearches()
        val first = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request(handicap(1))) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request(handicap(2))) }
        assertEquals(ChessResult.Err(ChessError.Cancelled), first.await())
        fake.release()
        assertIs<ChessResult.Ok<EngineMove>>(second.await())
    }

    @Test
    fun `a handicap request cancels a foreign analyse`() = runTest {
        val fake = scripted(listOf(0, 0, 0))
        val engine = service(fake)
        fake.holdSearches()
        val analysis = async(start = CoroutineStart.UNDISPATCHED) {
            engine.analyse(AnalysisRequest(startFen, emptyList(), null, 1)).toList()
        }
        val move = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request(handicap(3))) }
        assertEquals(listOf(AnalysisEvent.Failed(ChessError.Cancelled)), analysis.await())
        fake.release()
        assertIs<ChessResult.Ok<EngineMove>>(move.await())
    }

    @Test
    fun `stop during a handicap search gives Cancelled and Stopped`() = runTest {
        val fake = scripted(listOf(0, 0, 0))
        val engine = service(fake)
        fake.holdSearches()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request(handicap(1))) }
        engine.stop()
        assertEquals(ChessResult.Err(ChessError.Cancelled), pending.await())
        assertEquals(EngineState.Stopped, engine.state.value)
    }

    @Test
    fun `crash of the delegate is passed through`() = runTest {
        val fake = scripted(listOf(0, 0, 0))
        val engine = service(fake)
        fake.holdSearches()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request(handicap(1))) }
        fake.crash("boom")
        assertEquals(ChessResult.Err(ChessError.EngineCrashed("boom")), pending.await())
        assertEquals(EngineState.Failed(ChessError.EngineCrashed("boom")), engine.state.value)
    }

    @Test
    fun `non handicap bestMove analyse and lifecycle go to the delegate unchanged`() = runTest {
        val fake = FakeEngineService(rules)
        val engine = HandicapEngineService(fake, rules, Random(1))
        assertNull(engine.capabilities)
        assertEquals(ChessResult.Ok(Unit), engine.start(EngineConfig(threads = 2)))
        assertEquals(FakeEngineService.DEFAULT_CAPABILITIES, engine.capabilities)
        assertEquals(listOf(EngineConfig(threads = 2)), fake.appliedConfigs)

        val moveRequest = request(full)
        assertEquals(ChessResult.Ok(EngineMove(Uci("a2a3"), null)), engine.bestMove(moveRequest))
        assertEquals(listOf(moveRequest), fake.moveRequests)

        val analysis = AnalysisRequest(startFen, emptyList(), SearchLimit(depth = 1), 2)
        assertEquals(2 + 1, engine.analyse(analysis).toList().size)
        assertEquals(listOf(analysis), fake.analysisRequests)
        engine.newGame()
    }

    @Test
    fun `invalid step is a contract violation`() = runTest {
        val engine = service(scripted(listOf(0)))
        assertFailsWith<IllegalArgumentException> {
            kotlinx.coroutines.runBlocking { engine.bestMove(request(handicap(0))) }
        }
        assertFailsWith<IllegalArgumentException> {
            kotlinx.coroutines.runBlocking { engine.bestMove(request(handicap(HandicapPolicy.STEPS + 1))) }
        }
    }

    @Test
    fun `incomplete last iteration does not mix depths`() = runTest {
        val fake = FakeEngineService(rules)
        fake.onAnalyse {
            fun u(depth: Int, line: Int, cp: Int, mv: String) = AnalysisEvent.Update(
                AnalysisUpdate(depth, null, line, Evaluation.Centipawns(cp), listOf(Uci(mv)), 1, 0, 0),
            )
            listOf(
                u(2, 1, 0, "a2a3"), u(2, 2, 0, "b2b3"), u(2, 3, 0, "c2c3"), u(2, 4, 0, "d2d3"), u(2, 5, 0, "e2e3"),
                u(3, 1, 0, "a2a3"), u(3, 2, 0, "b2b3"), // глибина 3 урвалась: лише дві лінії
                AnalysisEvent.Finished,
            )
        }
        val engine = service(fake)
        val seen = (1..300).map { seed ->
            val e = HandicapEngineService(fake, rules, Random(seed)).also { it.start() }
            (e.bestMove(request(handicap(1))) as ChessResult.Ok).value.best.value
        }.toSet()
        // Береться повна глибина 2 з п'ятьма кандидатами, а не неповна глибина 3 з двома.
        assertTrue(seen.size > 2)
        assertTrue(engine.state.value == EngineState.Ready)
    }
}
