// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.calibration

import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.chesstrainer.core.engine.DefaultDifficultyPresets
import org.chesstrainer.core.engine.DifficultyIds
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.HandicapEngineService
import org.chesstrainer.core.engine.HandicapPolicy
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.game.DefaultChessGameFactory
import org.chesstrainer.core.game.GameStatus
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci

/**
 * ТІЛЬКИ ІНСТРУМЕНТ НА ПК + телефон (CONTRACTS 3.5 п. 8–9): матчі калібрування Handicap. Не входить у `build`,
 * `test` і CI. Stockfish `sf_19`, зібраний NDK (`build-stockfish-android.sh`), працює на телефоні через `adb shell`.
 * Запуск: `./gradlew :core-chess:api:handicapMatch --args="<команда> ..."`, докладніше `core-chess/api/HANDICAP.md`.
 *
 * Команди:
 *  - `nodes`: перераховує час пошуку пресета `UCI_Elo` (мс) у вузли за вимірами на телефоні; результат у
 *    `.calibration/matches/elo_nodes.txt`;
 *  - `run --dir NAME [--games N] [--workers W] [--seed S] [--pairs 1-2,2-3,3-4,4-5,E-5] [--max-plies P]`:
 *    матчі; кожна партія пишеться в `.calibration/matches/NAME/results.tsv` одразу, повторний запуск з тим самим
 *    `--dir` продовжує (зіграні партії пропускає); збій телефона/adb не завершує процес, а чекає й відновлює;
 *  - `report --dir NAME`: таблиця пар з результатів у `NAME/report.md`.
 *
 * Ліміти: кроки Handicap це **глибина** з `DefaultDifficultyPresets` (детермінована, не залежить від нагріву),
 * `UCI_Elo` це вузли (час пресета, перерахований командою `nodes`). Решта (Threads = 1, Hash = 16, `ucinewgame`
 * перед кожним пошуком) як у виміру метрики.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: error("Команда: nodes | run | report")
    val opts = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val env = CalibrationEnv(CalibrationEnv.findRoot())
    try {
        when (command) {
            "nodes" -> MatchTool.eloNodes(env, opts["elo"]?.toInt() ?: 1320)
            "run" -> MatchTool.run(env, opts)
            "report" -> println(MatchTool.report(MatchTool.runDir(env, opts)))
            else -> error("Невідома команда $command")
        }
    } finally {
        env.close()
    }
}

/** Учасник: `H1..H5` (крок Handicap), `E1320`/`E1500`/`E1700` (`UCI_Elo`; `E` = `E1320`), `R` (випадковий хід). */
private fun sideName(side: String) = when {
    side == "E" -> "UCI_Elo 1320"
    side == "E22" -> "UCI_Elo ${DefaultDifficultyPresets.ELO_22_NOMINAL} (id 22)"
    side.startsWith("E") -> "UCI_Elo ${side.substring(1)}"
    side == "R" -> "випадковий"
    side == "F" -> "Full"
    else -> "Крок ${side.substring(1, 2)}"
}

/** Рівні `UCI_Elo`, доступні в матчах: значення -> `DifficultyId` пресета. */
private val ELO_SIDES = linkedMapOf(
    1320 to DifficultyIds.ELO_MIN, 1500 to DifficultyIds.ELO_1500, 1700 to DifficultyIds.ELO_1700,
    1900 to DifficultyIds.ELO_1900, 2100 to DifficultyIds.ELO_2100,
    DefaultDifficultyPresets.ELO_22_NOMINAL to DifficultyIds.ELO_22, 2400 to DifficultyIds.ELO_2400, 2700 to DifficultyIds.ELO_2700,
)

private fun eloOf(side: String): Int? = when {
    side == "E" -> 1320
    side == "E22" -> DefaultDifficultyPresets.ELO_22_NOMINAL
    side.startsWith("E") -> side.substring(1).toIntOrNull()
    else -> null
}

