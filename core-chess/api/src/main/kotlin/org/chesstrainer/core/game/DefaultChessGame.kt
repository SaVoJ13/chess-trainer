// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.game

import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.rules.ChessRules

/**
 * Незмінна партія. Власні правила нічиєї (CONTRACTS 3.2) рахуються тут, а не в бібліотеці правил:
 * повторення за [Position.positionKey], лічильник півходів з [Position.halfmoveClock].
 */
internal class DefaultChessGame(
    private val rules: ChessRules,
    override val startPosition: Position,
    override val moves: List<PlayedMove> = emptyList(),
) : ChessGame {

    override val position: Position get() = moves.lastOrNull()?.positionAfter ?: startPosition

    override val status: GameStatus by lazy(LazyThreadSafetyMode.PUBLICATION) { computeStatus() }

    override fun play(move: Move): ChessResult<ChessGame> {
        if (status.isOver) return ChessResult.Err(ChessError.IllegalMove(rules.toUci(move).value))
        return append(move)
    }

    /**
     * Додає легальний хід без перевірки завершення партії. Для відтворення PGN: історичні партії
     * можуть тривати після п'ятого повторення чи 75 ходів (FIDE ввела ці правила пізніше).
     */
    fun append(move: Move): ChessResult<DefaultChessGame> {
        val before = position
        return when (val applied = rules.apply(before, move)) {
            is ChessResult.Err -> applied
            is ChessResult.Ok -> {
                val played = PlayedMove(move, rules.toUci(move), rules.toSan(before, move), applied.value)
                ChessResult.Ok(DefaultChessGame(rules, startPosition, moves + played))
            }
        }
    }

    override fun undo(plies: Int): ChessGame {
        require(plies >= 0) { "plies must not be negative: $plies" }
        if (plies == 0) return this
        return DefaultChessGame(rules, startPosition, moves.dropLast(plies))
    }

    override fun positionAt(ply: Int): Position {
        require(ply in 0..moves.size) { "ply $ply out of range 0..${moves.size}" }
        return if (ply == 0) startPosition else moves[ply - 1].positionAfter
    }

    private fun computeStatus(): GameStatus {
        val current = position
        if (rules.isCheckmate(current)) return GameStatus.Checkmate(current.sideToMove.opposite)
        if (rules.isStalemate(current)) return GameStatus.Stalemate
        if (rules.isInsufficientMaterial(current)) return GameStatus.InsufficientMaterial

        val repetitions = repetitionCount(current)
        // Якщо одночасно 75 ходів і п'ятикратне повторення, причиною вказується 75 ходів (порядок CONTRACTS 3.2).
        if (current.halfmoveClock >= SEVENTY_FIVE_MOVE_PLIES) {
            return GameStatus.DrawAutomatic(AutoDrawReason.SEVENTY_FIVE_MOVE_RULE)
        }
        if (repetitions >= FIVEFOLD) return GameStatus.DrawAutomatic(AutoDrawReason.FIVEFOLD_REPETITION)

        val claims = buildSet {
            if (repetitions >= THREEFOLD) add(ClaimReason.THREEFOLD_REPETITION)
            if (current.halfmoveClock >= FIFTY_MOVE_PLIES) add(ClaimReason.FIFTY_MOVE_RULE)
        }
        return if (claims.isEmpty()) GameStatus.Ongoing else GameStatus.DrawClaimable(claims)
    }

    private fun repetitionCount(current: Position): Int {
        val key = current.positionKey
        var count = if (startPosition.positionKey == key) 1 else 0
        for (played in moves) if (played.positionAfter.positionKey == key) count++
        return count
    }

    private val GameStatus.isOver: Boolean
        get() = this is GameStatus.Checkmate || this is GameStatus.Stalemate ||
            this is GameStatus.InsufficientMaterial || this is GameStatus.DrawAutomatic

    private companion object {
        const val THREEFOLD = 3
        const val FIVEFOLD = 5
        const val FIFTY_MOVE_PLIES = 100
        const val SEVENTY_FIVE_MOVE_PLIES = 150
    }
}
