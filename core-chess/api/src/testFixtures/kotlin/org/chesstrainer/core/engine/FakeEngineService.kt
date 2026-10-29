// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules

/**
 * Детермінований фейк [EngineService] для тестів `learning`, `app` і контрактних тестів (CONTRACTS 7.3).
 * Без реального часу, потоків і годинника: усе відбувається в корутині викликача.
 */
class FakeEngineService(
    private val rules: ChessRules,
    capabilities: EngineCapabilities = DEFAULT_CAPABILITIES,
) : EngineService {

    private val knownCapabilities = capabilities
    private val lock = Any()
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)

    private var _capabilities: EngineCapabilities? = null
    private var current: Search? = null
    private var held = false
    private var bestMoveResponder: ((MoveRequest) -> ChessResult<EngineMove>)? = null
    private var analyseResponder: ((AnalysisRequest) -> List<AnalysisEvent>)? = null

    private val _appliedConfigs = CopyOnWriteArrayList<EngineConfig>()
    private val _moveRequests = CopyOnWriteArrayList<MoveRequest>()
    private val _analysisRequests = CopyOnWriteArrayList<AnalysisRequest>()

    /** Конфігурація після обрізання за `capabilities`. */
    val appliedConfigs: List<EngineConfig> get() = _appliedConfigs.toList()
    val moveRequests: List<MoveRequest> get() = _moveRequests.toList()
    val analysisRequests: List<AnalysisRequest> get() = _analysisRequests.toList()

    override val state: StateFlow<EngineState> = _state.asStateFlow()
    override val capabilities: EngineCapabilities? get() = synchronized(lock) { _capabilities }

    /** Пошук і причина його переривання: `null` після [release] означає «продовжуй». */
    private class Search(val held: Boolean) {
        val outcome = CompletableDeferred<ChessError?>()
    }

    // --- сценарії ---

    fun onBestMove(responder: (MoveRequest) -> ChessResult<EngineMove>) {
        synchronized(lock) { bestMoveResponder = responder }
    }

    /** Список має закінчуватися [AnalysisEvent.Finished] або [AnalysisEvent.Failed]. */
    fun onAnalyse(responder: (AnalysisRequest) -> List<AnalysisEvent>) {
        synchronized(lock) { analyseResponder = responder }
    }

    /** Нові пошуки чекають [release], щоб тест міг перевірити скасування. */
    fun holdSearches() {
        synchronized(lock) { held = true }
    }

    fun release() {
        synchronized(lock) {
            held = false
            current?.takeIf { it.held }?.outcome?.complete(null)
        }
    }

    fun crash(reason: String = "fake crash") {
        synchronized(lock) {
            val error = ChessError.EngineCrashed(reason)
            current?.outcome?.complete(error)
            current = null
            _state.value = EngineState.Failed(error)
        }
    }

    // --- EngineService ---

    override suspend fun start(config: EngineConfig): ChessResult<Unit> = synchronized(lock) {
        when (_state.value) {
            EngineState.Ready, EngineState.Busy, EngineState.Starting -> return@synchronized ChessResult.Ok(Unit)
            EngineState.Stopped, is EngineState.Failed -> Unit
        }
        _state.value = EngineState.Starting
        _appliedConfigs += EngineConfig(
            threads = config.threads.coerceIn(1, knownCapabilities.threadsMax),
            hashMb = config.hashMb.coerceIn(1, knownCapabilities.hashMaxMb),
        )
        _capabilities = knownCapabilities
        _state.value = EngineState.Ready
        ChessResult.Ok(Unit)
    }

    override suspend fun stop() {
        synchronized(lock) {
            current?.outcome?.complete(ChessError.Cancelled)
            current = null
            _state.value = EngineState.Stopped
        }
    }

    override suspend fun newGame() = Unit

    override suspend fun bestMove(request: MoveRequest): ChessResult<EngineMove> {
        require(request.level.strength !is Strength.Handicap) {
            "Strength.Handicap is handled by HandicapEngineService, not by a raw engine"
        }
        val search = begin() ?: return ChessResult.Err(unavailable())
        try {
            _moveRequests += request
            awaitRelease(search)?.let { return ChessResult.Err(it) }
            val responder = synchronized(lock) { bestMoveResponder }
            if (responder != null) return responder(request)
            return defaultBestMove(request)
        } finally {
            finish(search)
        }
    }

    override fun analyse(request: AnalysisRequest): Flow<AnalysisEvent> = flow {
        val search = begin()
        if (search == null) {
            emit(AnalysisEvent.Failed(unavailable()))
            return@flow
        }
        try {
            _analysisRequests += request
            val error = awaitRelease(search)
            if (error != null) {
                emit(AnalysisEvent.Failed(error))
                return@flow
            }
            val responder = synchronized(lock) { analyseResponder }
            val events = if (responder != null) responder(request) else defaultAnalysis(request)
            events.forEach { emit(it) }
        } finally {
            finish(search)
        }
    }

    // --- внутрішнє ---

    private fun unavailable() = ChessError.EngineUnavailable("fake engine is not running")

    /** Починає пошук: скасовує попередній. `null`, якщо рушій не запущено. */
    private fun begin(): Search? = synchronized(lock) {
        when (_state.value) {
            EngineState.Ready, EngineState.Busy -> Unit
            else -> return@synchronized null
        }
        current?.outcome?.complete(ChessError.Cancelled)
        Search(held).also {
            current = it
            _state.value = EngineState.Busy
        }
    }

    private fun finish(search: Search) {
        synchronized(lock) {
            if (current === search) {
                current = null
                _state.value = EngineState.Ready
            }
        }
    }

    /** `null` — можна продовжувати; інакше причина переривання. */
    private suspend fun awaitRelease(search: Search): ChessError? =
        if (search.held) search.outcome.await() else null

    private fun legalMovesSorted(position: Position): List<Pair<Uci, Move>> =
        rules.legalMoves(position).map { rules.toUci(it) to it }.sortedBy { it.first.value }

    private fun positionOf(startFen: Fen, moves: List<Uci>): ChessResult<Position> {
        var position = when (val parsed = rules.parseFen(startFen.value)) {
            is ChessResult.Ok -> parsed.value
            is ChessResult.Err -> return parsed
        }
        for (uci in moves) {
            val move = when (val parsed = rules.parseUci(position, uci.value)) {
                is ChessResult.Ok -> parsed.value
                is ChessResult.Err -> return parsed
            }
            position = when (val applied = rules.apply(position, move)) {
                is ChessResult.Ok -> applied.value
                is ChessResult.Err -> return applied
            }
        }
        return ChessResult.Ok(position)
    }

    private fun defaultBestMove(request: MoveRequest): ChessResult<EngineMove> {
        val position = when (val result = positionOf(request.startFen, request.moves)) {
            is ChessResult.Ok -> result.value
            is ChessResult.Err -> return result
        }
        val first = legalMovesSorted(position).firstOrNull() ?: return ChessResult.Err(ChessError.NoLegalMoves)
        return ChessResult.Ok(EngineMove(best = first.first, ponder = null))
    }

    private fun defaultAnalysis(request: AnalysisRequest): List<AnalysisEvent> {
        val position = when (val result = positionOf(request.startFen, request.moves)) {
            is ChessResult.Ok -> result.value
            is ChessResult.Err -> return listOf(AnalysisEvent.Failed(result.error))
        }
        val sorted = legalMovesSorted(position)
        if (sorted.isEmpty()) return listOf(AnalysisEvent.Failed(ChessError.NoLegalMoves))
        val lines = minOf(request.multiPv, sorted.size)
        val updates = (0 until lines).map { i ->
            AnalysisEvent.Update(
                AnalysisUpdate(
                    depth = 1,
                    selDepth = null,
                    multiPv = i + 1,
                    evaluation = Evaluation.Centipawns(0),
                    pv = listOf(sorted[i].first),
                    nodes = 1,
                    nps = 0,
                    timeMs = 0,
                ),
            )
        }
        return updates + AnalysisEvent.Finished
    }

    companion object {
        /** `threadsMax`, `hashMaxMb` і `maxMultiPv` це тестові значення, не факти про Stockfish. */
        val DEFAULT_CAPABILITIES = EngineCapabilities(
            name = "FakeEngine",
            version = "0",
            eloRange = 1320..3190,
            skillLevelRange = 0..20,
            maxMultiPv = 5,
            threadsMax = 4,
            hashMaxMb = 256,
        )
    }
}
