// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.core.testing.assertIs
import org.chesstrainer.core.testing.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Семантика `EngineService` (CONTRACTS 3.4), однакова для «сирої» реалізації та для `HandicapEngineService`
 * (CONTRACTS 3.5: контрактні тести запускаються і для декоратора поверх `FakeEngineService`).
 * Каталогу `tests/contract/` ще немає (QA); це JVM-версія тих самих вимог, яку QA може перенести.
 */
@RunWith(Parameterized::class)
class EngineSemanticsTest(
    @Suppress("unused") private val name: String,
    private val decorated: Boolean,
    private val strength: Strength,
) {
    private val rules = ChesslibRules()
    private val fake = FakeEngineService(rules)
    private val engine: EngineService =
        if (decorated) HandicapEngineService(fake, rules, Random(1)) else fake
    private val startFen = Fen(rules.startPosition().fen.value)
    private val matedFen = Fen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
    private val level = DifficultyLevel(DifficultyId(1), strength, SearchLimit(depth = 1), 0, null, DifficultyGroup.MAX)

    private fun request(fen: Fen = startFen, moves: List<Uci> = emptyList()) = MoveRequest(fen, moves, level)

    private fun error(result: ChessResult<EngineMove>) = (result as ChessResult.Err).error

    @Test
    fun `start gives Ready and capabilities and repeated start is ignored`() = runTest {
        assertEquals(EngineState.Stopped, engine.state.value)
        assertEquals(null, engine.capabilities)
        assertEquals(ChessResult.Ok(Unit), engine.start())
        assertEquals(EngineState.Ready, engine.state.value)
        assertEquals(FakeEngineService.DEFAULT_CAPABILITIES, engine.capabilities)
        assertEquals(ChessResult.Ok(Unit), engine.start(EngineConfig(threads = 3)))
        assertEquals(1, fake.appliedConfigs.size)
    }

    @Test
    fun `requests before start and after stop are EngineUnavailable`() = runTest {
        assertIs<ChessError.EngineUnavailable>(error(engine.bestMove(request())))
        engine.start()
        engine.stop()
        assertEquals(EngineState.Stopped, engine.state.value)
        assertIs<ChessError.EngineUnavailable>(error(engine.bestMove(request())))
    }

    @Test
    fun `bestMove returns a legal move and the engine goes back to Ready`() = runTest {
        engine.start()
        val move = (engine.bestMove(request()) as ChessResult.Ok).value
        assertTrue(move.best.value in rules.legalMoves(rules.startPosition()).map { rules.toUci(it).value })
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `positions without moves and bad requests are errors and keep the engine Ready`() = runTest {
        engine.start()
        assertEquals(ChessResult.Err(ChessError.NoLegalMoves), engine.bestMove(request(matedFen)))
        assertEquals(EngineState.Ready, engine.state.value)
        assertIs<ChessError.InvalidFen>(error(engine.bestMove(request(Fen("nope")))))
        assertEquals(EngineState.Ready, engine.state.value)
        assertIs<ChessError.IllegalMove>(error(engine.bestMove(request(moves = listOf(Uci("e2e5"))))))
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `last request wins`() = runTest {
        engine.start()
        fake.holdSearches()
        val first = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request()) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request()) }
        assertEquals(ChessResult.Err(ChessError.Cancelled), first.await())
        fake.release()
        assertIs<ChessResult.Ok<EngineMove>>(second.await())
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `last request wins even when it ends with an error`() = runTest {
        engine.start()
        fake.holdSearches()
        val first = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request()) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request(matedFen)) }
        assertEquals(ChessResult.Err(ChessError.Cancelled), first.await())
        fake.release()
        assertEquals(ChessResult.Err(ChessError.NoLegalMoves), second.await())
        assertEquals(EngineState.Ready, engine.state.value)
    }

    @Test
    fun `stop during a search gives Cancelled and Stopped`() = runTest {
        engine.start()
        fake.holdSearches()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request()) }
        engine.stop()
        assertEquals(ChessResult.Err(ChessError.Cancelled), pending.await())
        assertEquals(EngineState.Stopped, engine.state.value)
    }

    @Test
    fun `crash gives EngineCrashed and restart works`() = runTest {
        engine.start()
        fake.holdSearches()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { engine.bestMove(request()) }
        fake.crash("boom")
        assertEquals(ChessResult.Err(ChessError.EngineCrashed("boom")), pending.await())
        assertEquals(EngineState.Failed(ChessError.EngineCrashed("boom")), engine.state.value)
        assertIs<ChessError.EngineUnavailable>(error(engine.bestMove(request())))

        fake.release()
        assertEquals(ChessResult.Ok(Unit), engine.start())
        assertEquals(EngineState.Ready, engine.state.value)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = listOf(
            arrayOf("raw engine, Full", false, Strength.Full),
            arrayOf("HandicapEngineService, Full", true, Strength.Full),
            arrayOf("HandicapEngineService, Handicap", true, Strength.Handicap(2)),
        )
    }
}
