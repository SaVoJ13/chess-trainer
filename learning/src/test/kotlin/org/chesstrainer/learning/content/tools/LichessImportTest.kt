// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content.tools

import java.nio.file.Files
import java.nio.file.Path
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.learning.content.ContentTestSupport
import org.chesstrainer.learning.content.ContentValidator
import org.chesstrainer.learning.content.PuzzleProblem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LichessImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val rules = ContentTestSupport.rules

    /** Рядки взято зі сторінки database.lichess.org (розділ Puzzles, Sample) + штучні негативні. */
    private val header = "PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags,DailyDate"
    private val sampleRows = listOf(
        "00sHx,q3k1nr/1pp1nQpp/3p4/1P2p3/4P3/B1PP1b2/B5PP/5K2 b k - 0 17,e8d7 a2e6 d7d8 f7f8,1760,80,83,72,mate mateIn2 middlegame short,https://lichess.org/yyznGmXs/black#34,Italian_Game Italian_Game_Classical_Variation,",
        "00sJ9,r3r1k1/p4ppp/2p2n2/1p6/3P1qb1/2NQR3/PPB2PP1/R1B3K1 w - - 5 18,e3g3 e8e1 g1h2 e1c1 a1c1 f4h6 h2g1 h6c1,2671,105,87,325,advantage attraction fork middlegame sacrifice veryLong,https://lichess.org/gyFeQsOE#35,French_Defense French_Defense_Exchange_Variation,1607774862751",
        "00sJb,Q1b2r1k/p2np2p/5bp1/q7/5P2/4B3/PPP3PP/2KR1B1R w - - 1 17,d1d7 a5e1 d7d1 e1e3 c1b1 e3b6,2235,76,97,64,advantage fork long,https://lichess.org/kiuvTFoE#33,Sicilian_Defense Sicilian_Defense_Dragon_Variation,",
        "00sO1,1k1r4/pp3pp1/2p1p3/4b3/P3n1P1/8/KPP2PN1/3rBR1R b - - 2 31,b8c7 e1a5 b7b6 f1d1,998,85,94,293,advantage discoveredAttack master middlegame short,https://lichess.org/vsfFkG0s/black#62,,",
    )

    private fun csv(vararg extra: String): Path {
        val f = tmp.newFile().toPath()
        Files.write(f, (listOf(header) + sampleRows + extra).joinToString("\n", postfix = "\n").toByteArray())
        return f
    }

    private fun options(input: Path, out: Path) = BuildOptions(
        input = input,
        outDir = out,
        packId = "t",
        total = 50,
        minPopularity = 0,
        maxRatingDeviation = 200,
        minPlays = 0,
    )

    @Test
    fun firstMoveBelongsToTheOpponentAndFenIsCanonical() {
        val row = LichessRow.parse(sampleRows[0])!!
        val c = LichessConverter(rules).convert(row, listOf("mate_in_2", "middlegame")) as Conversion.Ok
        // Після e8d7 ходять білі; solution = решта ходів.
        assertEquals(listOf("a2e6", "d7d8", "f7f8"), c.puzzle.solution)
        assertEquals("li-00sHx", c.puzzle.id)
        val fen = c.puzzle.fen
        assertEquals(6, fen.split(' ').size)
        assertEquals("w", fen.split(' ')[1])
        assertEquals((rules.parseFen(fen) as ChessResult.Ok).value.fen.value, fen)
    }

    @Test
    fun shortFenIsNormalizedToSixFields() {
        val row = LichessRow.parse("x1,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -,e2e4 e7e5 g1f3 b8c6,1500,50,90,1000,fork,u,,")!!
        val c = LichessConverter(rules).convert(row, listOf("fork")) as Conversion.Ok
        assertEquals(6, c.puzzle.fen.split(' ').size)
        assertEquals(listOf("e7e5", "g1f3", "b8c6"), c.puzzle.solution)
    }

    @Test
    fun conversionRejectsIllegalOpponentMoveAndIllegalSolution() {
        val bad1 = LichessRow.parse("b1,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1,e2e5 e7e5 d1h5 b8c6,1500,50,90,1000,fork,u,,")!!
        val r1 = LichessConverter(rules).convert(bad1, listOf("fork")) as Conversion.Rejected
        assertEquals(PuzzleProblem.ILLEGAL_MOVE, r1.issues[0].problem)
        val bad2 = LichessRow.parse("b2,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1,e2e4 e7e5 d1d5 b8c6,1500,50,90,1000,fork,u,,")!!
        val r2 = LichessConverter(rules).convert(bad2, listOf("fork")) as Conversion.Rejected
        assertEquals(PuzzleProblem.ILLEGAL_MOVE, r2.issues[0].problem)
        val bad3 = LichessRow.parse("b3,not-a-fen,e2e4 e7e5 d1h5 b8c6,1500,50,90,1000,fork,u,,")!!
        val r3 = LichessConverter(rules).convert(bad3, listOf("fork")) as Conversion.Rejected
        assertEquals(PuzzleProblem.INVALID_FEN, r3.issues[0].problem)
    }

    @Test
    fun oddMovesCountFromSourceIsRejected() {
        val row = LichessRow.parse("b4,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1,e2e4 e7e5 g1f3 b8c6,1500,50,90,1000,fork,u,,")!!
        // 4 ходи = розв'язок із 3 пів-ходів: придатний; 3 ходи = розв'язок із 2: ні.
        assertTrue(LichessConverter(rules).convert(row, listOf("fork")) is Conversion.Ok)
        val even = LichessRow.parse("b5,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1,e2e4 e7e5 g1f3,1500,50,90,1000,fork,u,,")!!
        assertTrue(LichessConverter(rules).convert(even, listOf("fork")) is Conversion.Rejected)
    }

    @Test
    fun builderProducesValidPackAndCountsRejects() {
        val unknownTag = "u1,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1,e2e4 e7e5 g1f3 b8c6,1500,50,90,1000,fork brandNewTag,u,,"
        val complex = "u2,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1,e2e4 e7e5 g1f3 b8c6,1500,50,90,1000,fork zugzwang,u,,"
        val illegal = "u3,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1,e2e5 e7e5 g1f3 b8c6,1500,50,90,1000,fork,u,,"
        val out = tmp.newFolder().toPath()
        val b = PuzzlePackBuilder(rules, options(csv(unknownTag, complex, illegal), out))
        val report = b.build()
        b.write(report)

        assertEquals(1L, report.prefilterRejects["unknown_theme_tag"])
        assertEquals(mapOf("brandNewTag" to 1L), report.unknownTags)
        assertEquals(1L, report.prefilterRejects["too_complex_theme"])
        assertEquals(mapOf("ILLEGAL_MOVE" to 1L), report.invalidByProblem)
        assertEquals(4, report.selected.size)
        assertTrue(report.selected.none { it.id == "li-u3" })
        assertEquals(report.selected.map { it.rating }, report.selected.map { it.rating }.sorted())

        val validation = ContentValidator(rules).validateDirectory(out)
        assertEquals(validation.findings.toString(), emptyList<Any>(), validation.findings)
        assertEquals(mapOf("t" to 4), validation.puzzleCounts)
    }

    @Test
    fun twoRunsGiveIdenticalFiles() {
        val input = csv()
        val out1 = tmp.newFolder().toPath()
        val out2 = tmp.newFolder().toPath()
        for (out in listOf(out1, out2)) {
            val b = PuzzlePackBuilder(rules, options(input, out))
            b.write(b.build())
        }
        for (name in listOf("manifest.json", "puzzles/t.json")) {
            assertArrayEquals(name, Files.readAllBytes(out1.resolve(name)), Files.readAllBytes(out2.resolve(name)))
        }
        assertNotNull(Files.size(out1.resolve("puzzles/t.json")))
    }

    @Test
    fun seedChangesSelectionButNotValidity() {
        val rows = (1..40).map {
            "s$it,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1,e2e4 e7e5 g1f3 b8c6,1500,50,90,1000,fork,u,,"
        }
        val input = tmp.newFile().toPath().also { Files.write(it, (listOf(header) + rows).joinToString("\n").toByteArray()) }
        fun ids(seed: String) = PuzzlePackBuilder(rules, options(input, tmp.newFolder().toPath()).copy(total = 5, seed = seed))
            .build().selected.map { it.id }.toSet()
        assertEquals(5, ids("a").size)
        assertEquals(ids("a"), ids("a"))
        assertTrue(ids("a") != ids("b"))
    }
}
