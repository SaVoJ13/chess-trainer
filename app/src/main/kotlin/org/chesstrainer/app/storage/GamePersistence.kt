// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.chesstrainer.app.game.DifficultyDefaults
import org.chesstrainer.app.game.GameOutcome
import org.chesstrainer.app.game.GameUiState
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.game.ChessGameFactory
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.pgn.PgnCodec
import org.chesstrainer.core.pgn.PgnResult

/** Партія, відновлена із сховища (колір людини береться зі збереженого запису). */
class ResumableGame(
    val id: GameId,
    val createdAt: Instant,
    val game: ChessGame,
    val playerColor: Color,
    /** Рівень партії; записи без рівня (до v0.12) відновлюються з [DifficultyDefaults.LEGACY_GAME]. */
    val difficultyId: DifficultyId = DifficultyDefaults.LEGACY_GAME,
)

/**
 * Перетворення стану партії на [SavedGame] і назад. Шахової логіки тут немає: ходи пишуться й читаються
 * лише через `ChessGameFactory`/`PgnCodec`, нічого похідного (позиція, статус) не зберігається.
 */
class GamePersistence(
    private val games: ChessGameFactory,
    private val codec: PgnCodec,
) {
    fun encode(state: GameUiState, id: GameId?, createdAt: Instant, updatedAt: Instant): SavedGame {
        val outcome = state.outcome
        val headers = mapOf(
            "Event" to "Chess Trainer game",
            "Date" to DATE_FORMAT.format(createdAt.atOffset(ZoneOffset.UTC)),
            "White" to if (state.playerColor == Color.WHITE) "Player" else "Engine",
            "Black" to if (state.playerColor == Color.BLACK) "Player" else "Engine",
        )
        val pgn = codec.write(games.toPgn(state.game, headers, pgnResultOf(outcome)))
        return SavedGame(
            id = id,
            pgn = pgn,
            mode = GameMode.VS_ENGINE,
            playerColor = state.playerColor,
            difficultyId = state.difficultyId,
            finished = outcome != null,
            termination = outcome?.termination,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    /** `null`, якщо запис пошкоджено або це не партія проти рушія. */
    fun decode(saved: SavedGame): ChessGame? {
        if (saved.mode != GameMode.VS_ENGINE || saved.playerColor == null) return null
        return try {
            val parsed = codec.parse(saved.pgn).firstOrNull() as? ChessResult.Ok ?: return null
            (games.fromPgn(parsed.value) as? ChessResult.Ok)?.value
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * Партія для перегляду на дошці (будь-який режим). Результат береться з `PgnGame.result`, а не зі статусу
     * партії: для імпортованих він може відрізнятися (здача, час, нічия за згодою; CONTRACTS 3.2).
     * `null`, якщо PGN не вдається прочитати або відтворити.
     */
    fun decodeForReview(saved: SavedGame): ReviewedGame? = try {
        val parsed = codec.parse(saved.pgn).firstOrNull() as? ChessResult.Ok
        val game = parsed?.let { (games.fromPgn(it.value) as? ChessResult.Ok)?.value }
        if (parsed == null || game == null) null else ReviewedGame(game, parsed.value.result, parsed.value.headers)
    } catch (e: RuntimeException) {
        null
    }

    private fun pgnResultOf(outcome: GameOutcome?): PgnResult = when (outcome) {
        null -> PgnResult.UNKNOWN
        is GameOutcome.Checkmate -> winnerResult(outcome.winner)
        is GameOutcome.Resigned -> winnerResult(outcome.winner)
        GameOutcome.Stalemate,
        GameOutcome.InsufficientMaterial,
        GameOutcome.DrawClaimed,
        is GameOutcome.DrawAutomatic,
        -> PgnResult.DRAW
    }

    private fun winnerResult(winner: Color) = if (winner == Color.WHITE) PgnResult.WHITE_WINS else PgnResult.BLACK_WINS

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    }
}

/** Партія для перегляду: ходи, результат з тегу PGN і теги (дані, не тексти інтерфейсу). */
class ReviewedGame(val game: ChessGame, val result: PgnResult, val headers: Map<String, String>)
