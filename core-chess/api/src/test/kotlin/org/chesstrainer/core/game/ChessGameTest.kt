// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.game

import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.pgn.PgnGame
import org.chesstrainer.core.pgn.PgnMove
import org.chesstrainer.core.pgn.PgnResult
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.core.testing.assertFailsWith
import org.chesstrainer.core.testing.assertIs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessGameTest {
    private val rules = ChesslibRules()
    private val factory = DefaultChessGameFactory(rules)

    private fun position(fen: String): Position = (rules.parseFen(fen) as ChessResult.Ok).value

    private fun game(fen: String? = null): ChessGame = factory.new(fen?.let(::position))

    private fun ChessGame.play(uci: String): ChessGame {
        val move = (rules.parseUci(position, uci) as ChessResult.Ok).value
        val result = play(move)
        check(result is ChessResult.Ok) { "Move $uci rejected: $result" }
        return result.value
    }

    private fun ChessGame.play(vararg ucis: String): ChessGame = ucis.fold(this) { g, u -> g.play(u) }

    private val knightShuffle = arrayOf("g1f3", "g8f6", "f3g1", "f6g8")

    // ------------------------------------------------------------ базова поведінка

    @Test
    fun newGameStartsFromStandardPosition() {
        val g = factory.new()
        assertEquals(rules.startPosition(), g.startPosition)
        assertEquals(rules.startPosition(), g.position)
        assertTrue(g.moves.isEmpty())
        assertEquals(GameStatus.Ongoing, g.status)
    }

    @Test
    fun playRecordsMoveSanAndPositionAndLeavesOriginalUntouched() {
        val g0 = factory.new()
        val g1 = g0.play("e2e4")
        assertTrue(g0.moves.isEmpty())
        assertEquals(1, g1.moves.size)
        val played = g1.moves[0]
        assertEquals("e4", played.san)
        assertEquals(Uci("e2e4"), played.uci)
        assertEquals(Move(Square.parse("e2")!!, Square.parse("e4")!!), played.move)
        assertEquals(g1.position, played.positionAfter)
        assertEquals(Color.BLACK, g1.position.sideToMove)
    }

    @Test
    fun illegalMoveIsRejectedWithoutException() {
        val result = factory.new().play(Move(Square.parse("e2")!!, Square.parse("e5")!!))
        assertEquals(ChessResult.Err(ChessError.IllegalMove("e2e5")), result)
    }

    @Test
    fun undoAndPositionAt() {
        val g = game().play("e2e4", "e7e5", "g1f3")
        assertEquals(g.startPosition, g.positionAt(0))
        assertEquals(g.moves[1].positionAfter, g.positionAt(2))
        assertEquals(g.position, g.positionAt(3))
        assertFailsWith<IllegalArgumentException> { g.positionAt(4) }
        assertFailsWith<IllegalArgumentException> { g.positionAt(-1) }

        val back = g.undo()
        assertEquals(2, back.moves.size)
        assertEquals(g.positionAt(2), back.position)
        assertSame(g, g.undo(0))
        assertEquals(1, g.undo(2).moves.size)
        assertEquals(0, g.undo(3).moves.size)
        val beyond = g.undo(99)
        assertTrue(beyond.moves.isEmpty())
        assertEquals(g.startPosition, beyond.position)
        assertFailsWith<IllegalArgumentException> { g.undo(-1) }
        // після undo партію можна продовжити іншим ходом
        assertIs<ChessResult.Ok<*>>(back.play(Move(Square.parse("b1")!!, Square.parse("c3")!!)))
    }

    // ------------------------------------------------------------ мат, пат, матеріал

    @Test
    fun checkmateEndsTheGame() {
        val g = game().play("f2f3", "e7e5", "g2g4", "d8h4")
        assertEquals(GameStatus.Checkmate(Color.BLACK), g.status)
        assertEquals("Qh4#", g.moves.last().san)
        val any = g.position.let { rules.legalMoves(it) }
        assertTrue(any.isEmpty())
        assertIs<ChessResult.Err>(g.play(Move(Square.parse("a2")!!, Square.parse("a3")!!)))
    }

    @Test
    fun stalemateEndsTheGame() {
        val g = game("7k/8/5K2/8/8/8/8/6Q1 w - - 0 1").play("g1g6")
        assertEquals(GameStatus.Stalemate, g.status)
        assertIs<ChessResult.Err>(g.play(Move(Square.parse("h8")!!, Square.parse("g8")!!)))
    }

    @Test
    fun insufficientMaterialEndsTheGame() {
        val g = game("7k/8/8/8/8/8/r7/K7 w - - 0 1")
        assertEquals(GameStatus.Ongoing, g.status)
        val after = game("8/8/8/4k3/8/8/1n6/K1B5 w - - 0 1").play("c1b2")
        assertEquals(GameStatus.InsufficientMaterial, after.status)
        assertIs<ChessResult.Err>(after.play(Move(Square.parse("e5")!!, Square.parse("e4")!!)))
    }

    @Test
    fun knightVersusKnightIsNotInsufficientMaterial() {
        val g = game("8/8/8/3nk3/8/8/8/3NK3 w - - 0 1")
        assertEquals(GameStatus.Ongoing, g.status)
    }

    // ------------------------------------------------------------ повторення

    @Test
    fun threefoldRepetitionIsClaimableAndGameContinues() {
        var g = game()
        g = g.play(*knightShuffle)
        assertEquals(GameStatus.Ongoing, g.status) // друге входження
        g = g.play(*knightShuffle)
        assertEquals(GameStatus.DrawClaimable(setOf(ClaimReason.THREEFOLD_REPETITION)), g.status)
        assertIs<ChessResult.Ok<*>>(g.play(Move(Square.parse("e2")!!, Square.parse("e4")!!)))
    }

    @Test
    fun fivefoldRepetitionIsAutomaticAndEndsTheGame() {
        var g = game()
        repeat(4) { g = g.play(*knightShuffle) }
        assertEquals(GameStatus.DrawAutomatic(AutoDrawReason.FIVEFOLD_REPETITION), g.status)
        assertIs<ChessResult.Err>(g.play(Move(Square.parse("e2")!!, Square.parse("e4")!!)))
    }

    @Test
    fun fourthRepetitionIsStillOnlyClaimable() {
        var g = game()
        repeat(3) { g = g.play(*knightShuffle) }
        assertEquals(GameStatus.DrawClaimable(setOf(ClaimReason.THREEFOLD_REPETITION)), g.status)
    }

    @Test
    fun repetitionIgnoresMoveCountersAndUsesPositionKey() {
        // Початкова позиція має лічильники 0 1; після повернення вони інші, але позиція та сама.
        val g = game().play(*knightShuffle).play(*knightShuffle)
        assertTrue(g.position.fen != g.startPosition.fen)
        assertEquals(g.startPosition.positionKey, g.position.positionKey)
        assertIs<GameStatus.DrawClaimable>(g.status)
    }

    @Test
    fun changedCastlingRightsMeanNoRepetition() {
        // Король ходить туди й назад: права рокіровки втрачені, тож позиція не збігається з початковою.
        val g = game("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
            .play("e1e2", "a8a7", "e2e1", "a7a8", "e1e2", "a8a7", "e2e1", "a7a8")
        assertEquals(GameStatus.Ongoing, g.status)
    }

    // ------------------------------------------------------------ 50 і 75 ходів

    @Test
    fun fiftyMoveRuleIsClaimableAtHundredPlies() {
        val before = game("4k3/8/8/8/8/8/8/R3K3 w - - 98 80").play("e1d1")
        assertEquals(GameStatus.Ongoing, before.status) // 99
        val g = before.play("e8d8")
        assertEquals(100, g.position.halfmoveClock)
        assertEquals(GameStatus.DrawClaimable(setOf(ClaimReason.FIFTY_MOVE_RULE)), g.status)
        assertIs<ChessResult.Ok<*>>(g.play(Move(Square.parse("d1")!!, Square.parse("e1")!!)))
    }

    @Test
    fun seventyFiveMoveRuleIsAutomatic() {
        val g = game("3k4/8/8/8/8/8/8/R2K4 w - - 148 90").play("d1e1")
        assertEquals(GameStatus.DrawClaimable(setOf(ClaimReason.FIFTY_MOVE_RULE)), g.status) // 149
        val end = g.play("d8e8")
        assertEquals(150, end.position.halfmoveClock)
        assertEquals(GameStatus.DrawAutomatic(AutoDrawReason.SEVENTY_FIVE_MOVE_RULE), end.status)
        assertIs<ChessResult.Err>(end.play(Move(Square.parse("a1")!!, Square.parse("a2")!!)))
    }

    @Test
    fun claimsCombineRepetitionAndFiftyMove() {
        // Лічильник уже великий, а позиція повторюється: обидві причини в одному статусі.
        var g = game("4k3/8/8/8/8/8/8/R3K1N1 w - - 90 80")
        g = g.play("g1f3", "e8d8", "f3g1", "d8e8", "g1f3", "e8d8", "f3g1", "d8e8")
        assertEquals(98, g.position.halfmoveClock)
        // початкова позиція зустрілась тричі (півходи 0, 4, 8), півходів ще 98
        assertEquals(GameStatus.DrawClaimable(setOf(ClaimReason.THREEFOLD_REPETITION)), g.status)
        // позиція після півходу 2 зустрілась втретє (2, 6, 10), і лічильник дійшов до 100
        g = g.play("g1f3", "e8d8")
        assertEquals(100, g.position.halfmoveClock)
        assertEquals(
            GameStatus.DrawClaimable(setOf(ClaimReason.THREEFOLD_REPETITION, ClaimReason.FIFTY_MOVE_RULE)),
            g.status,
        )
    }

    // ------------------------------------------------------------ пріоритет статусів

    @Test
    fun checkmateOnHundredthPlyStaysCheckmate() {
        val g = game("7k/5K2/8/8/8/8/8/6Q1 w - - 99 80").play("g1g7")
        assertEquals(100, g.position.halfmoveClock)
        assertEquals(GameStatus.Checkmate(Color.WHITE), g.status)
    }

    @Test
    fun checkmateOnHundredFiftiethPlyStaysCheckmate() {
        val g = game("7k/5K2/8/8/8/8/8/6Q1 w - - 149 80").play("g1g7")
        assertEquals(150, g.position.halfmoveClock)
        assertEquals(GameStatus.Checkmate(Color.WHITE), g.status)
    }

    @Test
    fun stalemateBeatsFiftyAndSeventyFiveMoveRules() {
        assertEquals(GameStatus.Stalemate, game("7k/8/5K2/8/8/8/8/6Q1 w - - 99 80").play("g1g6").status)
        assertEquals(GameStatus.Stalemate, game("7k/8/5K2/8/8/8/8/6Q1 w - - 149 80").play("g1g6").status)
    }

    @Test
    fun insufficientMaterialBeatsFiftyAndSeventyFiveMoveRules() {
        assertEquals(GameStatus.InsufficientMaterial, game("4k3/8/8/8/8/8/8/3NK3 w - - 100 80").status)
        assertEquals(GameStatus.InsufficientMaterial, game("4k3/8/8/8/8/8/8/3NK3 w - - 150 80").status)
    }

    @Test
    fun automaticDrawBeatsClaimable() {
        // 150 півходів і одночасно повторення (початкова позиція стояла тричі): автоматична нічия
        val g = game("4k3/8/8/8/8/8/8/R3K3 w - - 146 80")
            .play("a1a2", "e8d8", "a2a1", "d8e8")
        assertEquals(150, g.position.halfmoveClock)
        assertEquals(GameStatus.DrawAutomatic(AutoDrawReason.SEVENTY_FIVE_MOVE_RULE), g.status)
    }

    @Test
    fun fivefoldBeatsClaimable() {
        var g = game("4k3/8/8/8/8/8/8/R3K3 w - - 0 1")
        repeat(4) { g = g.play("a1a2", "e8d8", "a2a1", "d8e8") }
        assertEquals(GameStatus.DrawAutomatic(AutoDrawReason.FIVEFOLD_REPETITION), g.status)
    }

    // ------------------------------------------------------------ PGN

    @Test
    fun toPgnPutsSevenTagRosterFirstAndKeepsTheRest() {
        val g = game().play("e2e4", "e7e5")
        val pgn = factory.toPgn(g, mapOf("White" to "Alice", "Annotator" to "me", "Result" to "ignored"), PgnResult.DRAW)
        assertEquals(
            listOf("Event", "Site", "Date", "Round", "White", "Black", "Result", "Annotator"),
            pgn.headers.keys.toList(),
        )
        assertEquals("Alice", pgn.headers["White"])
        assertEquals("?", pgn.headers["Black"])
        assertEquals("1/2-1/2", pgn.headers["Result"])
        assertEquals(PgnResult.DRAW, pgn.result)
        assertEquals(null, pgn.startFen)
        assertEquals(listOf("e4", "e5"), pgn.moves.map { it.san })
        assertEquals(listOf(Uci("e2e4"), Uci("e7e5")), pgn.moves.map { it.uci })
    }

    @Test
    fun toPgnRecordsNonStandardStart() {
        val fen = "4k3/8/8/8/8/8/8/R3K3 w Q - 0 1"
        val pgn = factory.toPgn(game(fen), emptyMap(), PgnResult.UNKNOWN)
        assertEquals(Fen(fen), pgn.startFen)
    }

    @Test
    fun fromPgnReplaysMovesAndRoundTripsThroughToPgn() {
        val original = game().play("e2e4", "e7e5", "g1f3", "b8c6")
        val pgn = factory.toPgn(original, emptyMap(), PgnResult.UNKNOWN)
        val restored = (factory.fromPgn(pgn) as ChessResult.Ok).value
        assertEquals(original.position, restored.position)
        assertEquals(original.moves.map { it.san }, restored.moves.map { it.san })
        assertEquals(original.status, restored.status)
    }

    @Test
    fun fromPgnUsesStartFen() {
        val fen = "4k3/8/8/8/8/8/8/R3K3 w Q - 0 1"
        val pgn = PgnGame(emptyMap(), Fen(fen), listOf(PgnMove("O-O-O", Uci("e1c1"))), PgnResult.UNKNOWN)
        val g = (factory.fromPgn(pgn) as ChessResult.Ok).value
        assertEquals(position(fen), g.startPosition)
        assertEquals("O-O-O", g.moves[0].san)
    }

    @Test
    fun fromPgnReportsIllegalMoveWithPly() {
        val pgn = PgnGame(
            emptyMap(), null,
            listOf(PgnMove("e4", Uci("e2e4")), PgnMove("e5", Uci("e7e5")), PgnMove("Ke3", Uci("e1e3"))),
            PgnResult.UNKNOWN,
        )
        assertEquals(
            ChessResult.Err(ChessError.PgnIllegalMove(gameIndex = 0, ply = 3, san = "Ke3")),
            factory.fromPgn(pgn),
        )
    }

    @Test
    fun fromPgnRejectsBadStartFen() {
        val pgn = PgnGame(emptyMap(), Fen("garbage"), emptyList(), PgnResult.UNKNOWN)
        val r = factory.fromPgn(pgn)
        assertTrue(r is ChessResult.Err && r.error is ChessError.InvalidFen)
    }

    @Test
    fun fromPgnDoesNotStopAtAutomaticDraw() {
        val shuffle = listOf("g1f3" to "Nf3", "g8f6" to "Nf6", "f3g1" to "Ng1", "f6g8" to "Ng8")
        val moves = List(5) { shuffle }.flatten().map { (u, s) -> PgnMove(s, Uci(u)) } +
            PgnMove("e4", Uci("e2e4"))
        val g = (factory.fromPgn(PgnGame(emptyMap(), null, moves, PgnResult.UNKNOWN)) as ChessResult.Ok).value
        assertEquals(21, g.moves.size)
    }
}
