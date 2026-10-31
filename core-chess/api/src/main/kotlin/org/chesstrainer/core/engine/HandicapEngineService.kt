// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules

/**
 * Декоратор над «сирим» [EngineService] (CONTRACTS 3.5): `bestMove` зі `Strength.Handicap` обирає хід сам,
 * решта запитів іде в [delegate] без змін (аналіз завжди на повну силу). Алгоритм: `HANDICAP.md`.
 *
 * Один запит Handicap це один пошук у [delegate] (`analyse` з MultiPV), тож правила 3.4 («останній запит
 * перемагає», `stop()` → `Cancelled`, `EngineCrashed`) виконує сам рушій, а декоратор передає його помилки як є.
 * Запит, відхилений до пошуку (`InvalidFen`, `IllegalMove`, `NoLegalMoves`), у рушій не потрапляє; лише коли
 * рушій зайнятий, йому передається той самий запит, щоб він скасував попередній пошук, як цього вимагає 3.4.
 *
 * [random] витрачається рівно двома киданнями на кожен успішно обраний хід Handicap.
 */
class HandicapEngineService(
    private val delegate: EngineService,
    private val rules: ChessRules,
    private val random: Random,
) : EngineService {

    override val state: StateFlow<EngineState> get() = delegate.state
    override val capabilities: EngineCapabilities? get() = delegate.capabilities

    override suspend fun start(config: EngineConfig): ChessResult<Unit> = delegate.start(config)
    override suspend fun stop() = delegate.stop()
    override suspend fun newGame() = delegate.newGame()
    override fun analyse(request: AnalysisRequest): Flow<AnalysisEvent> = delegate.analyse(request)

    override suspend fun bestMove(request: MoveRequest): ChessResult<EngineMove> {
        val handicap = request.level.strength as? Strength.Handicap ?: return delegate.bestMove(request)
        HandicapPolicy.profile(handicap.step) // IllegalArgumentException для кроку поза діапазоном: до прийняття запиту

        val lines = minOf(HandicapPolicy.CANDIDATES, capabilities?.maxMultiPv ?: HandicapPolicy.CANDIDATES)
        val analysis = AnalysisRequest(request.startFen, request.moves, request.level.limit, lines)

        val running = delegate.state.value.let { it == EngineState.Ready || it == EngineState.Busy }
        if (!running) return search(analysis, expectedLines = lines).asError() // EngineUnavailable від рушія

        val position = when (val parsed = positionAfter(request.startFen, request.moves)) {
            is ChessResult.Ok -> parsed.value
            is ChessResult.Err -> return rejected(analysis, parsed.error)
        }
        val legal = rules.legalMoves(position)
        if (legal.isEmpty()) return rejected(analysis, ChessError.NoLegalMoves)

        val updates = when (val found = search(analysis, expectedLines = minOf(lines, legal.size))) {
            is SearchResult.Lines -> found.updates
            is SearchResult.Error -> return ChessResult.Err(found.error)
        }
        val candidates = updates.mapNotNull { candidateOf(it, position) }
        // Кидання витрачаються завжди, навіть коли кандидат один: однакова кількість викликів Random на хід.
        val blindDraw: Double
        val pickDraw: Double
        synchronized(random) {
            blindDraw = random.nextDouble()
            pickDraw = random.nextDouble()
        }
        if (candidates.isEmpty()) {
            return ChessResult.Err(ChessError.EngineCrashed("engine returned no usable candidate moves"))
        }
        val best = HandicapPolicy.choose(candidates, handicap.step, blindDraw, pickDraw)
        return ChessResult.Ok(EngineMove(best, ponder = null))
    }

    /** Відхилений до пошуку запит: якщо рушій зайнятий, він мусить скасувати попередній пошук (3.4). */
    private suspend fun rejected(analysis: AnalysisRequest, error: ChessError): ChessResult<EngineMove> {
        if (delegate.state.value == EngineState.Busy) delegate.analyse(analysis).collect()
        return ChessResult.Err(error)
    }

    private sealed interface SearchResult {
        data class Lines(val updates: List<AnalysisUpdate>) : SearchResult
        data class Error(val error: ChessError) : SearchResult
    }

    private fun SearchResult.asError(): ChessResult<EngineMove> = when (this) {
        is SearchResult.Error -> ChessResult.Err(error)
        is SearchResult.Lines -> ChessResult.Err(ChessError.EngineUnavailable("engine is not running"))
    }

    /**
     * Один пошук MultiPV. Береться найглибша глибина, на якій є всі [expectedLines] ліній (інакше найсвіжіші
     * лінії кожного номера), щоб не зіставляти оцінки різної глибини, коли пошук урвався посеред ітерації.
     */
    private suspend fun search(analysis: AnalysisRequest, expectedLines: Int): SearchResult {
        val byDepth = sortedMapOf<Int, MutableMap<Int, AnalysisUpdate>>()
        val latest = mutableMapOf<Int, AnalysisUpdate>()
        var failure: ChessError? = null
        delegate.analyse(analysis).collect { event ->
            when (event) {
                is AnalysisEvent.Update -> {
                    byDepth.getOrPut(event.update.depth) { mutableMapOf() }[event.update.multiPv] = event.update
                    latest[event.update.multiPv] = event.update
                }
                AnalysisEvent.Finished -> Unit
                is AnalysisEvent.Failed -> failure = event.error
            }
        }
        failure?.let { return SearchResult.Error(it) }
        val complete = byDepth.entries.lastOrNull { it.value.size >= expectedLines }?.value ?: latest
        return SearchResult.Lines(complete.toSortedMap().values.toList())
    }

    private fun candidateOf(update: AnalysisUpdate, position: Position): HandicapCandidate? {
        val first = update.pv.firstOrNull() ?: return null
        if (rules.parseUci(position, first.value) !is ChessResult.Ok) return null
        return HandicapCandidate(first, moverScore(update.evaluation, position.sideToMove))
    }

    /** Оцінка рушія (з погляду білих) з погляду сторони, що ходить; мат у N ходів це `MATE_CP − 10·N`. */
    private fun moverScore(evaluation: Evaluation, mover: Color): Int {
        val white = when (evaluation) {
            is Evaluation.Centipawns -> evaluation.cp
            is Evaluation.Mate -> {
                val magnitude = HandicapPolicy.MATE_CP - minOf(kotlin.math.abs(evaluation.moves), 100) * 10
                if (evaluation.moves > 0) magnitude else -magnitude
            }
        }
        return (if (mover == Color.WHITE) white else -white).coerceIn(-HandicapPolicy.MATE_CP, HandicapPolicy.MATE_CP)
    }

    private fun positionAfter(startFen: Fen, moves: List<Uci>): ChessResult<Position> {
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

    companion object {
        /** Мінімум `capabilities.maxMultiPv`, за якого пресети пропонують рівні Handicap. */
        const val REQUIRED_MULTI_PV: Int = HandicapPolicy.CANDIDATES
    }
}
