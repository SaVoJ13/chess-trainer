// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.puzzle

import org.chesstrainer.app.game.PendingPromotion
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.learning.puzzle.Puzzle
import org.chesstrainer.learning.puzzle.PuzzleMode
import org.chesstrainer.learning.puzzle.PuzzleState
import org.chesstrainer.learning.puzzle.PuzzleStats
import org.chesstrainer.learning.puzzle.ThemeId

/**
 * Рівень складності для вибору гравця. Межі це складність задачі в балах із пакета контенту
 * (CONTRACTS 6.1); в інтерфейсі вони не називаються «рейтингом Lichess».
 * [from] і [to] лише для підпису діапазону в інтерфейсі.
 */
enum class PuzzleLevel(val range: IntRange?, val from: Int?, val to: Int?) {
    ANY(null, null, null),
    EASY(0..999, null, 999),
    MEDIUM(1000..1799, 1000, 1799),
    HARD(1800..Int.MAX_VALUE, 1800, null),
}

/** Вибір гравця на екрані налаштування. [theme] = null означає «будь-яка». */
data class PuzzleSetup(
    val theme: ThemeId? = null,
    val level: PuzzleLevel = PuzzleLevel.ANY,
    val mode: PuzzleMode = PuzzleMode.NEW,
)

enum class ContentAvailability { LOADING, READY, UNAVAILABLE }

/** Повідомлення на екрані вибору після невдалої спроби почати задачу. */
enum class MenuNotice { NO_NEW_PUZZLES, NO_FAILED_PUZZLES, PUZZLE_UNREADABLE }

/** Показана підказка: [step] 1 = тема, 2 = яка фігура, 3 = хід. */
data class HintView(val step: Int, val theme: ThemeId? = null, val from: Square? = null, val move: Move? = null)

/** Перегляд ходів розв'язку: [positions] має на один елемент більше, ніж [moves] (позиція 0 це початкова). */
data class SolutionReview(
    val positions: List<Position>,
    val moves: List<Move>,
    val san: List<String>,
    val index: Int,
) {
    val position: Position get() = positions[index]
    val lastMove: Move? get() = if (index == 0) null else moves[index - 1]
    val canBack: Boolean get() = index > 0
    val canForward: Boolean get() = index < moves.size
}

/** Задача на дошці. [state] береться зі `PuzzleSession.state`, а не з `lineCompleted` (CONTRACTS 4.1, v0.16). */
data class PuzzlePlay(
    val puzzle: Puzzle,
    val playerColor: Color,
    val position: Position,
    val lastMove: Move? = null,
    val checkSquare: Square? = null,
    val selected: Square? = null,
    val targets: Set<Square> = emptySet(),
    val pendingPromotion: PendingPromotion? = null,
    val state: PuzzleState = PuzzleState.IN_PROGRESS,
    /** Лінію завершено або розв'язок відкрито: ходи й підказки вже не приймаються. */
    val over: Boolean = false,
    val wrongMove: Boolean = false,
    val hint: HintView? = null,
    val hintsUsed: Int = 0,
    val wrongMoves: Int = 0,
    val review: SolutionReview? = null,
    /** Іде відповідь суперника: ввід тимчасово заблоковано. */
    val busy: Boolean = false,
) {
    val canMove: Boolean get() = !over && !busy && review == null && pendingPromotion == null
    val canHint: Boolean get() = !over && !busy
}

data class PuzzleUiState(
    val setup: PuzzleSetup = PuzzleSetup(),
    val stats: PuzzleStats = PuzzleStats(0, 0, 0),
    val availability: ContentAvailability = ContentAvailability.LOADING,
    val notice: MenuNotice? = null,
    /** Шукаємо наступну задачу. */
    val loading: Boolean = false,
    val play: PuzzlePlay? = null,
)
