// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.pgn

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.chesstrainer.app.storage.GameId
import org.chesstrainer.app.storage.GameMode
import org.chesstrainer.app.storage.GameRepository
import org.chesstrainer.app.storage.SavedGame
import org.chesstrainer.app.storage.SavedGameSummary
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.pgn.DefaultPgnCodec
import org.chesstrainer.core.pgn.PgnResult
import org.chesstrainer.core.rules.ChesslibRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnTransferTest {
    private val codec = DefaultPgnCodec(ChesslibRules())

    private class MemoryRepository : GameRepository {
        val rows = mutableListOf<SavedGame>()

        override suspend fun save(game: SavedGame): GameId {
            val id = GameId(rows.size + 1L)
            rows += game.copy(id = id)
            return id
        }

        override suspend fun load(id: GameId) = rows.firstOrNull { it.id == id }

        override suspend fun delete(id: GameId) {
            rows.removeAll { it.id == id }
        }

        override fun observeAll(): Flow<List<SavedGameSummary>> = flowOf(emptyList())

        override suspend fun lastUnfinished(): SavedGame? = null
    }

    private val ok = "[Event \"Партія A\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0\n\n"
    private val illegal = "[Event \"B\"]\n\n1. e4 e5 2. Ke3 *\n\n"

    private fun import(text: String, repo: MemoryRepository = MemoryRepository()): Pair<ImportResult, MemoryRepository> =
        runBlocking { PgnImporter(codec, repo).import(ByteArrayInputStream(text.toByteArray())) to repo }

    @Test
    fun importsValidGamesAndReportsTheNumbersOfSkippedOnes() {
        val (result, repo) = import(ok + illegal + ok)
        assertEquals(ImportResult.Done(imported = 2, skipped = listOf(2)), result)
        assertEquals(2, repo.rows.size)
        repo.rows.forEach {
            assertEquals(GameMode.IMPORTED, it.mode)
            assertTrue(it.finished)
            assertEquals(null, it.termination)
            assertEquals(null, it.playerColor)
            assertEquals(null, it.difficultyId)
            assertEquals(4, (codec.parse(it.pgn).first() as ChessResult.Ok).value.moves.size)
        }
        assertTrue("перша партія файлу новіша: у списку вона зверху", repo.rows[0].updatedAt > repo.rows[1].updatedAt)
    }

    @Test
    fun cyrillicTagsAndByteOrderMarkSurvive() {
        val (result, repo) = import(0xFEFF.toChar() + ok)
        assertEquals(ImportResult.Done(1, emptyList()), result)
        assertTrue(repo.rows.single().pgn.contains("Партія A"))
    }

    @Test
    fun moreThan500GamesAreRejectedWithoutPartialImport() {
        val (result, repo) = import(ok.repeat(501))
        assertEquals(ImportResult.TooMany, result)
        assertTrue(repo.rows.isEmpty())
    }

    @Test
    fun exactly500GamesAreImported() {
        val (result, repo) = import(ok.repeat(500))
        assertEquals(ImportResult.Done(500, emptyList()), result)
        assertEquals(500, repo.rows.size)
    }

    @Test
    fun fileOverTwoMegabytesIsRejectedWithoutPartialImport() {
        val repo = MemoryRepository()
        val big = ByteArrayInputStream(ByteArray(PgnImporter.MAX_BYTES + 1) { ' '.code.toByte() })
        val result = runBlocking { PgnImporter(codec, repo).import(big) }
        assertEquals(ImportResult.TooLarge, result)
        assertTrue(repo.rows.isEmpty())
    }

    @Test
    fun emptyFileHasNoGames() {
        assertEquals(ImportResult.NoGames, import("").first)
    }

    @Test
    fun unreadableStreamIsAReadError() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("test")
        }
        val repo = MemoryRepository()
        assertEquals(ImportResult.ReadError, runBlocking { PgnImporter(codec, repo).import(failing) })
        assertTrue(repo.rows.isEmpty())
    }

    // --- експорт ---

    private fun engineGame(color: Color = Color.WHITE, level: Int? = 3) = SavedGame(
        id = GameId(1),
        pgn = "[Event \"Chess Trainer game\"]\n[White \"Player\"]\n[Black \"Engine\"]\n\n1. e4 e5 *\n",
        mode = GameMode.VS_ENGINE,
        playerColor = color,
        difficultyId = level?.let(::DifficultyId),
        finished = false,
        termination = null,
        createdAt = Instant.parse("2026-11-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-11-01T10:05:00Z"),
    )

    private fun parseAll(text: String) = codec.parse(text).map { (it as ChessResult.Ok).value }.toList()

    @Test
    fun engineGameGetsDefaultTags() {
        val game = parseAll(PgnExporter(codec).export(listOf(engineGame())).text).single()
        assertEquals("Chess Trainer", game.headers["Event"])
        assertEquals("Player", game.headers["White"])
        assertEquals("Stockfish (level 3)", game.headers["Black"])
        assertEquals("2026.11.01", game.headers["Date"])
        assertEquals(PgnResult.UNKNOWN, game.result)
        assertEquals(2, game.moves.size)
    }

    @Test
    fun playerColorBlackSwapsTheNames() {
        val game = parseAll(PgnExporter(codec).export(listOf(engineGame(Color.BLACK, level = null))).text).single()
        assertEquals("Stockfish", game.headers["White"])
        assertEquals("Player", game.headers["Black"])
    }

    @Test
    fun importedGameKeepsItsOwnTagsAndResult() {
        val repo = MemoryRepository()
        import(ok, repo)
        val export = PgnExporter(codec).export(repo.rows)
        val game = parseAll(export.text).single()
        assertEquals("Партія A", game.headers["Event"])
        assertEquals(PgnResult.WHITE_WINS, game.result)
        assertEquals(1, export.exported)
    }

    @Test
    fun allGamesGoToOneFileAndUnreadableOnesAreCounted() {
        val broken = engineGame().copy(pgn = "це не PGN {{{")
        val export = PgnExporter(codec).export(listOf(engineGame(), broken, engineGame(Color.BLACK)))
        assertEquals(2, export.exported)
        assertEquals(1, export.unreadable)
        assertEquals(2, parseAll(export.text).size)
        assertTrue(export.text.lineSequence().all { it.length <= 80 || it.startsWith("[") })
    }

    @Test
    fun exportedFileImportsBackWithTheSameMoves() {
        val text = PgnExporter(codec).export(listOf(engineGame(), engineGame(Color.BLACK))).text
        val (result, repo) = import(text)
        assertEquals(ImportResult.Done(2, emptyList()), result)
        assertEquals(listOf(2, 2), repo.rows.map { (codec.parse(it.pgn).first() as ChessResult.Ok).value.moves.size })
    }
}
