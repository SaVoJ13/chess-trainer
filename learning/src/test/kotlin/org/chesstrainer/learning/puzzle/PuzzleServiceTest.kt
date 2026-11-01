// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.puzzle

import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.chesstrainer.learning.content.ContentKind
import org.chesstrainer.learning.content.ContentManifest
import org.chesstrainer.learning.content.ContentPack
import org.chesstrainer.learning.content.InMemoryContentSource
import org.chesstrainer.learning.content.SchemaVersion
import org.chesstrainer.learning.puzzle.PuzzleTestSupport.puzzle
import org.chesstrainer.learning.puzzle.PuzzleTestSupport.rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PuzzleServiceTest {
    private val puzzles = listOf(
        puzzle("p1", themes = listOf("fork", "middlegame"), rating = 500),
        puzzle("p2", themes = listOf("pin"), rating = 900),
        puzzle("p3", themes = listOf("fork"), rating = 1200),
        puzzle("p4", themes = listOf("skewer", "endgame"), rating = 1500),
        puzzle("p5", themes = listOf("fork", "pin"), rating = 2000),
    )
    private val content = InMemoryContentSource(
        ContentManifest(SchemaVersion(1, 0), listOf(ContentPack("t", ContentKind.PUZZLES, emptySet(), 5, setOf("CT-001")))),
        puzzles,
    )
    private val progress = InMemoryProgress()

    private fun service(seed: Int = 1) = DefaultPuzzleService(content, progress, rules, Random(seed))

    private fun new(filter: PuzzleFilter = PuzzleFilter()) = PuzzleRequest(filter, PuzzleMode.NEW)

    private suspend fun attempt(p: Puzzle, state: PuzzleState) = progress.recordAttempt(
        PuzzleAttempt(PuzzleResult(p.id, state, 0, 0, 100), Instant.EPOCH),
    )

    private fun byId(id: String) = puzzles.first { it.id.value == id }

    @Test
    fun filterByThemesAndRatingRange() = runBlocking {
        val s = service()
        val seen = HashSet<String>()
        repeat(40) {
            val p = s.next(new(PuzzleFilter(themes = setOf(ThemeId("fork")), ratingRange = 1000..2500)))!!
            seen += p.id.value
        }
        assertEquals(setOf("p3", "p5"), seen)
    }

    @Test
    fun themeFilterMatchesAnyOfThemes() = runBlocking {
        val s = service()
        val seen = (1..60).map { s.next(new(PuzzleFilter(themes = setOf(ThemeId("skewer"), ThemeId("pin")))))!!.id.value }.toSet()
        assertEquals(setOf("p2", "p4", "p5"), seen)
    }

    @Test
    fun excludeIdsAreRespected() = runBlocking {
        val s = service()
        val filter = PuzzleFilter(excludeIds = setOf("p1", "p2", "p3", "p4").map { PuzzleId(it) }.toSet())
        repeat(5) { assertEquals("p5", s.next(new(filter))!!.id.value) }
    }

    @Test
    fun newModeSkipsAttemptedAndEndsWithNull() = runBlocking {
        val s = service()
        val order = ArrayList<String>()
        while (true) {
            val p = s.next(new()) ?: break
            order += p.id.value
            attempt(p, PuzzleState.SOLVED)
        }
        assertEquals(5, order.size)
        assertEquals(5, order.toSet().size)
        assertNull(s.next(new()))
    }

    @Test
    fun newModeSkipsFailedAttemptsToo() = runBlocking {
        attempt(byId("p1"), PuzzleState.FAILED)
        attempt(byId("p2"), PuzzleState.SOLVED)
        val s = service()
        val seen = (1..40).map { s.next(new())!!.id.value }.toSet()
        assertTrue(seen.none { it == "p1" || it == "p2" })
    }

    @Test
    fun doesNotRepeatInARowWhenThereIsAnotherPuzzle() = runBlocking {
        val s = service()
        var prev: PuzzleId? = null
        repeat(50) {
            val p = s.next(new())!!
            assertNotEquals(prev, p.id)
            prev = p.id
        }
    }

    @Test
    fun lastPuzzleIsReturnedAgainWhenItIsTheOnlyOne() = runBlocking {
        val s = service()
        val filter = PuzzleFilter(ratingRange = 400..600)
        assertEquals("p1", s.next(new(filter))!!.id.value)
        assertEquals("p1", s.next(new(filter))!!.id.value)
    }

    @Test
    fun deterministicForTheSameRandom() = runBlocking {
        fun run(seed: Int): List<String> {
            val s = service(seed)
            return runBlocking { (1..30).map { s.next(new())!!.id.value } }
        }
        assertEquals(run(7), run(7))
        assertNotEquals(run(7), run(8))
    }

    @Test
    fun retryFailedGivesOldestFirstAndHonoursFilter() = runBlocking {
        attempt(byId("p3"), PuzzleState.FAILED)
        attempt(byId("p1"), PuzzleState.FAILED)
        attempt(byId("p2"), PuzzleState.SOLVED)
        attempt(byId("p5"), PuzzleState.FAILED)
        val s = service()
        val retry = { f: PuzzleFilter -> PuzzleRequest(f, PuzzleMode.RETRY_FAILED) }
        assertEquals("p3", service().next(retry(PuzzleFilter()))!!.id.value)
        assertEquals("p3", service().next(retry(PuzzleFilter(excludeIds = setOf(PuzzleId("p1")))))!!.id.value)
        assertEquals("p1", service().next(retry(PuzzleFilter(ratingRange = 0..600)))!!.id.value)
        assertEquals("p5", service().next(retry(PuzzleFilter(ratingRange = 1900..3000)))!!.id.value)
        assertNull(service().next(retry(PuzzleFilter(themes = setOf(ThemeId("skewer"))))))
    }

    @Test
    fun retryFailedSkipsRemovedAndSolvedAndDoesNotRepeatInARow() = runBlocking {
        progress.recordAttempt(
            PuzzleAttempt(PuzzleResult(PuzzleId("gone"), PuzzleState.FAILED, 1, 0, 1), Instant.EPOCH),
        )
        attempt(byId("p2"), PuzzleState.FAILED)
        attempt(byId("p4"), PuzzleState.FAILED)
        val s = service()
        val retry = PuzzleRequest(PuzzleFilter(), PuzzleMode.RETRY_FAILED)
        assertEquals("p2", s.next(retry)!!.id.value)
        assertEquals("p4", s.next(retry)!!.id.value) // не поспіль
        assertEquals("p2", s.next(retry)!!.id.value)
        attempt(byId("p2"), PuzzleState.SOLVED)
        attempt(byId("p4"), PuzzleState.SOLVED)
        assertNull(s.next(retry))
    }

    @Test
    fun sessionIsCreatedForThePuzzle() {
        val p = puzzles.first()
        assertEquals(p, service().session(p).puzzle)
    }
}
