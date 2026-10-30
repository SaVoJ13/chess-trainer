// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine.debug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.chesstrainer.core.engine.AnalysisEvent
import org.chesstrainer.core.engine.AnalysisRequest
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.EngineState
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.engine.Strength
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.engine.EngineTrace
import org.chesstrainer.engine.JniTransport
import org.chesstrainer.engine.RemoteTransport
import org.chesstrainer.engine.UciEngineService
import org.chesstrainer.engine.UciTransport

/** Транспорт, що вміє «завмерти»: не передає команд і губить рядки рушія. Імітує зависання (G1). */
internal class FreezableTransport(private val inner: UciTransport) : UciTransport {
    @Volatile var frozen = false

    override fun open(listener: UciTransport.Listener): String = inner.open(object : UciTransport.Listener {
        override fun onLine(line: String, readAtNs: Long) {
            if (!frozen) listener.onLine(line, readAtNs)
        }

        override fun onClosed(crashed: Boolean, reason: String) {
            if (!frozen) listener.onClosed(crashed, reason)
        }
    })

    override fun send(line: String) {
        if (!frozen) inner.send(line)
    }

    override fun close() = inner.close()

    override fun emittedInfoLines() = inner.emittedInfoLines()

    override fun crash() = inner.crash()
}

/**
 * G1: тест штучного зависання транспорту і прогін start/analyse/stop (CONTRACTS 3.4, v0.8).
 * Рядки `RESULT ...` у logcat, теґ `EnginePrototype`.
 */
class WatchdogScenarios(private val context: Context, private val mode: String, private val forceBaseline: Boolean) {
    private val rules = ChesslibRules()
    private val middlegame = Fen("r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P1B2/2PBPN2/PP1N1PPP/R2QK2R w KQ - 0 8")
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun transport(): UciTransport =
        if (mode == "remote") RemoteTransport(context, forceBaseline) else JniTransport(context) { forceBaseline }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun result(scenario: String, vararg values: Pair<String, Any?>) {
        log("RESULT scenario=$scenario mode=$mode " + values.joinToString(" ") { "${it.first}=${it.second}" })
    }

    private fun ms(startNs: Long) = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0

    private fun level(limit: SearchLimit) =
        DifficultyLevel(DifficultyId(1), Strength.Full, limit, 0, null, DifficultyGroup.MAX)

