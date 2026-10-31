// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

/**
 * Стабільні [DifficultyId] (CONTRACTS 1, 3.5): значення зберігаються в партіях, ніколи не змінюються
 * й не використовуються повторно. Назви в UI: ключі `difficulty_<id>`.
 * Нові рівні отримують нові номери; прогалини між групами залишено для вставок.
 */
object DifficultyIds {
    val HANDICAP_1 = DifficultyId(1)
    val HANDICAP_2 = DifficultyId(2)
    val HANDICAP_3 = DifficultyId(3)
    val HANDICAP_4 = DifficultyId(4)
    val HANDICAP_5 = DifficultyId(5)

    /** Мінімум `capabilities.eloRange` (число береться з рушія). */
    val ELO_MIN = DifficultyId(10)
    val ELO_1500 = DifficultyId(11)
    val ELO_1700 = DifficultyId(12)
    val ELO_1900 = DifficultyId(20)
    val ELO_2100 = DifficultyId(21)
    val ELO_2400 = DifficultyId(30)
    val ELO_2700 = DifficultyId(31)

    val FULL = DifficultyId(99)

    /** Усі коли-небудь видані значення, для тесту стабільності. */
    val all: List<DifficultyId> = listOf(
        HANDICAP_1, HANDICAP_2, HANDICAP_3, HANDICAP_4, HANDICAP_5,
        ELO_MIN, ELO_1500, ELO_1700, ELO_1900, ELO_2100, ELO_2400, ELO_2700, FULL,
    )
}

/**
 * Пресети рівнів складності (CONTRACTS 3.5). Усі числа сили Elo, межі пошуку й паузи живуть лише тут;
 * діапазон Elo береться з `capabilities`. Значення пошуку й пауз початкові, не відкалібровані матчами.
 * Рівні Handicap з'являються лише при `capabilities.maxMultiPv >= HandicapEngineService.REQUIRED_MULTI_PV`;
 * рівні UCI_Elo лише коли значення потрапляє в `capabilities.eloRange` (рівень «мінімальний Elo» бере
 * нижню межу діапазону). Рівень `Full` є завжди.
 */
class DefaultDifficultyPresets : DifficultyPresets {

    override fun levels(capabilities: EngineCapabilities): List<DifficultyLevel> {
        val result = mutableListOf<DifficultyLevel>()
        if (capabilities.maxMultiPv >= HandicapEngineService.REQUIRED_MULTI_PV) {
            val ids = listOf(
                DifficultyIds.HANDICAP_1, DifficultyIds.HANDICAP_2, DifficultyIds.HANDICAP_3,
                DifficultyIds.HANDICAP_4, DifficultyIds.HANDICAP_5,
            )
            ids.forEachIndexed { i, id ->
                val step = i + 1
                val profile = HandicapPolicy.profile(step)
                result += DifficultyLevel(
                    id = id,
                    strength = Strength.Handicap(step),
                    limit = SearchLimit(depth = profile.searchDepth),
                    minReplyDelayMs = profile.minReplyDelayMs,
                    approxElo = null,
                    group = DifficultyGroup.BEGINNER,
                )
            }
        }
        capabilities.eloRange?.let { range ->
            eloSteps.forEach { step ->
                val elo = step.elo ?: range.first
                if (elo in range) {
                    result += DifficultyLevel(
                        id = step.id,
                        strength = Strength.Elo(elo),
                        limit = SearchLimit(moveTimeMs = step.moveTimeMs),
                        minReplyDelayMs = step.delayMs,
                        approxElo = elo,
                        group = step.group,
                    )
                }
            }
        }
        result += DifficultyLevel(
            id = DifficultyIds.FULL,
            strength = Strength.Full,
            limit = SearchLimit(moveTimeMs = 1500),
            minReplyDelayMs = 0,
            approxElo = null,
            group = DifficultyGroup.MAX,
        )
        return result.distinctBy { it.strength }
    }

    override fun byId(id: DifficultyId, capabilities: EngineCapabilities): DifficultyLevel? =
        levels(capabilities).firstOrNull { it.id == id }

    private class EloStep(
        val id: DifficultyId,
        /** `null` = нижня межа `eloRange`. */
        val elo: Int?,
        val moveTimeMs: Long,
        val delayMs: Long,
        val group: DifficultyGroup,
    )

    private val eloSteps = listOf(
        EloStep(DifficultyIds.ELO_MIN, null, 300, 400, DifficultyGroup.AMATEUR),
        EloStep(DifficultyIds.ELO_1500, 1500, 400, 350, DifficultyGroup.AMATEUR),
        EloStep(DifficultyIds.ELO_1700, 1700, 500, 300, DifficultyGroup.AMATEUR),
        EloStep(DifficultyIds.ELO_1900, 1900, 600, 250, DifficultyGroup.CLUB),
        EloStep(DifficultyIds.ELO_2100, 2100, 800, 200, DifficultyGroup.CLUB),
        EloStep(DifficultyIds.ELO_2400, 2400, 1000, 150, DifficultyGroup.STRONG),
        EloStep(DifficultyIds.ELO_2700, 2700, 1200, 100, DifficultyGroup.STRONG),
    )
}
