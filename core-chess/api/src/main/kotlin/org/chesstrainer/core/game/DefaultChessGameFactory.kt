// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.game

import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.pgn.PgnGame
import org.chesstrainer.core.pgn.PgnMove
import org.chesstrainer.core.pgn.PgnResult
import org.chesstrainer.core.rules.ChessRules

class DefaultChessGameFactory(private val rules: ChessRules) : ChessGameFactory {

    override fun new(start: Position?): ChessGame = DefaultChessGame(rules, start ?: rules.startPosition())

    /**
     * Відтворює ходи з PGN за полем [PgnMove.uci]. Нелегальний хід дає `PgnIllegalMove` (`gameIndex` = 0:
     * `PgnGame` не знає свого номера у файлі). Завершення партії за правилами нічиєї відтворення не зупиняє.
     */
    override fun fromPgn(pgn: PgnGame): ChessResult<ChessGame> {
        val start = when (val fen = pgn.startFen) {
            null -> rules.startPosition()
            else -> when (val parsed = rules.parseFen(fen.value)) {
                is ChessResult.Ok -> parsed.value
                is ChessResult.Err -> return parsed
            }
        }
        var game = DefaultChessGame(rules, start)
        for ((index, pgnMove) in pgn.moves.withIndex()) {
            val illegal = ChessResult.Err(ChessError.PgnIllegalMove(gameIndex = 0, ply = index + 1, san = pgnMove.san))
            val move = (rules.parseUci(game.position, pgnMove.uci.value) as? ChessResult.Ok)?.value ?: return illegal
            game = (game.append(move) as? ChessResult.Ok)?.value ?: return illegal
        }
        return ChessResult.Ok(game)
    }

    /**
     * Seven Tag Roster іде першим (відсутні теги отримують значення за PGN-стандартом), далі решта [headers].
     * `Result` завжди береться з [result]. `SetUp`/`FEN` записує [org.chesstrainer.core.pgn.PgnCodec.write]
     * за [PgnGame.startFen]; він заданий, лише якщо партія почалась не зі стандартної позиції.
     */
    override fun toPgn(game: ChessGame, headers: Map<String, String>, result: PgnResult): PgnGame {
        val ordered = LinkedHashMap<String, String>()
        for ((tag, default) in SEVEN_TAG_ROSTER) {
            ordered[tag] = if (tag == "Result") result.tag else headers[tag] ?: default
        }
        for ((tag, value) in headers) if (tag !in ordered) ordered[tag] = value

        val standard = rules.startPosition()
        val startFen = if (game.startPosition.fen == standard.fen) null else game.startPosition.fen
        val moves = game.moves.map { PgnMove(san = it.san, uci = it.uci) }
        return PgnGame(ordered, startFen, moves, result)
    }

    private companion object {
        val SEVEN_TAG_ROSTER = listOf(
            "Event" to "?",
            "Site" to "?",
            "Date" to "????.??.??",
            "Round" to "?",
            "White" to "?",
            "Black" to "?",
            "Result" to "*",
        )
    }
}