    suspend fun hang() {
        val frozen = FreezableTransport(transport())
        val engine = UciEngineService(frozen, rules)
        val checks = mutableListOf<Pair<String, Boolean>>()

        // 1. Завмерлий транспорт під час нескінченного аналізу → Failed(EngineUnavailable) ≤ 10 с.
        check(engine.start() is ChessResult.Ok) { "start" }
        var lastEvent: AnalysisEvent? = null
        val firstUpdate = AtomicBoolean(false)
        val collector = scope.launch {
            engine.analyse(AnalysisRequest(middlegame, emptyList(), null)).collect {
                lastEvent = it
                if (it is AnalysisEvent.Update) firstUpdate.set(true)
            }
        }
        withTimeout(10_000) { while (!firstUpdate.get()) delay(20) }
        frozen.frozen = true
        val t0 = SystemClock.elapsedRealtimeNanos()
        val failedState = withTimeout(20_000) { engine.state.first { it is EngineState.Failed } }
        val detectMs = ms(t0)
        collector.join()
        val eventOk = (lastEvent as? AnalysisEvent.Failed)?.error is ChessError.EngineUnavailable
        val stateOk = (failedState as EngineState.Failed).error is ChessError.EngineUnavailable
        frozen.frozen = false
        val tRestart = SystemClock.elapsedRealtimeNanos()
        val restarted = engine.start()
        val restartMs = ms(tRestart)
        val move = if (restarted is ChessResult.Ok) engine.bestMove(MoveRequest(Fen(START), emptyList(), level(SearchLimit(depth = 6)))) else null
        if (restarted !is ChessResult.Ok) EngineTrace.dump("hang-analyse restart failed: $restarted")
        result(
            "hang-analyse", "detect_ms" to "%.0f".format(detectMs), "within_10s" to (detectMs <= 10_000),
            "event_unavailable" to eventOk, "state_unavailable" to stateOk,
            "restart_ok" to (restarted is ChessResult.Ok), "restart_ms" to "%.0f".format(restartMs),
            "move_ok" to (move is ChessResult.Ok),
        )
        checks += "analyse" to (detectMs <= 10_000 && eventOk && stateOk && restarted is ChessResult.Ok && move is ChessResult.Ok)

        // 2. Завмерлий транспорт під час bestMove: помилка за moveTime + 5 с, а не за сторожовим 10 с.
        val t1 = SystemClock.elapsedRealtimeNanos()
        frozen.frozen = true
        val timedOut = engine.bestMove(MoveRequest(Fen(START), emptyList(), level(SearchLimit(moveTimeMs = 500))))
        val boundMs = ms(t1)
        frozen.frozen = false
        val unavailable = timedOut is ChessResult.Err && timedOut.error is ChessError.EngineUnavailable
        val failedBestMove = engine.state.value is EngineState.Failed
        val again = engine.start()
        result(
            "hang-bestmove", "ms" to "%.0f".format(boundMs), "expected_max_ms" to 5_500 + 300,
            "err_unavailable" to unavailable, "state_failed" to failedBestMove, "restart_ok" to (again is ChessResult.Ok),
        )
        checks += "bestmove" to (unavailable && failedBestMove && boundMs <= 5_800 && again is ChessResult.Ok)

        // 3. Завмерлий транспорт під час start(): помилка ≤ 15 с, стан Failed, повторний start() працює.
        engine.stop()
        frozen.frozen = true
        val t2 = SystemClock.elapsedRealtimeNanos()
        val startResult = engine.start()
        val startMs = ms(t2)
        frozen.frozen = false
        val startUnavailable = startResult is ChessResult.Err && startResult.error is ChessError.EngineUnavailable
        val failedStart = engine.state.value is EngineState.Failed
        val t3 = SystemClock.elapsedRealtimeNanos()
        val recovered = engine.start()
        result(
            "hang-start", "ms" to "%.0f".format(startMs), "within_15s" to (startMs <= 15_000),
            "err_unavailable" to startUnavailable, "state_failed" to failedStart,
            "restart_ok" to (recovered is ChessResult.Ok), "restart_ms" to "%.0f".format(ms(t3)),
        )
        checks += "start" to (startUnavailable && failedStart && startMs <= 15_000 && recovered is ChessResult.Ok)
        engine.stop()
        result("hang", "passed" to checks.count { it.second }, "total" to checks.size, "failed" to checks.filter { !it.second }.map { it.first })
    }

