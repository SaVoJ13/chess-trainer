// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import java.util.zip.GZIPInputStream
import org.chesstrainer.core.model.Uci
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Захист шкали кроків Handicap у CI (CONTRACTS 3.5 п. 7, v0.22; HANDICAP.md розд. 4.1, 7): метрики B300, B100, M1 і H
 * на **реальних позиціях** (2497 основних, 120 «мат в 1», 120 «вільна фігура»; пошуки Stockfish sf_19 глибин 1–3 і
 * еталон глибини 12 з кешу інструмента виміру) мають строго спадати від кроку 1 до 5 і не відхилятися від таблиці 4.1.
 * Ресурс `calibration/real-positions.tsv.gz` (≈ 1,2 МБ) створює `handicapMeasure --args="export-test-data --out …"`.
 * Метрики точні (без вибірки), телефон не потрібен.
 */
class RealPositionMetricsTest {

    private class Position(val kind: Char, val reference: Map<String, Int>, val byDepth: Map<Int, List<HandicapCandidate>>) {
        val best = reference.values.max()
        fun loss(uci: String) = best - reference.getValue(uci)
        fun givesMate(uci: String) = reference.getValue(uci) >= HandicapPolicy.MATE_CP - 10
    }

    private fun parseCandidates(text: String) = text.split(' ').filter { it.isNotEmpty() }.map {
        HandicapCandidate(Uci(it.substringBefore(':')), it.substringAfter(':').toInt())
    }

    private val positions: List<Position> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/calibration/real-positions.tsv.gz")) { "немає ресурсу" }
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val f = line.split('\t')
                Position(
                    f[0][0],
                    f[1].split(' ').associate { it.substringBefore(':') to it.substringAfter(':').toInt() },
                    mapOf(1 to parseCandidates(f[2]), 2 to parseCandidates(f[3]), 3 to parseCandidates(f[4])),
                )
            }.toList()
        }
    }

    private class Metrics(val b300: Double, val b100: Double, val m1: Double, val h: Double)

    private fun metrics(step: Int): Metrics {
        val profile = HandicapPolicy.profile(step)
        fun dist(p: Position) = HandicapPolicy.distribution(p.byDepth.getValue(profile.searchDepth), profile)
        fun probLoss(p: Position, threshold: Int) = dist(p).sumOf { (c, pr) -> if (p.loss(c.uci.value) >= threshold) pr else 0.0 }
        val main = positions.filter { it.kind == 'M' }
        val mate = positions.filter { it.kind == 'T' }
        val free = positions.filter { it.kind == 'F' }
        return Metrics(
            b300 = main.sumOf { probLoss(it, 300) } / main.size,
            b100 = main.sumOf { probLoss(it, 100) } / main.size,
            m1 = mate.sumOf { p -> dist(p).sumOf { (c, pr) -> if (p.givesMate(c.uci.value)) 0.0 else pr } } / mate.size,
            h = free.sumOf { probLoss(it, 300) } / free.size,
        )
    }

    private val all: List<Metrics> by lazy { (1..HandicapPolicy.STEPS).map { metrics(it) } }

    private fun assertStrictlyDecreasing(label: String, values: List<Double>) {
        for (i in 1 until values.size) assertTrue("$label має строго спадати: $values", values[i] < values[i - 1])
    }

    @Test
    fun `resource has the documented position sets`() {
        assertEquals(2497, positions.count { it.kind == 'M' })
        assertEquals(120, positions.count { it.kind == 'T' })
        assertEquals(120, positions.count { it.kind == 'F' })
        assertTrue(positions.all { p -> p.byDepth.values.all { it.isNotEmpty() } })
    }

    @Test
    fun `B300 B100 M1 and H fall strictly from step 1 to step 5 on real positions`() {
        assertStrictlyDecreasing("B300", all.map { it.b300 })
        assertStrictlyDecreasing("B100", all.map { it.b100 })
        assertStrictlyDecreasing("M1", all.map { it.m1 })
        assertStrictlyDecreasing("H", all.map { it.h })
    }

    @Test
    fun `missed mate in one at step 1 is 30 to 70 percent`() {
        assertTrue("M1 step 1: ${all[0].m1}", all[0].m1 in 0.30..0.70)
    }

    @Test
    fun `metrics match the table in HANDICAP md 4_1`() {
        // Таблиця 4.1 (v0.4): B300 / B100 / M1 пропущено / H пропущено, у відсотках до десятих.
        val expected = listOf(
            listOf(27.5, 58.8, 49.7, 61.4),
            listOf(20.6, 51.4, 40.2, 51.5),
            listOf(18.3, 48.8, 33.8, 42.2),
            listOf(14.8, 44.3, 29.6, 37.1),
            listOf(12.6, 41.7, 26.1, 30.7),
        )
        all.forEachIndexed { i, m ->
            val actual = listOf(m.b300, m.b100, m.m1, m.h).map { it * 100 }
            actual.zip(expected[i]).forEachIndexed { j, (a, e) ->
                assertEquals("крок ${i + 1}, метрика #$j", e, a, 0.06)
            }
        }
    }
}
