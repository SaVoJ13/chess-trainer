// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import org.chesstrainer.app.settings.DifficultyPreferences
import org.chesstrainer.core.engine.DefaultDifficultyPresets
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyIds
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.DifficultyPresets
import org.chesstrainer.core.engine.EngineCapabilities

/** Пресети без пауз перед ходом рушія: тести не чекають справжнього часу. */
class ZeroDelayPresets(private val base: DifficultyPresets = DefaultDifficultyPresets()) : DifficultyPresets {
    override fun levels(capabilities: EngineCapabilities): List<DifficultyLevel> =
        base.levels(capabilities).map { it.copy(minReplyDelayMs = 0) }

    override fun byId(id: DifficultyId, capabilities: EngineCapabilities): DifficultyLevel? =
        levels(capabilities).firstOrNull { it.id == id }
}

/** Пресети з однаковою паузою для всіх рівнів. */
class FixedDelayPresets(private val delayMs: Long) : DifficultyPresets {
    private val base = DefaultDifficultyPresets()

    override fun levels(capabilities: EngineCapabilities): List<DifficultyLevel> =
        base.levels(capabilities).map { it.copy(minReplyDelayMs = delayMs) }

    override fun byId(id: DifficultyId, capabilities: EngineCapabilities): DifficultyLevel? =
        levels(capabilities).firstOrNull { it.id == id }
}

/** Налаштування в пам'яті. Типово останній рівень `UCI_Elo`: його «сирий» рушій обробляє без `HandicapEngineService`. */
class MemoryPreferences(var last: DifficultyId? = DifficultyIds.ELO_MIN) : DifficultyPreferences {
    override fun lastUsed(): DifficultyId? = last

    override fun rememberUsed(id: DifficultyId) {
        last = id
    }
}
