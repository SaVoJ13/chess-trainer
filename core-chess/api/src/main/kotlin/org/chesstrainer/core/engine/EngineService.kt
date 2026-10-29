// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci

interface EngineService {
    val state: StateFlow<EngineState>

    /** `null` до першого успішного [start]. */
    val capabilities: EngineCapabilities?

    /** Ready/Busy: повторний start ігнорується (Ok). Змінити налаштування: [stop] і [start] з новою конфігурацією. */
    suspend fun start(config: EngineConfig = EngineConfig()): ChessResult<Unit>
    suspend fun stop()

    /** UCI "ucinewgame": очищує стан між партіями. */
    suspend fun newGame()
    suspend fun bestMove(request: MoveRequest): ChessResult<EngineMove>

    /** Скасування збору = UCI "stop"; Flow завершується разом із пошуком. */
    fun analyse(request: AnalysisRequest): Flow<AnalysisEvent>
}

sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data object Ready : EngineState
    data object Busy : EngineState
    data class Failed(val error: ChessError) : EngineState
}

data class EngineCapabilities(
    val name: String,
    val version: String,
    /** З опції UCI_Elo, яку повідомляє рушій; `null`, якщо опції немає. */
    val eloRange: IntRange?,
    /** З опції "Skill Level". */
    val skillLevelRange: IntRange?,
    val maxMultiPv: Int,
    /** Межа опції Threads рушія (довідково). Реальну кількість потоків обирає app через [EngineConfig]. */
    val threadsMax: Int,
    /** Межа опції Hash (довідково). */
    val hashMaxMb: Int,
)

/**
 * Хоча б одне поле не `null`, інакше [IllegalArgumentException]. Нескінченний аналіз задається
 * не тут, а через `AnalysisRequest.limit == null`.
 */
data class SearchLimit(val depth: Int? = null, val moveTimeMs: Long? = null, val nodes: Long? = null) {
    init {
        require(depth != null || moveTimeMs != null || nodes != null) { "SearchLimit needs at least one limit" }
    }
}

data class MoveRequest(val startFen: Fen, val moves: List<Uci>, val level: DifficultyLevel)

/** `limit == null` → нескінченний аналіз до скасування. */
data class AnalysisRequest(
    val startFen: Fen,
    val moves: List<Uci>,
    val limit: SearchLimit?,
    val multiPv: Int = 1,
)

data class EngineMove(val best: Uci, val ponder: Uci?)

/** Значення поза межами `capabilities` обрізаються реалізацією; типові збігаються зі стандартними опціями Stockfish. */
data class EngineConfig(val threads: Int = 1, val hashMb: Int = 16)

sealed interface AnalysisEvent {
    data class Update(val update: AnalysisUpdate) : AnalysisEvent

    /** Пошук завершився за лімітом; після нього Flow завершується. */
    data object Finished : AnalysisEvent

    /**
     * Cancelled (замінений новим запитом), EngineCrashed, EngineUnavailable, NoLegalMoves;
     * після нього Flow завершується.
     */
    data class Failed(val error: ChessError) : AnalysisEvent
}

data class AnalysisUpdate(
    val depth: Int,
    val selDepth: Int?,
    val multiPv: Int,
    val evaluation: Evaluation,
    val pv: List<Uci>,
    val nodes: Long,
    val nps: Long,
    val timeMs: Long,
)
