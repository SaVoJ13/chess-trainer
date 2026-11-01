// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.content

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.learning.content.ContentProblem
import org.chesstrainer.learning.puzzle.PuzzleFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackedContentSourceTest {
    private val rules = ChesslibRules()

    private fun source(
        files: Map<String, String>,
        onMeasured: (ContentLoadMeasurement) -> Unit = {},
    ) = packedContentSource(
        opener = { path -> ByteArrayInputStream((files[path] ?: throw FileNotFoundException(path)).toByteArray()) },
        rules = rules,
        dispatcher = Dispatchers.Unconfined,
        onMeasured = onMeasured,
    )

    private fun manifest(version: String = "1.0") =
        """{"schemaVersion":"$version","packs":[{"id":"p","kind":"PUZZLES","languages":[],"itemCount":2,"sourceIds":["CT-001"]}]}"""

    private fun pack(version: String = "1.0") = """{"schemaVersion":"$version","packId":"p","sourceId":"CT-001","params":{},"puzzles":[
        {"id":"a1","fen":"6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1","solution":["a1a8"],"themes":["back_rank_mate","mate_in_1"],"rating":500},
        {"id":"a2","fen":"6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1","solution":["a1a8"],"themes":["back_rank_mate"],"rating":1500}]}"""

    private val ok = mapOf(
        "content/common/manifest.json" to manifest(),
        "content/common/puzzles/p.json" to pack(),
    )

    @Test
    fun loadsOnceAndAnswersFromMemory() = runBlocking {
        val measured = ArrayList<ContentLoadMeasurement>()
        val s = source(ok) { measured += it }
        assertEquals(ContentStatus.Ready(2, emptyList()), s.status())
        assertEquals(listOf("a1"), s.puzzles(PuzzleFilter(ratingRange = 0..999)).map { it.id.value })
        assertEquals(2, s.puzzles(PuzzleFilter()).size)
        assertEquals("a2", s.puzzle(org.chesstrainer.learning.puzzle.PuzzleId("a2"))?.id?.value)
        assertEquals(1, measured.size)
        assertEquals(2, measured.single().puzzleCount)
    }

    @Test
    fun unknownMajorGivesUnavailableNotCrash() = runBlocking {
        val s = source(ok + ("content/common/manifest.json" to manifest("2.0")))
        val status = s.status()
        assertTrue(status is ContentStatus.Unavailable)
        assertTrue((status as ContentStatus.Unavailable).problem is ContentProblem.UnsupportedSchema)
        assertEquals(emptyList<Any>(), s.puzzles(PuzzleFilter()))
    }

    @Test
    fun corruptedManifestGivesUnavailable() = runBlocking {
        val s = source(ok + ("content/common/manifest.json" to "{not json"))
        assertTrue((s.status() as ContentStatus.Unavailable).problem is ContentProblem.Malformed)
    }

    @Test
    fun missingManifestGivesUnavailable() = runBlocking {
        val s = source(emptyMap())
        assertTrue((s.status() as ContentStatus.Unavailable).problem is ContentProblem.MissingPack)
    }

    @Test
    fun corruptedPackIsSkippedWithProblem() = runBlocking {
        val s = source(ok + ("content/common/puzzles/p.json" to "garbage"))
        val status = s.status() as ContentStatus.Ready
        assertEquals(0, status.puzzleCount)
        assertTrue(status.problems.single() is ContentProblem.Malformed)
    }

    @Test
    fun missingPackFileIsReportedNotThrown() = runBlocking {
        val s = source(mapOf("content/common/manifest.json" to manifest()))
        val status = s.status() as ContentStatus.Ready
        assertTrue(status.problems.single() is ContentProblem.MissingPack)
    }

    /** Довідковий вимір на ПК для закоміченого пакета (на телефоні вимір пише лог `PuzzleContent`, див. журнал). */
    @Test
    fun committedPackLoadsAndReportsTiming() = runBlocking {
        val root = File("../content/common")
        val files = mapOf(
            "content/common/manifest.json" to File(root, "manifest.json").readText(),
            "content/common/puzzles/tactics-mvp.json" to File(root, "puzzles/tactics-mvp.json").readText(),
        )
        var m: ContentLoadMeasurement? = null
        val s = source(files) { m = it }
        val status = s.status() as ContentStatus.Ready
        assertEquals(4000, status.puzzleCount)
        assertEquals(emptyList<ContentProblem>(), status.problems)
        println("PC reference: ${m!!.puzzleCount} puzzles in ${m!!.elapsedMs} ms")
    }
}
