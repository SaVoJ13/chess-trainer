// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.game

import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.pgn.PgnGame
import org.chesstrainer.core.pgn.PgnResult

data class PlayedMove(val move: Move, val uci: Uci, val san: String, val positionAfter: Position)

/** Незмінна партія; кожна дія повертає новий екземпляр. */
interface ChessGame {
    val startPosition: Position
    val position: Position
    val moves: List<PlayedMove>
    val status: GameStatus

    /** `Err(IllegalMove)`, якщо хід нелегальний або партію завершено. */
    fun play(move: Move): ChessResult<ChessGame>

    /** `plies` > `moves.size` → повертає початкову позицію. */
    fun undo(plies: Int = 1): ChessGame

    /** 0 = початкова; для перегляду партії. */
    fun positionAt(ply: Int): Position
}

interface ChessGameFactory {
    /** `null` = стандартна початкова позиція. */
    fun new(start: Position? = null): ChessGame
    fun fromPgn(pgn: PgnGame): ChessResult<ChessGame>
    fun toPgn(game: ChessGame, headers: Map<String, String>, result: PgnResult): PgnGame
}

sealed interface GameStatus {
    data object Ongoing : GameStatus
    data class Checkmate(val winner: Color) : GameStatus
    data object Stalemate : GameStatus
    data object InsufficientMaterial : GameStatus

    /** Партія триває, гравець може вимагати нічию. */
    data class DrawClaimable(val reasons: Set<ClaimReason>) : GameStatus
    data class DrawAutomatic(val reason: AutoDrawReason) : GameStatus
}

enum class ClaimReason { THREEFOLD_REPETITION, FIFTY_MOVE_RULE }

enum class AutoDrawReason { FIVEFOLD_REPETITION, SEVENTY_FIVE_MOVE_RULE }
