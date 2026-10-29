// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.model

@JvmInline
value class Fen(val value: String)

/** "e2e4", "e7e8q"; рокіровка у форматі e1g1. */
@JvmInline
value class Uci(val value: String)

/** Поле дошки 0..63; a1 = 0, b1 = 1, …, h8 = 63. */
@JvmInline
value class Square(val index: Int) {
    init {
        require(index in 0..63) { "Square index out of range: $index" }
    }

    val file: Int get() = index % 8
    val rank: Int get() = index / 8
    val name: String get() = "${'a' + file}${rank + 1}"

    companion object {
        fun of(file: Int, rank: Int): Square {
            require(file in 0..7 && rank in 0..7) { "Square out of range: file=$file, rank=$rank" }
            return Square(rank * 8 + file)
        }

        /** `null` для некоректного рядка. */
        fun parse(name: String): Square? {
            if (name.length != 2) return null
            val file = name[0] - 'a'
            val rank = name[1] - '1'
            return if (file in 0..7 && rank in 0..7) of(file, rank) else null
        }
    }
}

enum class Color {
    WHITE, BLACK;

    val opposite: Color get() = if (this == WHITE) BLACK else WHITE
}

enum class PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }

data class Piece(val color: Color, val type: PieceType)

data class Move(val from: Square, val to: Square, val promotion: PieceType? = null)

data class CastlingRights(
    val whiteKingSide: Boolean,
    val whiteQueenSide: Boolean,
    val blackKingSide: Boolean,
    val blackQueenSide: Boolean,
)

/**
 * Незмінна позиція. Рівність двох позицій дорівнює рівності [positionKey]
 * (реалізація перевизначає `equals`/`hashCode`).
 */
interface Position {
    /** Повний FEN з лічильниками; поле взяття на проході за специфікацією FEN (після кожного подвійного ходу пішака). */
    val fen: Fen

    /**
     * FEN без лічильників; поле взяття на проході лише коли таке взяття легально можливе
     * (з урахуванням зв'язки й шаху). Ключ повторень позиції.
     */
    val positionKey: String
    val sideToMove: Color
    val castling: CastlingRights
    val enPassant: Square?
    val halfmoveClock: Int
    val fullmoveNumber: Int

    fun pieceAt(square: Square): Piece?
}
