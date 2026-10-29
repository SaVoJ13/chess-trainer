// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.model

import org.chesstrainer.core.engine.SearchLimit
import org.junit.Test
import org.junit.Assert.assertEquals
import org.chesstrainer.core.testing.assertFailsWith
import org.junit.Assert.assertNull

class ModelTest {
    @Test
    fun `square coordinates and names`() {
        assertEquals("a1", Square(0).name)
        assertEquals("h8", Square(63).name)
        assertEquals(Square(28), Square.of(file = 4, rank = 3))
        assertEquals(4, Square(28).file)
        assertEquals(3, Square(28).rank)
        assertEquals("e4", Square(28).name)
    }

    @Test
    fun `square parse round trips and rejects garbage`() {
        for (i in 0..63) assertEquals(Square(i), Square.parse(Square(i).name))
        listOf("", "e", "e44", "i1", "a9", "a0", "E4").forEach { assertNull(it, Square.parse(it)) }
    }

    @Test
    fun `square rejects out of range`() {
        assertFailsWith<IllegalArgumentException> { Square(64) }
        assertFailsWith<IllegalArgumentException> { Square(-1) }
        assertFailsWith<IllegalArgumentException> { Square.of(8, 0) }
    }

    @Test
    fun `color opposite`() {
        assertEquals(Color.BLACK, Color.WHITE.opposite)
        assertEquals(Color.WHITE, Color.BLACK.opposite)
    }

    @Test
    fun `mate is never zero`() {
        assertFailsWith<IllegalArgumentException> { Evaluation.Mate(0) }
        Evaluation.Mate(1)
        Evaluation.Mate(-3)
    }

    @Test
    fun `search limit needs at least one limit`() {
        assertFailsWith<IllegalArgumentException> { SearchLimit() }
        SearchLimit(depth = 1)
        SearchLimit(moveTimeMs = 100)
        SearchLimit(nodes = 1000)
    }
}
