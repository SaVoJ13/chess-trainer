// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine.debug

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import org.chesstrainer.core.engine.AnalysisEvent
import org.chesstrainer.core.engine.AnalysisRequest
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.engine.EngineStats
import org.chesstrainer.engine.JniTransport
import org.chesstrainer.engine.RemoteTransport
import org.chesstrainer.engine.UciEngineService
import org.chesstrainer.engine.UciTransport

/**
 * G2 (PSS після `stop()`) і G3 (потік `analyse`: лічильники й затримка) з CONTRACTS 3.4 v0.8.
 * Рядки `RESULT ...` і мітки `MEM_*` у logcat, теґ `EnginePrototype`; PSS знімає скрипт хоста за мітками.
 */
class MeasureScenarios(private val context: Context, private val mode: String, private val forceBaseline: Boolean) {
    private val rules = ChesslibRules()
    private val middlegame = Fen("r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P1B2/2PBPN2/PP1N1PPP/R2QK2R w KQ - 0 8")

    private fun transport(): UciTransport =
        if (mode == "remote") RemoteTransport(context, forceBaseline) else JniTransport(context) { forceBaseline }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun result(scenario: String, vararg values: Pair<String, Any?>) {
        log("RESULT scenario=$scenario mode=$mode " + values.joinToString(" ") { "${it.first}=${it.second}" })
    }

    private fun ownPssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    /** G3: `analyse` 10 с, MultiPV 1 і 5, [repeat] повторів у кожній конфігурації. */
    suspend fun stream(repeat: Int) {
        val engine = UciEngineService(transport(), rules)
        for (multiPv in listOf(1, 5)) {
            repeat(repeat) { i ->
                engine.stop()
                if (engine.start(EngineConfig(1, 16)) !is ChessResult.Ok) {
                    log("start failed")
                    return
                }
                EngineStats.reset()
                var consumed = 0L
                var finished = false
                val begin = SystemClock.elapsedRealtimeNanos()
                engine.analyse(AnalysisRequest(middlegame, emptyList(), SearchLimit(moveTimeMs = 10_000), multiPv)).collect {
                    if (it is AnalysisEvent.Update) consumed++
                    if (it is AnalysisEvent.Finished) finished = true
                }
                val seconds = (SystemClock.elapsedRealtimeNanos() - begin) / 1e9
                val emitted = engine.emittedInfoLinesForTest()
                val s = EngineStats.snapshot()
                val latencies = s.latenciesNs.sorted()
                fun pct(p: Int) = if (latencies.isEmpty()) -1.0 else latencies[(latencies.size * p / 100).coerceAtMost(latencies.size - 1)] / 1e6
                val lossless = emitted == s.received && s.received == s.parsed + s.skipped &&
                    s.parsed == consumed && latencies.size.toLong() == consumed
                result(
                    "stream", "multipv" to multiPv, "i" to i, "finished" to finished,
                    "engine_emitted" to emitted, "app_received" to s.received, "parsed" to s.parsed, "skipped_by_contract" to s.skipped,
                    "consumer_received" to consumed, "lossless" to lossless,
                    "per_s" to "%.1f".format(consumed / seconds),
                    "lat_p50_ms" to "%.2f".format(pct(50)), "lat_p95_ms" to "%.2f".format(pct(95)), "lat_max_ms" to "%.2f".format(pct(100)),
                )
            }
        }
        engine.stop()
    }

    /** G2: PSS до запуску, під час роботи рушія і після `stop()`; знімки робить скрипт хоста за мітками `MEM_*`. */
    suspend fun memoryAfterStop(rounds: Int) {
        val engine = UciEngineService(transport(), rules)
        log("MEM_BASE pid=${android.os.Process.myPid()} own_pss_kb=${ownPssKb()}")
        delay(SETTLE_MS)
        repeat(rounds) { round ->
            engine.start(EngineConfig(1, 16))
            engine.analyse(AnalysisRequest(middlegame, emptyList(), SearchLimit(moveTimeMs = 5000))).collect { }
            log("MEM_RUNNING round=$round own_pss_kb=${ownPssKb()}")
            delay(SETTLE_MS)
            engine.stop()
            log("MEM_STOPPED round=$round own_pss_kb=${ownPssKb()}")
            delay(SETTLE_MS)
        }
        log("MEM_END")
    }


    private fun batteryTempC(): Double {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        return (intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    }

    /**
     * Чистий порівняльний вимір `armv8` проти `armv8-dotprod` (одна серія, чергування порядку ABBA…).
     * Бібліотеку вибирає процес, тому кожне випробування це новий `:engine` (режим remote) з потрібним прапорцем.
     * Робота фіксована: один потік, `Hash 16`, `go nodes N`; пошук детермінований, тож порівнюється час і nps.
     */
    suspend fun dotprodCompare(pairs: Int, nodes: Long = 4_000_000, pauseMs: Long = 10_000) {
        val dot = ArrayList<Double>()
        val base = ArrayList<Double>()
        val dotNps = ArrayList<Long>()
        val baseNps = ArrayList<Long>()
        repeat(pairs) { pair ->
            val order = if (pair % 2 == 0) listOf(false, true) else listOf(true, false)   // true = baseline armv8
            for (baseline in order) {
                val engine = UciEngineService(RemoteTransport(context, baseline), rules)
                val temp = batteryTempC()
                if (engine.start(EngineConfig(1, 16)) !is ChessResult.Ok) {
                    log("start failed")
                    return
                }
                var lastNps = 0L
                var lastNodes = 0L
                val t = SystemClock.elapsedRealtimeNanos()
                engine.analyse(AnalysisRequest(middlegame, emptyList(), SearchLimit(nodes = nodes), 1)).collect {
                    if (it is AnalysisEvent.Update) {
                        lastNps = it.update.nps
                        lastNodes = it.update.nodes
                    }
                }
                val seconds = (SystemClock.elapsedRealtimeNanos() - t) / 1e9
                engine.stop()
                (if (baseline) base else dot) += seconds
                (if (baseline) baseNps else dotNps) += lastNps
                result(
                    "dotcmp", "pair" to pair, "lib" to (if (baseline) "armv8" else "armv8_dotprod"),
                    "wall_s" to "%.2f".format(seconds), "nodes" to lastNodes, "nps_last" to lastNps, "battery_c" to temp,
                )
                delay(pauseMs)
            }
        }
        fun med(v: List<Double>) = v.sorted()[v.size / 2]
        fun medL(v: List<Long>) = v.sorted()[v.size / 2]
        result(
            "dotcmp-summary", "pairs" to pairs, "nodes" to nodes,
            "dotprod_wall_median_s" to "%.2f".format(med(dot)), "dotprod_wall_max_s" to "%.2f".format(dot.max()),
            "armv8_wall_median_s" to "%.2f".format(med(base)), "armv8_wall_max_s" to "%.2f".format(base.max()),
            "dotprod_nps_median" to medL(dotNps), "armv8_nps_median" to medL(baseNps),
            "speedup_by_median_wall" to "%.3f".format(med(base) / med(dot)),
        )
    }

    private companion object {
        const val TAG = "EnginePrototype"
        const val SETTLE_MS = 6_000L
    }
}
