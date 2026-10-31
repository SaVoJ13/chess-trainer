// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import kotlin.random.Random
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.testing.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandicapPolicyTest {
    private fun cands(vararg scores: Int) =
        scores.mapIndexed { i, cp -> HandicapCandidate(Uci("a${i + 1}b${i + 1}"), cp) }

    /** Набори кандидатів різної форми: рівні, спокійні, тактичні, з катастрофічними ходами, з матом. */
    private val samples = listOf(
        cands(0, 0, 0, 0, 0),
        cands(30, 20, 10, 0, -20),
        cands(60, 0, -50, -120, -300),
        cands(500, 40, 20, -100, -400),
        cands(900, 100, 0, -300, -900),
        cands(HandicapPolicy.MATE_CP - 10, 50, 0, -200, -700),
        cands(-200, -250, -400, -900, -1200),
        cands(25, -10),
        cands(100),
    )

    private fun expectedLoss(c: List<HandicapCandidate>, step: Int): Double {
        val best = c.maxOf { it.scoreCp }
        return HandicapPolicy.distribution(c, step).sumOf { (cand, p) -> p * (best - cand.scoreCp) }
    }

    @Test
    fun `distribution sums to one and is non negative`() {
        for (s in samples) for (step in 1..HandicapPolicy.STEPS) {
            val d = HandicapPolicy.distribution(s, step)
            assertEquals(1.0, d.sumOf { it.second }, 1e-9)
            assertTrue(d.all { it.second >= 0.0 })
        }
    }

    @Test
    fun `chance of the best move grows monotonically with step`() {
        for (s in samples) {
            val best = HandicapPolicy.sorted(s).first().uci
            val p = (1..HandicapPolicy.STEPS).map { step ->
                HandicapPolicy.distribution(s, step).first { it.first.uci == best }.second
            }
            for (i in 1 until p.size) assertTrue("sample $s: $p", p[i] >= p[i - 1] - 1e-12)
        }
    }

    @Test
    fun `expected loss shrinks monotonically with step`() {
        for (s in samples) {
            val loss = (1..HandicapPolicy.STEPS).map { expectedLoss(s, it) }
            for (i in 1 until loss.size) assertTrue("sample $s: $loss", loss[i] <= loss[i - 1] + 1e-9)
        }
    }

    @Test
    fun `strongest step chooses the best move clearly more often than the weakest`() {
        val s = cands(60, 0, -50, -120, -300)
        val top = HandicapPolicy.sorted(s).first().uci
        fun share(step: Int) = HandicapPolicy.distribution(s, step).first { it.first.uci == top }.second
        assertTrue(share(1) < 0.4)
        assertTrue(share(HandicapPolicy.STEPS) > 1.5 * share(1))
    }

    @Test
    fun `catastrophic moves are excluded by maxLoss and never chosen`() {
        val s = cands(0, -30, -2000)
        for (step in 1..HandicapPolicy.STEPS) {
            val p = HandicapPolicy.distribution(s, step).last().second
            assertEquals(0.0, p, 0.0)
        }
        val rng = Random(3)
        repeat(2000) {
            val picked = HandicapPolicy.choose(s, 1, rng.nextDouble(), rng.nextDouble())
            assertTrue(picked != s[2].uci)
        }
    }

    @Test
    fun `weakest step does not give material away on every move`() {
        // Типова позиція: найкращий хід, кілька майже рівних, один програшний фігури (≈ −300).
        val s = cands(20, 10, 0, -30, -300)
        val rng = Random(11)
        val picks = (1..5000).map { HandicapPolicy.choose(s, 1, rng.nextDouble(), rng.nextDouble()) }
        val bad = picks.count { it == s[4].uci }
        assertTrue("share of the losing move: ${bad / 5000.0}", bad / 5000.0 < 0.15)
        assertTrue(picks.toSet().size > 1) // але це не завжди один і той самий хід
    }

    @Test
    fun `weakest step sometimes overlooks a tactical best move such as mate in one`() {
        val s = cands(HandicapPolicy.MATE_CP - 10, 20, 0, -50, -100)
        val rng = Random(2)
        val misses = (1..5000).count { HandicapPolicy.choose(s, 1, rng.nextDouble(), rng.nextDouble()) != s[0].uci }
        assertTrue("miss rate ${misses / 5000.0}", misses / 5000.0 in 0.2..0.4)
        val strongMisses = (1..5000).count { HandicapPolicy.choose(s, 5, rng.nextDouble(), rng.nextDouble()) != s[0].uci }
        assertTrue(strongMisses / 5000.0 < 0.06)
    }

    @Test
    fun `choose is deterministic and sorts candidates itself`() {
        val s = cands(30, 20, 0, -50, -80)
        val shuffled = listOf(s[3], s[0], s[4], s[2], s[1])
        for (blind in listOf(0.0, 0.5, 0.99)) for (pick in listOf(0.0, 0.3, 0.7, 0.999)) {
            assertEquals(
                HandicapPolicy.choose(s, 2, blind, pick),
                HandicapPolicy.choose(shuffled, 2, blind, pick),
            )
        }
    }

    @Test
    fun `ties are ordered by UCI string`() {
        val tie = listOf(HandicapCandidate(Uci("e2e4"), 0), HandicapCandidate(Uci("d2d4"), 0))
        assertEquals(listOf("d2d4", "e2e4"), HandicapPolicy.sorted(tie).map { it.uci.value })
    }

    @Test
    fun `draws at the extremes stay inside the candidate set`() {
        val s = cands(10, 0, -10, -20, -30)
        val all = s.map { it.uci }.toSet()
        for (step in 1..HandicapPolicy.STEPS) for (draw in listOf(0.0, 0.5, 0.999999999)) {
            assertTrue(HandicapPolicy.choose(s, step, draw, draw) in all)
        }
    }

    @Test
    fun `profile parameters are monotone in step and step is range checked`() {
        val p = (1..HandicapPolicy.STEPS).map { HandicapPolicy.profile(it) }
        for (i in 1 until p.size) {
            assertTrue(p[i].temperatureCp < p[i - 1].temperatureCp)
            assertTrue(p[i].maxLossCp <= p[i - 1].maxLossCp)
            assertTrue(p[i].blindSpotPermille < p[i - 1].blindSpotPermille)
            assertTrue(p[i].searchDepth >= p[i - 1].searchDepth)
        }
        assertTrue(HandicapPolicy.STEPS >= 3)
        assertFailsWith<IllegalArgumentException> { HandicapPolicy.profile(0) }
        assertFailsWith<IllegalArgumentException> { HandicapPolicy.profile(HandicapPolicy.STEPS + 1) }
    }
}
