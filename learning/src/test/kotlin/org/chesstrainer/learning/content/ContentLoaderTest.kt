// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.chesstrainer.learning.content.ContentTestSupport.entry
import org.chesstrainer.learning.content.ContentTestSupport.manifestFor
import org.chesstrainer.learning.content.ContentTestSupport.pack
import org.chesstrainer.learning.content.ContentTestSupport.valid
import org.chesstrainer.learning.puzzle.PuzzleFilter
import org.chesstrainer.learning.puzzle.PuzzleId
import org.chesstrainer.learning.puzzle.PuzzleMode
import org.chesstrainer.learning.puzzle.PuzzleRequest
import org.chesstrainer.learning.puzzle.PuzzleState
import org.chesstrainer.learning.puzzle.PuzzleStep
import org.chesstrainer.learning.puzzle.PuzzleTestSupport
import org.chesstrainer.learning.puzzle.DefaultPuzzleService
import org.chesstrainer.learning.puzzle.InMemoryProgress
import org.chesstrainer.learning.puzzle.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentLoaderTest {
    private val rules = ContentTestSupport.rules

    private fun loader(v: LoadValidation = LoadValidation.FULL) = ContentLoader(rules, v)

    private fun loaded(r: ContentLoadResult): LoadedContent = (r as ContentLoadResult.Loaded).content

    private fun load(
        puzzles: List<PuzzleRecord>,
        v: LoadValidation = LoadValidation.FULL,
        entryCount: Int = puzzles.size,
        packVersion: String = "1.0",
    ): ContentLoadResult {
        val manifest = manifestFor(entry(count = entryCount))
        val text = pack(puzzles, version = packVersion)
        return loader(v).load(manifest) { if (it == "p") text else null }
    }

    @Test
    fun mapsRecordToPuzzleAndManifestToContract() {
        val record = valid("a-1").copy(themes = listOf("fork", "middlegame"), rating = 1234)
        val c = loaded(load(listOf(record)))
        assertEquals(emptyList<ContentProblem>(), c.problems)
        assertEquals(SchemaVersion(1, 0), c.manifest.schemaVersion)
        assertEquals(
            listOf(ContentPack("p", ContentKind.PUZZLES, emptySet(), 1, setOf("CT-001"))),
            c.manifest.packs,
        )
        val p = c.puzzles.single()
        assertEquals(PuzzleId("a-1"), p.id)
        assertEquals(record.fen, p.startFen.value)
        assertEquals(listOf("e2e4"), p.solution.map { it.value })
        assertEquals(setOf(ThemeId("fork"), ThemeId("middlegame")), p.themes)
        assertEquals(1234, p.rating)
        assertEquals("CT-001", p.sourceId)
    }

    @Test
    fun higherMinorIsAcceptedUnknownMajorOfManifestFailsGracefully() {
        val ok = loader().load(manifestFor(version = "1.7")) { null }
        assertEquals(SchemaVersion(1, 7), loaded(ok).manifest.schemaVersion)

        val bad = loader().load(manifestFor(version = "2.0")) { null }
        val problem = (bad as ContentLoadResult.Failed).problem
        assertEquals(ContentProblem.UnsupportedSchema("manifest.json", "2.0"), problem)
    }

    @Test
    fun brokenManifestFailsWithoutThrowing() {
        for (text in listOf("not json", "{}", "{\"schemaVersion\":\"x\",\"packs\":[]}", "{\"schemaVersion\":\"1.0\"}")) {
            assertTrue(text, loader().load(text) { null } is ContentLoadResult.Failed)
        }
    }

    @Test
    fun unknownMajorOfPackSkipsPackOnly() {
        val c = loaded(load(listOf(valid()), packVersion = "2.0"))
        assertTrue(c.puzzles.isEmpty())
        assertEquals(listOf(ContentProblem.UnsupportedSchema("puzzles/p.json", "2.0")), c.problems)
    }

    @Test
    fun missingPackIsReportedNotFatal() {
        val c = loaded(loader().load(manifestFor(entry(id = "gone"))) { null })
        assertEquals(listOf<ContentProblem>(ContentProblem.MissingPack("puzzles/gone.json")), c.problems)
    }

    @Test
    fun corruptedPackIsSkipped() {
        val c = loaded(loader().load(manifestFor(entry())) { "{ broken" })
        assertTrue(c.puzzles.isEmpty())
        assertTrue(c.problems.single() is ContentProblem.Malformed)
    }

    @Test
    fun invalidPuzzlesAreSkippedOthersKept() {
        val good = valid("good")
        val illegal = valid("illegal").copy(solution = listOf("e2e5"))
        val oddFen = valid("fen").copy(fen = "garbage")
        val noTactic = valid("phase").copy(themes = listOf("opening"))
        val even = valid("even").copy(solution = listOf("e2e4", "e7e5"))
        val c = loaded(load(listOf(good, illegal, oddFen, noTactic, even)))
        assertEquals(listOf("good"), c.puzzles.map { it.id.value })
        assertEquals(4, c.problems.filterIsInstance<ContentProblem.InvalidPuzzle>().size)
    }

    @Test
    fun structureModeIsCheapButStillRejectsShapeErrors() {
        val illegalButWellFormed = valid("a").copy(solution = listOf("e2e5")) // форма вірна, легальність не перевіряється
        val badUci = valid("b").copy(solution = listOf("e2-e4"))
        val badFen = valid("c").copy(fen = "garbage")
        val c = loaded(load(listOf(illegalButWellFormed, badUci, badFen, valid("d")), LoadValidation.STRUCTURE))
        assertEquals(listOf("a", "d"), c.puzzles.map { it.id.value }.sorted())
        assertEquals(2, c.problems.size)
    }

    @Test
    fun duplicateIdsAndItemCountMismatchAreReported() {
        val c = loaded(load(listOf(valid("x"), valid("x")), entryCount = 5))
        assertEquals(1, c.puzzles.size)
        assertTrue(c.problems.any { it is ContentProblem.DuplicatePuzzle })
        assertTrue(c.problems.contains(ContentProblem.ItemCountMismatch("puzzles/p.json", 5, 2)))
    }

    @Test
    fun loadsFromStreams() {
        val manifest = manifestFor(entry(count = 1))
        val text = pack(listOf(valid()))
        val opened = ArrayList<String>()
        val r = loader().load(ByteArrayInputStream(manifest.toByteArray())) { id ->
            opened += id
            if (id == "p") ByteArrayInputStream(text.toByteArray()) else null
        }
        assertEquals(listOf("p"), opened)
        assertEquals(1, loaded(r).puzzles.size)
    }

    @Test
    fun ioErrorOnPackIsAProblemNotACrash() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("disk")
        }
        val r = loader().load(ByteArrayInputStream(manifestFor(entry()).toByteArray())) { broken }
        assertTrue(loaded(r).problems.single() is ContentProblem.MissingPack)
    }

    @Test
    fun inMemorySourceFiltersSortsAndLimits() = runBlocking {
        val records = listOf(
            valid("c").copy(rating = 900, themes = listOf("pin")),
            valid("a").copy(rating = 500, themes = listOf("fork")),
            valid("b").copy(rating = 500, themes = listOf("fork", "pin")),
        )
        val source = loaded(load(records)).source()
        assertEquals(listOf("a", "b", "c"), source.puzzles(PuzzleFilter()).map { it.id.value })
        assertEquals(listOf("b", "c"), source.puzzles(PuzzleFilter(themes = setOf(ThemeId("pin")))).map { it.id.value })
        assertEquals(listOf("c"), source.puzzles(PuzzleFilter(ratingRange = 600..1000)).map { it.id.value })
        assertEquals(listOf("b", "c"), source.puzzles(PuzzleFilter(excludeIds = setOf(PuzzleId("a")))).map { it.id.value })
        assertEquals(1, source.puzzles(PuzzleFilter(limit = 1)).size)
        assertNotNull(source.puzzle(PuzzleId("a")))
        assertNull(source.puzzle(PuzzleId("zzz")))
        assertEquals(1, source.manifest().packs.size)
    }

    @Test
    fun committedContentLoadsCompletelyAndEveryPuzzleSolvesBySolution() = runBlocking {
        val content = PuzzleTestSupport.loadCommitted()
        assertEquals(content.problems.toString(), emptyList<ContentProblem>(), content.problems)
        assertTrue("content has puzzles", content.puzzles.isNotEmpty())
        assertEquals(
            content.manifest.packs.filter { it.kind == ContentKind.PUZZLES }.sumOf { it.itemCount },
            content.puzzles.size,
        )
        val service = DefaultPuzzleService(content.source(), InMemoryProgress(), rules)
        for (p in content.puzzles) {
            val s = service.session(p)
            for ((i, uci) in p.solution.withIndex()) {
                if (i % 2 == 1) continue // ходи суперника робить сесія
                val step = s.submit(PuzzleTestSupport.mv(uci.value))
                assertTrue("${p.id.value} ply ${i + 1}", step is PuzzleStep.Correct)
                assertEquals(p.id.value, i == p.solution.size - 1, (step as PuzzleStep.Correct).lineCompleted)
            }
            assertEquals(p.id.value, PuzzleState.SOLVED, s.state)
            assertEquals(0, s.result().wrongMoves)
        }
    }

    @Test
    fun requestForUnknownThemeFindsNothing() = runBlocking {
        val content = PuzzleTestSupport.loadCommitted(LoadValidation.STRUCTURE)
        val service = DefaultPuzzleService(content.source(), InMemoryProgress(), rules)
        val request = PuzzleRequest(PuzzleFilter(themes = setOf(ThemeId("no_such_theme"))), PuzzleMode.NEW)
        assertNull(service.next(request))
    }
}
