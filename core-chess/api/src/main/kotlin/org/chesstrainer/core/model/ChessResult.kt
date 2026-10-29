// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.model

sealed interface ChessResult<out T> {
    data class Ok<T>(val value: T) : ChessResult<T>
    data class Err(val error: ChessError) : ChessResult<Nothing>
}

/** Поле `reason` призначене для логів, не для UI. */
sealed interface ChessError {
    data class InvalidFen(val reason: String) : ChessError
    data class IllegalMove(val move: String) : ChessError
    data class PgnSyntax(val gameIndex: Int, val line: Int, val reason: String) : ChessError
    data class PgnIllegalMove(val gameIndex: Int, val ply: Int, val san: String) : ChessError
    data class EngineUnavailable(val reason: String) : ChessError
    data class EngineCrashed(val reason: String) : ChessError

    /** Рушія запитали про позицію без легальних ходів (мат або пат). Очікувана помилка, не збій рушія. */
    data object NoLegalMoves : ChessError
    data object Cancelled : ChessError
}
