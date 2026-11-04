// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.analysis

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Evaluation

/** На чий бік перевага. */
enum class Advantage { WHITE, BLACK, EQUAL }

/**
 * Оцінка для показу. `Evaluation` завжди з погляду білих (CONTRACTS 2), тож знак тут не перевертається:
 * «+0,35» означає перевагу білих, «-0,35» чорних.
 */
sealed interface EvalDisplay {
    /** [signed] це пішаки із знаком, наприклад «+0,35» (сантипішаки / 100). */
    data class Pawns(val signed: String, val advantage: Advantage) : EvalDisplay

    /** Мат за [moves] ходів (завжди додатне число), матує [winner]. */
    data class Mate(val moves: Int, val winner: Color) : EvalDisplay
}

fun Evaluation.display(locale: Locale): EvalDisplay = when (this) {
    is Evaluation.Centipawns -> EvalDisplay.Pawns(
        signed = formatPawns(cp, locale),
        advantage = when {
            cp > 0 -> Advantage.WHITE
            cp < 0 -> Advantage.BLACK
            else -> Advantage.EQUAL
        },
    )
    // Mate(0) за контрактом не буває; на всяк випадок 0 читаємо як мат на користь білих.
    is Evaluation.Mate -> EvalDisplay.Mate(abs(moves), if (moves >= 0) Color.WHITE else Color.BLACK)
}

/** Сантипішаки в пішаки з двома знаками й знаком (крім нуля): 35 → «+0,35», -120 → «-1,20», 0 → «0,00». */
fun formatPawns(centipawns: Int, locale: Locale): String {
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    val absolute = abs(centipawns.toLong())
    val sign = when {
        centipawns > 0 -> "+"
        centipawns < 0 -> "-"
        else -> ""
    }
    return "$sign${absolute / 100}$separator${(absolute % 100).toString().padStart(2, '0')}"
}
