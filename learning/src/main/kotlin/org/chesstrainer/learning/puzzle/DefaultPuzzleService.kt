// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.puzzle

import kotlin.random.Random
import kotlin.time.TimeSource
import org.chesstrainer.core.rules.ChessRules
import org.chesstrainer.learning.content.ContentSource

/**
 * Вибір задач і створення сесій (CONTRACTS 4.1).
 *
 * - [PuzzleMode.NEW]: задачі за фільтром, яких немає в `attemptedIds()`; випадкова з рівномірним розподілом
 *   (кандидати спершу сортуються за id, тож при заданому [random] результат детермінований).
 * - [PuzzleMode.RETRY_FAILED]: `failedPuzzleIds()`, найстаріші першими; перша, що проходить фільтр.
 * - Задача, видана попереднім викликом, не видається знову поспіль, якщо є інша; якщо вона єдина, видається.
 * - `PuzzleFilter.limit` у `next` не обмежує вибір (обмежує розмір відповіді `ContentSource.puzzles`); сервіс
 *   запитує всі збіги, щоб вибір не залежав від порядку у файлі.
 *
 * Запис спроби в [PuzzleProgressRepository] виконує `app` (`PuzzleSession.result()` + `recordAttempt`).
 * Не потокобезпечний: викликати з одного потоку/корутини.
 */
class DefaultPuzzleService(
    private val content: ContentSource,
    private val progress: PuzzleProgressRepository,
    private val rules: ChessRules,
    private val random: Random = Random.Default,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : PuzzleService {

    private var lastServed: PuzzleId? = null

    override suspend fun next(request: PuzzleRequest): Puzzle? {
        val puzzle = when (request.mode) {
            PuzzleMode.NEW -> nextNew(request.filter)
            PuzzleMode.RETRY_FAILED -> nextFailed(request.filter)
        }
        lastServed = puzzle?.id ?: lastServed
        return puzzle
    }

    override fun session(puzzle: Puzzle): PuzzleSession = DefaultPuzzleSession(puzzle, rules, timeSource)

    private suspend fun nextNew(filter: PuzzleFilter): Puzzle? {
        val base = filter.excludeIds + progress.attemptedIds()
        val last = lastServed
        var pool = candidates(filter, if (last != null) base + last else base)
        if (pool.isEmpty() && last != null) pool = candidates(filter, base)
        return if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]
    }

    private suspend fun candidates(filter: PuzzleFilter, exclude: Set<PuzzleId>): List<Puzzle> =
        content.puzzles(filter.copy(excludeIds = exclude, limit = Int.MAX_VALUE)).sortedBy { it.id.value }

    private suspend fun nextFailed(filter: PuzzleFilter): Puzzle? {
        val matches = ArrayList<Puzzle>()
        for (id in progress.failedPuzzleIds(Int.MAX_VALUE)) {
            if (id in filter.excludeIds) continue
            val p = content.puzzle(id) ?: continue // задачу прибрано з пакета: пропускаємо без помилки
            if (matchesFilter(p, filter)) matches += p
        }
        return matches.firstOrNull { it.id != lastServed } ?: matches.firstOrNull()
    }

    private fun matchesFilter(p: Puzzle, f: PuzzleFilter): Boolean =
        (f.themes.isEmpty() || p.themes.any { it in f.themes }) &&
            (f.ratingRange == null || p.rating in f.ratingRange)
}
