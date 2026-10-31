// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.chesstrainer.app.game.EngineStatus
import org.chesstrainer.app.game.GameController
import org.chesstrainer.app.game.GameOutcome
import org.chesstrainer.app.game.GameTermination
import org.chesstrainer.app.game.GameUiState
import org.chesstrainer.app.game.fixedLevel
import org.chesstrainer.core.engine.FakeEngineService
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.game.ChessGameFactory
import org.chesstrainer.core.game.DefaultChessGameFactory
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.pgn.DefaultPgnCodec
import org.chesstrainer.core.rules.ChesslibRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Автозбереження й відновлення партії: справжній `GameController`, `AutosaveGameSaver`, справжні правила й PGN,
 * сховище в пам'яті. «Перезапуск застосунку» це нова сесія з тим самим сховищем. Без Android.
 */
class AutosaveTest {

    private class MemoryRepository : GameRepository {
        val rows = linkedMapOf<Long, SavedGame>()
        private var nextId = 1L
        var failing = false
        var saves = 0

        override suspend fun save(game: SavedGame): GameId {
            if (failing) throw IOException("test: disk error")
            val id = game.id ?: GameId(nextId++)
            rows[id.value] = game.copy(id = id)
            saves++
            return id
        }

        override suspend fun load(id: GameId): SavedGame? {
            if (failing) throw IOException("test: disk error")
            return rows[id.value]
        }

        override suspend fun delete(id: GameId) {
            if (failing) throw IOException("test: disk error")
            rows.remove(id.value)
        }

        override suspend fun lastUnfinished(): SavedGame? {
            if (failing) throw IOException("test: disk error")
            return rows.values.filter { !it.finished }.maxWithOrNull(compareBy({ it.updatedAt }, { it.id!!.value }))
        }

        fun insert(game: SavedGame): GameId {
            val id = GameId(nextId++)
            rows[id.value] = game.copy(id = id)
            return id
        }
    }

    /** Одне «запущене» застосунок: свій контролер, рушій і черга збереження над спільним сховищем. */
    private class Session(
        val repo: MemoryRepository,
        scope: CoroutineScope,
        fen: String? = null,
        color: Color = Color.WHITE,
        clockStart: Long = 1_000,
    ) {
        val rules = ChesslibRules()
        val fake = FakeEngineService(rules)
        val errors = mutableListOf<Throwable>()
        private var tick = clockStart
        val persistence = GamePersistence(DefaultChessGameFactory(rules), DefaultPgnCodec(rules))
        val saver = AutosaveGameSaver(repo, persistence, scope, clock = { Instant.ofEpochSecond(tick++) }, onError = { errors += it })
        val controller = GameController(
            rules = rules,
            games = factoryFor(fen),
            engine = fake,
            scope = scope,
            levelFor = { caps -> fixedLevel(caps).copy(minReplyDelayMs = 0) },
            saver = saver,
            playerColor = color,
        )
        val state get() = controller.state.value

        private fun factoryFor(fen: String?): ChessGameFactory {
            val base = DefaultChessGameFactory(rules)
            return object : ChessGameFactory by base {
                override fun new(start: Position?) =
                    base.new(start ?: fen?.let { (rules.parseFen(it) as ChessResult.Ok).value })
            }
        }

        suspend fun await(predicate: (GameUiState) -> Boolean): GameUiState =
            withTimeout(5_000) { controller.state.first(predicate) }

        suspend fun settle() = repeat(30) { yield() }

        /** Перший за UCI легальний хід людини (так само вибирає й `FakeEngineService`). */
        fun humanMove() {
            val move = rules.legalMoves(state.position).minByOrNull { rules.toUci(it).value }!!
            controller.onSquareTap(move.from)
            controller.onSquareTap(move.to)
        }

        fun tap(from: String, to: String) {
            controller.onSquareTap(Square.parse(from)!!)
            controller.onSquareTap(Square.parse(to)!!)
        }

        /** Півхід людини й відповідь рушія. */
        suspend fun playRound(from: String, to: String) {
            val before = state.game.moves.size
            tap(from, to)
            await { it.game.moves.size == before + 2 && it.engine == EngineStatus.Idle }
        }

        suspend fun flush() = saver.flush()

        fun decode(row: SavedGame): ChessGame = persistence.decode(row)!!
    }

