// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.puzzle

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.learning.content.ContentLoadResult
import org.chesstrainer.learning.content.ContentLoader
import org.chesstrainer.learning.content.LoadValidation
import org.chesstrainer.learning.content.LoadedContent

internal object PuzzleTestSupport {
    val rules = ChesslibRules()

    /** Хід у форматі UCI ("e2e4", "e7e8q") як [Move]. */
    fun mv(uci: String): Move = Move(
        Square.parse(uci.substring(0, 2))!!,
        Square.parse(uci.substring(2, 4))!!,
        uci.getOrNull(4)?.let {
            when (it) {
                'q' -> PieceType.QUEEN
                'r' -> PieceType.ROOK
                'b' -> PieceType.BISHOP
                else -> PieceType.KNIGHT
            }
        },
    )

    fun puzzle(
        id: String,
        fen: String = MATE_FEN,
        solution: List<String> = listOf("a1a8"),
        themes: List<String> = listOf("back_rank_mate", "mate_in_1"),
        rating: Int = 1000,
    ) = Puzzle(PuzzleId(id), Fen(fen), solution.map { Uci(it) }, themes.map { ThemeId(it) }.toSet(), rating, "CT-001")

    /** Мат в 1 ходом Ra8#. */
    const val MATE_FEN = "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"

    /** Мат в 1 ходом Ra8# або Rb8#. */
    const val TWO_ROOKS_FEN = "6k1/5ppp/8/8/8/8/8/RR4K1 w - - 0 1"

    /** Закомічений контент `content/common` через [ContentLoader] (cwd тестів = каталог модуля). */
    fun loadCommitted(validation: LoadValidation = LoadValidation.FULL): LoadedContent {
        val common = Paths.get("..", "content", "common")
        val read = { name: String -> String(Files.readAllBytes(common.resolve(name)), StandardCharsets.UTF_8) }
        val result = ContentLoader(rules, validation).load(read("manifest.json")) { id ->
            val name = "puzzles/$id.json"
            if (Files.isRegularFile(common.resolve(name))) read(name) else null
        }
        return (result as ContentLoadResult.Loaded).content
    }
}

/** Проста реалізація порту в пам'яті (лише для тестів). */
class InMemoryProgress : PuzzleProgressRepository {
    val attempts = ArrayList<PuzzleAttempt>()
    private val stats = MutableStateFlow(PuzzleStats(0, 0, 0))

    override suspend fun recordAttempt(attempt: PuzzleAttempt) {
        attempts += attempt
        val last = lastByPuzzle()
        stats.value = PuzzleStats(
            last.size,
            last.values.count { it.state == PuzzleState.SOLVED },
            last.values.count { it.state == PuzzleState.FAILED },
        )
    }

    private fun lastByPuzzle(): Map<PuzzleId, PuzzleResult> =
        attempts.associate { it.result.puzzleId to it.result }

    override suspend fun lastResult(id: PuzzleId): PuzzleResult? = lastByPuzzle()[id]

    /** FAILED, після яких ще не було SOLVED; у порядку першої появи в журналі (найстаріші першими). */
    override suspend fun failedPuzzleIds(limit: Int): List<PuzzleId> {
        val last = lastByPuzzle()
        return attempts.map { it.result.puzzleId }.distinct()
            .filter { last.getValue(it).state == PuzzleState.FAILED }
            .take(limit)
    }

    override suspend fun attemptedIds(): Set<PuzzleId> = attempts.map { it.result.puzzleId }.toSet()
    override fun stats(): Flow<PuzzleStats> = stats
}
