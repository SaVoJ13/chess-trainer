// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app

import kotlinx.coroutines.runBlocking
import org.chesstrainer.core.engine.EngineState
import org.chesstrainer.core.engine.FakeEngineService
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Перевіряє підключення testFixtures `:core-chess:api` (CONTRACTS 7.3). */
class TestFixturesWiringTest {
    // Локальна заглушка: FakeEngineService.start() не викликає жодного методу правил.
    private object UnusedRules : ChessRules {
        override fun startPosition(): Position = TODO()
        override fun parseFen(fen: String): ChessResult<Position> = TODO()
        override fun legalMoves(position: Position): List<Move> = TODO()
        override fun legalMovesFrom(position: Position, from: Square): List<Move> = TODO()
        override fun apply(position: Position, move: Move): ChessResult<Position> = TODO()
        override fun parseUci(position: Position, uci: String): ChessResult<Move> = TODO()
        override fun toUci(move: Move): Uci = TODO()
        override fun toSan(position: Position, move: Move): String = TODO()
        override fun parseSan(position: Position, san: String): ChessResult<Move> = TODO()
        override fun isInCheck(position: Position): Boolean = TODO()
        override fun needsPromotion(position: Position, from: Square, to: Square): Boolean = TODO()
        override fun isCheckmate(position: Position): Boolean = TODO()
        override fun isStalemate(position: Position): Boolean = TODO()
        override fun isInsufficientMaterial(position: Position): Boolean = TODO()
    }

    @Test
    fun fakeEngineStartsAndReportsDefaultCapabilities() = runBlocking {
        val engine = FakeEngineService(UnusedRules)
        assertNull(engine.capabilities)

        val result = engine.start()

        assertEquals(ChessResult.Ok(Unit), result)
        assertEquals(EngineState.Ready, engine.state.value)
        assertEquals(FakeEngineService.DEFAULT_CAPABILITIES, engine.capabilities)
    }
}