    private fun scenario(block: suspend CoroutineScope.(scope: CoroutineScope) -> Unit) = runBlocking {
        withTimeout(20_000) {
            val job = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + job)
            try {
                block(scope)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    private val fiftyMoveFen = "8/8/8/4k3/8/8/4K3/R7 w - - 98 80"

    // --- збереження ---

    @Test
    fun everyPlyOfHumanAndEngineIsSavedToTheSameRecord() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope)
        s.controller.start()

        s.tap("e2", "e4")
        s.flush()
        assertEquals(1, repo.rows.size)
        assertEquals(1, s.decode(repo.rows.values.single()).moves.size)

        s.await { it.game.moves.size == 2 && it.engine == EngineStatus.Idle }
        s.flush()
        val row = repo.rows.values.single()
        assertEquals(s.state.game.moves.map { it.uci }, s.decode(row).moves.map { it.uci })
        assertFalse(row.finished)
        assertNull(row.termination)
        assertEquals(GameMode.VS_ENGINE, row.mode)
        assertEquals(Color.WHITE, row.playerColor)
        assertNull("difficultyId не записується до появи пресетів", row.difficultyId)
        assertTrue(row.updatedAt > row.createdAt)
        assertTrue(s.errors.isEmpty())
    }

    @Test
    fun undoIsSaved() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope)
        s.controller.start()
        s.playRound("e2", "e4")
        s.playRound("d2", "d4")
        s.flush()
        assertEquals(4, s.decode(repo.rows.values.single()).moves.size)

        s.controller.undo()
        s.flush()
        assertEquals(2, s.decode(repo.rows.values.single()).moves.size)
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun undoToTheStartRemovesTheEmptyRecord() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope)
        s.controller.start()
        s.playRound("e2", "e4")
        s.flush()
        assertEquals(1, repo.rows.size)

        s.controller.undo()
        s.flush()
        assertTrue(s.state.game.moves.isEmpty())
        assertTrue("партія без ходів не зберігається", repo.rows.isEmpty())
    }

    @Test
    fun resignationIsSavedAsFinishedWithResult() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope)
        s.controller.start()
        s.playRound("e2", "e4")

        s.controller.resign()
        s.flush()
        val row = repo.rows.values.single()
        assertTrue(row.finished)
        assertEquals(GameTermination.RESIGNATION, row.termination)
        assertTrue("людина білими здалася: перемагають чорні", row.pgn.contains("0-1"))
        assertNull(repo.lastUnfinished())
    }

    @Test
    fun claimedDrawIsSavedAsFinishedDrawWithStartPosition() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope, fen = fiftyMoveFen)
        s.controller.start()
        s.playRound("a1", "a2")
        assertTrue(s.state.claimReasons.isNotEmpty())

        s.controller.claimDraw()
        s.flush()
        val row = repo.rows.values.single()
        assertTrue(row.finished)
        assertEquals(GameTermination.DRAW_CLAIMED, row.termination)
        assertTrue(row.pgn.contains("1/2-1/2"))
        val restored = s.decode(row)
        assertEquals(fiftyMoveFen, restored.startPosition.fen.value)
        assertEquals(s.state.game.moves.map { it.uci }, restored.moves.map { it.uci })
    }

    @Test
    fun checkmateIsSavedAsFinished() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope, fen = "7k/6pp/8/8/8/8/8/R5K1 w - - 0 1")
        s.controller.start()
        s.tap("a1", "a8")
        s.flush()
        val row = repo.rows.values.single()
        assertTrue(row.finished)
        assertEquals(GameTermination.CHECKMATE, row.termination)
        assertTrue(row.pgn.contains("1-0"))
    }

    @Test
    fun goingToBackgroundSavesTheCurrentState() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope)
        s.fake.holdSearches()
        s.controller.start()
        s.tap("e2", "e4")
        s.flush()
        val savedBefore = repo.saves
        val updatedBefore = repo.rows.values.single().updatedAt

        s.controller.onBackground()
        s.flush()
        assertEquals(savedBefore + 1, repo.saves)
        assertTrue(repo.rows.values.single().updatedAt > updatedBefore)
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun newGameClosesTheActiveGameAsAbandonedAndKeepsIt() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope)
        s.controller.start()
        s.playRound("e2", "e4")

        s.controller.newGame(Color.WHITE)
        s.flush()
        val old = repo.rows.values.single()
        assertTrue(old.finished)
        assertEquals(GameTermination.ABANDONED, old.termination)
        assertEquals(2, s.decode(old).moves.size)

        s.playRound("d2", "d4")
        s.flush()
        assertEquals("нова партія пишеться в новий запис", 2, repo.rows.size)
        assertEquals(1, repo.rows.values.count { !it.finished })
    }

    @Test
    fun newGameKeepsTheTerminationOfAnAlreadyFinishedGame() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope)
        s.controller.start()
        s.playRound("e2", "e4")
        s.controller.resign()
        s.flush()

        s.controller.newGame(Color.BLACK)
        s.flush()
        assertEquals(GameTermination.RESIGNATION, repo.rows.values.single().termination)
    }

    // --- відновлення ---

    @Test
    fun unfinishedGameIsOfferedAndContinuedOnTheSameRecord() = scenario { scope ->
        val repo = MemoryRepository()
        val first = Session(repo, scope)
        first.controller.start()
        first.playRound("e2", "e4")
        first.flush()
        val movesBefore = first.state.game.moves.map { it.uci }
        val createdBefore = repo.rows.values.single().createdAt
        // «Примусове завершення»: першу сесію просто покинуто.

        val s = Session(repo, scope, clockStart = 5_000)
        s.controller.start()
        s.controller.checkForSavedGame()
        assertTrue("до рішення користувача ходи заблоковані", s.state.awaitingResume)
        val offered = s.await { it.resumeOfferPlies != null }
        assertEquals(2, offered.resumeOfferPlies)
        s.controller.onSquareTap(Square.parse("e2")!!)
        assertNull(s.state.selected)
        s.controller.resign()
        assertFalse("здача заблокована до рішення", s.state.isOver)

        s.controller.continueSavedGame()
        assertFalse(s.state.awaitingResume)
        assertNull(s.state.resumeOfferPlies)
        assertEquals(movesBefore, s.state.game.moves.map { it.uci })
        assertEquals(Color.WHITE, s.state.playerColor)
        assertTrue(s.state.humanToMove)

        s.humanMove()
        s.await { it.game.moves.size == 4 && it.engine == EngineStatus.Idle }
        s.flush()
        assertEquals("продовження оновлює той самий запис", 1, repo.rows.size)
        assertEquals(4, s.decode(repo.rows.values.single()).moves.size)
        assertEquals("час створення зберігається", createdBefore, repo.rows.values.single().createdAt)
    }

    @Test
    fun restoredGameWithEngineToMoveLetsTheEngineMove() = scenario { scope ->
        val repo = MemoryRepository()
        val first = Session(repo, scope)
        first.fake.holdSearches()
        first.controller.start()
        first.tap("e2", "e4")
        first.flush()
        assertEquals(1, first.decode(repo.rows.values.single()).moves.size)

        val s = Session(repo, scope)
        s.controller.start()
        s.controller.checkForSavedGame()
        s.await { it.resumeOfferPlies != null }
        assertTrue("до згоди рушій не ходить", s.fake.moveRequests.isEmpty())

        s.controller.continueSavedGame()
        assertTrue(s.state.engineToMove)
        s.await { it.game.moves.size == 2 && it.engine == EngineStatus.Idle }
        assertEquals(1, s.fake.moveRequests.size)
        assertEquals(listOf("e2e4"), s.fake.moveRequests.single().moves.map { it.value })
        s.flush()
        assertEquals(2, s.decode(repo.rows.values.single()).moves.size)
    }

    @Test
    fun restoredGameKeepsThePlayerColor() = scenario { scope ->
        val repo = MemoryRepository()
        val first = Session(repo, scope, color = Color.BLACK)
        first.controller.start()
        first.await { it.game.moves.size == 1 && it.engine == EngineStatus.Idle }
        first.flush()

        val s = Session(repo, scope)
        s.controller.start()
        s.controller.checkForSavedGame()
        s.await { it.resumeOfferPlies != null }
        s.controller.continueSavedGame()
        assertEquals(Color.BLACK, s.state.playerColor)
        assertTrue(s.state.humanToMove)
        assertTrue("рушій білими вже зіграв", s.state.game.moves.size == 1)
    }

    @Test
    fun withoutSavedGameNothingIsOfferedAndPlayWorks() = scenario { scope ->
        val repo = MemoryRepository()
        val s = Session(repo, scope)
        s.controller.start()
        s.controller.checkForSavedGame()
        s.await { !it.awaitingResume }
        assertNull(s.state.resumeOfferPlies)
        s.controller.continueSavedGame()
        s.controller.declineSavedGame()
        assertTrue(s.state.game.moves.isEmpty())

        s.playRound("e2", "e4")
        s.flush()
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun finishedGamesAreNotOfferedButStayInStorage() = scenario { scope ->
        val repo = MemoryRepository()
        val first = Session(repo, scope)
        first.controller.start()
        first.playRound("e2", "e4")
        first.controller.resign()
        first.flush()

        val s = Session(repo, scope)
        s.controller.start()
        s.controller.checkForSavedGame()
        s.await { !it.awaitingResume }
        assertNull(s.state.resumeOfferPlies)
        assertEquals(1, repo.rows.size)
        assertEquals(GameTermination.RESIGNATION, repo.rows.values.single().termination)
    }

    @Test
    fun decliningClosesTheSavedGameWithoutDeletingIt() = scenario { scope ->
        val repo = MemoryRepository()
        val first = Session(repo, scope)
        first.controller.start()
        first.playRound("e2", "e4")
        first.flush()

        val s = Session(repo, scope)
        s.controller.start()
        s.controller.checkForSavedGame()
        s.await { it.resumeOfferPlies != null }
        s.controller.declineSavedGame()
        assertFalse(s.state.awaitingResume)
        assertTrue("нова партія з початкової позиції", s.state.game.moves.isEmpty())
        s.flush()
        val old = repo.rows.values.single()
        assertTrue(old.finished)
        assertEquals(GameTermination.ABANDONED, old.termination)

        s.playRound("d2", "d4")
        s.flush()
        assertEquals(2, repo.rows.size)
        assertEquals("лише одна активна партія", 1, repo.rows.values.count { !it.finished })

        val next = Session(repo, scope, clockStart = 9_000)
        next.controller.start()
        next.controller.checkForSavedGame()
        val offered = next.await { it.resumeOfferPlies != null }
        assertEquals(2, offered.resumeOfferPlies)
    }

    // --- пошкоджені дані й збої сховища ---

    private fun damaged(pgn: String, mode: GameMode = GameMode.VS_ENGINE, color: Color? = Color.WHITE) = SavedGame(
        id = null,
        pgn = pgn,
        mode = mode,
        playerColor = color,
        difficultyId = null,
        finished = false,
        termination = null,
        createdAt = Instant.ofEpochSecond(10),
        updatedAt = Instant.ofEpochSecond(20),
    )

    private fun assertStartsNewGame(row: SavedGame) = scenario { scope ->
        val repo = MemoryRepository()
        repo.insert(row)
        val s = Session(repo, scope)
        s.controller.start()
        s.controller.checkForSavedGame()
        s.await { !it.awaitingResume }
        assertNull(s.state.resumeOfferPlies)
        assertTrue(s.state.game.moves.isEmpty())
        assertTrue(s.state.humanToMove)
        s.flush()
        val closed = repo.rows.values.single()
        assertTrue("пошкоджений запис закрито, а не пропонується знову", closed.finished)
        assertEquals(GameTermination.ABANDONED, closed.termination)

        s.playRound("e2", "e4")
        assertNotNull(s.state.game.moves.firstOrNull())
    }

    @Test
    fun garbagePgnStartsANewGame() = assertStartsNewGame(damaged("це не PGN {{{"))

    @Test
    fun emptyPgnStartsANewGame() = assertStartsNewGame(damaged(""))

    @Test
    fun illegalMoveInPgnStartsANewGame() =
        assertStartsNewGame(damaged("[Event \"?\"]\n\n1. e4 e5 2. Ke7 *\n"))

    @Test
    fun recordWithoutPlayerColorStartsANewGame() =
        assertStartsNewGame(damaged("[Event \"?\"]\n\n1. e4 *\n", color = null))

    @Test
    fun nonEngineGameIsNotResumedAsEngineGame() =
        assertStartsNewGame(damaged("[Event \"?\"]\n\n1. e4 *\n", mode = GameMode.ANALYSIS_BOARD))

    @Test
    fun storageFailureNeverCrashesOrBlocksPlay() = scenario { scope ->
        val repo = MemoryRepository()
        repo.failing = true
        val s = Session(repo, scope)
        s.controller.start()
        s.controller.checkForSavedGame()
        s.await { !it.awaitingResume }
        assertNull(s.state.resumeOfferPlies)

        s.playRound("e2", "e4")
        s.flush()
        assertTrue(repo.rows.isEmpty())
        assertTrue("збої повідомляються, але чергу не зупинено", s.errors.isNotEmpty())
        assertTrue(s.state.humanToMove)

        repo.failing = false
        s.playRound("d2", "d4")
        s.flush()
        val row = repo.rows.values.single()
        assertEquals("наступне збереження містить усі ходи", 4, s.decode(row).moves.size)
    }

    // --- відтворення через ChessGame/PgnCodec ---

    @Test
    fun persistenceRoundTripKeepsStartPositionMovesAndResult() {
        val rules = ChesslibRules()
        val games = DefaultChessGameFactory(rules)
        val persistence = GamePersistence(games, DefaultPgnCodec(rules))
        val start = (rules.parseFen(fiftyMoveFen) as ChessResult.Ok).value
        var game = games.new(start)
        repeat(3) {
            val move = rules.legalMoves(game.position).minByOrNull { rules.toUci(it).value }!!
            game = (game.play(move) as ChessResult.Ok).value
        }
        val state = GameUiState(game = game, playerColor = Color.BLACK, endedBy = GameOutcome.DrawClaimed)

        val saved = persistence.encode(state, id = null, createdAt = Instant.ofEpochSecond(0), updatedAt = Instant.ofEpochSecond(1))
        assertTrue(saved.finished)
        assertEquals(GameTermination.DRAW_CLAIMED, saved.termination)
        assertEquals(Color.BLACK, saved.playerColor)
        assertNull(saved.difficultyId)
        assertTrue(saved.pgn.contains("[Date \"1970.01.01\"]"))

        val restored = persistence.decode(saved)!!
        assertEquals(game.startPosition.fen, restored.startPosition.fen)
        assertEquals(game.moves.map { it.uci }, restored.moves.map { it.uci })
        assertEquals(game.position.fen, restored.position.fen)
    }
}