/** Пара: (очікувано сильніший, слабший). Правило сесій 18–19: 1–2, 2–3, 3–4, 4–5 і «крок 5 проти мін. UCI_Elo». */
private val ALL_PAIRS: Map<String, Pair<String, String>> = linkedMapOf(
    "1-2" to ("H2" to "H1"),
    "2-3" to ("H3" to "H2"),
    "3-4" to ("H4" to "H3"),
    "4-5" to ("H5" to "H4"),
    "E-5" to ("E" to "H5"),
    // Контрольні (діагностика інструмента): R = рівномірно випадковий легальний хід; H5b = другий екземпляр кроку 5.
    "5-R" to ("H5" to "R"),
    "1-R" to ("H1" to "R"),
    "5-5" to ("H5" to "H5b"),
    "5-1" to ("H5" to "H1"),
    // Матчі-локатори (CONTRACTS v0.19, 3.5 п. 8): хто сильніший, заздалегідь невідомо; перша сторона лише мітка,
    // очки рахуються за міткою сторони, без порогу.
    "E1320-1" to ("E1320" to "H1"),
    "E1320-2" to ("E1320" to "H2"),
    "E1320-3" to ("E1320" to "H3"),
    "E1320-4" to ("E1320" to "H4"),
    "E1500-5" to ("E1500" to "H5"),
    "E1700-5" to ("E1700" to "H5"),
    "E1500-E1320" to ("E1500" to "E1320"),
    // Локатори вище 1700 (CONTRACTS v0.20, рішення Архітектора сесії 21) і пара 1500–1700 для нової драбини.
    "E1700-E1500" to ("E1700" to "E1500"),
    "E1900-E1700" to ("E1900" to "E1700"),
    "E2100-E1900" to ("E2100" to "E1900"),
    // Верхні пари (CONTRACTS v0.22, 3.5 п. 8): `F` = Full (ліміт часу пресета), `E2400`/`E2700` на вузлах.
    "E2400-E2100" to ("E2400" to "E2100"),
    "E2700-E2400" to ("E2700" to "E2400"),
    "F-E2700" to ("F" to "E2700"),
    // Рівень id 22 (CONTRACTS v0.23): `E22` = UCI_Elo ELO_22_NOMINAL на вузлах.
    "E22-E2100" to ("E22" to "E2100"),
    "E2400-E22" to ("E2400" to "E22"),
)

private val LOCATOR_PAIRS = setOf(
    "E1320-1", "E1320-2", "E1320-3", "E1320-4", "E1500-5", "E1700-5", "E1500-E1320", "E1700-E1500", "E1900-E1700", "E2100-E1900",
    "E2400-E2100", "E2700-E2400", "F-E2700", "E22-E2100", "E2400-E22",
)

/**
 * Набір дебютів для матчів: типово 24 дебюти виміру ([Openings.lines]); `--openings confirm` це інші 24 дебюти
 * ([Openings.confirm]) для підтвердного прогону (CONTRACTS v0.22, 3.5 п. 8).
 */
@Volatile private var openingSetName = "main"
/** Рівень `Full` (ліміт часу пресета, не вузли: CONTRACTS v0.22, 3.5 п. 8) для пар із стороною `F`. */
@Volatile private var activeFull: DifficultyLevel? = null
private fun activeOpenings(): List<String> = if (openingSetName == "confirm") Openings.confirm else Openings.lines

private val DEFAULT_PAIRS = listOf("1-2", "2-3", "3-4", "4-5", "E-5")

/** Файл трасування ходів (діагностика): сторона, колір, кандидати, вибір. `null` = вимкнено. */
@Volatile private var traceFile: File? = null
@Volatile private var traceGames = 0

private data class GameRecord(
    val pair: String, val game: Int, val opening: Int, val white: String, val outcome: String, val reason: String,
    val plies: Int, val tempC: String, val moves: String,
) {
    /** Очки очікувано сильнішого (1, 0.5, 0). */
    fun strongerScore(strong: String): Double = when (outcome) {
        "D" -> 0.5
        "W" -> if (white == strong) 1.0 else 0.0
        else -> if (white == strong) 0.0 else 1.0
    }

    fun line() = listOf(pair, game, opening, white, outcome, reason, plies, tempC, moves).joinToString("\t")

    companion object {
        fun parse(line: String): GameRecord? {
            val f = line.split('\t')
            if (f.size < 9) return null // обірваний останній рядок (переривання під час запису) ігнорується
            return GameRecord(f[0], f[1].toInt(), f[2].toInt(), f[3], f[4], f[5], f[6].toInt(), f[7], f[8])
        }
    }
}

