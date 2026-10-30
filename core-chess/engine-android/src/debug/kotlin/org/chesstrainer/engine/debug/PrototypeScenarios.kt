// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine.debug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.chesstrainer.core.engine.AnalysisEvent
import org.chesstrainer.core.engine.AnalysisRequest
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.EngineState
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.engine.Strength
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.engine.InProcessEngineService
import org.chesstrainer.engine.RemoteEngineService
import org.chesstrainer.engine.UciEngineService

/**
 * Сценарії прототипу (план Архітектора, п. 4–6). Результати йдуть у logcat, тег [TAG]; рядки
 * `RESULT ...` легко розбирати скриптом. Виключно debug-набір джерел.
 */
class PrototypeScenarios(
    private val context: Context,
    private val mode: String,        // "inproc" | "remote"
    private val forceBaseline: Boolean,
    private val noWait: Boolean = false,   // налагодження G1: не чекати смерті старого :engine
) {
    private val rules = ChesslibRules()
    private val startFen = Fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    private val middlegame = Fen("r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P1B2/2PBPN2/PP1N1PPP/R2QK2R w KQ - 0 8")

    private fun newEngine(): UciEngineService = when (mode) {
        "remote" -> RemoteEngineService(context, rules, forceBaseline)
        else -> InProcessEngineService(context, rules, forceBaseline)
    }

    private var shared: UciEngineService? = null
    val engine: UciEngineService get() = shared ?: newEngine().also { shared = it }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun result(scenario: String, vararg values: Pair<String, Any?>) {
        log("RESULT scenario=$scenario mode=$mode baseline=$forceBaseline " + values.joinToString(" ") { "${it.first}=${it.second}" })
    }

    private fun ms(startNs: Long) = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0

    private fun level(strength: Strength, limit: SearchLimit) =
        DifficultyLevel(DifficultyId(1), strength, limit, 0, null, DifficultyGroup.MAX)

    private fun request(strength: Strength, limit: SearchLimit, fen: Fen = startFen) =
        MoveRequest(fen, emptyList(), level(strength, limit))

    suspend fun run(scenario: String, repeat: Int = 5) {
        log("BEGIN scenario=$scenario mode=$mode baseline=$forceBaseline")
        try {
            when (scenario) {
                "hello" -> hello()
                "contract" -> contract()
                "cold" -> cold()
                "restart" -> restart(repeat)
                "bestmove" -> bestMoveTimes(repeat)
                "ipc" -> ipc(repeat)
                "analyse" -> analyse(repeat)
                "memory" -> memory()
                "crash" -> crash()
                "bgprep" -> prepareBackground()
                "bgcheck" -> backgroundCheck()
                "stop" -> engine.stop()
                "nnuefifo" -> NnueResearch(context).fifo()
                "dotcmp" -> MeasureScenarios(context, mode, forceBaseline).dotprodCompare(repeat)
                "stream" -> MeasureScenarios(context, mode, forceBaseline).stream(repeat)
                "memstop" -> MeasureScenarios(context, mode, forceBaseline).memoryAfterStop(repeat)
                "hang" -> WatchdogScenarios(context, mode, forceBaseline).hang()
                "soak", "soaklong" -> {
                    org.chesstrainer.engine.RemoteTransport.awaitOldProcessDeath = !noWait
                    WatchdogScenarios(context, mode, forceBaseline).soak(repeat, scenario == "soaklong")
                }
                else -> log("unknown scenario $scenario")
            }
        } catch (t: Throwable) {
            log("FAILED scenario=$scenario: $t")
        }
        log("END scenario=$scenario")
    }

    private suspend fun startedEngine(config: EngineConfig = EngineConfig()): UciEngineService? {
        val e = engine
        val r = e.start(config)
        if (r !is ChessResult.Ok) {
            log("start failed: $r state=${e.state.value}")
            return null
        }
        return e
    }

    private suspend fun hello() {
        val started = SystemClock.elapsedRealtimeNanos()
        val e = startedEngine() ?: return
        val startMs = ms(started)
        log("state=${e.state.value} capabilities=${e.capabilities}")
        val t = SystemClock.elapsedRealtimeNanos()
        val move = e.bestMove(request(Strength.Full, SearchLimit(moveTimeMs = 1000)))
        val legal = (move as? ChessResult.Ok)?.let {
            val p = (rules.parseFen(startFen.value) as ChessResult.Ok).value
            rules.parseUci(p, it.value.best.value) is ChessResult.Ok
        }
        result("hello", "start_ms" to "%.0f".format(startMs), "best" to move, "legal" to legal, "best_ms" to "%.0f".format(ms(t)))
    }

    private suspend fun contract() {
        val suite = EngineContractSuite(rules) { engine }
        val outcomes = suite.runAll()
        for (o in outcomes) log("CONTRACT ${if (o.passed) "PASS" else "FAIL"} mode=$mode ${o.name}${o.error?.let { ": $it" } ?: ""}")
        result("contract", "passed" to outcomes.count { it.passed }, "total" to outcomes.size)
    }

    /** Холодний запуск: бажано в свіжому процесі (скрипт робить force-stop між повторами). */
    private suspend fun cold() {
        val t = SystemClock.elapsedRealtimeNanos()
        val e = startedEngine() ?: return
        result("cold", "start_ms" to "%.0f".format(ms(t)), "state" to e.state.value)
        e.stop()
    }

    /** Повторні start() після stop() в тому ж процесі (бібліотека вже завантажена). */
    private suspend fun restart(repeat: Int) {
        val e = engine
        repeat(repeat) { i ->
            e.stop()
            val t = SystemClock.elapsedRealtimeNanos()
            val r = e.start(EngineConfig())
            result("restart", "i" to i, "start_ms" to "%.0f".format(ms(t)), "ok" to (r is ChessResult.Ok))
        }
        e.stop()
    }

    private suspend fun bestMoveTimes(repeat: Int) {
        val e = startedEngine() ?: return
        val weakest = e.capabilities!!.eloRange!!.first
        for ((name, strength) in listOf("full" to Strength.Full, "elo_min" to Strength.Elo(weakest))) {
            repeat(repeat) { i ->
                val t = SystemClock.elapsedRealtimeNanos()
                val r = e.bestMove(request(strength, SearchLimit(moveTimeMs = 1000), middlegame))
                result("bestmove", "level" to name, "elo" to (strength as? Strength.Elo)?.elo, "i" to i, "ms" to "%.0f".format(ms(t)), "move" to (r as? ChessResult.Ok)?.value?.best?.value)
            }
        }
        e.stop()
    }

    /** Накладні IPC: пошук на 1 вузол майже не має самого пошуку, лишаються Binder і диспетчеризація. */
    private suspend fun ipc(count: Int) {
        val e = startedEngine() ?: return
        val warm = if (count < 20) 100 else count
        repeat(20) { e.bestMove(request(Strength.Full, SearchLimit(nodes = 1))) }
        val samples = ArrayList<Double>(warm)
        repeat(warm) {
            val t = SystemClock.elapsedRealtimeNanos()
            e.bestMove(request(Strength.Full, SearchLimit(nodes = 1)))
            samples += ms(t)
        }
        samples.sort()
        result(
            "ipc",
            "n" to samples.size,
            "p50_ms" to "%.2f".format(samples[samples.size / 2]),
            "p95_ms" to "%.2f".format(samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)]),
            "max_ms" to "%.2f".format(samples.last()),
        )
        e.stop()
    }

    private suspend fun analyse(repeat: Int) {
        for (hash in listOf(16, 64)) {
            for (threads in listOf(1, 2, 4)) {
                repeat(repeat) { i -> analyseOnce(threads, hash, i) }
            }
        }
        engine.stop()
    }

    private suspend fun analyseOnce(threads: Int, hash: Int, i: Int) {
        val e = engine
        e.stop()
        if (e.start(EngineConfig(threads, hash)) !is ChessResult.Ok) {
            log("start failed")
            return
        }
        var updates = 0
        var lastNps = 0L
        var maxGapMs = 0.0
        var lastAt = SystemClock.elapsedRealtimeNanos()
        val begin = lastAt
        var depth = 0
        var finished = false
        e.analyse(AnalysisRequest(middlegame, emptyList(), SearchLimit(moveTimeMs = 10_000))).collect { event ->
            when (event) {
                is AnalysisEvent.Update -> {
                    val now = SystemClock.elapsedRealtimeNanos()
                    maxGapMs = maxOf(maxGapMs, (now - lastAt) / 1e6)
                    lastAt = now
                    updates++
                    lastNps = event.update.nps
                    depth = event.update.depth
                }
                AnalysisEvent.Finished -> finished = true
                is AnalysisEvent.Failed -> log("analyse failed ${event.error}")
            }
        }
        val seconds = ms(begin) / 1000.0
        result(
            "analyse", "threads" to threads, "hash" to hash, "i" to i,
            "updates" to updates, "updates_per_s" to "%.1f".format(updates / seconds),
            "max_gap_ms" to "%.0f".format(maxGapMs), "nps_last" to lastNps, "depth" to depth, "finished" to finished,
            "wall_s" to "%.1f".format(seconds),
        )
    }

    /** Тримає запущений рушій, щоб скрипт зняв `dumpsys meminfo` обох процесів. */
    private suspend fun memory() {
        val e = startedEngine(EngineConfig(1, 16)) ?: return
        e.analyse(AnalysisRequest(middlegame, emptyList(), SearchLimit(moveTimeMs = 5000))).collect { }
        log("MEMORY_READY pid=${android.os.Process.myPid()}")
        delay(40_000)
        log("MEMORY_DONE")
        e.stop()
    }

    /**
     * Навмисний збій нативного коду. У режимі remote застосунок має вижити; в inproc процес помре
     * (тоді скрипт бачить обрив logcat і PID, що зник).
     */
    private suspend fun crash() {
        val e = startedEngine() ?: return
        log("CRASH_ABOUT_TO pid=${android.os.Process.myPid()} mode=$mode")
        e.crashNativeForTest()
        val failedAfter = SystemClock.elapsedRealtimeNanos()
        val state = try {
            withTimeout(10_000) { e.state.first { it is EngineState.Failed } }
        } catch (t: Throwable) {
            e.state.value
        }
        val detectMs = ms(failedAfter)
        val t = SystemClock.elapsedRealtimeNanos()
        val restarted = e.start(EngineConfig())
        val restartMs = ms(t)
        val move = if (restarted is ChessResult.Ok) e.bestMove(request(Strength.Full, SearchLimit(depth = 6))) else null
        result(
            "crash", "state_after" to state, "detect_ms" to "%.0f".format(detectMs),
            "restart" to (restarted is ChessResult.Ok), "restart_ms" to "%.0f".format(restartMs),
            "move_ok" to (move is ChessResult.Ok), "pid" to android.os.Process.myPid(),
        )
        e.stop()
    }

    /** Після повернення з фону: чи рушій живий, а якщо ні, чи `start()` відновлює його без дій користувача. */
    private suspend fun backgroundCheck() {
        val e = engine
        val before = e.state.value
        var recovered = false
        var restartMs = 0.0
        var move = e.bestMove(request(Strength.Full, SearchLimit(depth = 6)))
        if (move !is ChessResult.Ok) {
            val t = SystemClock.elapsedRealtimeNanos()
            recovered = e.start(EngineConfig()) is ChessResult.Ok
            restartMs = ms(t)
            move = e.bestMove(request(Strength.Full, SearchLimit(depth = 6)))
        }
        result(
            "bgcheck", "state_before" to before, "first_move_ok" to (!recovered && move is ChessResult.Ok),
            "needed_restart" to recovered, "restart_ms" to "%.0f".format(restartMs),
            "final_move_ok" to (move is ChessResult.Ok), "state_after" to e.state.value,
        )
    }

    /** Запускає рушій для сценарію background: далі скрипт відправляє застосунок у фон. */
    private suspend fun prepareBackground() {
        val e = startedEngine()
        log("BG_READY state=${e?.state?.value}")
    }

    private companion object {
        const val TAG = "EnginePrototype"
    }
}

