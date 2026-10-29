// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import org.chesstrainer.core.model.CastlingRights
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Piece
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules

/** Позиція-заглушка: FEN це довільний ключ, після ходу до нього дописується `>uci`. */
data class StubPosition(override val fen: Fen) : Position {
    override val positionKey: String get() = fen.value
    override val sideToMove: Color get() = Color.WHITE
    override val castling: CastlingRights get() = CastlingRights(false, false, false, false)
    override val enPassant: Square? get() = null
    override val halfmoveClock: Int get() = 0
    override val fullmoveNumber: Int get() = 1
    override fun pieceAt(square: Square): Piece? = null
}

/** Мінімальні правила для тестів Fake: легальні ходи беруться з таблиці за ключем позиції. */
class StubRules(private val legal: Map<String, List<String>>) : ChessRules {
    override fun parseFen(fen: String): ChessResult<Position> =
        if (fen in legal) ChessResult.Ok(StubPosition(Fen(fen))) else ChessResult.Err(ChessError.InvalidFen(fen))

    override fun legalMoves(position: Position): List<Move> =
        legal[position.fen.value].orEmpty().map(::moveOf)

    override fun parseUci(position: Position, uci: String): ChessResult<Move> =
        if (uci in legal[position.fen.value].orEmpty()) ChessResult.Ok(moveOf(uci))
        else ChessResult.Err(ChessError.IllegalMove(uci))

    override fun apply(position: Position, move: Move): ChessResult<Position> {
        val uci = toUci(move).value
        return if (uci in legal[position.fen.value].orEmpty()) {
            ChessResult.Ok(StubPosition(Fen("${position.fen.value}>$uci")))
        } else {
            ChessResult.Err(ChessError.IllegalMove(uci))
        }
    }

    override fun toUci(move: Move): Uci = Uci(move.from.name + move.to.name)

    private fun moveOf(uci: String) = Move(Square.parse(uci.substring(0, 2))!!, Square.parse(uci.substring(2, 4))!!)

    override fun startPosition(): Position = error("not needed")
    override fun legalMovesFrom(position: Position, from: Square): List<Move> = error("not needed")
    override fun toSan(position: Position, move: Move): String = error("not needed")
    override fun parseSan(position: Position, san: String): ChessResult<Move> = error("not needed")
    override fun isInCheck(position: Position): Boolean = error("not needed")
    override fun needsPromotion(position: Position, from: Square, to: Square): Boolean = error("not needed")
    override fun isCheckmate(position: Position): Boolean = error("not needed")
    override fun isStalemate(position: Position): Boolean = error("not needed")
    override fun isInsufficientMaterial(position: Position): Boolean = error("not needed")
}
