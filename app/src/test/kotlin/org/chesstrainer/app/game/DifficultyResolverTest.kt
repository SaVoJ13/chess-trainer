// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import org.chesstrainer.core.engine.DefaultDifficultyPresets
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyIds
import org.chesstrainer.core.engine.EngineCapabilities
import org.chesstrainer.core.engine.FakeEngineService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Чиста логіка вибору рівня (CONTRACTS 5, v0.12) без Android і рушія. */
class DifficultyResolverTest {
    private val resolver = DifficultyResolver(DefaultDifficultyPresets())
    private val full: EngineCapabilities = FakeEngineService.DEFAULT_CAPABILITIES

    // --- рівень за замовчуванням ---

    @Test
    fun firstLaunchUsesDifficulty3() {
        assertEquals(DifficultyId(3), DifficultyDefaults.initial(null))
    }

    @Test
    fun lastUsedLevelWinsOverTheFirstLaunchDefault() {
        assertEquals(DifficultyId(21), DifficultyDefaults.initial(DifficultyId(21)))
    }

    @Test
    fun legacyGamesUseDifficulty10() {
        assertEquals(DifficultyId(10), DifficultyDefaults.LEGACY_GAME)
    }

    // --- доступний рівень лишається ---

    @Test
    fun availableLevelIsKept() {
        for (id in DifficultyIds.all) {
            val r = resolver.resolve(id, full)
            assertEquals(id, r.level.id)
            assertFalse("рівень $id доступний, замінювати не треба", r.substituted)
        }
    }

    // --- недоступний рівень → найближчий у групі ---

    @Test
    fun handicapWithoutEnoughMultiPvFallsBackToTheWeakestAvailable() {
        val caps = full.copy(maxMultiPv = 1)
        assertTrue("рівнів BEGINNER на цьому рушії немає", resolver.levels(caps).none { it.group == DifficultyGroup.BEGINNER })
        val r = resolver.resolve(DifficultyIds.HANDICAP_3, caps)
        assertTrue(r.substituted)
        assertEquals(resolver.levels(caps).first(), r.level)
        assertEquals(DifficultyIds.ELO_MIN, r.level.id)
    }

    @Test
    fun eloOutsideTheRangeFallsBackToTheNearestLevelOfTheSameGroup() {
        // Діапазон 1600..2500: 1500 (id 11) недоступний. У AMATEUR лишаються id 10 (мінімум = 1600) і 12 (1700),
        // обидва за один крок у повному списку; за рівної відстані береться слабший.
        val caps = full.copy(eloRange = 1600..2500)
        val r = resolver.resolve(DifficultyIds.ELO_1500, caps)
        assertTrue(r.substituted)
        assertEquals(DifficultyIds.ELO_MIN, r.level.id)
        assertEquals(DifficultyGroup.AMATEUR, r.level.group)
    }

    @Test
    fun eloAboveTheRangeFallsBackToTheStrongestOfItsGroup() {
        val caps = full.copy(eloRange = 1320..2500)
        val r = resolver.resolve(DifficultyIds.ELO_2700, caps)
        assertTrue(r.substituted)
        assertEquals(DifficultyIds.ELO_2400, r.level.id)
        assertEquals(DifficultyGroup.STRONG, r.level.group)
    }

    @Test
    fun emptyGroupFallsBackToTheWeakestAvailableLevel() {
        val caps = full.copy(maxMultiPv = 1, eloRange = 1320..1450)
        assertEquals(listOf(DifficultyIds.ELO_MIN, DifficultyIds.FULL), resolver.levels(caps).map { it.id })
        val r = resolver.resolve(DifficultyIds.ELO_2100, caps)
        assertTrue(r.substituted)
        assertEquals(DifficultyIds.ELO_MIN, r.level.id)
    }

    @Test
    fun deviceWithoutEloOptionStillGetsALevel() {
        val caps = full.copy(maxMultiPv = 1, eloRange = null)
        val r = resolver.resolve(DifficultyIds.ELO_1900, caps)
        assertEquals(DifficultyIds.FULL, r.level.id)
        assertTrue(r.substituted)
    }

    @Test
    fun unknownIdFallsBackToTheWeakestAvailableLevel() {
        val r = resolver.resolve(DifficultyId(12345), full)
        assertTrue(r.substituted)
        assertEquals(resolver.levels(full).first(), r.level)
    }

    // --- групування й показ чисел ---

    @Test
    fun levelsAreGroupedInOrderAndEachGroupIsContiguous() {
        val groups = resolver.levels(full).groupedByGroup()
        assertEquals(
            listOf(DifficultyGroup.BEGINNER, DifficultyGroup.AMATEUR, DifficultyGroup.CLUB, DifficultyGroup.STRONG, DifficultyGroup.MAX),
            groups.map { it.first },
        )
        assertEquals(resolver.levels(full), groups.flatMap { it.second })
        assertEquals(listOf(1, 2, 3, 4, 5), groups[0].second.map { it.id.value })
        assertEquals(listOf(99), groups.last().second.map { it.id.value })
    }

    @Test
    fun approximateNumberIsShownOnlyForEloLevels() {
        for (level in resolver.levels(full)) {
            val shown = level.displayedApproxElo()
            if (level.group == DifficultyGroup.BEGINNER || level.id == DifficultyIds.FULL) {
                assertNull("для ${level.id} числа немає", shown)
            } else {
                assertNotNull(shown)
                assertEquals(level.approxElo, shown)
            }
        }
        // Мінімальний рівень UCI_Elo бере число з рушія, а не з коду застосунку.
        val min = resolver.levels(full.copy(eloRange = 1400..3000)).first { it.id == DifficultyIds.ELO_MIN }
        assertEquals(1400, min.displayedApproxElo())
    }
}
