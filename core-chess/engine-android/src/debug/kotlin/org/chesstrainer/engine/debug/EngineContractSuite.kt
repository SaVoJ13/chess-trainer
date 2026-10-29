// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.chesstrainer.core.engine.AnalysisEvent
import org.chesstrainer.core.engine.AnalysisRequest
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.EngineState
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.engine.Strength
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules

/**
 * Контрактні тести [EngineService] (CONTRACTS 3.4, семантика v0.6/v0.7) для запуску на пристрої.
 * Один набір для будь-якої реалізації. Без `androidx.test`: це звичайний код, що повертає звіт.
 * Кожен тест сам починає зі `stop()`, тож порядок не важливий.
 */
class EngineContractSuite(
    private val rules: ChessRules,
    private val newEngine: () -> EngineService,
) {
    class Outcome(val name: String, val error: String?) {
        val passed get() = error == null
    }

    private val startFen = Fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    private val checkmated = Fen("R5k1/5ppp/8/8/8/8/8/6K1 b - - 0 1")
    private val stalemated = Fen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
    private val fixedDepth = DifficultyLevel(DifficultyId(1), Strength.Full, SearchLimit(depth = 6), 0, null, DifficultyGroup.MAX)

    private fun level(strength: Strength, limit: SearchLimit = SearchLimit(depth = 4)) =
        DifficultyLevel(DifficultyId(2), strength, limit, 0, null, DifficultyGroup.AMATEUR)

    private fun req(fen: Fen = startFen, moves: List<Uci> = emptyList(), level: DifficultyLevel = fixedDepth) =
        MoveRequest(fen, moves, level)

    suspend fun runAll(): List<Outcome> {
        val engine = newEngine()
        val results = mutableListOf<Outcome>()
        for ((name, body) in tests) {
            val error = try {
                engine.stop()
                withTimeout(120_000) { body(engine) }
                null
            } catch (t: Throwable) {
                "${t::class.simpleName}: ${t.message}"
            }
            results += Outcome(name, error)
        }
        engine.stop()
        return results
    }

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }

    private fun checkOk(result: ChessResult<*>, what: String) =
        check(result is ChessResult.Ok) { "$what: expected Ok, got $result" }

    private fun legal(engine: EngineService, move: Uci, fen: Fen = startFen): Boolean {
        val position = (rules.parseFen(fen.value) as ChessResult.Ok).value
        return rules.parseUci(position, move.value) is ChessResult.Ok
    }

    private val tests: List<Pair<String, suspend (EngineService) -> Unit>> = listOf(
        "capabilities are null before first start, set after" to { e ->
            checkOk(e.start(), "start")
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
            val c = e.capabilities
            check(c != null && c.name.isNotEmpty()) { "capabilities $c" }
            check(c!!.eloRange != null && c.skillLevelRange != null && c.maxMultiPv >= 3) { "capabilities $c" }
        },
        "repeated start in Ready is Ok" to { e ->
            checkOk(e.start(), "first")
            checkOk(e.start(), "second")
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
        },
        "concurrent start waits for the same launch (Starting)" to { e ->
            val scope = CoroutineScope(Dispatchers.Default)
            val both = listOf(scope.async { e.start() }, scope.async { e.start() }).awaitAll()
            both.forEach { checkOk(it, "concurrent start") }
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
        },
        "requests before start are EngineUnavailable" to { e ->
            val r = e.bestMove(req())
            check(r is ChessResult.Err && r.error is ChessError.EngineUnavailable) { "bestMove: $r" }
            val a = e.analyse(AnalysisRequest(startFen, emptyList(), SearchLimit(depth = 2))).toList()
            check(a.size == 1 && a[0] is AnalysisEvent.Failed && (a[0] as AnalysisEvent.Failed).error is ChessError.EngineUnavailable) { "analyse: $a" }
        },
        "bestMove returns a legal move and state goes back to Ready" to { e ->
            checkOk(e.start(), "start")
            val r = e.bestMove(req())
            check(r is ChessResult.Ok && legal(e, r.value.best)) { "bestMove: $r" }
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
        },
        "bestMove replays moves (legal reply after 1.e4)" to { e ->
            checkOk(e.start(), "start")
            val moves = listOf(Uci("e2e4"))
            val r = e.bestMove(req(moves = moves))
            val position = (rules.parseFen(startFen.value) as ChessResult.Ok).value
            val after = (rules.apply(position, (rules.parseUci(position, "e2e4") as ChessResult.Ok).value) as ChessResult.Ok).value
            check(r is ChessResult.Ok && rules.parseUci(after, r.value.best.value) is ChessResult.Ok) { "bestMove: $r" }
        },
        "Elo and SkillLevel strengths produce legal moves" to { e ->
            checkOk(e.start(), "start")
            val caps = e.capabilities!!
            for (s in listOf(Strength.Elo(caps.eloRange!!.first), Strength.SkillLevel(caps.skillLevelRange!!.first))) {
                val r = e.bestMove(req(level = level(s)))
                check(r is ChessResult.Ok && legal(e, r.value.best)) { "$s: $r" }
            }
        },
        "checkmate position is NoLegalMoves, engine stays Ready" to { e ->
            checkOk(e.start(), "start")
            val r = e.bestMove(req(fen = checkmated))
            check(r == ChessResult.Err(ChessError.NoLegalMoves)) { "bestMove: $r" }
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
            val a = e.analyse(AnalysisRequest(checkmated, emptyList(), SearchLimit(depth = 2))).toList()
            check(a == listOf(AnalysisEvent.Failed(ChessError.NoLegalMoves))) { "analyse: $a" }
        },
        "stalemate position is NoLegalMoves" to { e ->
            checkOk(e.start(), "start")
            val r = e.bestMove(req(fen = stalemated))
            check(r == ChessResult.Err(ChessError.NoLegalMoves)) { "bestMove: $r" }
        },
        "invalid FEN and illegal move are request errors, engine stays Ready" to { e ->
            checkOk(e.start(), "start")
            val bad = e.bestMove(req(fen = Fen("not a fen")))
            check(bad is ChessResult.Err && bad.error is ChessError.InvalidFen) { "bad fen: $bad" }
            val illegal = e.bestMove(req(moves = listOf(Uci("e2e5"))))
            check(illegal is ChessResult.Err && illegal.error is ChessError.IllegalMove) { "illegal: $illegal" }
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
        },
        "analyse emits Updates and Finished; MultiPV lines 1..3" to { e ->
            checkOk(e.start(), "start")
            val events = e.analyse(AnalysisRequest(startFen, emptyList(), SearchLimit(depth = 8), multiPv = 3)).toList()
            check(events.last() == AnalysisEvent.Finished) { "last: ${events.last()}" }
            val lines = events.filterIsInstance<AnalysisEvent.Update>().map { it.update.multiPv }.toSet()
            check(lines == setOf(1, 2, 3)) { "multipv lines $lines" }
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
        },
        "evaluation is from White's point of view (Black to move, White up two rooks)" to { e ->
            checkOk(e.start(), "start")
            val fen = Fen("4k3/8/8/8/8/8/8/R3K2R b - - 0 1")
            val last = e.analyse(AnalysisRequest(fen, emptyList(), SearchLimit(depth = 10))).toList()
                .filterIsInstance<AnalysisEvent.Update>().last().update.evaluation
            check(last is Evaluation.Centipawns && last.cp > 300 || last is Evaluation.Mate && last.moves > 0) { "eval $last" }
        },
        "mate is reported with sign: White mates in 1" to { e ->
            checkOk(e.start(), "start")
            val fen = Fen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
            val last = e.analyse(AnalysisRequest(fen, emptyList(), SearchLimit(depth = 6))).toList()
                .filterIsInstance<AnalysisEvent.Update>().last().update.evaluation
            check(last == Evaluation.Mate(1)) { "eval $last" }
        },
        "new request cancels running analyse with Failed(Cancelled)" to { e ->
            checkOk(e.start(), "start")
            val scope = CoroutineScope(Dispatchers.Default)
            val first = scope.async { e.analyse(AnalysisRequest(startFen, emptyList(), null)).toList() }
            delay(500)
            val r = e.bestMove(req())
            checkOk(r, "second request")
            val events = withTimeout(5000) { first.await() }
            check(events.last() == AnalysisEvent.Failed(ChessError.Cancelled)) { "last: ${events.lastOrNull()}" }
        },
        "a failing request still cancels the previous search (last request wins)" to { e ->
            checkOk(e.start(), "start")
            val scope = CoroutineScope(Dispatchers.Default)
            val first = scope.async { e.bestMove(req(level = level(Strength.Full, SearchLimit(moveTimeMs = 30_000)))) }
            delay(500)
            val second = e.bestMove(req(fen = checkmated))
            check(second == ChessResult.Err(ChessError.NoLegalMoves)) { "second: $second" }
            val r = withTimeout(5000) { first.await() }
            check(r == ChessResult.Err(ChessError.Cancelled)) { "first: $r" }
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
        },
        "collector cancellation frees the engine without events" to { e ->
            checkOk(e.start(), "start")
            val got = e.analyse(AnalysisRequest(startFen, emptyList(), null)).first()
            check(got is AnalysisEvent.Update) { "first: $got" }
            withTimeout(5000) { e.state.first { it == EngineState.Ready } }
            val r = e.bestMove(req())
            checkOk(r, "bestMove after cancellation")
        },
        "stop() during search: Err(Cancelled) and state Stopped" to { e ->
            checkOk(e.start(), "start")
            val scope = CoroutineScope(Dispatchers.Default)
            val search = scope.async { e.bestMove(req(level = level(Strength.Full, SearchLimit(moveTimeMs = 30_000)))) }
            delay(500)
            e.stop()
            val r = withTimeout(5000) { search.await() }
            check(r == ChessResult.Err(ChessError.Cancelled)) { "search: $r" }
            check(e.state.value == EngineState.Stopped) { "state ${e.state.value}" }
            val after = e.bestMove(req())
            check(after is ChessResult.Err && after.error is ChessError.EngineUnavailable) { "after stop: $after" }
        },
        "stop then start again gives Ready and working search" to { e ->
            checkOk(e.start(), "start")
            e.stop()
            checkOk(e.start(EngineConfig(threads = 2, hashMb = 32)), "restart")
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
            checkOk(e.bestMove(req()), "bestMove")
        },
        "newGame keeps the engine usable" to { e ->
            checkOk(e.start(), "start")
            e.newGame()
            check(e.state.value == EngineState.Ready) { "state ${e.state.value}" }
            checkOk(e.bestMove(req()), "bestMove")
        },
        "Strength.Handicap is a contract violation (IllegalArgumentException)" to { e ->
            checkOk(e.start(), "start")
            val thrown = try {
                e.bestMove(req(level = level(Strength.Handicap(1))))
                null
            } catch (t: IllegalArgumentException) {
                t
            }
            check(thrown != null) { "no exception" }
        },
    )
}
