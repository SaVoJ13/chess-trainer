// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.puzzle

import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules
import org.chesstrainer.learning.content.ThemeCatalog
import org.chesstrainer.learning.content.ThemeKind

/**
 * Сесія однієї задачі (CONTRACTS 4.1). Не потокобезпечна: UI викликає її з одного потоку.
 *
 * Правила:
 * - хід приймається, якщо збігається з наступним ходом `solution` або дає мат (альтернативний мат);
 *   інші ходи (і нелегальні) дають [PuzzleStep.Wrong], позиція не змінюється;
 * - після [PuzzleStep.Wrong], підказки [Hint.FullMove] стан стає [PuzzleState.FAILED], але гравець може
 *   доходити розв'язок до кінця ([PuzzleStep.Correct] із `lineCompleted = true` означає «лінію завершено»,
 *   `state` лишається FAILED);
 * - `lineCompleted` описує лише кінець лінії; результат задачі визначає [state] (SOLVED або FAILED);
 * - [revealSolution] можна викликати будь-коли, вона дає FAILED і завершує сесію: далі кожен хід дає [PuzzleStep.Finished];
 * - після кінця лінії хід теж дає [PuzzleStep.Finished];
 * - [position] це поточна позиція (на початку збігається з позицією з `startFen`), [startPosition] початкова;
 * - [hint] після завершення сесії кидає [IllegalStateException] (порушення контракту програмістом).
 *
 * @param timeSource джерело часу, у тестах [kotlin.time.TestTimeSource].
 * @throws IllegalArgumentException якщо `startFen` або розв'язок не проходять правила (контент має бути перевірений).
 */
class DefaultPuzzleSession(
    override val puzzle: Puzzle,
    private val rules: ChessRules,
    timeSource: TimeSource = TimeSource.Monotonic,
) : PuzzleSession {

    val startPosition: Position = when (val r = rules.parseFen(puzzle.startFen.value)) {
        is ChessResult.Ok -> r.value
        is ChessResult.Err -> throw IllegalArgumentException("Puzzle ${puzzle.id.value}: invalid startFen: ${r.error}")
    }

    private val started: TimeMark = timeSource.markNow()
    private var finishedAtMs: Long? = null

    private var current: Position = startPosition
    private var ply = 0 // індекс наступного ходу гравця в solution
    private var completed = false
    private var revealed = false
    private var failed = false
    private var wrongMoves = 0
    private var hintsUsed = 0
    private var hintLevel = 0
    private var nudgeShown = false

    /** Перша ТАКТИЧНА тема за пріоритетом каталогу (теми фази для підказки не використовуються). */
    private val nudgeTheme: ThemeId? = puzzle.themes
        .filter { ThemeCatalog.info(it.value)?.kind == ThemeKind.TACTIC }
        .minByOrNull { ThemeCatalog.priority(it.value) }

    override val position: Position get() = current

    override val state: PuzzleState
        get() = when {
            failed -> PuzzleState.FAILED
            completed -> PuzzleState.SOLVED
            else -> PuzzleState.IN_PROGRESS
        }

    private val over: Boolean get() = completed || revealed

    override suspend fun submit(move: Move): PuzzleStep {
        if (over) return PuzzleStep.Finished
        val expected = expectedMove(ply)
        val after = (rules.apply(current, move) as? ChessResult.Ok)?.value
        val accepted = after != null && (move == expected || rules.isCheckmate(after))
        if (!accepted) {
            wrongMoves++
            failed = true
            return PuzzleStep.Wrong
        }
        current = after!!
        hintLevel = 0
        if (move != expected || ply + 1 >= puzzle.solution.size) {
            // Альтернативний мат, або остання скінчена лінія розв'язку.
            return finish()
        }
        val replyMove = expectedMove(ply + 1)
        current = apply(current, replyMove)
        ply += 2
        return PuzzleStep.Correct(replyMove, lineCompleted = false)
    }

    private fun finish(): PuzzleStep {
        completed = true
        finishedAtMs = elapsedMs()
        return PuzzleStep.Correct(null, lineCompleted = true)
    }

    override fun hint(): Hint {
        check(!over) { "hint() after the puzzle is finished" }
        val expected = expectedMove(ply)
        when {
            hintLevel == 0 && nudgeTheme != null && !nudgeShown -> {
                nudgeShown = true
                hintLevel = 1
                hintsUsed++
                return Hint.Nudge(nudgeTheme)
            }
            hintLevel <= 1 -> {
                hintLevel = 2
                hintsUsed++
                return Hint.Direction(expected.from)
            }
            else -> {
                if (hintLevel == 2) hintsUsed++
                hintLevel = 3
                failed = true
                return Hint.FullMove(expected)
            }
        }
    }

    override fun revealSolution(): List<Uci> {
        failed = true
        if (!revealed) {
            revealed = true
            if (finishedAtMs == null) finishedAtMs = elapsedMs()
        }
        return puzzle.solution
    }

    override fun result(): PuzzleResult =
        PuzzleResult(puzzle.id, state, wrongMoves, hintsUsed, finishedAtMs ?: elapsedMs())

    private fun elapsedMs(): Long = started.elapsedNow().toLong(DurationUnit.MILLISECONDS)

    /** Хід розв'язку [index], розібраний у [current]: викликати, коли `current` це позиція перед цим ходом. */
    private fun expectedMove(index: Int): Move =
        when (val r = rules.parseUci(current, puzzle.solution[index].value)) {
            is ChessResult.Ok -> r.value
            is ChessResult.Err -> throw IllegalArgumentException(
                "Puzzle ${puzzle.id.value}: illegal solution move ${puzzle.solution[index].value} at ply ${index + 1}",
            )
        }

    private fun apply(position: Position, move: Move): Position =
        when (val r = rules.apply(position, move)) {
            is ChessResult.Ok -> r.value
            is ChessResult.Err -> throw IllegalArgumentException("Puzzle ${puzzle.id.value}: illegal solution move $move")
        }
}
