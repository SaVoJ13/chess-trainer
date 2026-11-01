// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

import java.nio.file.Paths
import org.chesstrainer.learning.content.ContentTestSupport.START_FEN
import org.chesstrainer.learning.content.ContentTestSupport.valid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Перевірка готових файлів: позитивний випадок і негативні приклади CONTRACTS 6.1. */
class ContentValidatorTest {
    private fun problems(p: PuzzleRecord) = ContentTestSupport.validate(listOf(p)).map { it.message }

    private fun assertRejected(p: PuzzleRecord, problem: PuzzleProblem) {
        val messages = problems(p)
        assertTrue("expected $problem in $messages", messages.any { it.startsWith(problem.name) })
    }

    @Test
    fun validPackPasses() {
        assertEquals(emptyList<Finding>(), ContentTestSupport.validate(listOf(valid("a"), valid("b"))))
    }

    @Test
    fun invalidFenIsRejected() = assertRejected(valid().copy(fen = "not a fen"), PuzzleProblem.INVALID_FEN)

    @Test
    fun fenWithoutKingIsRejected() =
        assertRejected(valid().copy(fen = "8/8/8/8/8/8/8/8 w - - 0 1"), PuzzleProblem.INVALID_FEN)

    @Test
    fun fourFieldFenIsNotCanonical() =
        assertRejected(valid().copy(fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"), PuzzleProblem.NON_CANONICAL_FEN)

    @Test
    fun illegalMoveIsRejected() = assertRejected(valid().copy(solution = listOf("e2e5")), PuzzleProblem.ILLEGAL_MOVE)

    @Test
    fun illegalMoveLaterInSolutionIsRejected() =
        assertRejected(valid().copy(solution = listOf("e2e4", "e7e5", "e1e3")), PuzzleProblem.ILLEGAL_MOVE)

    @Test
    fun evenSolutionLengthIsRejected() =
        assertRejected(valid().copy(solution = listOf("e2e4", "e7e5")), PuzzleProblem.EVEN_SOLUTION_LENGTH)

    @Test
    fun emptySolutionIsRejected() = assertRejected(valid().copy(solution = emptyList()), PuzzleProblem.EMPTY_SOLUTION)

    @Test
    fun unknownThemeIsRejected() = assertRejected(valid().copy(themes = listOf("fork", "zigzag")), PuzzleProblem.UNKNOWN_THEME)

    @Test
    fun lichessCamelCaseTagIsNotAThemeId() =
        assertRejected(valid().copy(themes = listOf("backRankMate")), PuzzleProblem.UNKNOWN_THEME)

    @Test
    fun phaseOnlyThemesAreRejected() = assertRejected(valid().copy(themes = listOf("endgame")), PuzzleProblem.NO_TACTIC_THEME)

    @Test
    fun noThemesIsRejected() = assertRejected(valid().copy(themes = emptyList()), PuzzleProblem.NO_THEMES)

    @Test
    fun badRatingIsRejected() = assertRejected(valid().copy(rating = 0), PuzzleProblem.BAD_RATING)

    @Test
    fun mateThemeMustMatchSolution() =
        assertRejected(valid().copy(themes = listOf("mate_in_1")), PuzzleProblem.MATE_THEME_MISMATCH)

    @Test
    fun mateInOneAcceptsRealMate() {
        // Мат дурня: 1.f3 e5 2.g4 Qh4#; задача починається після 2.g4.
        val fen = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2"
        val p = PuzzleRecord("t-mate", fen, listOf("d8h4"), listOf("mate_in_1"), 600)
        assertEquals(emptyList<Finding>(), ContentTestSupport.validate(listOf(p)))
    }

    @Test
    fun badPuzzleIdIsRejected() = assertRejected(valid("bad id!"), PuzzleProblem.BAD_ID)

    @Test
    fun duplicatePuzzleIdIsRejected() {
        val messages = ContentTestSupport.validate(listOf(valid("a"), valid("a"))).map { it.message }
        assertTrue(messages.toString(), messages.any { it.startsWith("duplicate puzzle id") })
    }

    @Test
    fun unknownMajorOfPackIsRejected() {
        val findings = ContentTestSupport.validate(listOf(valid()), packVersion = "2.0")
        assertTrue(findings.toString(), findings.any { it.message.contains("unsupported schema MAJOR 2") })
    }

    @Test
    fun unknownMajorOfManifestIsRejectedAndDoesNotCrash() {
        val findings = ContentTestSupport.validate(listOf(valid()), manifestVersion = "2.0")
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("unsupported schema MAJOR 2"))
    }

    @Test
    fun newerMinorIsAccepted() {
        assertEquals(emptyList<Finding>(), ContentTestSupport.validate(listOf(valid()), packVersion = "1.9", manifestVersion = "1.9"))
    }

    @Test
    fun malformedVersionIsRejected() {
        val findings = ContentTestSupport.validate(listOf(valid()), packVersion = "one")
        assertTrue(findings.toString(), findings.any { it.message.contains("not MAJOR.MINOR") })
    }

    @Test
    fun brokenJsonDoesNotCrash() {
        val manifest = ContentTestSupport.manifestFor(ContentTestSupport.entry())
        val findings = ContentValidator(ContentTestSupport.rules).validate(manifest) { "{ not json" }.findings
        assertTrue(findings.toString(), findings.any { it.where == "puzzles/p.json" })
    }

    @Test
    fun itemCountMismatchIsRejected() {
        val manifest = ContentTestSupport.manifestFor(ContentTestSupport.entry(count = 5))
        val text = ContentTestSupport.pack(listOf(valid()))
        val findings = ContentValidator(ContentTestSupport.rules).validate(manifest) { text }.findings
        assertTrue(findings.toString(), findings.any { it.message.contains("itemCount 5") })
    }

    @Test
    fun missingPackFileIsRejected() {
        val manifest = ContentTestSupport.manifestFor(ContentTestSupport.entry())
        val findings = ContentValidator(ContentTestSupport.rules).validate(manifest) { null }.findings
        assertTrue(findings.toString(), findings.any { it.message.contains("not found") })
    }

    @Test
    fun sourceIdMustBeInRegistryAndVerifiedWhenRequired() {
        val registry = SourceRegistry.parse(
            "| CT-001 | x | v | u | l | w | Кандидат | — | n |\n| CT-009 | x | v | u | l | w | **Перевірено** | a | n |",
        )
        val notVerified = ContentTestSupport.validate(listOf(valid()), registry = registry, requireVerified = true)
        assertTrue(notVerified.toString(), notVerified.any { it.message.contains("Кандидат") })
        assertEquals(emptyList<Finding>(), ContentTestSupport.validate(listOf(valid()), registry = registry))
    }

    @Test
    fun committedContentIsValid() {
        val common = Paths.get("..", "content", "common")
        val registryFile = Paths.get("..", "docs", "THIRD_PARTY.md")
        val registry = SourceRegistry.parse(String(java.nio.file.Files.readAllBytes(registryFile), Charsets.UTF_8))
        val report = ContentValidator(ContentTestSupport.rules, registry, requireVerifiedSources = true).validateDirectory(common)
        assertEquals(report.findings.take(10).toString(), emptyList<Finding>(), report.findings)
        assertTrue("manifest lists no packs", report.puzzleCounts.isNotEmpty())
    }

    @Test
    fun startFenConstantIsCanonical() {
        val r = ContentTestSupport.rules.parseFen(START_FEN) as org.chesstrainer.core.model.ChessResult.Ok
        assertEquals(START_FEN, r.value.fen.value)
    }
}
