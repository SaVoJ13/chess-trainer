// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.review

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.chesstrainer.app.storage.GameId
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.pgn.PgnResult
import org.chesstrainer.core.rules.ChessRules

/** Стан перегляду партії: на дошці позиція після [ply] півходів (0 = початкова). */
data class ReviewState(
    val game: ChessGame,
    /** Результат з тегу PGN (CONTRACTS 3.2), а не зі статусу партії. */
    val result: PgnResult,
    val headers: Map<String, String>,
    val ply: Int,
    val flipped: Boolean,
    val bottom: Color,
    val checkSquare: Square?,
) {
    val plyCount: Int get() = game.moves.size
    val position: Position get() = game.positionAt(ply)

    /** Хід, що привів до показаної позиції. */
    val lastMove: Move? get() = if (ply > 0) game.moves[ply - 1].move else null
    val canGoBack: Boolean get() = ply > 0
    val canGoForward: Boolean get() = ply < plyCount

    /** Бік, що стоїть унизу дошки. */
    val bottomColor: Color get() = if (flipped) bottom.opposite else bottom
}

/**
 * Перегляд завершеної чи імпортованої партії без можливості ходити (CONTRACTS 5, «Список партій»).
 * Позиції дає лише `ChessGame.positionAt`; шахової логіки тут немає.
 */
class ReviewController(
    val id: GameId,
    private val rules: ChessRules,
    game: ChessGame,
    result: PgnResult,
    headers: Map<String, String>,
    bottom: Color = Color.WHITE,
    startPly: Int = game.moves.size,
) {
    private val _state = MutableStateFlow(build(game, result, headers, startPly.coerceIn(0, game.moves.size), false, bottom))
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    fun first() = goTo(0)

    fun previous() = goTo(state.value.ply - 1)

    fun next() = goTo(state.value.ply + 1)

    fun last() = goTo(state.value.plyCount)

    /** Перехід на позицію після [ply] півходів; значення поза межами обмежуються. */
    fun goTo(ply: Int) {
        _state.update { s ->
            val target = ply.coerceIn(0, s.plyCount)
            if (target == s.ply) s else s.copy(ply = target, checkSquare = checkSquareOf(s.game.positionAt(target)))
        }
    }

    fun flip() = _state.update { it.copy(flipped = !it.flipped) }

    private fun build(
        game: ChessGame,
        result: PgnResult,
        headers: Map<String, String>,
        ply: Int,
        flipped: Boolean,
        bottom: Color,
    ) = ReviewState(game, result, headers, ply, flipped, bottom, checkSquareOf(game.positionAt(ply)))

    private fun checkSquareOf(position: Position): Square? {
        if (!rules.isInCheck(position)) return null
        return (0..63).map { Square(it) }.firstOrNull {
            val piece = position.pieceAt(it)
            piece?.type == PieceType.KING && piece.color == position.sideToMove
        }
    }
}

/** Рядок списку ходів: номер ходу й півходи (нумерація від 1) білих і чорних; `null`, якщо його немає в партії. */
data class MoveRow(val number: Int, val white: Int?, val black: Int?)

/**
 * Рядки списку ходів з урахуванням початкової позиції: партія з FEN, де першими ходять чорні, починається
 * рядком без ходу білих.
 */
fun moveRows(game: ChessGame): List<MoveRow> {
    val total = game.moves.size
    val rows = mutableListOf<MoveRow>()
    var number = game.startPosition.fullmoveNumber
    var ply = 1
    if (total > 0 && game.startPosition.sideToMove == Color.BLACK) {
        rows += MoveRow(number, null, 1)
        ply = 2
        number++
    }
    while (ply <= total) {
        rows += MoveRow(number, ply, if (ply + 1 <= total) ply + 1 else null)
        ply += 2
        number++
    }
    return rows
}
