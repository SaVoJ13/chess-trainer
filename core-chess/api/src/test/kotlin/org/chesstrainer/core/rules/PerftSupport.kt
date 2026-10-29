// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.rules

import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Position
import org.junit.Assert.assertEquals

/**
 * Еталони: https://www.chessprogramming.org/Perft_Results (прочитано 2026-10-29).
 * Позиція 2 у таблиці це «Kiwipete»; FEN без лічильників на сторінці доповнено «0 1».
 */
object PerftSupport {
    const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    const val KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
    const val POSITION_3 = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"
    const val POSITION_4 = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"
    const val POSITION_5 = "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8"
    const val POSITION_6 = "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10"

    val rules = ChesslibRules()

    fun perft(position: Position, depth: Int): Long {
        val moves = rules.legalMoves(position)
        if (depth == 1) return moves.size.toLong()
        var total = 0L
        for (move in moves) {
            val next = (rules.apply(position, move) as ChessResult.Ok).value
            total += perft(next, depth - 1)
        }
        return total
    }

    fun check(fen: String, vararg expected: Pair<Int, Long>) {
        val position = (rules.parseFen(fen) as ChessResult.Ok).value
        for ((depth, nodes) in expected) {
            assertEquals("perft() for $fen", nodes, perft(position, depth))
        }
    }
}
