// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.pgn

import org.chesstrainer.core.game.DefaultChessGameFactory
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.core.testing.assertIs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PgnCodecTest {
    private val rules = ChesslibRules()
    private val codec = DefaultPgnCodec(rules)

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/pgn/$name")) { "missing resource $name" }
            .readBytes().toString(Charsets.UTF_8)

    private fun ok(result: ChessResult<PgnGame>): PgnGame = (result as ChessResult.Ok).value

    private fun parseAll(text: String) = codec.parse(text).toList()

    private fun sampleGames(): List<PgnGame> =
        parseAll(resource("sample.pgn")).filterIsInstance<ChessResult.Ok<PgnGame>>().map { it.value }

    // ------------------------------------------------------------ золоті файли

    @Test
    fun sampleFileParsesIntoExpectedResults() {
        val results = parseAll(resource("sample.pgn"))
        assertEquals(6, results.size)
        assertIs<ChessResult.Ok<*>>(results[0])
        assertIs<ChessResult.Ok<*>>(results[1])
        assertEquals(ChessResult.Err(ChessError.PgnIllegalMove(gameIndex = 2, ply = 3, san = "Ke3")), results[2])
        assertEquals(3, ((results[3] as ChessResult.Err).error as ChessError.PgnSyntax).gameIndex)
        assertEquals(4, ((results[4] as ChessResult.Err).error as ChessError.PgnSyntax).gameIndex)
        assertIs<ChessResult.Ok<*>>(results[5]) // помилки попередніх партій не зупинили розбір
    }

    @Test
    fun firstGameKeepsMainLineCommentsAndNags() {
        val game = sampleGames()[0]
        assertEquals(
            listOf("Event", "Site", "Date", "Round", "White", "Black", "Result", "ECO"),
            game.headers.keys.toList(),
        )
        assertEquals("Test Cup", game.headers["Event"])
        assertEquals(PgnResult.WHITE_WINS, game.result)
        assertNull(game.startFen)
        assertEquals(20, game.moves.size)
        assertEquals("King's pawn", game.moves[0].comment)
        assertEquals(emptyList<Int>(), game.moves[1].nags)
        // варіант 2. f4 пропущено: головна лінія 2. Nf3 Nc6
        assertEquals("Nf3", game.moves[2].san)
        assertEquals("Nc6", game.moves[3].san)
        assertEquals(listOf(1), game.moves[3].nags)
        assertEquals("Bc5", game.moves[5].san)
        assertEquals(listOf(5), game.moves[5].nags) // !?
        assertEquals("trailing line comment", game.moves[5].comment)
        assertEquals("O-O", game.moves[6].san) // 0-0 нормалізовано
        assertEquals(Uci("e1g1"), game.moves[6].uci)
    }

    @Test
    fun fenGameCarriesStartFenAndHidesSetUpTags() {
        val game = sampleGames()[1]
        assertEquals(Fen("4k3/8/8/8/8/8/4P3/4K3 b - - 0 40"), game.startFen)
        assertTrue("FEN" !in game.headers && "SetUp" !in game.headers)
        assertEquals(listOf("Kd7", "Kd2", "Kd6"), game.moves.map { it.san })
        assertEquals("?", game.headers["Site"]) // відсутній тег Seven Tag Roster отримує типове значення
        assertEquals("*", game.headers["Result"])
    }

    @Test
    fun unicodeAndEscapedQuotesSurvive() {
        val game = sampleGames()[2]
        assertEquals("Кубок", game.headers["Event"])
        assertEquals("Ірина", game.headers["White"])
        assertEquals("Оле\"ксій", game.headers["Black"])
        assertEquals(PgnResult.DRAW, game.result)
    }

    @Test
    fun writeMatchesGoldenFile() {
        val written = sampleGames().joinToString("") { codec.write(it) }
        if (System.getenv("UPDATE_GOLDEN") == "1") {
            val header = "% SPDX-License-Identifier: GPL-3.0-or-later\n" +
                "% SPDX-FileCopyrightText: 2026 Chess Trainer contributors\n"
            File("src/test/resources/pgn/sample.expected.pgn").writeText(header + written, Charsets.UTF_8)
            return
        }
        val expected = resource("sample.expected.pgn").lineSequence()
            .filterNot { it.startsWith("%") }.joinToString("\n")
        assertEquals(expected, written)
    }

    @Test
    fun writtenMovetextLinesAreAtMost80Characters() {
        val written = sampleGames().joinToString("") { codec.write(it) }
        for (line in written.lines()) {
            assertTrue("Line too long (${line.length}): $line", line.length <= 80)
        }
    }

    @Test
    fun writeStartsWithSevenTagRosterAndEndsWithBlankLine() {
        val text = codec.write(sampleGames()[0])
        assertEquals(
            listOf("Event", "Site", "Date", "Round", "White", "Black", "Result", "ECO"),
            text.lines().takeWhile { it.startsWith("[") }.map { it.substring(1, it.indexOf(' ')) },
        )
        assertTrue(text.endsWith("\n\n"))
    }

    // ------------------------------------------------------------ круговий тест

    @Test
    fun roundTripParseWriteParseIsStable() {
        val games = sampleGames()
        assertEquals(3, games.size)
        for (game in games) {
            val text = codec.write(game)
            val again = parseAll(text)
            assertEquals(1, again.size)
            assertEquals(game, ok(again[0]))
            assertEquals(text, codec.write(ok(again[0])))
        }
        val all = games.joinToString("") { codec.write(it) }
        assertEquals(games, parseAll(all).map { ok(it) })
    }

    @Test
    fun roundTripThroughGameFactory() {
        val factory = DefaultChessGameFactory(rules)
        val source = sampleGames()[0]
        val game = (factory.fromPgn(source) as ChessResult.Ok).value
        val pgn = factory.toPgn(game, source.headers, source.result)
        assertEquals(source.moves.map { it.san to it.uci }, pgn.moves.map { it.san to it.uci })
        assertEquals(source.headers, pgn.headers)
    }

    // ------------------------------------------------------------ окремі випадки

    @Test
    fun emptyAndBlankTextGiveNoGames() {
        assertTrue(parseAll("").isEmpty())
        assertTrue(parseAll("  \n\n% only an escape line\n").isEmpty())
    }

    @Test
    fun movetextWithoutTagsIsAGame() {
        val game = ok(parseAll("1. e4 e5 2. Nf3 *")[0])
        assertEquals(3, game.moves.size)
        assertEquals("?", game.headers["Event"])
    }

    @Test
    fun resultFromHeaderWhenMovetextHasNoTerminator() {
        val game = ok(parseAll("[Result \"0-1\"]\n\n1. e4 e5\n")[0])
        assertEquals(PgnResult.BLACK_WINS, game.result)
        assertEquals("0-1", game.headers["Result"])
    }

    @Test
    fun missingTerminatorBeforeNextGameDoesNotMergeGames() {
        val games = parseAll("[Event \"A\"]\n\n1. e4 e5\n\n[Event \"B\"]\n\n1. d4 *\n")
        assertEquals(2, games.size)
        assertEquals(2, ok(games[0]).moves.size)
        assertEquals("B", ok(games[1]).headers["Event"])
    }

    @Test
    fun nestedVariationsAndTheirCommentsAreSkipped() {
        val game = ok(parseAll("1. e4 (1. d4 (1. c4 {x}) d5 {y}) e5 {main} *")[0])
        assertEquals(listOf("e4", "e5"), game.moves.map { it.san })
        assertNull(game.moves[0].comment)
        assertEquals("main", game.moves[1].comment)
    }

    @Test
    fun commentWhitespaceIsCollapsed() {
        val game = ok(parseAll("1. e4 {a   b\n  c} *")[0])
        assertEquals("a b c", game.moves[0].comment)
    }

    @Test
    fun suffixAnnotationsBecomeNags() {
        val game = ok(parseAll("1. e4! e5? 2. Nf3!! Nc6?? 3. Bb5?! a6!? *")[0])
        assertEquals(
            listOf(listOf(1), listOf(2), listOf(3), listOf(4), listOf(6), listOf(5)),
            game.moves.map { it.nags },
        )
    }

    @Test
    fun brokenGamesAreReportedAndSkipped() {
        val results = parseAll("1. e4 e5 2. Qh5 Nc6 3. Zz9 *\n\n1. d4 d5 *\n")
        assertEquals(2, results.size)
        assertEquals(ChessResult.Err(ChessError.PgnIllegalMove(0, 5, "Zz9")), results[0])
        assertEquals(2, ok(results[1]).moves.size)
    }

    @Test
    fun unterminatedCommentIsASyntaxErrorNotACrash() {
        val results = parseAll("1. e4 {never closed e5 *")
        assertEquals(1, results.size)
        assertIs<ChessResult.Err>(results[0])
    }

    @Test
    fun invalidFenTagIsASyntaxError() {
        val results = parseAll("[FEN \"nonsense\"]\n\n1. e4 *\n\n1. d4 *\n")
        assertEquals(2, results.size)
        assertIs<ChessError.PgnSyntax>((results[0] as ChessResult.Err).error)
        assertEquals(1, ok(results[1]).moves.size)
    }

    @Test
    fun parseIsLazy() {
        var consumed = 0
        val first = codec.parse(resource("sample.pgn")).onEach { consumed++ }.first()
        assertIs<ChessResult.Ok<*>>(first)
        assertEquals(1, consumed)
    }

    @Test
    fun writeUsesBlackMoveNumberAfterComments() {
        val game = PgnGame(
            emptyMap(), null,
            listOf(
                PgnMove("e4", Uci("e2e4"), comment = "good"),
                PgnMove("e5", Uci("e7e5")),
                PgnMove("Nf3", Uci("g1f3")),
            ),
            PgnResult.UNKNOWN,
        )
        assertEquals("1. e4 {good} 1... e5 2. Nf3 *", codec.write(game).substringAfter("\n\n").trim())
    }

    @Test
    fun writeEscapesQuotesAndBackslashesInTags() {
        val game = PgnGame(mapOf("White" to "A \"B\" \\C"), null, emptyList(), PgnResult.UNKNOWN)
        val text = codec.write(game)
        assertTrue(text.contains("[White \"A \\\"B\\\" \\\\C\"]"))
        assertEquals("A \"B\" \\C", ok(parseAll(text)[0]).headers["White"])
    }
}
