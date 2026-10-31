// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import kotlin.math.exp
import org.chesstrainer.core.model.Uci

/**
 * Параметри одного кроку `Strength.Handicap`. Початкові, ще не відкалібровані матчами
 * (CONTRACTS 3.5, п. 8): див. `HANDICAP.md`.
 */
internal data class HandicapProfile(
    /** Температура вибору: чим більша, тим охочіше обирається гірший хід. */
    val temperatureCp: Int,
    /** Кандидати гірші за найкращого більш ніж на стільки взагалі не розглядаються (найкращий лишається завжди). */
    val maxLossCp: Int,
    /** Імовірність (у проміле) «не побачити» тактичний найкращий хід, коли він помітно кращий за решту. */
    val blindSpotPermille: Int,
    /** Ліміт пошуку для рівня в пресетах. */
    val searchDepth: Int,
    val minReplyDelayMs: Long,
)

/** Хід-кандидат з оцінкою з погляду сторони, що ходить (більше = краще). */
internal data class HandicapCandidate(val uci: Uci, val scoreCp: Int)

/** Чиста (без рушія й часу) частина алгоритму Handicap. Детермінована при заданих двох киданнях. */
internal object HandicapPolicy {
    /** Кількість кроків Handicap; step 1 найслабший, [STEPS] найсильніший. */
    const val STEPS = 5

    /** Скільки ліній MultiPV потрібно алгоритму (і мінімум `capabilities.maxMultiPv` для рівнів Handicap). */
    const val CANDIDATES = 5

    /** Перевага найкращого ходу над другим, починаючи з якої хід вважається «тактичним». */
    const val TACTIC_GAP_CP = 200

    /** Оцінка мату в 0 ходів (мат у N ходів = [MATE_CP] − 10·N), щоб зіставляти мат із сантипішаками. */
    const val MATE_CP = 10_000

    private val profiles = listOf(
        //             T    maxLoss blind depth delay
        HandicapProfile(350, 800, 300, 1, 700),
        HandicapProfile(250, 600, 200, 2, 600),
        HandicapProfile(170, 450, 120, 3, 500),
        HandicapProfile(110, 350, 60, 4, 400),
        HandicapProfile(70, 250, 25, 5, 300),
    )

    fun profile(step: Int): HandicapProfile {
        require(step in 1..STEPS) { "Handicap step must be in 1..$STEPS, was $step" }
        return profiles[step - 1]
    }

    /** Порядок кандидатів: за оцінкою (спадання), за рівності за рядком UCI. */
    fun sorted(candidates: List<HandicapCandidate>): List<HandicapCandidate> =
        candidates.sortedWith(compareByDescending<HandicapCandidate> { it.scoreCp }.thenBy { it.uci.value })

    /** Чи є найкращий хід «тактичним»: він помітно (≥ [TACTIC_GAP_CP]) кращий за другий. */
    fun isTactical(sorted: List<HandicapCandidate>): Boolean =
        sorted.size >= 2 && sorted[0].scoreCp - sorted[1].scoreCp >= TACTIC_GAP_CP

    /** Імовірності вибору для кожного кандидата, у порядку [sorted]; сума = 1. */
    fun distribution(candidates: List<HandicapCandidate>, step: Int): List<Pair<HandicapCandidate, Double>> {
        val profile = profile(step)
        val ordered = sorted(candidates)
        if (ordered.isEmpty()) return emptyList()
        val all = softmax(ordered, profile)
        if (!isTactical(ordered)) return ordered.zip(all)
        val blind = profile.blindSpotPermille / 1000.0
        val rest = listOf(0.0) + softmax(ordered.drop(1), profile)
        return ordered.mapIndexed { i, c -> c to ((1 - blind) * all[i] + blind * rest[i]) }
    }

    /**
     * Вибір ходу. Завжди використовує обидва кидання: [blindDraw] вирішує, чи «не помічено» тактичний хід,
     * [pickDraw] обирає хід із розподілу; обидва в `[0, 1)`.
     */
    fun choose(candidates: List<HandicapCandidate>, step: Int, blindDraw: Double, pickDraw: Double): Uci {
        val profile = profile(step)
        val ordered = sorted(candidates)
        require(ordered.isNotEmpty()) { "No candidates" }
        val pool = if (isTactical(ordered) && blindDraw < profile.blindSpotPermille / 1000.0) {
            ordered.drop(1)
        } else {
            ordered
        }
        val weights = softmax(pool, profile)
        var cumulative = 0.0
        for (i in pool.indices) {
            cumulative += weights[i]
            if (pickDraw < cumulative) return pool[i].uci
        }
        // Накопичена сума могла не дійти до pickDraw через округлення: беремо останнього з ненульовою вагою.
        return pool[weights.indexOfLast { it > 0.0 }].uci
    }

    /** Нормовані ваги `exp(-втрата / T)` для кандидатів, не гірших за найкращого більш ніж на `maxLossCp`. */
    private fun softmax(ordered: List<HandicapCandidate>, profile: HandicapProfile): List<Double> {
        val best = ordered.first().scoreCp
        val raw = ordered.map { c ->
            val loss = best - c.scoreCp
            if (loss > profile.maxLossCp) 0.0 else exp(-loss.toDouble() / profile.temperatureCp)
        }
        val sum = raw.sum()
        return raw.map { it / sum }
    }
}
