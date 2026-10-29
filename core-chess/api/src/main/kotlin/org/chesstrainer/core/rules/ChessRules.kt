// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.rules

import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci

/**
 * Порядок ходів у [legalMoves] / [legalMovesFrom] не гарантується. Споживачі, яким потрібна
 * детермінованість, сортують самі.
 */
interface ChessRules {
    fun startPosition(): Position

    /** Перевіряє синтаксис і базову легальність (по одному королю, сторона не під шахом не на своєму ході). */
    fun parseFen(fen: String): ChessResult<Position>
    fun legalMoves(position: Position): List<Move>

    /** Для підсвічування на дошці. */
    fun legalMovesFrom(position: Position, from: Square): List<Move>

    /** Нелегальний хід → `Err(IllegalMove)`; виняток не кидається. */
    fun apply(position: Position, move: Move): ChessResult<Position>
    fun parseUci(position: Position, uci: String): ChessResult<Move>
    fun toUci(move: Move): Uci

    /** З "+" і "#"; нелегальний хід → [IllegalArgumentException] (порушення контракту програмістом). */
    fun toSan(position: Position, move: Move): String
    fun parseSan(position: Position, san: String): ChessResult<Move>
    fun isInCheck(position: Position): Boolean

    /** UI питає, чи показувати вибір фігури. */
    fun needsPromotion(position: Position, from: Square, to: Square): Boolean

    /** Шах і немає легальних ходів. */
    fun isCheckmate(position: Position): Boolean

    /** Немає шаху і немає легальних ходів. */
    fun isStalemate(position: Position): Boolean

    /** Правило з CONTRACTS 3.2. */
    fun isInsufficientMaterial(position: Position): Boolean
}
