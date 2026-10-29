// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

sealed interface Strength {
    data object Full : Strength

    /** Лише в межах `capabilities.eloRange`. */
    data class Elo(val elo: Int) : Strength

    /** Лише в межах `capabilities.skillLevelRange`. */
    data class SkillLevel(val level: Int) : Strength

    /** Слабше за мінімум UCI_Elo; step 1 = найслабший. */
    data class Handicap(val step: Int) : Strength
}

/** Стабільний, зберігається в партіях. */
@JvmInline
value class DifficultyId(val value: Int)

data class DifficultyLevel(
    val id: DifficultyId,
    val strength: Strength,
    val limit: SearchLimit,
    /** Мінімальна пауза перед ходом рушія; виконує app. */
    val minReplyDelayMs: Long,
    /** Показується в UI лише якщо не `null`; для Handicap = `null`. */
    val approxElo: Int?,
    val group: DifficultyGroup,
)

enum class DifficultyGroup { BEGINNER, AMATEUR, CLUB, STRONG, MAX }

interface DifficultyPresets {
    /** Від найслабшого до найсильнішого. */
    fun levels(capabilities: EngineCapabilities): List<DifficultyLevel>
    fun byId(id: DifficultyId, capabilities: EngineCapabilities): DifficultyLevel?
}