    /**
     * ≥ 200 циклів start/analyse/stop. Зависання: окремий виклик довший за поріг [STALL_MS] → мітка
     * `SOAK_STALL` (скрипт хоста знімає dumpsys/ps/debuggerd, поки процес ще завис) і дамп буфера трасування.
     */
    suspend fun soak(cycles: Int, longAnalyse: Boolean = false) {
        val engine = UciEngineService(transport(), rules)
        var anomalies = 0
        var unfinished = 0
        var maxStart = 0.0
        var maxStop = 0.0
        var maxAnalyse = 0.0
        var failedStarts = 0
        val begin = SystemClock.elapsedRealtimeNanos()

        suspend fun <T> timed(cycle: Int, phase: String, block: suspend () -> T): Pair<T?, Double> {
            val t = SystemClock.elapsedRealtimeNanos()
            val monitor = scope.launch {
                delay(STALL_MS)
                log("SOAK_STALL cycle=$cycle phase=$phase pid=${android.os.Process.myPid()} state=${engine.state.value}")
                EngineTrace.dump("stall cycle=$cycle phase=$phase")
            }
            try {
                return withTimeout(HARD_LIMIT_MS) { block() } to ms(t)
            } catch (e: TimeoutCancellationException) {
                unfinished++
                log("SOAK_UNFINISHED cycle=$cycle phase=$phase after ${HARD_LIMIT_MS} ms state=${engine.state.value}")
                EngineTrace.dump("unfinished cycle=$cycle phase=$phase")
                return null to ms(t)
            } finally {
                monitor.cancel()
            }
        }

        for (cycle in 1..cycles) {
            val (stopped, stopMs) = timed(cycle, "stop") { engine.stop() }
            maxStop = maxOf(maxStop, stopMs)
            val (started, startMs) = timed(cycle, "start") { engine.start(EngineConfig()) }
            maxStart = maxOf(maxStart, startMs)
            if (stopped == null || started == null) continue
            if (started !is ChessResult.Ok) {
                failedStarts++
                anomalies++
                log("SOAK_ANOMALY cycle=$cycle phase=start result=$started")
                EngineTrace.dump("start failed cycle=$cycle")
                continue
            }
            val (outcome, analyseMs) = timed(cycle, "analyse") {
                when (if (longAnalyse) 0 else cycle % 3) {
                    0 -> {
                        var updates = 0
                        var last: AnalysisEvent? = null
                        engine.analyse(AnalysisRequest(middlegame, emptyList(), SearchLimit(moveTimeMs = if (longAnalyse) 10_000 else 1000))).collect {
                            if (it is AnalysisEvent.Update) updates++
                            last = it
                        }
                        if (updates > 0 && last is AnalysisEvent.Finished) "ok" else "bad:updates=$updates last=$last"
                    }
                    1 -> {
                        val first = engine.analyse(AnalysisRequest(middlegame, emptyList(), null)).first()
                        if (first is AnalysisEvent.Update) "ok" else "bad:$first"
                    }
                    else -> {
                        val r = engine.bestMove(MoveRequest(Fen(START), emptyList(), level(SearchLimit(moveTimeMs = 300))))
                        if (r is ChessResult.Ok) "ok" else "bad:$r"
                    }
                }
            }
            maxAnalyse = maxOf(maxAnalyse, analyseMs)
            if (outcome != null && outcome != "ok") {
                anomalies++
                log("SOAK_ANOMALY cycle=$cycle phase=analyse variant=${cycle % 3} outcome=$outcome")
                EngineTrace.dump("analyse anomaly cycle=$cycle")
            }
            if (cycle % 10 == 0) {
                log("SOAK_PROGRESS cycle=$cycle anomalies=$anomalies unfinished=$unfinished elapsed_s=${"%.0f".format(ms(begin) / 1000)}")
            }
        }
        engine.stop()
        result(
            "soak", "cycles" to cycles, "anomalies" to anomalies, "unfinished" to unfinished, "failed_starts" to failedStarts,
            "max_start_ms" to "%.0f".format(maxStart), "max_stop_ms" to "%.0f".format(maxStop),
            "max_analyse_ms" to "%.0f".format(maxAnalyse), "wall_s" to "%.0f".format(ms(begin) / 1000),
            "wait_old_death" to RemoteTransport.awaitOldProcessDeath, "long_analyse" to longAnalyse,
            "bind_retries" to EngineTrace.counter("bind.retry"), "force_kills" to EngineTrace.counter("close.force-kill"),
            "old_process_alive_at_open" to EngineTrace.counter("open.old-process-still-alive"), "watchdog_trips" to EngineTrace.counter("watchdog.unavailable"),
        )
    }

    private companion object {
        const val TAG = "EnginePrototype"
        const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        const val STALL_MS = 4_000L
        const val HARD_LIMIT_MS = 60_000L
    }
}
