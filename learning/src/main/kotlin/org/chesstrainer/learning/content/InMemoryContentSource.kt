// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

import org.chesstrainer.learning.puzzle.Puzzle
import org.chesstrainer.learning.puzzle.PuzzleFilter
import org.chesstrainer.learning.puzzle.PuzzleId

/**
 * [ContentSource] над розібраним контентом у пам'яті (без android.*). Фільтр: тема збігається, якщо задача має
 * хоча б одну з `filter.themes`; `ratingRange` включно; відповідь впорядкована за `(rating, id)` і обрізана до `limit`.
 */
class InMemoryContentSource(
    private val manifest: ContentManifest,
    puzzles: Collection<Puzzle>,
) : ContentSource {
    private val sorted: List<Puzzle> = puzzles.sortedWith(compareBy({ it.rating }, { it.id.value }))
    private val byId: Map<PuzzleId, Puzzle> = sorted.associateBy { it.id }

    override suspend fun manifest(): ContentManifest = manifest

    override suspend fun puzzles(filter: PuzzleFilter): List<Puzzle> =
        sorted.asSequence()
            .filter { it.id !in filter.excludeIds }
            .filter { filter.ratingRange == null || it.rating in filter.ratingRange }
            .filter { filter.themes.isEmpty() || it.themes.any { t -> t in filter.themes } }
            .take(filter.limit.coerceAtLeast(0))
            .toList()

    override suspend fun puzzle(id: PuzzleId): Puzzle? = byId[id]
}
