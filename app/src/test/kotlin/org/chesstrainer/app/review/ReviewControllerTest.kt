// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.review

import org.chesstrainer.app.storage.GameId
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.game.DefaultChessGameFactory
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.pgn.PgnResult
import org.chesstrainer.core.rules.ChesslibRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewControllerTest {
    private val rules = ChesslibRules()
    private val factory = DefaultChessGameFactory(rules)

    private fun game(sans: List<String>, start: Position? = null): ChessGame {
        var g = factory.new(start)
        for (san in sans) {
            val move = (rules.parseSan(g.position, san) as ChessResult.Ok).value
            g = (g.play(move) as ChessResult.Ok).value
        }
        return g
    }

    private fun controller(g: ChessGame, startPly: Int = g.moves.size) =
        ReviewController(GameId(1), rules, g, PgnResult.UNKNOWN, emptyMap(), startPly = startPly)

    @Test
    fun navigationButtonsMoveThroughThePositionsAndStopAtTheEnds() {
        val g = game(listOf("e4", "e5", "Nf3"))
        val c = controller(g)
        assertEquals(3, c.state.value.ply)
        assertFalse(c.state.value.canGoForward)

        c.previous()
        assertEquals(2, c.state.value.ply)
        assertEquals(g.positionAt(2), c.state.value.position)
        c.first()
        assertEquals(0, c.state.value.ply)
        assertFalse(c.state.value.canGoBack)
        assertNull(c.state.value.lastMove)
        c.previous()
        assertEquals("перед початком не йдемо", 0, c.state.value.ply)
        c.next()
        assertEquals(g.moves[0].move, c.state.value.lastMove)
        c.last()
        assertEquals(3, c.state.value.ply)
        c.next()
        assertEquals("за кінець не йдемо", 3, c.state.value.ply)
    }

    @Test
    fun goToClampsAndShowsThePositionAfterThatPly() {
        val g = game(listOf("e4", "e5", "Nf3", "Nc6"))
        val c = controller(g, startPly = 99)
        assertEquals("початковий хід обмежено", 4, c.state.value.ply)
        c.goTo(1)
        assertEquals(g.positionAt(1), c.state.value.position)
        c.goTo(-5)
        assertEquals(0, c.state.value.ply)
        c.goTo(100)
        assertEquals(4, c.state.value.ply)
    }

    @Test
    fun checkIsMarkedOnlyOnPositionsWithCheck() {
        val g = game(listOf("f3", "e5", "g4", "Qh4#"))
        val c = controller(g)
        assertEquals(Square.parse("e1"), c.state.value.checkSquare)
        c.previous()
        assertNull(c.state.value.checkSquare)
    }

    @Test
    fun flipSwapsTheBottomSide() {
        val c = controller(game(listOf("e4")))
        assertEquals(Color.WHITE, c.state.value.bottomColor)
        c.flip()
        assertEquals(Color.BLACK, c.state.value.bottomColor)
    }

    @Test
    fun emptyGameCanBeShown() {
        val c = controller(game(emptyList()))
        assertEquals(0, c.state.value.plyCount)
        assertFalse(c.state.value.canGoBack || c.state.value.canGoForward)
        assertTrue(moveRows(c.state.value.game).isEmpty())
    }

    @Test
    fun moveRowsPairWhiteAndBlackPlies() {
        val rows = moveRows(game(listOf("e4", "e5", "Nf3")))
        assertEquals(listOf(MoveRow(1, 1, 2), MoveRow(2, 3, null)), rows)
    }

    @Test
    fun moveRowsHonourBlackToMoveStartPosition() {
        val start = (rules.parseFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1") as ChessResult.Ok).value
        val rows = moveRows(game(listOf("e5", "Nf3", "Nc6"), start))
        assertEquals(listOf(MoveRow(1, null, 1), MoveRow(2, 2, 3)), rows)
    }
}
