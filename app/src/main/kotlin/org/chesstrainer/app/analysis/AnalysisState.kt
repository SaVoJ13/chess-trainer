// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.analysis

import org.chesstrainer.app.game.EngineProblem
import org.chesstrainer.app.game.PendingPromotion
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square

/** Одна лінія рушія (MultiPV): [rank] від 1, оцінка з погляду білих, [pv] це готовий текст SAN. */
data class AnalysisLine(val rank: Int, val evaluation: Evaluation, val depth: Int, val pv: String)

/** Помилка поля введення FEN; текст підбирає UI. */
enum class FenProblem { EMPTY, INVALID }

/** Чи можна просити в рушія оцінку цієї позиції. */
sealed interface PositionVerdict {
    data object Playable : PositionVerdict
    data class Checkmate(val winner: Color) : PositionVerdict
    data object Stalemate : PositionVerdict
    data object InsufficientMaterial : PositionVerdict
}

sealed interface AnalysisEngine {
    /** Аналіз не веде пошук (не запущено, зупинено користувачем, пауза у фоні). */
    data object Idle : AnalysisEngine

    /** Рушій запускається (до 15 с, CONTRACTS 3.4). */
    data object Starting : AnalysisEngine

    data object Running : AnalysisEngine

    data class Problem(val kind: EngineProblem) : AnalysisEngine
}

data class AnalysisState(
    /** Уся лінія ходів; на дошці показано позицію після [ply] півходів. */
    val game: ChessGame,
    val ply: Int,
    val flipped: Boolean = false,
    val selected: Square? = null,
    val targets: Set<Square> = emptySet(),
    val pendingPromotion: PendingPromotion? = null,
    /** Текст поля FEN: слідкує за позицією на дошці, доки користувач його не редагує. */
    val fenText: String,
    val fenProblem: FenProblem? = null,
    val multiPv: Int = 1,
    val maxMultiPv: Int = AnalysisController.MAX_MULTI_PV,
    val engine: AnalysisEngine = AnalysisEngine.Idle,
    val lines: List<AnalysisLine> = emptyList(),
    val verdict: PositionVerdict = PositionVerdict.Playable,
    val checkSquare: Square? = null,
) {
    val plyCount: Int get() = game.moves.size
    val position: Position get() = game.positionAt(ply)
    val lastMove: Move? get() = if (ply > 0) game.moves[ply - 1].move else null
    val canGoBack: Boolean get() = ply > 0
    val canGoForward: Boolean get() = ply < plyCount

    /** Знизу дошки білі, якщо не перевернуто. */
    val bottomColor: Color get() = if (flipped) Color.BLACK else Color.WHITE
    val running: Boolean get() = engine is AnalysisEngine.Starting || engine is AnalysisEngine.Running
    val canAnalyse: Boolean get() = verdict is PositionVerdict.Playable
}
