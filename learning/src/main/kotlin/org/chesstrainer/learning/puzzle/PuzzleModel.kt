// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.puzzle

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci

// Типи й порти CONTRACTS 4.1–4.2. Змінює лише Архітектор.

@JvmInline value class PuzzleId(val value: String)

/** Назва для UI: strings.xml, ключ puzzle_theme_<id>. */
@JvmInline value class ThemeId(val value: String)

data class Puzzle(
    val id: PuzzleId,
    /** Позиція, де ХОДИТЬ ГРАВЕЦЬ. */
    val startFen: Fen,
    /** Гравець, відповідь, гравець, …; завжди непарна довжина. */
    val solution: List<Uci>,
    val themes: Set<ThemeId>,
    val rating: Int,
    /** ID запису в docs/THIRD_PARTY.md. */
    val sourceId: String,
)

data class PuzzleFilter(
    /** Порожньо = будь-які. */
    val themes: Set<ThemeId> = emptySet(),
    val ratingRange: IntRange? = null,
    val excludeIds: Set<PuzzleId> = emptySet(),
    val limit: Int = 50,
)

/** REVIEW_DUE додається пізніше. */
enum class PuzzleMode { NEW, RETRY_FAILED }

data class PuzzleRequest(val filter: PuzzleFilter, val mode: PuzzleMode)

interface PuzzleService {
    /** null = задачі за фільтром закінчились. */
    suspend fun next(request: PuzzleRequest): Puzzle?
    fun session(puzzle: Puzzle): PuzzleSession
}

interface PuzzleSession {
    val puzzle: Puzzle
    val position: Position
    val state: PuzzleState
    suspend fun submit(move: Move): PuzzleStep

    /** Кожен виклик дає наступний крок підказки (до FullMove). */
    fun hint(): Hint

    /** Задача вважається не розв'язаною. */
    fun revealSolution(): List<Uci>
    fun result(): PuzzleResult
}

enum class PuzzleState { IN_PROGRESS, SOLVED, FAILED }

sealed interface PuzzleStep {
    data class Correct(val opponentReply: Move?, val solved: Boolean) : PuzzleStep

    /** Позиція не змінюється; спроба зараховується як FAILED. */
    data object Wrong : PuzzleStep

    /** Хід після завершення ігнорується. */
    data object Finished : PuzzleStep
}

sealed interface Hint {
    /** Крок 1: тема/ідея. */
    data class Nudge(val theme: ThemeId) : Hint

    /** Крок 2: яка фігура. */
    data class Direction(val fromSquare: Square) : Hint

    /** Крок 3: конкретний хід. */
    data class FullMove(val move: Move) : Hint
}

data class PuzzleResult(
    val puzzleId: PuzzleId,
    val state: PuzzleState,
    val wrongMoves: Int,
    val hintsUsed: Int,
    val timeMs: Long,
)

data class PuzzleAttempt(val result: PuzzleResult, val at: Instant)

// ---- 4.2: порти (визначає learning, реалізує app)

interface PuzzleProgressRepository {
    suspend fun recordAttempt(attempt: PuzzleAttempt)
    suspend fun lastResult(id: PuzzleId): PuzzleResult?

    /** FAILED, після яких ще не було SOLVED; найстаріші першими. */
    suspend fun failedPuzzleIds(limit: Int): List<PuzzleId>
    suspend fun attemptedIds(): Set<PuzzleId>
    fun stats(): Flow<PuzzleStats>
}

data class PuzzleStats(val attempted: Int, val solved: Int, val failed: Int)
