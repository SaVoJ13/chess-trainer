// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

import org.chesstrainer.learning.puzzle.Puzzle
import org.chesstrainer.learning.puzzle.PuzzleFilter
import org.chesstrainer.learning.puzzle.PuzzleId

// Порт ContentSource і типи манифесту (CONTRACTS 4.2). Змінює лише Архітектор.

interface ContentSource {
    suspend fun manifest(): ContentManifest
    suspend fun puzzles(filter: PuzzleFilter): List<Puzzle>
    suspend fun puzzle(id: PuzzleId): Puzzle?
}

data class SchemaVersion(val major: Int, val minor: Int)

data class ContentManifest(val schemaVersion: SchemaVersion, val packs: List<ContentPack>)

data class ContentPack(
    val id: String,
    val kind: ContentKind,
    val languages: Set<String>,
    val itemCount: Int,
    val sourceIds: Set<String>,
)

enum class ContentKind { PUZZLES, LESSONS, OPENINGS }