internal object MatchTool {
    private const val DEFAULT_GAMES = 144 // 3 × 48 (24 дебюти × 2 кольори): кожен дебют рівно по 3 рази кожним кольором
    private const val NODE_SAMPLES = 60

    private val matchesDir = { env: CalibrationEnv -> File(env.dir, "matches").also { it.mkdirs() } }

    fun runDir(env: CalibrationEnv, opts: Map<String, String>): File =
        File(matchesDir(env), opts["dir"] ?: error("Потрібен --dir NAME")).also { it.mkdirs() }

    // ---------------------------------------------------------------- телефон

    private fun adb(env: CalibrationEnv, vararg args: String, timeoutSec: Long = 20): String? = runCatching {
        val p = ProcessBuilder(listOf(env.launch[0], "-s", env.launch[2]) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out
    }.getOrNull()

    private fun deviceOnline(env: CalibrationEnv): Boolean = runCatching {
        val p = ProcessBuilder(env.launch[0], "devices").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(15, TimeUnit.SECONDS)
        out.lines().any { it.startsWith(env.launch[2] + "\t") && it.endsWith("\tdevice") }
    }.getOrDefault(false)

    /** `true`, якщо екран увімкнений і розблокований. `null`, якщо телефон не відповів. */
    private fun unlocked(env: CalibrationEnv): Boolean? {
        val out = adb(env, "shell", "dumpsys power | grep mWakefulness=; dumpsys window | grep isKeyguardShowing") ?: return null
        return "mWakefulness=Awake" in out && "isKeyguardShowing=false" in out
    }

    private class Battery(val tempC: Double, val level: Int, val charging: Boolean)

    private fun battery(env: CalibrationEnv): Battery? {
        val out = adb(env, "shell", "dumpsys battery") ?: return null
        fun field(name: String) = Regex("^\\s*$name: (-?\\d+)", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toInt()
        val t = field("temperature") ?: return null
        val usb = Regex("USB powered: true").containsMatchIn(out) || Regex("AC powered: true").containsMatchIn(out)
        return Battery(t / 10.0, field("level") ?: -1, usb)
    }

    private fun lockReminder(env: CalibrationEnv, waitSec: Long) {
        val deadline = System.currentTimeMillis() + waitSec * 1000
        var shown = false
        while (true) {
            when (unlocked(env)) {
                true -> { if (shown) println("Телефон розблоковано, продовжуємо."); return }
                else -> {
                    if (!shown || System.currentTimeMillis() % 60_000 < 5_000) {
                        println(">>> НАГАДУВАННЯ: розблокуйте телефон (екран увімкнений, без блокування) і тримайте його підключеним: " +
                            "на заблокованому екрані ОС може обмежувати процеси adb.")
                    }
                    shown = true
                }
            }
            if (System.currentTimeMillis() > deadline) {
                println(">>> Телефон досі заблоковано або не відповідає; продовжуємо (результати не залежать від нагріву: ліміти в глибині/вузлах).")
                return
            }
            Thread.sleep(5_000)
        }
    }

    private fun waitForDevice(env: CalibrationEnv, why: String) {
        println(">>> Втрата зв'язку з телефоном/adb ($why). Чекаємо й відновлюємо (завершення не буде).")
        var n = 0
        while (!deviceOnline(env)) {
            if (n % 6 == 0) {
                println("    телефона немає (${n * 10} с), перевірте кабель/налагодження USB")
                runCatching { ProcessBuilder(env.launch[0], "start-server").start().waitFor(15, TimeUnit.SECONDS) }
            }
            n++
            Thread.sleep(10_000)
        }
        println("    телефон повернувся.")
    }

    // ---------------------------------------------------------------- вузли для UCI_Elo

    private fun nodesFile(env: CalibrationEnv) = File(matchesDir(env), "elo_nodes.txt")

    fun eloNodes(env: CalibrationEnv, elo: Int) {
        lockReminder(env, 60)
        val presets = DefaultDifficultyPresets()
        val engine = env.newEngine(AnalysisCache(null, retain = false))
        val level0 = presets.byId(ELO_SIDES[elo] ?: error("Немає пресета UCI_Elo $elo (є ${ELO_SIDES.keys})"), engine.capabilities!!)!!
        // Пресет з вузлами має стелю 3 × час пресета; вимір вузлів іде за часом пресета без ліміту вузлів.
        val ms = (level0.limit.moveTimeMs ?: error("пресет UCI_Elo без ліміту в часі")).let { if (level0.limit.nodes != null) it / 3 else it }
        val level = level0.copy(limit = SearchLimit(moveTimeMs = ms))
        val games = Games(env).load()
        val fens = games.flatMap { g -> (10..minOf(50, g.moves.size - 1) step 2).map { Games(env).fenAt(g, it) } }
            .distinct().let { list -> val r = Random(20261101); list.shuffled(r).take(NODE_SAMPLES) }
        check(fens.size >= NODE_SAMPLES) { "Замало позицій з самогри (запустіть prepare)" }
        val b0 = battery(env)
        val nodes = fens.map { fen ->
            runBlocking { engine.bestMove(MoveRequest(Fen(fen), emptyList(), level)) }
            engine.lastNodes
        }.sorted()
        val b1 = battery(env)
        val median = nodes[nodes.size / 2]
        val mean = nodes.average().toLong()
        val text = "elo=${(level.strength as org.chesstrainer.core.engine.Strength.Elo).elo} moveTimeMs=$ms samples=${nodes.size} " +
            "min=${nodes.first()} median=$median mean=$mean max=${nodes.last()} nps_median=${median * 1000 / ms} " +
            "temp_before=${b0?.tempC} temp_after=${b1?.tempC} nodesLimit=$median"
        println(text)
        val kept = nodesFile(env).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() && !it.startsWith("elo=$elo ") }.orEmpty()
        nodesFile(env).writeText((kept + text).joinToString("\n") + "\n")
        println("Записано ${nodesFile(env)}. Ліміт для матчів: $median вузлів (медіана вузлів за $ms мс).")
    }

    private fun eloNodesLimit(env: CalibrationEnv, elo: Int): Long {
        val f = nodesFile(env)
        check(f.isFile) { "Немає ${f.path}: спершу команда `nodes --elo $elo`" }
        val line = f.readLines().firstOrNull { it.startsWith("elo=$elo ") } ?: error("Немає вузлів для UCI_Elo $elo: спершу `nodes --elo $elo`")
        return line.substringAfter("nodesLimit=").trim().toLong()
    }

    // ---------------------------------------------------------------- матчі

    fun run(env: CalibrationEnv, opts: Map<String, String>) {
        val dir = runDir(env, opts)
        val games = opts["games"]?.toInt() ?: DEFAULT_GAMES
        val workers = opts["workers"]?.toInt() ?: 4
        val seed = opts["seed"]?.toLong() ?: 20261101L
        val maxPlies = opts["max-plies"]?.toInt() ?: 300
        opts["openings"]?.let { require(it == "main" || it == "confirm") { "--openings main|confirm" }; openingSetName = it }
        opts["trace"]?.let { traceFile = File(dir, "trace.tsv"); traceGames = it.toInt() }
        val pairKeys = (opts["pairs"] ?: DEFAULT_PAIRS.joinToString(",")).split(',').map { it.trim() }
        pairKeys.forEach { require(it in ALL_PAIRS) { "Невідома пара $it (є ${ALL_PAIRS.keys})" } }
        val usedElos = pairKeys.flatMap { ALL_PAIRS.getValue(it).toList() }.mapNotNull { eloOf(it) }.distinct().sorted()
        val nodesByElo = usedElos.associateWith { eloNodesLimit(env, it) }
        val presets = DefaultDifficultyPresets()
        val probe = env.newEngine(AnalysisCache(null, retain = false))
        val caps = probe.capabilities!!
        val eloLevels = usedElos.associateWith { elo ->
            presets.byId(ELO_SIDES.getValue(elo), caps)!!.let { it.copy(limit = SearchLimit(nodes = nodesByElo.getValue(elo))) }
        }
        val handicapLevels = (1..HandicapPolicy.STEPS).associate { "H$it" to presets.byId(DifficultyIds.all[it - 1], caps)!! }
        val fullLevel = presets.byId(DifficultyIds.FULL, caps)!!
        activeFull = fullLevel
        runBlocking { probe.stop() }

        // Конфіг: повторний запуск з іншими параметрами не змішує результати. `games` можна збільшувати.
        val config = buildString {
            appendLine("engine=${AnalysisCache.ENGINE_TAG}")
            appendLine("seed=$seed")
            appendLine("maxPlies=$maxPlies")
            nodesByElo.forEach { (elo, n) -> appendLine("eloNodes=$n eloUci=$elo") }
            handicapLevels.forEach { (k, l) ->
                appendLine("$k=${HandicapPolicy.profile(k.substring(1).toInt())} limit=${l.limit}")
            }
            appendLine("openings=${activeOpenings().size}" + if (openingSetName == "main") "" else " set=$openingSetName")
            if (pairKeys.any { "F" in ALL_PAIRS.getValue(it).toList() }) appendLine("F=${fullLevel.strength} limit=${fullLevel.limit}")
        }
        val cfgFile = File(dir, "config.txt")
        if (cfgFile.isFile) check(cfgFile.readText() == config) {
            "Конфігурація ${cfgFile.path} відрізняється від поточної; використайте інший --dir.\nБуло:\n${cfgFile.readText()}\nЄ:\n$config"
        } else cfgFile.writeText(config)

        val resultsFile = File(dir, "results.tsv")
        val done = HashSet<Pair<String, Int>>()
        val resultLock = Any()
        if (resultsFile.isFile) resultsFile.readLines().mapNotNull(GameRecord::parse).forEach { done += it.pair to it.game }
        // Черга: за номером партії, потім за парою (часткові результати лишаються збалансованими між парами).
        val queue = ArrayDeque<Pair<String, Int>>()
        for (g in 0 until games) for (p in pairKeys) if ((p to g) !in done) queue += p to g
        val total = pairKeys.size * games
        println("Матчі: ${pairKeys.size} пар × $games партій; зіграно раніше ${done.size}, лишилось ${queue.size}. Каталог: $dir")
        if (queue.isEmpty()) { println(report(dir)); return }

        lockReminder(env, 120)
        val batteryLog = File(dir, "battery.log")
        val progress = AtomicLong(done.size.toLong())
        val started = System.currentTimeMillis()
        val lastBatteryRef = java.util.concurrent.atomic.AtomicReference<Battery?>(null)
        val monitor = Thread {
            while (!Thread.currentThread().isInterrupted) {
                battery(env)?.let { b ->
                    lastBatteryRef.set(b)
                    batteryLog.appendText("${java.time.Instant.now()}\t${b.tempC}\t${b.level}\t${if (b.charging) "ext" else "bat"}\n")
                }
                memLog(dir)
                try { Thread.sleep(60_000) } catch (e: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true; it.start() }

        val factory = DefaultChessGameFactory(env.rules)
        val activity = Array(workers) { AtomicLong(System.currentTimeMillis()) }
        val engines = arrayOfNulls<UciProcessEngine>(workers)
        // Сторож: хід триває мілісекунди чи секунди; 120 с без відповіді означає зависання adb/рушія.
        val watchdog = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(10_000) } catch (e: InterruptedException) { return@Thread }
                for (w in 0 until workers) {
                    if (System.currentTimeMillis() - activity[w].get() > 120_000) {
                        engines[w]?.let { println(">>> сторож: робітник $w без відповіді 120 с, обриваємо процес"); it.kill() }
                        activity[w].set(System.currentTimeMillis())
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        val pool = Executors.newFixedThreadPool(workers)
        val futures = (0 until workers).map { w ->
            pool.submit {
                var engine: UciProcessEngine? = null
                var failures = 0
                while (true) {
                    val task = synchronized(queue) { queue.pollFirst() } ?: break
                    val (pairKey, g) = task
                    val (strong, weak) = ALL_PAIRS.getValue(pairKey)
                    while (true) {
                        try {
                            val e = engine ?: env.newEngine(AnalysisCache(null, retain = false)).also { engine = it; engines[w] = it }
                            val rec = playGame(
                                env, factory, e, pairKey, g, strong, weak, handicapLevels, eloLevels, seed, maxPlies,
                                lastBatteryRef.get()?.tempC?.toString() ?: "?", activity[w],
                            )
                            synchronized(resultLock) { resultsFile.appendText(rec.line() + "\n") }
                            val n = progress.incrementAndGet()
                            val sec = (System.currentTimeMillis() - started) / 1000
                            println("  [$n/$total, ${sec}s] пара $pairKey партія $g: ${rec.outcome} (${rec.reason}, ${rec.plies} пн), T=${rec.tempC}°C")
                            failures = 0
                            break
                        } catch (ex: Exception) {
                            println(">>> збій партії $pairKey/$g (робітник $w): ${ex.javaClass.simpleName}: ${ex.message}")
                            engine?.let { old -> runCatching { runBlocking { old.stop() } } }
                            engine = null; engines[w] = null
                            if (!deviceOnline(env)) { waitForDevice(env, ex.message ?: ex.javaClass.simpleName); failures = 0 }
                            else if (++failures > 6) throw IllegalStateException("Партія $pairKey/$g не грається після $failures спроб", ex)
                            else Thread.sleep(3_000L * failures)
                        }
                    }
                }
                engine?.let { runCatching { runBlocking { it.stop() } } }
            }
        }
        try {
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow(); monitor.interrupt(); watchdog.interrupt()
        }
        memLog(dir)
        println(memSummary(dir))
        println(report(dir))
    }

    /** Пам'ять процесу JVM (перевірка виправлення OOM): пікова купа за MXBean і пікова робоча пам'ять процесу (Windows). */
    private fun memLog(dir: File) {
        val rt = Runtime.getRuntime()
        val heapPeak = java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used }
        val ws = runCatching {
            val pid = ProcessHandle.current().pid()
            val p = ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Process -Id $pid).PeakWorkingSet64")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(20, TimeUnit.SECONDS)
            out.toLongOrNull()
        }.getOrNull()
        File(dir, "mem.log").appendText(
            "${java.time.Instant.now()}\theap_used_mb=${(rt.totalMemory() - rt.freeMemory()) shr 20}\theap_peak_mb=${heapPeak shr 20}" +
                "\theap_max_mb=${rt.maxMemory() shr 20}\tpeak_working_set_mb=${ws?.let { it shr 20 } ?: "?"}\n",
        )
    }

    private fun memSummary(dir: File): String {
        val lines = File(dir, "mem.log").takeIf { it.isFile }?.readLines().orEmpty()
        fun max(key: String) = lines.mapNotNull { l -> Regex("$key=(\\d+)").find(l)?.groupValues?.get(1)?.toLong() }.maxOrNull()
        return "Пам'ять JVM: пікова купа ${max("heap_peak_mb")} МБ (макс. ${max("heap_max_mb")} МБ), " +
            "пікова робоча пам'ять процесу ${max("peak_working_set_mb")} МБ; журнал mem.log"
    }

    private fun playGame(
        env: CalibrationEnv, factory: DefaultChessGameFactory, engine: UciProcessEngine, pairKey: String, g: Int,
        strong: String, weak: String, handicapLevels: Map<String, DifficultyLevel>, eloLevels: Map<Int, DifficultyLevel>,
        seed: Long, maxPlies: Int, tempC: String, activity: AtomicLong,
    ): GameRecord {
        val opening = (g / 2) % activeOpenings().size // пари партій (g, g+1) грають той самий дебют зі зміною кольорів
        val strongWhite = g % 2 == 0
        val pairIndex = ALL_PAIRS.keys.indexOf(pairKey)
        fun rnd(slot: Int) = Random(seed * 1_000_003 + pairIndex * 100_003L + g * 2L + slot)
        val tracing = traceFile != null && g < traceGames
        val tracer = TraceEngine(engine)
        fun mover(side: String, slot: Int): (List<Uci>) -> Uci = when (side) {
            "E", "E1320", "E1500", "E1700", "E1900", "E2100", "E22", "E2400", "E2700", "F" -> { moves ->
                val eloLevel = if (side == "F") checkNotNull(activeFull) else eloLevels.getValue(eloOf(side)!!)
                val r = runBlocking { engine.bestMove(MoveRequest(Fen(CalibrationEnv.START_FEN), moves, eloLevel)) }
                (r as? ChessResult.Ok)?.value?.best ?: error("UCI_Elo: ${(r as ChessResult.Err).error}")
            }
            "R" -> {
                val random = rnd(slot)
                ({ moves ->
                    var p = env.rules.startPosition()
                    for (m in moves) p = (env.rules.apply(p, (env.rules.parseUci(p, m.value) as ChessResult.Ok).value) as ChessResult.Ok).value
                    val legal = env.rules.legalMoves(p).map { env.rules.toUci(it) }.sortedBy { it.value }
                    legal[random.nextInt(legal.size)]
                })
            }
            else -> {
                // "H5b" це другий екземпляр кроку 5 (з власним Random); рівень береться за перших двома символами.
                val service = HandicapEngineService(tracer, env.rules, rnd(slot))
                runBlocking { service.start() }
                val level = handicapLevels.getValue(side.take(2))
                ({ moves ->
                    tracer.clear()
                    val r = runBlocking { service.bestMove(MoveRequest(Fen(CalibrationEnv.START_FEN), moves, level)) }
                    (r as? ChessResult.Ok)?.value?.best ?: error("$side: ${(r as ChessResult.Err).error}")
                })
            }
        }
        val whiteSide = if (strongWhite) strong else weak
        val blackSide = if (strongWhite) weak else strong
        val white = mover(whiteSide, 0)
        val black = mover(blackSide, 1)

        runBlocking { engine.newGame() }
        var game: ChessGame = factory.new(null)
        val played = ArrayList<Uci>()
        fun apply(uci: Uci) {
            val move = (env.rules.parseUci(game.position, uci.value) as? ChessResult.Ok)?.value
                ?: error("Нелегальний хід ${uci.value} у ${game.position.fen.value}")
            game = (game.play(move) as? ChessResult.Ok)?.value ?: error("Партію завершено перед ходом ${uci.value}")
            played += uci
        }
        activeOpenings()[opening].split(' ').forEach { apply(Uci(it)) }

        var outcome: String? = null
        var reason = ""
        while (outcome == null) {
            when (val s = game.status) {
                GameStatus.Ongoing -> Unit
                is GameStatus.Checkmate -> { outcome = if (s.winner == Color.WHITE) "W" else "B"; reason = "mate"; break }
                GameStatus.Stalemate -> { outcome = "D"; reason = "stalemate"; break }
                GameStatus.InsufficientMaterial -> { outcome = "D"; reason = "material"; break }
                is GameStatus.DrawClaimable -> { outcome = "D"; reason = s.reasons.joinToString("+") { it.name.lowercase() }; break }
                is GameStatus.DrawAutomatic -> { outcome = "D"; reason = s.reason.name.lowercase(); break }
            }
            if (played.size >= maxPlies) { outcome = "D"; reason = "maxplies"; break }
            val toMove = if (game.position.sideToMove == Color.WHITE) white else black
            activity.set(System.currentTimeMillis())
            val moverColor = game.position.sideToMove
            val moverSide = if (moverColor == Color.WHITE) whiteSide else blackSide
            val chosen = toMove(played.toList())
            if (tracing) {
                val cands = tracer.candidates(moverColor).joinToString(" ") { "${it.first}:${it.second}" }
                val line = "$pairKey\t$g\t${played.size}\t${moverColor.name}\t$moverSide\t${chosen.value}\t$cands\n"
                synchronized(traceFile!!) { traceFile!!.appendText(line) }
            }
            apply(chosen)
            activity.set(System.currentTimeMillis())
        }
        return GameRecord(
            pairKey, g, opening, whiteSide, outcome!!, reason, played.size, tempC, played.joinToString(" ") { it.value },
        )
    }

    // ---------------------------------------------------------------- звіт

    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)

    fun report(dir: File): String {
        val file = File(dir, "results.tsv")
        val records = if (file.isFile) file.readLines().mapNotNull(GameRecord::parse) else emptyList()
        val out = StringBuilder()
        out.appendLine("| Пара (сторона A проти B; очки за міткою A) | партій | перемоги A / нічиї / поразки A | очки A | % | 95 % ДІ | ≥ 60 % | Δ Elo A−B (95 % ДІ) | A з білими % / A з чорними % | сер. півходів | обрив за 300 пн |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for ((key, sides) in ALL_PAIRS) {
            val (strong, weak) = sides
            val rs = records.filter { it.pair == key }
            if (rs.isEmpty()) continue
            val scores = rs.map { it.strongerScore(strong) }
            val n = scores.size
            val w = scores.count { it == 1.0 }
            val d = scores.count { it == 0.5 }
            val l = scores.count { it == 0.0 }
            val p = scores.average()
            val se = sqrt(scores.sumOf { (it - p) * (it - p) } / (n - 1).coerceAtLeast(1) / n)
            val lo = (p - 1.96 * se).coerceAtLeast(0.0)
            val hi = (p + 1.96 * se).coerceAtMost(1.0)
            val pc = p.coerceIn(0.001, 0.999)
            val elo = 400 * log10(pc / (1 - pc))
            val whiteScores = rs.filter { it.white == strong }.map { it.strongerScore(strong) }
            val blackScores = rs.filter { it.white != strong }.map { it.strongerScore(strong) }
            fun eloOfP(x: Double) = 400 * log10(x.coerceIn(0.001, 0.999) / (1 - x.coerceIn(0.001, 0.999)))
            val eloLo = eloOfP(lo)
            val eloHi = eloOfP(hi)
            val verdict = when {
                key in LOCATOR_PAIRS -> "— (локатор)"
                n < 100 -> if (p >= 0.6) "так (n<100, попередньо)" else "ні (n<100, попередньо)"
                p >= 0.6 && lo >= 0.6 -> "так"
                p >= 0.6 -> "так (нижня межа ДІ ${f1(lo * 100)} %)"
                else -> "НІ"
            }
            out.appendLine(
                "| ${sideName(strong)} проти ${sideName(weak)} | $n | $w / $d / $l | ${f1(p * n)} з $n | ${f1(p * 100)} | " +
                    "${f1(lo * 100)}–${f1(hi * 100)} | $verdict | ${f1(elo)} (${f1(eloLo)}…${f1(eloHi)}) | " +
                    "${if (whiteScores.isEmpty()) "—" else f1(whiteScores.average() * 100)} / ${if (blackScores.isEmpty()) "—" else f1(blackScores.average() * 100)} | " +
                    "${f1(rs.map { it.plies }.average())} | ${rs.count { it.reason == "maxplies" }} |",
            )
        }
        out.appendLine()
        val temps = records.mapNotNull { it.tempC.toDoubleOrNull() }
        out.appendLine(
            "Партій усього: ${records.size}; дебютів: ${records.map { it.opening }.distinct().size} з ${activeOpenings().size}; " +
                "температура батареї на початку партій: " +
                (if (temps.isEmpty()) "—" else "${f1(temps.min())}–${f1(temps.max())} °C (середня ${f1(temps.average())}); журнал battery.log"),
        )
        out.appendLine("Нічиї за причинами: " + records.filter { it.outcome == "D" }.groupingBy { it.reason }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" })
        File(dir, "report.md").writeText(out.toString())
        return out.toString()
    }
}

/** Діагностична обгортка: пам'ятає лінії останнього `analyse` (найглибша ітерація), решту передає як є. */
private class TraceEngine(private val delegate: UciProcessEngine) : org.chesstrainer.core.engine.EngineService by delegate {
    private val seen = java.util.concurrent.CopyOnWriteArrayList<org.chesstrainer.core.engine.AnalysisUpdate>()

    fun clear() = seen.clear()

    override fun analyse(request: org.chesstrainer.core.engine.AnalysisRequest) =
        delegate.analyse(request).onEach { if (it is org.chesstrainer.core.engine.AnalysisEvent.Update) seen += it.update }

    /** (хід, оцінка з погляду [mover]) за найглибшою ітерацією, від найкращого. */
    fun candidates(mover: Color): List<Pair<String, Int>> {
        val deepest = seen.maxOfOrNull { it.depth } ?: return emptyList()
        return seen.filter { it.depth == deepest }.map {
            val white = when (val e = it.evaluation) {
                is org.chesstrainer.core.model.Evaluation.Centipawns -> e.cp
                is org.chesstrainer.core.model.Evaluation.Mate -> if (e.moves > 0) 10000 - e.moves else -10000 - e.moves
            }
            (it.pv.firstOrNull()?.value ?: "?") to (if (mover == Color.WHITE) white else -white)
        }.sortedByDescending { it.second }
    }
}
