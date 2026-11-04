// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.EngineState
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.engine.Strength
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.rules.ChesslibRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таймаути й сторожовий механізм [UciEngineService] (CONTRACTS 3.4, v0.8) на JVM через [FakeUciTransport]
 * з короткими [EngineTimeouts]. Запит QA (`tests/PLAN.md`, розд. 3), затверджено Архітектором (сесія 22).
 */
class UciEngineServiceTimeoutTest {
    private val transport = FakeUciTransport()
    private val timeouts = EngineTimeouts(
        startMs = 300, searchGraceMs = 100, livenessMs = 400, stopMs = 200, closeWaitMs = 200, tickMs = 25, pingAfterMs = 100,
    )
    private val service = UciEngineService(transport, ChesslibRules(), timeouts)

    @After
    fun tearDown() = runBlocking { service.stop() }

    private fun request(limit: SearchLimit) = MoveRequest(
        Fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"), emptyList(),
        DifficultyLevel(DifficultyId(99), Strength.Full, limit, 0, null, DifficultyGroup.MAX),
    )

    private fun failedWith(): ChessError? = (service.state.value as? EngineState.Failed)?.error

    private fun awaitFailed() = runBlocking { withTimeout(3_000) { service.state.first { it is EngineState.Failed } } }

    private fun assertRestartWorks() = runBlocking {
        transport.silentOnUci = false; transport.mute = false; transport.ignoreGo = false; transport.ignoreStop = false
        assertEquals(ChessResult.Ok(Unit), service.start())
        assertEquals(EngineState.Ready, service.state.value)
        val move = service.bestMove(request(SearchLimit(depth = 1)))
        assertTrue("bestMove після повторного start: $move", move is ChessResult.Ok)
    }

    @Test
    fun noUciokWithinStartTimeoutFailsStartAndAllowsRestart() = runBlocking {
        transport.silentOnUci = true
        val begin = System.nanoTime()
        val result = service.start()
        val tookMs = (System.nanoTime() - begin) / 1_000_000
        assertTrue("очікували EngineUnavailable, є $result", (result as? ChessResult.Err)?.error is ChessError.EngineUnavailable)
        assertTrue("start мав урватися за ≈ startMs, а не чекати 10 с (uci-таймаут): $tookMs мс", tookMs < 3_000)
        assertTrue(failedWith() is ChessError.EngineUnavailable)
        assertRestartWorks()
    }

    @Test
    fun openFailureGivesEngineUnavailableAndAllowsRestart() = runBlocking {
        transport.failOpen = true
        val result = service.start()
        assertTrue((result as? ChessResult.Err)?.error is ChessError.EngineUnavailable)
        assertTrue(failedWith() is ChessError.EngineUnavailable)
        transport.failOpen = false
        assertRestartWorks()
    }

    @Test
    fun noBestmoveWithinMoveTimePlusGraceFailsWithUnavailableNotCrashed() = runBlocking {
        assertEquals(ChessResult.Ok(Unit), service.start())
        transport.ignoreGo = true
        val begin = System.nanoTime()
        val result = service.bestMove(request(SearchLimit(moveTimeMs = 50)))
        val tookMs = (System.nanoTime() - begin) / 1_000_000
        assertTrue("очікували EngineUnavailable, є $result", (result as? ChessResult.Err)?.error is ChessError.EngineUnavailable)
        assertTrue("відмова не раніше за moveTimeMs + searchGraceMs (150 мс): $tookMs мс", tookMs >= 140)
        assertTrue("відмова не пізніше ≈ 2 с: $tookMs мс", tookMs < 2_000)
        assertTrue(failedWith() is ChessError.EngineUnavailable)
        assertRestartWorks()
    }

    @Test
    fun silenceOnLivenessProbeFailsWithoutAnySearch() = runBlocking {
        assertEquals(ChessResult.Ok(Unit), service.start())
        transport.mute = true
        awaitFailed()
        assertTrue("очікували EngineUnavailable, є ${failedWith()}", failedWith() is ChessError.EngineUnavailable)
        assertTrue("сторож мав надіслати isready", transport.sent.count { it == "isready" } >= 2)
        assertRestartWorks()
    }

    @Test
    fun longSilentSearchIsNotAHangWhenEngineAnswersIsready() = runBlocking {
        assertEquals(ChessResult.Ok(Unit), service.start())
        // Пошук мовчить 900 мс (> livenessMs 400), але на службовий isready рушій відповідає readyok.
        transport.bestmoveDelayMs = 900
        val result = service.bestMove(request(SearchLimit(moveTimeMs = 2_000)))
        assertTrue("очікували Ok, є $result", result is ChessResult.Ok)
        assertEquals(EngineState.Ready, service.state.value)
        assertTrue("isready мав надсилатись під час тиші", transport.sent.count { it == "isready" } >= 3)
    }

    @Test
    fun stopWithoutAcknowledgementCancelsSearchAndFailsWithUnavailableNotCrashed() = runBlocking {
        assertEquals(ChessResult.Ok(Unit), service.start())
        transport.ignoreGo = true
        transport.ignoreStop = true
        val first = async(start = CoroutineStart.UNDISPATCHED) { service.bestMove(request(SearchLimit(moveTimeMs = 60_000))) }
        withTimeout(3_000) { service.state.first { it == EngineState.Busy } }

        // Новий запит скасовує перший: рушій на stop мовчить.
        val second = service.bestMove(request(SearchLimit(depth = 1)))
        val firstResult = withTimeout(3_000) { first.await() }
        assertEquals("перший пошук скасовано", ChessResult.Err(ChessError.Cancelled), firstResult)
        assertTrue("другий запит: EngineUnavailable, є $second", (second as? ChessResult.Err)?.error is ChessError.EngineUnavailable)
        val error = failedWith()
        assertTrue("стан Failed(EngineUnavailable), не EngineCrashed: $error", error is ChessError.EngineUnavailable)
        assertTrue("UCI stop мав бути надісланий", "stop" in transport.sent)
        assertRestartWorks()
    }

    @Test
    fun cancelledSearchWithAcknowledgedStopReturnsToReady() = runBlocking {
        assertEquals(ChessResult.Ok(Unit), service.start())
        transport.ignoreGo = true // go не завершується сам, лише по stop
        val first = async(start = CoroutineStart.UNDISPATCHED) { service.bestMove(request(SearchLimit(moveTimeMs = 60_000))) }
        withTimeout(3_000) { service.state.first { it == EngineState.Busy } }
        service.stop()
        assertEquals(ChessResult.Err(ChessError.Cancelled), withTimeout(3_000) { first.await() })
        assertEquals(EngineState.Stopped, service.state.value)
    }

    @Test
    fun crashWithoutStopGivesEngineCrashedAndAllowsRestart() = runBlocking {
        assertEquals(ChessResult.Ok(Unit), service.start())
        transport.crash()
        awaitFailed()
        assertTrue("очікували EngineCrashed, є ${failedWith()}", failedWith() is ChessError.EngineCrashed)
        assertRestartWorks()
    }

    @Test
    fun requestsOnFailedEngineAreRejectedWithoutHang() = runBlocking {
        transport.silentOnUci = true
        service.start()
        val result = service.bestMove(request(SearchLimit(depth = 1)))
        assertTrue((result as? ChessResult.Err)?.error is ChessError.EngineUnavailable)
        assertFalse("жодного go на несправному рушії", transport.sent.any { it.startsWith("go") })
    }
}
