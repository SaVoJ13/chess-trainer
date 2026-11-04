// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyPresetsTest {
    private val presets = DefaultDifficultyPresets()
    private val caps = FakeEngineService.DEFAULT_CAPABILITIES

    @Test
    fun `DifficultyId values are stable`() {
        // Значення зберігаються в партіях: змінювати чи використовувати повторно заборонено.
        val expected = mapOf(
            DifficultyIds.HANDICAP_1 to 1, DifficultyIds.HANDICAP_2 to 2, DifficultyIds.HANDICAP_3 to 3,
            DifficultyIds.HANDICAP_4 to 4, DifficultyIds.HANDICAP_5 to 5,
            DifficultyIds.ELO_MIN to 10, DifficultyIds.ELO_1500 to 11, DifficultyIds.ELO_1700 to 12,
            DifficultyIds.ELO_1900 to 20, DifficultyIds.ELO_2100 to 21,
            DifficultyIds.ELO_2400 to 30, DifficultyIds.ELO_2700 to 31,
            DifficultyIds.FULL to 99,
        )
        expected.forEach { (id, value) -> assertEquals(value, id.value) }
        assertEquals(expected.size, DifficultyIds.all.size)
        assertEquals(DifficultyIds.all.size, DifficultyIds.all.map { it.value }.toSet().size)
    }

    @Test
    fun `levels are ordered weakest to strongest with unique ids and strengths`() {
        val levels = presets.levels(caps)
        assertEquals(levels.size, levels.map { it.id }.toSet().size)
        assertEquals(levels.size, levels.map { it.strength }.toSet().size)
        assertEquals(DifficultyIds.HANDICAP_1, levels.first().id)
        assertEquals(DifficultyIds.FULL, levels.last().id)
        assertTrue(levels.map { it.group.ordinal }.zipWithNext().all { (a, b) -> a <= b })
        val elos = levels.mapNotNull { (it.strength as? Strength.Elo)?.elo }
        assertEquals(elos.sorted(), elos)
    }

    @Test
    fun `at least three handicap levels, all weaker than the minimum UCI_Elo, with no claimed rating`() {
        val handicap = presets.levels(caps).filter { it.strength is Strength.Handicap }
        assertTrue(handicap.size >= 3)
        assertEquals((1..handicap.size).toList(), handicap.map { (it.strength as Strength.Handicap).step })
        assertTrue(handicap.all { it.approxElo == null && it.group == DifficultyGroup.BEGINNER })
    }

    @Test
    fun `Elo levels stay inside capabilities and the minimum level uses the reported lower bound`() {
        val narrow = caps.copy(eloRange = 1600..2200)
        val elo = presets.levels(narrow).mapNotNull { it.strength as? Strength.Elo }.map { it.elo }
        assertTrue(elo.isNotEmpty() && elo.all { it in 1600..2200 })
        assertEquals(1600, elo.first())

        val shifted = caps.copy(eloRange = 1000..3000)
        assertEquals(1000, presets.byId(DifficultyIds.ELO_MIN, shifted)?.let { (it.strength as Strength.Elo).elo })
    }

    @Test
    fun `without an Elo option no Elo levels are offered`() {
        val levels = presets.levels(caps.copy(eloRange = null))
        assertTrue(levels.none { it.strength is Strength.Elo })
        assertTrue(levels.any { it.strength is Strength.Full })
    }

    @Test
    fun `handicap levels are skipped when maxMultiPv is too small`() {
        val small = caps.copy(maxMultiPv = HandicapEngineService.REQUIRED_MULTI_PV - 1)
        val levels = presets.levels(small)
        assertTrue(levels.none { it.strength is Strength.Handicap })
        assertNull(presets.byId(DifficultyIds.HANDICAP_1, small))
        assertTrue(levels.any { it.strength is Strength.Elo })
        val exact = caps.copy(maxMultiPv = HandicapEngineService.REQUIRED_MULTI_PV)
        assertTrue(presets.levels(exact).any { it.strength is Strength.Handicap })
    }

    @Test
    fun `ids of a level do not depend on the capabilities`() {
        val other = caps.copy(eloRange = 1000..2000, maxMultiPv = 64)
        for (id in listOf(DifficultyIds.HANDICAP_3, DifficultyIds.ELO_1700, DifficultyIds.FULL)) {
            assertEquals(id, presets.byId(id, caps)?.id)
            assertEquals(id, presets.byId(id, other)?.id)
        }
    }

    @Test
    fun `search limits and delays are sane and handicap limits are small`() {
        for (level in presets.levels(caps)) {
            assertTrue(level.minReplyDelayMs >= 0)
            val l = level.limit
            assertTrue(l.depth != null || l.moveTimeMs != null || l.nodes != null)
            if (level.strength is Strength.Handicap) assertTrue((l.depth ?: 99) <= 8)
        }
    }

    @Test
    fun `unknown id gives null`() {
        assertNull(presets.byId(org.chesstrainer.core.engine.DifficultyId(1234), caps))
    }

    @Test
    fun `handicap needs room for every legal move`() {
        // Кандидати це всі легальні ходи (до 64): менше ліній у рушія означає, що частина ходів не кандидати.
        assertEquals(64, HandicapEngineService.REQUIRED_MULTI_PV)
        assertEquals(HandicapPolicy.MAX_CANDIDATES, HandicapEngineService.REQUIRED_MULTI_PV)
        assertTrue(FakeEngineService.DEFAULT_CAPABILITIES.maxMultiPv >= HandicapEngineService.REQUIRED_MULTI_PV)
    }

    @Test
    fun `handicap levels search to the profile depth and keep the profile delay`() {
        val handicap = presets.levels(caps).filter { it.strength is Strength.Handicap }
        for (level in handicap) {
            val profile = HandicapPolicy.profile((level.strength as Strength.Handicap).step)
            assertEquals(SearchLimit(depth = profile.searchDepth), level.limit)
            assertEquals(profile.minReplyDelayMs, level.minReplyDelayMs)
        }
    }

    @Test
    fun `Elo levels stop at the node limit with a time ceiling of three times the preset time, Full stays time-limited`() {
        // CONTRACTS 3.4/3.5 п. 8 (v0.20): медіани вузлів за час пресета на SM-S928B (HANDICAP.md, розд. 8.4 і 9).
        val expected = mapOf(
            DifficultyIds.ELO_MIN to SearchLimit(nodes = 267_319, moveTimeMs = 900),
            DifficultyIds.ELO_1500 to SearchLimit(nodes = 347_779, moveTimeMs = 1_200),
            DifficultyIds.ELO_1700 to SearchLimit(nodes = 427_741, moveTimeMs = 1_500),
            DifficultyIds.ELO_1900 to SearchLimit(nodes = 524_630, moveTimeMs = 1_800),
            DifficultyIds.ELO_2100 to SearchLimit(nodes = 677_873, moveTimeMs = 2_400),
        )
        for ((id, limit) in expected) assertEquals("ліміт $id", limit, presets.byId(id, caps)?.limit)
        assertEquals(SearchLimit(moveTimeMs = 1500), presets.byId(DifficultyIds.FULL, caps)?.limit)
        // Рівні без виміряних вузлів лишаються на часі.
        for (id in listOf(DifficultyIds.ELO_2400, DifficultyIds.ELO_2700)) {
            val l = presets.byId(id, caps)?.limit
            assertNull(l?.nodes)
            assertTrue(l?.moveTimeMs != null)
        }
    }
}
