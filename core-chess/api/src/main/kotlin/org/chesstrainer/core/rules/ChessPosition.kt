// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.rules

import org.chesstrainer.core.model.CastlingRights
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Piece
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square

/**
 * Незмінна позиція, яку створює [ChesslibRules]. Рівність і хеш визначає [positionKey].
 *
 * [enPassant] це поле за специфікацією FEN (після кожного подвійного ходу пішака); у [positionKey]
 * воно потрапляє лише коли взяття легально можливе, це визначає [epCapturable] (обчислюється ліниво).
 */
internal class ChessPosition(
    private val board: Array<Piece?>,
    override val sideToMove: Color,
    override val castling: CastlingRights,
    override val enPassant: Square?,
    override val halfmoveClock: Int,
    override val fullmoveNumber: Int,
    private val epCapturable: (ChessPosition) -> Boolean,
) : Position {

    init {
        require(board.size == 64) { "Board must have 64 squares" }
    }

    override fun pieceAt(square: Square): Piece? = board[square.index]

    private val placementAndState: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildString {
            appendPlacement(board)
            append(' ')
            append(if (sideToMove == Color.WHITE) 'w' else 'b')
            append(' ')
            append(castlingField(castling))
        }
    }

    override val fen: Fen by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Fen("$placementAndState ${enPassant?.name ?: "-"} $halfmoveClock $fullmoveNumber")
    }

    override val positionKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val ep = if (enPassant != null && epCapturable(this)) enPassant.name else "-"
        "$placementAndState $ep"
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Position && other.positionKey == positionKey)

    override fun hashCode(): Int = positionKey.hashCode()

    override fun toString(): String = fen.value

    /** Копія дошки для побудови наступної позиції. */
    fun boardCopy(): Array<Piece?> = board.copyOf()

    companion object {
        private fun StringBuilder.appendPlacement(board: Array<Piece?>) {
            for (rank in 7 downTo 0) {
                var empty = 0
                for (file in 0..7) {
                    val piece = board[rank * 8 + file]
                    if (piece == null) {
                        empty++
                    } else {
                        if (empty > 0) append(empty)
                        empty = 0
                        append(symbol(piece))
                    }
                }
                if (empty > 0) append(empty)
                if (rank > 0) append('/')
            }
        }

        fun castlingField(rights: CastlingRights): String {
            val s = buildString {
                if (rights.whiteKingSide) append('K')
                if (rights.whiteQueenSide) append('Q')
                if (rights.blackKingSide) append('k')
                if (rights.blackQueenSide) append('q')
            }
            return s.ifEmpty { "-" }
        }

        fun symbol(piece: Piece): Char {
            val c = when (piece.type) {
                PieceType.PAWN -> 'p'
                PieceType.KNIGHT -> 'n'
                PieceType.BISHOP -> 'b'
                PieceType.ROOK -> 'r'
                PieceType.QUEEN -> 'q'
                PieceType.KING -> 'k'
            }
            return if (piece.color == Color.WHITE) c.uppercaseChar() else c
        }

        fun pieceOf(symbol: Char): Piece? {
            val type = when (symbol.lowercaseChar()) {
                'p' -> PieceType.PAWN
                'n' -> PieceType.KNIGHT
                'b' -> PieceType.BISHOP
                'r' -> PieceType.ROOK
                'q' -> PieceType.QUEEN
                'k' -> PieceType.KING
                else -> return null
            }
            return Piece(if (symbol.isUpperCase()) Color.WHITE else Color.BLACK, type)
        }
    }
}
