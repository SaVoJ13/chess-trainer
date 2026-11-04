// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.calibration

import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.rules.ChesslibRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Дебюти інструмента матчів (звичайний JVM-тест, телефон не потрібен): обидва набори легальні й не перетинаються. */
class OpeningsTest {
    private val rules = ChesslibRules()

    private fun assertLegal(line: String) {
        var position = rules.startPosition()
        for (m in line.split(' ')) {
            val move = (rules.parseUci(position, m) as? ChessResult.Ok)?.value ?: error("нелегальний хід $m у «$line»")
            position = (rules.apply(position, move) as ChessResult.Ok).value
        }
    }

    @Test
    fun `main and confirm opening sets are legal, 24 each, six plies, disjoint`() {
        for (set in listOf(Openings.lines, Openings.confirm)) {
            assertEquals(24, set.size)
            assertEquals(24, set.toSet().size)
            set.forEach { assertEquals(6, it.split(' ').size); assertLegal(it) }
        }
        assertTrue(Openings.lines.intersect(Openings.confirm.toSet()).isEmpty())
    }
}
