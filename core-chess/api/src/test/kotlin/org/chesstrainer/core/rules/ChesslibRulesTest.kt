// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.rules

import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.testing.assertFailsWith
import org.chesstrainer.core.testing.assertIs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChesslibRulesTest {
    private val rules = ChesslibRules()

    private fun pos(fen: String): Position = (rules.parseFen(fen) as ChessResult.Ok).value

    private fun sq(name: String) = Square.parse(name)!!

    private fun ucis(position: Position): Set<String> = rules.legalMoves(position).map { rules.toUci(it).value }.toSet()

    private fun play(position: Position, vararg uci: String): Position = uci.fold(position) { p, u ->
        val move = (rules.parseUci(p, u) as ChessResult.Ok).value
        (rules.apply(p, move) as ChessResult.Ok).value
    }

    // ------------------------------------------------------------ початкова позиція й FEN

    @Test
    fun startPositionFenRoundTrip() {
        val start = rules.startPosition()
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", start.fen.value)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -", start.positionKey)
        assertEquals(Color.WHITE, start.sideToMove)
        assertEquals(20, rules.legalMoves(start).size)
        assertEquals(start.fen, pos(start.fen.value).fen)
    }

    @Test
    fun fenFieldIsPerSpecAfterEveryDoublePush() {
        val after = play(rules.startPosition(), "e2e4")
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", after.fen.value)
        assertEquals(sq("e3"), after.enPassant)
        // але ключ без поля, бо чорні не можуть взяти на проході
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -", after.positionKey)
    }

    @Test
    fun fenWithFourFieldsGetsDefaultCounters() {
        val p = pos("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -")
        assertEquals(0, p.halfmoveClock)
        assertEquals(1, p.fullmoveNumber)
    }

    @Test
    fun invalidFensAreRejected() {
        val bad = listOf(
            "",
            "not a fen",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1",                  // 7 ranks
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNX w KQkq - 0 1",          // unknown piece
            "rnbqkbnr/pppppppp/9/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",          // bad digit
            "rnbqkbnr/ppppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",         // 9 squares
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1",          // side
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkqK - 0 1",         // castling
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq e4 0 1",         // ep square
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - -1 1",         // halfmove
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 0",          // fullmove
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN1 w KQkq - 0 1",          // right without rook
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq e3 0 1",         // ep without pawn
            "P7/8/8/8/8/8/8/k6K w - - 0 1",                                        // pawn on rank 8
        )
        for (fen in bad) {
            val r = rules.parseFen(fen)
            assertTrue("Expected InvalidFen for '$fen' but was $r", r is ChessResult.Err && r.error is ChessError.InvalidFen)
        }
    }

    @Test
    fun exactlyOneKingPerSide() {
        assertIs<ChessResult.Err>(rules.parseFen("8/8/8/8/8/8/8/K7 w - - 0 1"))           // немає чорного короля
        assertIs<ChessResult.Err>(rules.parseFen("k7/8/8/8/8/8/8/KK6 w - - 0 1"))         // два білі королі
        assertIs<ChessResult.Err>(rules.parseFen("kk6/8/8/8/8/8/8/K7 w - - 0 1"))         // два чорні королі
    }

    @Test
    fun sideNotToMoveMustNotBeInCheck() {
        // Білий король на e1 під боєм ладді e8, але хід чорних: позиція неможлива.
        assertIs<ChessResult.Err>(rules.parseFen("4r1k1/8/8/8/8/8/8/4K3 b - - 0 1"))
        // Та сама розстановка з ходом білих (шах білим) припустима.
        assertIs<ChessResult.Ok<*>>(rules.parseFen("4r1k1/8/8/8/8/8/8/4K3 w - - 0 1"))
    }

    // ------------------------------------------------------------ рокіровка

    @Test
    fun castlingBothSidesWhenFree() {
        val p = pos("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val moves = ucis(p)
        assertTrue("e1g1" in moves)
        assertTrue("e1c1" in moves)
        val after = play(p, "e1g1")
        assertEquals("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", after.fen.value)
        val afterQueen = play(p, "e1c1")
        assertEquals("r3k2r/8/8/8/8/8/8/2KR3R b kq - 1 1", afterQueen.fen.value)
        val black = play(pos("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1"), "e8c8")
        assertEquals("2kr3r/8/8/8/8/8/8/R3K2R w KQ - 1 2", black.fen.value)
    }

    @Test
    fun castlingForbiddenWhileInCheck() {
        val moves = ucis(pos("4r1k1/8/8/8/8/8/8/R3K2R w KQ - 0 1"))
        assertFalse("e1g1" in moves)
        assertFalse("e1c1" in moves)
    }

    @Test
    fun castlingForbiddenThroughAttackedSquare() {
        // Ладья f8 б'є f1: короткої рокіровки немає, довга можлива.
        val moves = ucis(pos("5rk1/8/8/8/8/8/8/R3K2R w KQ - 0 1"))
        assertFalse("e1g1" in moves)
        assertTrue("e1c1" in moves)
        // Ладья d8 б'є d1: довгої немає, коротка можлива.
        val moves2 = ucis(pos("3r2k1/8/8/8/8/8/8/R3K2R w KQ - 0 1"))
        assertFalse("e1c1" in moves2)
        assertTrue("e1g1" in moves2)
    }

    @Test
    fun castlingForbiddenOntoAttackedSquare() {
        // Ладья g8 б'є g1.
        val moves = ucis(pos("1k4r1/8/8/8/8/8/8/R3K2R w KQ - 0 1"))
        assertFalse("e1g1" in moves)
        assertTrue("e1c1" in moves)
    }

    @Test
    fun longCastlingAllowedWhenOnlyRookPathSquareB1IsAttacked() {
        val moves = ucis(pos("1r4k1/8/8/8/8/8/8/R3K2R w KQ - 0 1"))
        assertTrue("e1c1" in moves)
    }

    @Test
    fun castlingForbiddenWhenBlocked() {
        val moves = ucis(pos("r3k2r/8/8/8/8/8/8/RN2K1NR w KQkq - 0 1"))
        assertFalse("e1g1" in moves)
        assertFalse("e1c1" in moves)
    }

    @Test
    fun castlingRightsLostAfterMovesAndCaptures() {
        val p = pos("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertEquals("kq", castlingOf(play(p, "e1e2")))
        assertEquals("Qkq", castlingOf(play(p, "h1h2")))
        assertEquals("Kkq", castlingOf(play(p, "a1a2")))
        // Взяття ладді на h8 забирає право чорних на коротку рокіровку, а хід ладді h1 право білих.
        assertEquals("Qq", castlingOf(play(p, "h1h8")))
    }

    private fun castlingOf(p: Position): String = p.fen.value.split(' ')[2]

    // ------------------------------------------------------------ взяття на проході

    @Test
    fun enPassantCaptureRemovesPawn() {
        val p = pos("rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 3")
        assertTrue("d4e3" in ucis(p))
        val after = play(p, "d4e3")
        assertNull(after.pieceAt(sq("e4")))
        assertEquals(PieceType.PAWN, after.pieceAt(sq("e3"))?.type)
        assertEquals("rnbqkbnr/ppp1pppp/8/8/8/4p3/PPPP1PPP/RNBQKBNR w KQkq - 0 4", after.fen.value)
    }

    @Test
    fun enPassantIllegalWhenItExposesKingAlongRank() {
        // Горизонтальна зв'язка: після exd6 обидва пішаки зникають з 5-ї горизонталі й ладья h5 б'є короля a5.
        val p = pos("8/8/8/K2pP2r/8/8/8/7k w - d6 0 1")
        assertFalse("e5d6" in ucis(p))
        assertEquals("8/8/8/K2pP2r/8/8/8/7k w - -", p.positionKey)
        assertEquals("8/8/8/K2pP2r/8/8/8/7k w - d6 0 1", p.fen.value)
    }

    @Test
    fun enPassantIllegalWhenPawnIsPinnedOnFile() {
        val p = pos("4r1k1/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        assertFalse("e5d6" in ucis(p))
        assertTrue(p.positionKey.endsWith(" -"))
    }

    @Test
    fun enPassantLegalIsInPositionKey() {
        val p = pos("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        assertTrue("e5d6" in ucis(p))
        assertEquals("4k3/8/8/3pP3/8/8/8/4K3 w - d6", p.positionKey)
    }

    @Test
    fun enPassantCanCaptureCheckingPawn() {
        // Білий пішак d4 щойно пішов d2-d4 і дає шах королю c5; e4xd3 їх знімає.
        val p = pos("8/8/8/2k5/3Pp3/8/8/4K3 b - d3 0 1")
        assertTrue(rules.isInCheck(p))
        assertTrue("e4d3" in ucis(p))
        assertTrue(p.positionKey.endsWith(" d3"))
    }

    @Test
    fun enPassantAppearsInKeyAfterRealGame() {
        // 1.e4 d5 2.e5 f5: біла e5 може взяти f6.
        val p = play(rules.startPosition(), "e2e4", "d7d5", "e4e5", "f7f5")
        assertTrue(p.positionKey.endsWith(" f6"))
        assertTrue("e5f6" in ucis(p))
    }

    // ------------------------------------------------------------ positionKey, equals

    @Test
    fun equalityFollowsPositionKeyNotCounters() {
        val a = play(rules.startPosition(), "g1f3", "g8f6", "f3g1", "f6g8")
        val b = rules.startPosition()
        assertEquals(b, a)
        assertEquals(b.hashCode(), a.hashCode())
        assertEquals(b.positionKey, a.positionKey)
        assertTrue(a.fen != b.fen) // лічильники різні
    }

    @Test
    fun positionKeyDiffersByCastlingRightsAndSide() {
        val a = pos("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertTrue(a != pos("r3k2r/8/8/8/8/8/8/R3K2R w KQk - 0 1"))
        assertTrue(a != pos("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1"))
    }

    @Test
    fun transpositionsGiveEqualPositions() {
        val a = play(rules.startPosition(), "e2e4", "e7e5", "g1f3")
        val b = play(rules.startPosition(), "g1f3", "e7e5", "e2e4")
        assertEquals(a, b)
    }

    // ------------------------------------------------------------ перетворення

    @Test
    fun promotionMovesAndNeedsPromotion() {
        val p = pos("8/P6k/8/8/8/8/8/K7 w - - 0 1")
        val promos = rules.legalMovesFrom(p, sq("a7"))
        assertEquals(
            setOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT),
            promos.mapNotNull { it.promotion }.toSet(),
        )
        assertEquals(4, promos.size)
        assertTrue(rules.needsPromotion(p, sq("a7"), sq("a8")))
        assertFalse(rules.needsPromotion(p, sq("a1"), sq("a2")))
        assertFalse(rules.needsPromotion(rules.startPosition(), sq("e2"), sq("e4")))
        val after = play(p, "a7a8n")
        assertEquals(PieceType.KNIGHT, after.pieceAt(sq("a8"))?.type)
        assertEquals(Color.WHITE, after.pieceAt(sq("a8"))?.color)
        assertEquals("a8=N", rules.toSan(p, Move(sq("a7"), sq("a8"), PieceType.KNIGHT)))
        assertEquals("a8=Q", rules.toSan(p, Move(sq("a7"), sq("a8"), PieceType.QUEEN)))
    }

    @Test
    fun promotionWithCaptureAndCheck() {
        val p = pos("1n5k/P7/8/8/8/8/8/K7 w - - 0 1")
        assertEquals("axb8=Q+", rules.toSan(p, Move(sq("a7"), sq("b8"), PieceType.QUEEN)))
        assertIs<ChessResult.Err>(rules.parseUci(p, "a7b8")) // без фігури нелегально
    }

    @Test
    fun blackPromotes() {
        val p = pos("7k/8/8/8/8/8/p7/7K b - - 0 1")
        assertTrue(rules.needsPromotion(p, sq("a2"), sq("a1")))
        val after = play(p, "a2a1q")
        assertEquals(PieceType.QUEEN, after.pieceAt(sq("a1"))?.type)
        assertEquals(Color.BLACK, after.pieceAt(sq("a1"))?.color)
        assertNotNull(after.pieceAt(sq("h1")))
    }

    // ------------------------------------------------------------ мат, пат, шах

    @Test
    fun stalemateIsRecognised() {
        val p = pos("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertTrue(rules.isStalemate(p))
        assertFalse(rules.isCheckmate(p))
        assertFalse(rules.isInCheck(p))
        assertTrue(rules.legalMoves(p).isEmpty())
    }

    @Test
    fun checkmateIsRecognised() {
        val p = pos("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1")
        assertTrue(rules.isCheckmate(p))
        assertFalse(rules.isStalemate(p))
        assertTrue(rules.isInCheck(p))
    }

    @Test
    fun checkWithMovesIsNeitherMateNorStalemate() {
        val p = pos("4k3/8/8/8/8/8/8/4RK2 b - - 0 1")
        assertTrue(rules.isInCheck(p))
        assertFalse(rules.isCheckmate(p))
        assertFalse(rules.isStalemate(p))
    }

    @Test
    fun foolsMateGivesHashInSan() {
        var p = rules.startPosition()
        for (u in listOf("f2f3", "e7e5", "g2g4")) p = play(p, u)
        val mate = Move(sq("d8"), sq("h4"))
        assertEquals("Qh4#", rules.toSan(p, mate))
        assertTrue(rules.isCheckmate(play(p, "d8h4")))
    }

    // ------------------------------------------------------------ недостатній матеріал

    @Test
    fun insufficientMaterialPerContract() {
        fun ins(fen: String) = rules.isInsufficientMaterial(pos(fen))
        assertTrue("K vs K", ins("8/8/8/4k3/8/8/8/4K3 w - - 0 1"))
        assertTrue("K+B vs K", ins("8/8/8/4k3/8/8/8/3BK3 w - - 0 1"))
        assertTrue("K+N vs K", ins("8/8/8/4k3/8/8/8/3NK3 w - - 0 1"))
        assertTrue("K vs K+N", ins("8/8/8/3nk3/8/8/8/4K3 w - - 0 1"))
        // Слони одного кольору (c1 і f4 обидва темні? c1: 2+0 => (2+0)%2=0, f4: (5+3)%2=0)
        assertTrue("same-colour bishops", ins("8/8/8/4k3/5b2/8/8/2B1K3 w - - 0 1"))
        assertFalse("different-colour bishops", ins("8/8/8/4k3/8/8/8/2BBK3 w - - 0 1"))
        assertFalse("K+N vs K+N is NOT a draw", ins("8/8/8/3nk3/8/8/8/3NK3 w - - 0 1"))
        assertFalse("K+2N vs K", ins("8/8/8/4k3/8/8/8/2NNK3 w - - 0 1"))
        assertFalse("K+B vs K+N", ins("8/8/8/3nk3/8/8/8/3BK3 w - - 0 1"))
        assertFalse("pawn", ins("8/8/8/4k3/8/8/4P3/4K3 w - - 0 1"))
        assertFalse("rook", ins("8/8/8/4k3/8/8/8/3RK3 w - - 0 1"))
        assertFalse("queen", ins("8/8/8/4k3/8/8/8/3QK3 w - - 0 1"))
    }

    // ------------------------------------------------------------ нелегальні ходи, UCI

    @Test
    fun applyIllegalMoveReturnsErrWithoutThrowing() {
        val start = rules.startPosition()
        val r = rules.apply(start, Move(sq("e2"), sq("e5")))
        assertEquals(ChessResult.Err(ChessError.IllegalMove("e2e5")), r)
        // порожнє поле, чужа фігура, король під шах
        assertIs<ChessResult.Err>(rules.apply(start, Move(sq("e4"), sq("e5"))))
        assertIs<ChessResult.Err>(rules.apply(start, Move(sq("e7"), sq("e5"))))
        val inCheck = pos("4r1k1/8/8/8/8/8/8/4K3 w - - 0 1")
        assertIs<ChessResult.Err>(rules.apply(inCheck, Move(sq("e1"), sq("e2"))))
        assertIs<ChessResult.Ok<*>>(rules.apply(inCheck, Move(sq("e1"), sq("d1"))))
    }

    @Test
    fun pinnedPieceCannotMove() {
        val p = pos("4r1k1/8/8/8/8/8/4N3/4K3 w - - 0 1")
        assertTrue(rules.legalMovesFrom(p, sq("e2")).isEmpty())
    }

    @Test
    fun toSanThrowsOnIllegalMove() {
        assertFailsWith<IllegalArgumentException> {
            rules.toSan(rules.startPosition(), Move(sq("e2"), sq("e5")))
        }
    }

    @Test
    fun parseUciRoundTripAndErrors() {
        val start = rules.startPosition()
        val ok = rules.parseUci(start, "e2e4")
        assertEquals(ChessResult.Ok(Move(sq("e2"), sq("e4"))), ok)
        assertEquals("e2e4", rules.toUci(Move(sq("e2"), sq("e4"))).value)
        assertEquals("e7e8q", rules.toUci(Move(sq("e7"), sq("e8"), PieceType.QUEEN)).value)
        for (bad in listOf("", "e2", "e2e5", "e2e4x", "z2e4", "e2e4q", "e1g1")) {
            assertEquals(ChessResult.Err(ChessError.IllegalMove(bad)), rules.parseUci(start, bad))
        }
    }

    // ------------------------------------------------------------ SAN

    @Test
    fun sanBasics() {
        val start = rules.startPosition()
        assertEquals("e4", rules.toSan(start, Move(sq("e2"), sq("e4"))))
        assertEquals("Nf3", rules.toSan(start, Move(sq("g1"), sq("f3"))))
        val p = play(start, "e2e4", "d7d5")
        assertEquals("exd5", rules.toSan(p, Move(sq("e4"), sq("d5"))))
    }

    @Test
    fun sanCastling() {
        val p = pos("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertEquals("O-O", rules.toSan(p, Move(sq("e1"), sq("g1"))))
        assertEquals("O-O-O", rules.toSan(p, Move(sq("e1"), sq("c1"))))
        // Рокіровка з шахом: ладья f1 б'є короля f8.
        val withCheck = pos("5k2/8/8/8/8/8/8/4K2R w K - 0 1")
        assertEquals("O-O+", rules.toSan(withCheck, Move(sq("e1"), sq("g1"))))

        assertEquals(ChessResult.Ok(Move(sq("e1"), sq("g1"))), rules.parseSan(p, "O-O"))
        assertEquals(ChessResult.Ok(Move(sq("e1"), sq("c1"))), rules.parseSan(p, "O-O-O"))
        assertEquals(ChessResult.Ok(Move(sq("e1"), sq("g1"))), rules.parseSan(p, "0-0"))
    }

    @Test
    fun sanDisambiguation() {
        // Два коні можуть піти на d2: за файлом.
        val knights = pos("4k3/8/8/8/8/8/8/1N2KN2 w - - 0 1")
        assertEquals("Nbd2", rules.toSan(knights, Move(sq("b1"), sq("d2"))))
        assertEquals("Nfd2", rules.toSan(knights, Move(sq("f1"), sq("d2"))))
        // Дві ладді на одному файлі: за горизонталлю.
        val rooks = pos("4k3/8/R7/8/8/8/R7/4K3 w - - 0 1")
        assertEquals("R6a4", rules.toSan(rooks, Move(sq("a6"), sq("a4"))))
        assertEquals("R2a4", rules.toSan(rooks, Move(sq("a2"), sq("a4"))))
        // Три ферзі a1, a5, e1 б'ють c3: у a1 суперники і на файлі, і на горизонталі (повна позначка),
        // у a5 суперник лише на файлі (горизонталь), у e1 лише на горизонталі (файл).
        val three = pos("8/7k/8/Q7/8/8/7K/Q3Q3 w - - 0 1")
        assertEquals("Qa1c3", rules.toSan(three, Move(sq("a1"), sq("c3"))))
        assertEquals("Q5c3", rules.toSan(three, Move(sq("a5"), sq("c3"))))
        assertEquals("Qec3", rules.toSan(three, Move(sq("e1"), sq("c3"))))
    }

    @Test
    fun parseSanAcceptsDecoratedAndRejectsAmbiguous() {
        val start = rules.startPosition()
        assertEquals(ChessResult.Ok(Move(sq("e2"), sq("e4"))), rules.parseSan(start, "e4"))
        assertEquals(ChessResult.Ok(Move(sq("e2"), sq("e4"))), rules.parseSan(start, "e4!?"))
        assertEquals(ChessResult.Ok(Move(sq("g1"), sq("f3"))), rules.parseSan(start, "Nf3"))
        assertIs<ChessResult.Err>(rules.parseSan(start, "Nd4"))
        assertIs<ChessResult.Err>(rules.parseSan(start, "e5"))
        assertIs<ChessResult.Err>(rules.parseSan(start, "xyz"))
        assertIs<ChessResult.Err>(rules.parseSan(start, ""))
        val knights = pos("4k3/8/8/8/8/8/8/1N2KN2 w - - 0 1")
        assertIs<ChessResult.Err>(rules.parseSan(knights, "Nd2")) // неоднозначно
        assertEquals(ChessResult.Ok(Move(sq("b1"), sq("d2"))), rules.parseSan(knights, "Nbd2"))
        val promo = pos("8/P6k/8/8/8/8/8/K7 w - - 0 1")
        assertEquals(ChessResult.Ok(Move(sq("a7"), sq("a8"), PieceType.QUEEN)), rules.parseSan(promo, "a8=Q"))
        assertIs<ChessResult.Err>(rules.parseSan(promo, "a8")) // без фігури
    }

    @Test
    fun sanRoundTripOverAllLegalMovesOfKiwipete() {
        val p = pos(PerftSupport.KIWIPETE)
        for (move in rules.legalMoves(p)) {
            val san = rules.toSan(p, move)
            assertEquals("SAN $san", ChessResult.Ok(move), rules.parseSan(p, san))
        }
    }
}
