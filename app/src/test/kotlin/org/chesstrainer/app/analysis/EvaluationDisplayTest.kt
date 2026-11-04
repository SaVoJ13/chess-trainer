// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.analysis

import java.util.Locale
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Evaluation
import org.junit.Assert.assertEquals
import org.junit.Test

class EvaluationDisplayTest {
    private val uk = Locale.forLanguageTag("uk-UA")
    private val en = Locale.US

    @Test
    fun centipawnsBecomePawnsWithSignAndLocaleSeparator() {
        assertEquals("+0,35", formatPawns(35, uk))
        assertEquals("+0.35", formatPawns(35, en))
        assertEquals("-1,20", formatPawns(-120, uk))
        assertEquals("+0,05", formatPawns(5, uk))
        assertEquals("-0,05", formatPawns(-5, uk))
        assertEquals("+12,00", formatPawns(1200, uk))
        assertEquals("0,00", formatPawns(0, uk))
    }

    @Test
    fun extremeValuesDoNotOverflow() {
        assertEquals("+21474836,47", formatPawns(Int.MAX_VALUE, uk))
        assertEquals("-21474836,48", formatPawns(Int.MIN_VALUE, uk))
    }

    @Test
    fun evaluationIsAlreadyFromWhitesPointOfViewSoTheSignIsNotFlipped() {
        assertEquals(EvalDisplay.Pawns("+0,35", Advantage.WHITE), Evaluation.Centipawns(35).display(uk))
        assertEquals(EvalDisplay.Pawns("-0,35", Advantage.BLACK), Evaluation.Centipawns(-35).display(uk))
        assertEquals(EvalDisplay.Pawns("0,00", Advantage.EQUAL), Evaluation.Centipawns(0).display(uk))
    }

    @Test
    fun mateShowsMovesCountAndWhoMates() {
        assertEquals(EvalDisplay.Mate(3, Color.WHITE), Evaluation.Mate(3).display(uk))
        assertEquals(EvalDisplay.Mate(2, Color.BLACK), Evaluation.Mate(-2).display(uk))
    }
}
