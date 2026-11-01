// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import kotlin.math.abs
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyIds
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.DifficultyPresets
import org.chesstrainer.core.engine.EngineCapabilities
import org.chesstrainer.core.engine.HandicapEngineService
import org.chesstrainer.core.engine.Strength

/** Правила вибору рівня за замовчуванням (CONTRACTS 5, v0.12). */
object DifficultyDefaults {
    /** Перший запуск: середній крок для початківців. */
    val FIRST_LAUNCH: DifficultyId = DifficultyIds.HANDICAP_3

    /** Партії, збережені до v0.12 без рівня, грались на мінімальному `UCI_Elo`. */
    val LEGACY_GAME: DifficultyId = DifficultyIds.ELO_MIN

    /** Попередньо обраний рівень: останній використаний, а при першому запуску [FIRST_LAUNCH]. */
    fun initial(lastUsed: DifficultyId?): DifficultyId = lastUsed ?: FIRST_LAUNCH
}

/** Рівень, який справді буде використано, і чи довелося замінити запитаний. */
data class ResolvedLevel(val level: DifficultyLevel, val substituted: Boolean)

/**
 * Підбирає рівень зі списку пресетів для можливостей саме цього рушія (CONTRACTS 5, v0.12): якщо запитаного
 * рівня немає, береться найближчий доступний тієї самої групи, а за його відсутності найслабший доступний.
 *
 * Групу недоступного рівня беремо з пресетів, а не з власної таблиці: пресети питаються про «еталонні»
 * можливості, де доступні всі рівні (ідентифікатори стабільні, склад рівнів залежить лише від `capabilities`).
 */
class DifficultyResolver(private val presets: DifficultyPresets) {

    /** Доступні на цьому рушії рівні від найслабшого до найсильнішого. */
    fun levels(capabilities: EngineCapabilities): List<DifficultyLevel> = presets.levels(capabilities)

    fun resolve(requested: DifficultyId, capabilities: EngineCapabilities): ResolvedLevel {
        val available = presets.levels(capabilities)
        require(available.isNotEmpty()) { "Presets returned no levels" }
        available.firstOrNull { it.id == requested }?.let { return ResolvedLevel(it, substituted = false) }

        val reference = presets.levels(referenceOf(capabilities))
        val position = reference.withIndex().associate { (index, level) -> level.id to index }
        val wanted = position[requested] ?: return ResolvedLevel(available.first(), substituted = true)
        val group = reference[wanted].group
        // Найближчий за порядком у повному списку; за однакової відстані слабший.
        val nearest = available
            .filter { it.group == group }
            .minWithOrNull(
                compareBy<DifficultyLevel>({ abs(position.getValue(it.id) - wanted) }, { position.getValue(it.id) }),
            )
        return ResolvedLevel(nearest ?: available.first(), substituted = true)
    }

    private fun referenceOf(capabilities: EngineCapabilities) = capabilities.copy(
        maxMultiPv = maxOf(capabilities.maxMultiPv, HandicapEngineService.REQUIRED_MULTI_PV),
        eloRange = REFERENCE_ELO_RANGE,
    )

    private companion object {
        /** Ширший за будь-який справжній діапазон: усі кроки `UCI_Elo` потрапляють усередину. */
        val REFERENCE_ELO_RANGE = 0..10_000
    }
}

/** Рівні за групами `DifficultyGroup` у порядку від слабшого (для діалогу «Нова партія»). */
fun List<DifficultyLevel>.groupedByGroup(): List<Pair<DifficultyGroup, List<DifficultyLevel>>> =
    groupBy { it.group }.toList()

/**
 * Число для «≈ N» біля рівня: лише для `UCI_Elo` (CONTRACTS 3.5 п. 6). Для Handicap і `Full` числа немає,
 * а саме значення ніде не називається рейтингом гравця.
 */
fun DifficultyLevel.displayedApproxElo(): Int? = if (strength is Strength.Elo) approxElo else null
