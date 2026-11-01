// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content.tools

import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.rules.ChessRules
import org.chesstrainer.learning.content.PuzzleChecker
import org.chesstrainer.learning.content.PuzzleIssue
import org.chesstrainer.learning.content.PuzzleProblem
import org.chesstrainer.learning.content.PuzzleRecord

/**
 * Рядок `lichess_db_puzzle.csv`:
 * `PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags,DailyDate`.
 * Поля не містять ком і лапок (перевірено на дампі), тому достатньо розділення по комі.
 */
class LichessRow(
    val id: String,
    val fen: String,
    val moves: List<String>,
    val rating: Int,
    val ratingDeviation: Int,
    val popularity: Int,
    val plays: Int,
    val tags: List<String>,
) {
    companion object {
        const val HEADER_START = "PuzzleId,FEN,Moves"

        /** null = рядок зіпсований (мало полів або не числа). */
        fun parse(line: String): LichessRow? {
            val f = line.trimEnd('\r').split(',')
            if (f.size < 8) return null
            return LichessRow(
                id = f[0],
                fen = f[1],
                moves = f[2].split(' ').filter { it.isNotEmpty() },
                rating = f[3].toIntOrNull() ?: return null,
                ratingDeviation = f[4].toIntOrNull() ?: return null,
                popularity = f[5].toIntOrNull() ?: return null,
                plays = f[6].toIntOrNull() ?: return null,
                tags = f[7].split(' ').filter { it.isNotEmpty() },
            )
        }
    }
}

/** Результат перетворення рядка Lichess у нашу задачу. */
sealed interface Conversion {
    data class Ok(val puzzle: PuzzleRecord) : Conversion
    data class Rejected(val issues: List<PuzzleIssue>) : Conversion
}

/**
 * Перетворення за правилом Lichess: у FEN перший хід списку Moves робить СУПЕРНИК. `startFen` = позиція після цього
 * ходу (ходить гравець), `solution` = решта ходів. FEN нормалізується до 6 полів (`Position.fen`).
 * Теги тут не обробляються: задає викликач ([themes]).
 */
class LichessConverter(private val rules: ChessRules) {
    private val checker = PuzzleChecker(rules)

    fun convert(row: LichessRow, themes: List<String>): Conversion {
        if (row.moves.size < 2 || row.moves.size % 2 != 0) {
            // Lichess: хід суперника + (хід гравця, відповідь)*; парна загальна кількість ≥ 2.
            return Conversion.Rejected(
                listOf(PuzzleIssue(PuzzleProblem.EVEN_SOLUTION_LENGTH, "Moves count ${row.moves.size} (need even, >= 2)")),
            )
        }
        val start = when (val r = rules.parseFen(row.fen)) {
            is ChessResult.Ok -> r.value
            is ChessResult.Err -> return Conversion.Rejected(listOf(PuzzleIssue(PuzzleProblem.INVALID_FEN, "${row.fen}: ${r.error}")))
        }
        val opponentMove = when (val r = rules.parseUci(start, row.moves[0])) {
            is ChessResult.Ok -> r.value
            is ChessResult.Err -> return Conversion.Rejected(listOf(PuzzleIssue(PuzzleProblem.ILLEGAL_MOVE, "opponent move ${row.moves[0]}")))
        }
        val playerPosition = when (val r = rules.apply(start, opponentMove)) {
            is ChessResult.Ok -> r.value
            is ChessResult.Err -> return Conversion.Rejected(listOf(PuzzleIssue(PuzzleProblem.ILLEGAL_MOVE, "opponent move ${row.moves[0]}")))
        }
        val record = PuzzleRecord(
            id = ID_PREFIX + row.id,
            fen = playerPosition.fen.value,
            solution = row.moves.drop(1),
            themes = themes,
            rating = row.rating,
        )
        val issues = checker.check(record)
        return if (issues.isEmpty()) Conversion.Ok(record) else Conversion.Rejected(issues)
    }

    companion object {
        /** Префікс стабільного id (CONTRACTS 1): `li-` + PuzzleId Lichess. */
        const val ID_PREFIX = "li-"
    }
}
