// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.rules.ChessRules

/** Задача в форматі файлу контенту (`content/README.md`). Поля відповідають `Puzzle` з CONTRACTS 4.1. */
data class PuzzleRecord(
    val id: String,
    val fen: String,
    val solution: List<String>,
    val themes: List<String>,
    val rating: Int,
)

enum class PuzzleProblem {
    INVALID_FEN, NON_CANONICAL_FEN, EMPTY_SOLUTION, EVEN_SOLUTION_LENGTH, ILLEGAL_MOVE, NON_CANONICAL_MOVE,
    NO_THEMES, UNKNOWN_THEME, DUPLICATE_THEME, NO_TACTIC_THEME, BAD_RATING, BAD_ID, MATE_THEME_MISMATCH,
}

data class PuzzleIssue(val problem: PuzzleProblem, val detail: String)

/** Перевірка однієї задачі через [ChessRules] (CONTRACTS 6.1). Без стану. */
class PuzzleChecker(private val rules: ChessRules) {

    /** Повна перевірка запису з файлу. Порожній список = задача придатна. */
    fun check(p: PuzzleRecord): List<PuzzleIssue> {
        val issues = ArrayList<PuzzleIssue>()
        if (!ID_REGEX.matches(p.id)) issues += PuzzleIssue(PuzzleProblem.BAD_ID, p.id)
        issues += checkMeta(p.themes, p.rating)
        issues += checkMoves(p.fen, p.solution, requireCanonicalFen = true, themes = p.themes)
        return issues
    }

    /** Теми й рейтинг. */
    fun checkMeta(themes: List<String>, rating: Int): List<PuzzleIssue> {
        val issues = ArrayList<PuzzleIssue>()
        if (rating !in PuzzleFormat.MIN_RATING..PuzzleFormat.MAX_RATING) {
            issues += PuzzleIssue(PuzzleProblem.BAD_RATING, rating.toString())
        }
        if (themes.isEmpty()) issues += PuzzleIssue(PuzzleProblem.NO_THEMES, "")
        for (t in themes) {
            if (!ThemeCatalog.isAccepted(t)) issues += PuzzleIssue(PuzzleProblem.UNKNOWN_THEME, t)
        }
        if (themes.toSet().size != themes.size) {
            issues += PuzzleIssue(PuzzleProblem.DUPLICATE_THEME, themes.joinToString(" "))
        }
        if (themes.isNotEmpty() && themes.none { ThemeCatalog.info(it)?.kind == ThemeKind.TACTIC }) {
            issues += PuzzleIssue(PuzzleProblem.NO_TACTIC_THEME, themes.joinToString(" "))
        }
        return issues
    }

    /**
     * FEN, довжина й легальність ходів розв'язку. [themes] (необов'язково) вмикає узгодження mate_in_N з розв'язком:
     * довжина 2N-1 і мат у кінці.
     */
    fun checkMoves(
        fen: String,
        solution: List<String>,
        requireCanonicalFen: Boolean,
        themes: List<String> = emptyList(),
    ): List<PuzzleIssue> {
        var position = when (val r = rules.parseFen(fen)) {
            is ChessResult.Ok -> r.value
            is ChessResult.Err -> return listOf(PuzzleIssue(PuzzleProblem.INVALID_FEN, "$fen: ${r.error}"))
        }
        val issues = ArrayList<PuzzleIssue>()
        if (requireCanonicalFen && position.fen.value != fen) {
            issues += PuzzleIssue(PuzzleProblem.NON_CANONICAL_FEN, "$fen -> ${position.fen.value}")
        }
        if (solution.isEmpty()) return issues + PuzzleIssue(PuzzleProblem.EMPTY_SOLUTION, "")
        if (solution.size % 2 == 0) issues += PuzzleIssue(PuzzleProblem.EVEN_SOLUTION_LENGTH, solution.size.toString())
        for ((index, uci) in solution.withIndex()) {
            val move = when (val r = rules.parseUci(position, uci)) {
                is ChessResult.Ok -> r.value
                is ChessResult.Err -> return issues + PuzzleIssue(PuzzleProblem.ILLEGAL_MOVE, "ply ${index + 1}: $uci")
            }
            if (rules.toUci(move).value != uci) {
                issues += PuzzleIssue(PuzzleProblem.NON_CANONICAL_MOVE, "ply ${index + 1}: $uci")
            }
            position = when (val r = rules.apply(position, move)) {
                is ChessResult.Ok -> r.value
                is ChessResult.Err -> return issues + PuzzleIssue(PuzzleProblem.ILLEGAL_MOVE, "ply ${index + 1}: $uci")
            }
        }
        val mateN = themes.mapNotNull { MATE_IN.matchEntire(it)?.groupValues?.get(1)?.toInt() }.firstOrNull()
        if (mateN != null && (solution.size != 2 * mateN - 1 || !rules.isCheckmate(position))) {
            issues += PuzzleIssue(PuzzleProblem.MATE_THEME_MISMATCH, "mate_in_$mateN, solution plies ${solution.size}")
        }
        return issues
    }

    private companion object {
        val ID_REGEX = Regex("[A-Za-z0-9_-]{1,32}")
        val MATE_IN = Regex("mate_in_([1-3])")
    }
}
