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

    private fun fromLosses(best: Int, losses: List<Int>) =
        losses.mapIndexed { i, loss -> HandicapCandidate(Uci("c${i / 10}d${i % 10}"), best - loss) }

    /**
     * Набори кандидатів «типових форм»: ВСІ легальні ходи позиції (30–38), а не кілька найкращих. Втрати (сп) взято
     * з розподілів справжнього Stockfish (див. HANDICAP.md): кілька майже рівних ходів, багато посередніх, значна
     * частка ходів, що віддають фігуру (≥ 300 сп) або гірше.
     */
    private val quiet = fromLosses(
        30,
        listOf(0, 5, 12, 20, 30, 40, 55, 70, 85, 110, 130, 150, 170, 190, 220, 250, 280, 310, 340, 380, 420, 480, 560, 640, 780, 900, 1100, 2000),
    )
    private val sharp = fromLosses(
        0,
        listOf(0, 10, 25, 45, 90, 120, 180, 240, 290, 320, 350, 400, 450, 520, 600, 700, 800, 900, 1000, 1200, 1500, 2400),
    )
    private val balanced = fromLosses(
        10,
        listOf(0, 8, 15, 25, 35, 50, 60, 80, 100, 130, 160, 200, 240, 280, 300, 330, 370, 410, 460, 520, 600, 720, 850, 1000, 1300, 1800),
    )
    private val typical = listOf(quiet, sharp, balanced)

    /** Мат в 1 і решта ходів різної якості (мат у 1: оцінка MATE_CP − 10). */
    private val mateInOne = cands(HandicapPolicy.MATE_CP - 10) +
        fromLosses(100, listOf(0, 30, 80, 150, 250, 400, 700, 1500)).map { it.copy(uci = Uci("z" + it.uci.value)) }

    /** Безкоштовна фігура: найкращий хід виграє ≥ 300 сп порівняно з рештою. */
    private val freePiece = fromLosses(
        320,
        listOf(0, 330, 340, 360, 380, 400, 430, 470, 520, 600, 700, 800, 1000),
    )

    private fun probLoss(c: List<HandicapCandidate>, step: Int, threshold: Int): Double {
        val best = c.maxOf { it.scoreCp }
        return HandicapPolicy.distribution(c, step).sumOf { (cand, p) -> if (best - cand.scoreCp >= threshold) p else 0.0 }
    }

    private fun expectedLoss(c: List<HandicapCandidate>, step: Int): Double {
        val best = c.maxOf { it.scoreCp }
        return HandicapPolicy.distribution(c, step).sumOf { (cand, p) -> p * (best - cand.scoreCp) }
    }

    private fun assertStrictlyDecreasing(label: String, values: List<Double>) {
        for (i in 1 until values.size) assertTrue("$label: $values", values[i] < values[i - 1])
    }

    // --- розподіл ---

    @Test
    fun `distribution sums to one and is non negative`() {
        for (s in typical + listOf(mateInOne, freePiece, cands(0), cands(25, -10), cands(-200, -250, -400, -900))) {
            for (step in 1..HandicapPolicy.STEPS) {
                val d = HandicapPolicy.distribution(s, step)
                assertEquals(1.0, d.sumOf { it.second }, 1e-9)
                assertTrue(d.all { it.second >= 0.0 })
            }
        }
    }

    @Test
    fun `candidates are all moves so the step can give a piece away`() {
        // Ходи з втратою ≥ 300 сп мають ненульову ймовірність на кроках 1–3 (CONTRACTS 3.5 п. 3).
        for (s in typical) for (step in 1..3) {
            assertTrue("step $step", HandicapPolicy.profile(step).maxLossCp >= 300)
            assertTrue("step $step: ${probLoss(s, step, 300)}", probLoss(s, step, 300) > 0.0)
        }
    }

    // --- метрика п. 4 на синтетичних наборах ---

    @Test
    fun `B300 falls strictly with step and step 1 is inside the target range`() {
        // Реальні позиції: тихі набори плюс позиції із вільною фігурою (тактичні, де діє сліпа пляма).
        val mix = typical + listOf(freePiece)
        val b300 = (1..HandicapPolicy.STEPS).map { step -> mix.map { probLoss(it, step, 300) }.average() }
        assertStrictlyDecreasing("B300", b300)
        assertTrue("step 1 B300 ${b300[0]}", b300[0] in 0.20..0.35)
        assertTrue("step 5 B300 ${b300[4]}", b300[4] in 0.0..0.05)
        for (s in typical) assertStrictlyDecreasing("B300 $s", (1..HandicapPolicy.STEPS).map { probLoss(s, it, 300) })
    }

    @Test
    fun `B100 falls strictly with step`() {
        for (s in typical) assertStrictlyDecreasing("B100", (1..HandicapPolicy.STEPS).map { probLoss(s, it, 100) })
    }

    @Test
    fun `missed mate in one falls strictly with step and is 30 to 70 percent at step 1`() {
        val matePlayed = (1..HandicapPolicy.STEPS).map { step ->
            HandicapPolicy.distribution(mateInOne, step).first { it.first.scoreCp >= HandicapPolicy.MATE_CP - 10 }.second
        }
        val missed = matePlayed.map { 1 - it }
        assertStrictlyDecreasing("M1", missed)
        assertTrue("M1 step 1: ${missed[0]}", missed[0] in 0.30..0.70)
    }

    @Test
    fun `missed free piece falls strictly with step`() {
        assertStrictlyDecreasing("H", (1..HandicapPolicy.STEPS).map { probLoss(freePiece, it, 300) })
    }

    @Test
    fun `expected loss and the chance of the best move are monotone in step`() {
        for (s in typical) {
            val loss = (1..HandicapPolicy.STEPS).map { expectedLoss(s, it) }
            for (i in 1 until loss.size) assertTrue("loss $loss", loss[i] <= loss[i - 1] + 1e-9)
            val best = HandicapPolicy.sorted(s).first().uci
            val p = (1..HandicapPolicy.STEPS).map { step ->
                HandicapPolicy.distribution(s, step).first { it.first.uci == best }.second
            }
            for (i in 1 until p.size) assertTrue("best $p", p[i] >= p[i - 1] - 1e-12)
        }
    }

    @Test
    fun `step 1 does not give a piece away every move and a run of five gross errors is rare`() {
        val rng = Random(11)
        for (s in typical) {
            val picks = (1..20_000).map { chooseWith(s, 1, rng) }
            val best = s.maxOf { it.scoreCp }
            val gross = picks.map { uci -> best - s.first { it.uci == uci }.scoreCp >= 300 }
            val share = gross.count { it } / 20_000.0
            assertTrue("share of gross errors $share", share < 0.40)
            assertTrue(picks.toSet().size > 1)
            // Серії з 5 ходів поспіль: незалежні кидання => share^5.
            assertTrue("five in a row ${Math.pow(share, 5.0)}", Math.pow(share, 5.0) < 0.01)
        }
    }

    // --- вибір ходу ---

    private fun chooseWith(c: List<HandicapCandidate>, step: Int, rng: Random) =
        HandicapPolicy.choose(c, step, List(HandicapPolicy.drawsPerMove(step)) { rng.nextDouble() })

    @Test
    fun `choose follows the distribution`() {
        val rng = Random(5)
        for (step in listOf(1, 3, 5)) {
            val n = 60_000
            val counts = HashMap<Uci, Int>()
            repeat(n) { counts.merge(chooseWith(quiet, step, rng), 1, Int::plus) }
            for ((cand, p) in HandicapPolicy.distribution(quiet, step)) {
                val freq = (counts[cand.uci] ?: 0).toDouble() / n
                assertEquals("step $step ${cand.uci.value}", p, freq, 0.012)
            }
        }
    }

    @Test
    fun `draw count is fixed per step and random moves exist only on steps 1 and 2`() {
        assertEquals(listOf(4, 4, 2, 2, 2), (1..HandicapPolicy.STEPS).map { HandicapPolicy.drawsPerMove(it) })
        for (step in 3..HandicapPolicy.STEPS) assertEquals(0, HandicapPolicy.profile(step).randomMovePermille)
        assertTrue(HandicapPolicy.profile(1).randomMovePermille in 1..150)
        assertTrue(HandicapPolicy.profile(2).randomMovePermille in 1..HandicapPolicy.profile(1).randomMovePermille)
        assertFailsWith<IllegalArgumentException> { HandicapPolicy.choose(quiet, 1, listOf(0.1, 0.2)) }
        assertFailsWith<IllegalArgumentException> { HandicapPolicy.choose(quiet, 5, listOf(0.1, 0.2, 0.3, 0.4)) }
    }

    @Test
    fun `random move is uniform among all candidates and only steps 1 and 2 can pick a catastrophic move`() {
        val s = cands(0, -30, -5000)
        val rng = Random(3)
        // Крок 1: домішка рівномірно випадкового ходу, тож «катастрофічний» хід можливий.
        val first = (1..20_000).count { chooseWith(s, 1, rng) == s[2].uci } / 20_000.0
        val p1 = HandicapPolicy.profile(1).randomMovePermille / 1000.0
        assertEquals(p1 / 3, first, 0.01)
        // Кроки 3–5: випадкових ходів немає, хід гірший за maxLoss неможливий.
        for (step in 3..HandicapPolicy.STEPS) {
            assertEquals(0.0, HandicapPolicy.distribution(s, step).last().second, 0.0)
            repeat(3000) { assertTrue(chooseWith(s, step, rng) != s[2].uci) }
        }
    }

    @Test
    fun `tactical best move is either seen or overlooked`() {
        val s = cands(HandicapPolicy.MATE_CP - 10, 20, 0, -50, -100)
        val rng = Random(2)
        val miss1 = (1..20_000).count { chooseWith(s, 1, rng) != s[0].uci } / 20_000.0
        val miss5 = (1..20_000).count { chooseWith(s, 5, rng) != s[0].uci } / 20_000.0
        assertTrue("miss1 $miss1", miss1 in 0.3..0.7)
        assertTrue("miss5 $miss5", miss5 < 0.06)
    }

    @Test
    fun `choose is deterministic and sorts candidates itself`() {
        val s = cands(30, 20, 0, -50, -80)
        val shuffled = listOf(s[3], s[0], s[4], s[2], s[1])
        for (step in 1..HandicapPolicy.STEPS) {
            val n = HandicapPolicy.drawsPerMove(step)
            for (a in listOf(0.0, 0.5, 0.99)) for (b in listOf(0.0, 0.3, 0.7, 0.999)) {
                val draws = List(n) { if (it % 2 == 0) a else b }
                assertEquals(HandicapPolicy.choose(s, step, draws), HandicapPolicy.choose(shuffled, step, draws))
            }
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
            assertTrue(HandicapPolicy.choose(s, step, List(HandicapPolicy.drawsPerMove(step)) { draw }) in all)
        }
    }

    @Test
    fun `profile parameters are monotone in step and step is range checked`() {
        val p = (1..HandicapPolicy.STEPS).map { HandicapPolicy.profile(it) }
        for (i in 1 until p.size) {
            assertTrue(p[i].temperatureCp < p[i - 1].temperatureCp)
            assertTrue(p[i].maxLossCp <= p[i - 1].maxLossCp)
            assertTrue(p[i].blindSpotPermille < p[i - 1].blindSpotPermille)
            assertTrue(p[i].randomMovePermille <= p[i - 1].randomMovePermille)
            assertTrue(p[i].searchDepth >= p[i - 1].searchDepth)
        }
        for (step in 1..3) assertTrue("maxLoss step $step", p[step - 1].maxLossCp >= 300)
        assertTrue(HandicapPolicy.STEPS >= 3)
        assertFailsWith<IllegalArgumentException> { HandicapPolicy.profile(0) }
        assertFailsWith<IllegalArgumentException> { HandicapPolicy.profile(HandicapPolicy.STEPS + 1) }
    }
}
