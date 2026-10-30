// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import org.chesstrainer.core.game.AutoDrawReason
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.game.ClaimReason
import org.chesstrainer.core.game.GameStatus
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Square

/** Причини завершення партії (CONTRACTS 5). */
enum class GameTermination {
    CHECKMATE,
    STALEMATE,
    INSUFFICIENT_MATERIAL,
    DRAW_CLAIMED,
    DRAW_AUTOMATIC,
    RESIGNATION,
    ABANDONED,
}

/** Завершення партії, яке UI показує користувачеві. */
sealed interface GameOutcome {
    val termination: GameTermination

    data class Checkmate(val winner: Color) : GameOutcome {
        override val termination get() = GameTermination.CHECKMATE
    }

    data object Stalemate : GameOutcome {
        override val termination get() = GameTermination.STALEMATE
    }

    data object InsufficientMaterial : GameOutcome {
        override val termination get() = GameTermination.INSUFFICIENT_MATERIAL
    }

    data object DrawClaimed : GameOutcome {
        override val termination get() = GameTermination.DRAW_CLAIMED
    }

    data class DrawAutomatic(val reason: AutoDrawReason) : GameOutcome {
        override val termination get() = GameTermination.DRAW_AUTOMATIC
    }

    data class Resigned(val winner: Color) : GameOutcome {
        override val termination get() = GameTermination.RESIGNATION
    }
}

enum class EngineProblem { UNAVAILABLE, CRASHED, UNEXPECTED }

sealed interface EngineStatus {
    /** Рушій запускається при вході на екран (до 15 с, CONTRACTS 3.4). */
    data object Starting : EngineStatus

    /** Рушій не веде пошук: хід людини, пауза у фоні або партію завершено. */
    data object Idle : EngineStatus

    data object Thinking : EngineStatus

    /** Рушій недоступний; UI показує повідомлення й кнопку «Спробувати знову». */
    data class Problem(val kind: EngineProblem) : EngineStatus
}

data class PendingPromotion(val from: Square, val to: Square)

data class GameUiState(
    val game: ChessGame,
    val playerColor: Color,
    val flipped: Boolean = false,
    val selected: Square? = null,
    /** Поля, на які може піти вибрана фігура. */
    val targets: Set<Square> = emptySet(),
    val pendingPromotion: PendingPromotion? = null,
    val engine: EngineStatus = EngineStatus.Starting,
    /** Завершення, яке не випливає зі статусу партії: здача або вимога нічиєї. */
    val endedBy: GameOutcome? = null,
    /** Кількість півходів, на якій людина обрала «Продовжити» у смузі нічиєї. */
    val claimDismissedAtPly: Int? = null,
    /** Триває перевірка збереженої партії при запуску: хід поки не дозволено. */
    val awaitingResume: Boolean = false,
    /** Кількість півходів у збереженій незавершеній партії, яку запропоновано продовжити. */
    val resumeOfferPlies: Int? = null,
    /** Король під шахом, якщо є. */
    val checkSquare: Square? = null,
) {
    val position get() = game.position
    val lastMove: Move? get() = game.moves.lastOrNull()?.move
    val engineColor: Color get() = playerColor.opposite

    /** Бік, що стоїть унизу дошки. */
    val bottomColor: Color get() = if (flipped) playerColor.opposite else playerColor

    val outcome: GameOutcome? get() = endedBy ?: when (val status = game.status) {
        is GameStatus.Checkmate -> GameOutcome.Checkmate(status.winner)
        GameStatus.Stalemate -> GameOutcome.Stalemate
        GameStatus.InsufficientMaterial -> GameOutcome.InsufficientMaterial
        is GameStatus.DrawAutomatic -> GameOutcome.DrawAutomatic(status.reason)
        GameStatus.Ongoing, is GameStatus.DrawClaimable -> null
    }

    val isOver: Boolean get() = outcome != null
    val humanToMove: Boolean get() = !isOver && !awaitingResume && position.sideToMove == playerColor
    val engineToMove: Boolean get() = !isOver && !awaitingResume && position.sideToMove == engineColor

    /** Скільки півходів скасувати, щоб знову ходила людина. */
    val undoPlies: Int get() = if (position.sideToMove == playerColor) 2 else 1
    val canUndo: Boolean get() = !isOver && game.moves.size >= undoPlies

    /** Підстави для вимоги нічиєї, якщо смугу зараз слід показати (CONTRACTS 5, `DrawClaimable`). */
    val claimReasons: Set<ClaimReason>
        get() {
            val status = game.status
            val visible = status is GameStatus.DrawClaimable && humanToMove && claimDismissedAtPly != game.moves.size
            return if (visible) (status as GameStatus.DrawClaimable).reasons else emptySet()
        }
}
