// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.engine

import kotlin.math.exp
import org.chesstrainer.core.model.Uci

/**
 * Параметри одного кроку `Strength.Handicap` (CONTRACTS 3.5, п. 3–5). Значення підібрані за метрикою п. 4 на
 * справжньому Stockfish, матчами не відкалібровані: див. `HANDICAP.md`.
 */
internal data class HandicapProfile(
    /** Температура вибору: чим більша, тим охочіше обирається гірший хід. */
    val temperatureCp: Int,
    /** Кандидати гірші за найкращого більш ніж на стільки взагалі не розглядаються (найкращий лишається завжди). */
    val maxLossCp: Int,
    /** Імовірність (у проміле) «не побачити» тактичний найкращий хід, коли він помітно кращий за решту. */
    val blindSpotPermille: Int,
    /** Імовірність (у проміле) зіграти рівномірно випадковий легальний хід. 0 на кроках 3–5. */
    val randomMovePermille: Int,
    /** Ліміт пошуку для рівня в пресетах. */
    val searchDepth: Int,
    val minReplyDelayMs: Long,
)

/** Хід-кандидат з оцінкою з погляду сторони, що ходить (більше = краще). */
internal data class HandicapCandidate(val uci: Uci, val scoreCp: Int)

/**
 * Чиста (без рушія й часу) частина алгоритму Handicap. Детермінована при заданих киданнях.
 *
 * Кандидати це **усі легальні ходи** (до [MAX_CANDIDATES]), а не кілька найкращих, інакше ходи, що віддають
 * фігуру, у вибірку не потрапляють (CONTRACTS 3.5, п. 3).
 */
internal object HandicapPolicy {
    /** Кількість кроків Handicap; step 1 найслабший, [STEPS] найсильніший. */
    const val STEPS = 5

    /** Скільки ліній MultiPV просить декоратор щонайбільше (і мінімум `capabilities.maxMultiPv` для рівнів Handicap). */
    const val MAX_CANDIDATES = 64

    /** Перевага найкращого ходу над другим, починаючи з якої хід вважається «тактичним». */
    const val TACTIC_GAP_CP = 200

    /** Оцінка мату в 0 ходів (мат у N ходів = [MATE_CP] − 10·N), щоб зіставляти мат із сантипішаками. */
    const val MATE_CP = 10_000

    private val profiles = listOf(
        //             T    maxLoss blind rnd depth delay
        HandicapProfile(193, 1000, 500, 100, 1, 700),
        HandicapProfile(140, 900, 400, 70, 1, 600),
        HandicapProfile(135, 800, 300, 0, 2, 500),
        HandicapProfile(115, 700, 250, 0, 3, 400),
        HandicapProfile(100, 650, 180, 0, 3, 300),
    )

    fun profile(step: Int): HandicapProfile {
        require(step in 1..STEPS) { "Handicap step must be in 1..$STEPS, was $step" }
        return profiles[step - 1]
    }

    /**
     * Скільки разів [choose] потребує кидання `Random` на один хід: фіксоване число для кроку, незалежне від
     * позиції. Крок з домішкою випадкового ходу: 4 (випадковий?, який саме, сліпа пляма, вибір); без неї: 2.
     */
    fun drawsPerMove(step: Int): Int = drawsPerMove(profile(step))

    fun drawsPerMove(profile: HandicapProfile): Int = if (profile.randomMovePermille > 0) 4 else 2

    /** Порядок кандидатів: за оцінкою (спадання), за рівності за рядком UCI. */
    fun sorted(candidates: List<HandicapCandidate>): List<HandicapCandidate> =
        candidates.sortedWith(compareByDescending<HandicapCandidate> { it.scoreCp }.thenBy { it.uci.value })

    /**
     * Чи є найкращий хід «тактичним»: він помітно (≥ [TACTIC_GAP_CP]) кращий за другий або це єдиний мат в 1 хід
     * (різниця між матом в 1 і матом в 3 всього 20 сп, але пропустити мат в 1 це груба помилка).
     */
    fun isTactical(sorted: List<HandicapCandidate>): Boolean =
        sorted.size >= 2 && (
            sorted[0].scoreCp - sorted[1].scoreCp >= TACTIC_GAP_CP ||
                (sorted[0].scoreCp >= MATE_CP - 10 && sorted[1].scoreCp < MATE_CP - 10)
            )

    /** Імовірності вибору для кожного кандидата, у порядку [sorted]; сума = 1. */
    fun distribution(candidates: List<HandicapCandidate>, step: Int): List<Pair<HandicapCandidate, Double>> =
        distribution(candidates, profile(step))

    /** Те саме для довільного профілю (потрібно інструменту підбору параметрів; сам застосунок бере [profile]). */
    fun distribution(candidates: List<HandicapCandidate>, profile: HandicapProfile): List<Pair<HandicapCandidate, Double>> {
        val ordered = sorted(candidates)
        if (ordered.isEmpty()) return emptyList()
        val n = ordered.size
        val base = DoubleArray(n)
        if (isTactical(ordered)) {
            val blind = profile.blindSpotPermille / 1000.0
            base[0] = 1 - blind
            softmax(ordered.drop(1), profile).forEachIndexed { i, w -> base[i + 1] = blind * w }
        } else {
            softmax(ordered, profile).forEachIndexed { i, w -> base[i] = w }
        }
        val random = profile.randomMovePermille / 1000.0
        return ordered.mapIndexed { i, c -> c to (random / n + (1 - random) * base[i]) }
    }

    /**
     * Вибір ходу за [draws] (кожне в `[0, 1)`, рівно [drawsPerMove] штук; усі витрачаються завжди, навіть коли
     * вони не потрібні, щоб кількість викликів `Random` не залежала від позиції). Порядок кидань: для кроку з
     * домішкою `[випадковий?, який саме]`, далі `[сліпа пляма, вибір]`.
     */
    fun choose(candidates: List<HandicapCandidate>, step: Int, draws: List<Double>): Uci =
        choose(candidates, profile(step), draws)

    fun choose(candidates: List<HandicapCandidate>, profile: HandicapProfile, draws: List<Double>): Uci {
        require(draws.size == drawsPerMove(profile)) { "Profile needs ${drawsPerMove(profile)} draws, got ${draws.size}" }
        val ordered = sorted(candidates)
        require(ordered.isNotEmpty()) { "No candidates" }
        val blindDraw = draws[draws.size - 2]
        val pickDraw = draws[draws.size - 1]
        if (profile.randomMovePermille > 0 && draws[0] < profile.randomMovePermille / 1000.0) {
            return ordered[(draws[1] * ordered.size).toInt().coerceIn(0, ordered.size - 1)].uci
        }
        val pool = if (isTactical(ordered)) {
            // Тактичний хід або бачать і грають його, або не бачать і вибирають серед решти.
            if (blindDraw >= profile.blindSpotPermille / 1000.0) return ordered[0].uci
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
