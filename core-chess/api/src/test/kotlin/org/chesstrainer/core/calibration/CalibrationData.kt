// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.calibration

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.chesstrainer.core.engine.AnalysisEvent
import org.chesstrainer.core.engine.AnalysisRequest
import org.chesstrainer.core.engine.AnalysisUpdate
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.HandicapCandidate
import org.chesstrainer.core.engine.HandicapEngineService
import org.chesstrainer.core.engine.HandicapPolicy
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.engine.Strength
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.core.rules.ChessRules

/**
 * ТІЛЬКИ ІНСТРУМЕНТ НА ПК: дані для виміру метрики B300/B100/M1/H (CONTRACTS 3.5 п. 4, 11).
 * Усе лежить у `.calibration/` (у `.gitignore`) і відновлюється після переривання.
 */
class CalibrationEnv(val root: File) {
    val dir = File(root, ".calibration")
    val data = File(dir, "data")
    /** Консольний Stockfish на телефоні власника (збирає `build-stockfish-android.sh`), UCI через `adb shell -T`. */
    private val remoteDir = "/data/local/tmp/chess-calib"
    val netPath = "$remoteDir/nn-1a298aa575a0.nnue"
    internal val launch: List<String> = run {
        val props = java.util.Properties().also { p -> File(root, "local.properties").inputStream().use { p.load(it) } }
        val adb = File(props.getProperty("sdk.dir"), "platform-tools/adb.exe")
        check(adb.isFile) { "Немає adb: ${adb.path}" }
        val serial = System.getenv("ANDROID_SERIAL") ?: run {
            val devices = ProcessBuilder(adb.path, "devices").start().inputStream.bufferedReader().readLines()
                .drop(1).filter { it.endsWith("\tdevice") }.map { it.substringBefore('\t') }
            check(devices.size == 1) { "Потрібен рівно один телефон по adb (або ANDROID_SERIAL): $devices" }
            devices.single()
        }
        listOf(adb.path, "-s", serial, "shell", "-T", "$remoteDir/stockfish")
    }
    val rules: ChessRules = ChesslibRules()
    val cache by lazy { AnalysisCache(File(data, "analysis.tsv")) } // 40+ МБ: матчі її не чіпають
    private val engines = ConcurrentLinkedQueue<UciProcessEngine>()

    init {
        data.mkdirs()
    }

    /** Запуск через adb іноді спорадично падає під навантаженням, тому кілька спроб. */
    fun newEngine(engineCache: AnalysisCache = cache): UciProcessEngine {
        var last: Throwable? = null
        repeat(8) { attempt ->
            val engine = UciProcessEngine(launch, netPath, rules, engineCache)
            try {
                runBlocking { engine.start() }
                engines += engine
                return engine
            } catch (e: IllegalStateException) {
                last = e
                runCatching { runBlocking { engine.stop() } }
                runCatching { ProcessBuilder(launch[0], "start-server").start().waitFor() }
                Thread.sleep(3000L * (attempt + 1))
            }
        }
        throw IllegalStateException("Stockfish не стартує після 5 спроб", last)
    }

    fun close() {
        engines.forEach { runBlocking { it.stop() } }
        engines.clear()
    }

    /** Виконує [action]; якщо процес рушія/adb упав, перезапускає рушій (і adb-сервер) і повторює (до 5 разів). */
    private fun <T> runResilient(local: ThreadLocal<UciProcessEngine>, item: T, action: (UciProcessEngine, T) -> Unit) {
        var last: Throwable? = null
        repeat(8) { attempt ->
            try {
                val engine = local.get() ?: newEngine().also { local.set(it) }
                action(engine, item)
                return
            } catch (e: IllegalStateException) {
                last = e
                local.get()?.let { old -> runCatching { runBlocking { old.stop() } }; engines.remove(old) }
                local.remove()
                runCatching { ProcessBuilder(launch[0], "start-server").start().waitFor() }
                Thread.sleep(3000L * (attempt + 1))
            }
        }
        throw IllegalStateException("Збій рушія після 5 спроб", last)
    }

    /** Виконує [action] паралельно на [workers] окремих процесах рушія (по одному на потік). */
    fun <T> parallel(items: List<T>, workers: Int, label: String, action: (UciProcessEngine, T) -> Unit) {
        if (items.isEmpty()) return
        val pool = Executors.newFixedThreadPool(workers)
        val local = ThreadLocal<UciProcessEngine>()
        val done = AtomicInteger()
        val started = System.currentTimeMillis()
        try {
            val futures = items.map { item ->
                pool.submit(Callable {
                    runResilient(local, item, action)
                    val n = done.incrementAndGet()
                    if (n % 50 == 0 || n == items.size) {
                        val sec = (System.currentTimeMillis() - started) / 1000
                        println("  $label: $n/${items.size} (${sec}s)")
                    }
                })
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    // --- рушій: пошуки з кешем ---

    fun search(engine: UciProcessEngine, fen: String, limit: SearchLimit, lines: Int): List<AnalysisUpdate> {
        val events = runBlocking { engine.analyse(AnalysisRequest(Fen(fen), emptyList(), limit, lines)).toList() }
        return events.filterIsInstance<AnalysisEvent.Update>().map { it.update }
    }

    fun legalCount(fen: String): Int =
        rules.legalMoves((rules.parseFen(fen) as ChessResult.Ok).value).size

    fun position(fen: String): Position = (rules.parseFen(fen) as ChessResult.Ok).value

    /** Кандидати декоратора для позиції при пошуку глибини [depth] (ті самі лінії, що бачить `HandicapEngineService`). */
    internal fun candidates(fen: String, depth: Int): List<HandicapCandidate> {
        val lines = minOf(legalCount(fen), HandicapPolicy.MAX_CANDIDATES)
        val key = AnalysisCache.key(Fen(fen), emptyList(), SearchLimit(depth = depth), lines)
        val entry = cache.get(key) ?: error("Немає в кеші пошуку (запустіть prepare): $fen d$depth")
        val position = position(fen)
        return entry.lines.mapNotNull { HandicapEngineService.candidateOf(rules, it, position) }
    }

    /** Еталон (CONTRACTS 3.5 п. 11): глибина [REF_DEPTH], MultiPV по всіх легальних ходах. */
    fun reference(fen: String): Reference {
        val key = AnalysisCache.key(Fen(fen), emptyList(), SearchLimit(depth = REF_DEPTH), legalCount(fen))
        val entry = cache.get(key) ?: error("Немає еталона в кеші (запустіть prepare): $fen")
        val position = position(fen)
        val scores = entry.lines.mapNotNull { HandicapEngineService.candidateOf(rules, it, position) }
        return Reference(position, scores.associate { it.uci.value to it.scoreCp })
    }

    fun hasReference(fen: String): Boolean =
        cache.get(AnalysisCache.key(Fen(fen), emptyList(), SearchLimit(depth = REF_DEPTH), legalCount(fen))) != null

    companion object {
        const val REF_DEPTH = 12
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun findRoot(): File {
            var d: File? = File(".").absoluteFile
            while (d != null) {
                if (File(d, "settings.gradle.kts").isFile) return d
                d = d.parentFile
            }
            error("Не знайдено корінь репозиторію")
        }
    }
}

/** Еталонні оцінки (з погляду сторони, що ходить) усіх легальних ходів позиції. */
class Reference(val position: Position, val scores: Map<String, Int>) {
    val best: Int = scores.values.max()
    val second: Int = scores.values.sortedDescending().getOrElse(1) { best }
    fun loss(uci: String): Int = best - (scores[uci] ?: error("Хід $uci поза еталоном"))

    /** Мат в 1: найкращий хід дає мат за один хід (оцінка `MATE_CP − 10`). */
    val isMateInOne: Boolean get() = best >= HandicapPolicy.MATE_CP - 10

    fun givesMate(uci: String): Boolean = (scores[uci] ?: Int.MIN_VALUE) >= HandicapPolicy.MATE_CP - 10

    /** Безкоштовне взяття фігури: найкращий хід бере фігуру (не пішака) і виграє ≥ 300 сп проти другого. */
    fun isFreePiece(rules: ChessRules): Boolean {
        if (best >= HandicapPolicy.MATE_CP - 1000 || best - second < 300) return false
        val bestUci = scores.entries.first { it.value == best }.key
        val move = (rules.parseUci(position, bestUci) as? ChessResult.Ok)?.value ?: return false
        val target = position.pieceAt(move.to) ?: return false
        return target.color != position.sideToMove &&
            target.type in setOf(PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN)
    }
}

/** 24 дебютні позиції (6 півходів), однакові для виміру й майбутніх матчів (CONTRACTS 3.5 п. 8, 11). */
object Openings {
    val lines: List<String> = listOf(
        "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6",
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6",
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4",
        "e2e4 c7c5 g1f3 b8c6 d2d4 c5d4",
        "e2e4 e7e6 d2d4 d7d5 b1c3 g8f6",
        "e2e4 c7c6 d2d4 d7d5 b1c3 d5e4",
        "e2e4 d7d5 e4d5 d8d5 b1c3 d5a5",
        "e2e4 g8f6 e4e5 f6d5 d2d4 d7d6",
        "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6",
        "d2d4 d7d5 c2c4 c7c6 g1f3 g8f6",
        "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4",
        "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7",
        "d2d4 g8f6 g1f3 e7e6 c1g5 h7h6",
        "d2d4 f7f5 g2g3 g8f6 f1g2 e7e6",
        "c2c4 e7e5 b1c3 g8f6 g1f3 b8c6",
        "c2c4 g8f6 b1c3 e7e6 g1f3 d7d5",
        "g1f3 d7d5 g2g3 g8f6 f1g2 e7e6",
        "g1f3 g8f6 c2c4 b7b6 g2g3 c8b7",
        "e2e4 e7e5 f2f4 e5f4 g1f3 g7g5",
        "e2e4 e7e5 b1c3 g8f6 f1c4 f6e4",
        "e2e4 e7e5 g1f3 g8f6 f3e5 d7d6",
        "e2e4 e7e6 d2d4 d7d5 e4e5 c7c5",
        "d2d4 d7d6 e2e4 g8f6 b1c3 g7g6",
        "b2b3 e7e5 c1b2 b8c6 e2e3 d7d5",
    ).also { check(it.size == 24) }
}

/** Самогра мінімального `UCI_Elo` (CONTRACTS 3.5 п. 11). Ходи Elo-обмеженого Stockfish не детерміновані (власний PRNG за часом), тому партії зберігаються. */
class Games(private val env: CalibrationEnv) {
    data class Game(val id: Int, val opening: Int, val moves: List<String>)

    private val file = File(env.data, "games.tsv")
    private val lock = Any()

    fun load(): List<Game> =
        if (!file.isFile) emptyList()
        else file.readLines().filter { it.isNotBlank() }.map {
            val f = it.split('\t')
            Game(f[0].toInt(), f[1].toInt(), f[2].split(' ').filter(String::isNotEmpty))
        }.sortedBy { it.id }

    /** Дограє партії до [total] (id 0 until total). */
    fun ensure(total: Int, workers: Int) {
        val have = load().map { it.id }.toSet()
        val missing = (0 until total).filter { it !in have }
        if (missing.isEmpty()) return
        println("Самогра: ${missing.size} партій (мін. UCI_Elo, 40 мс на хід)")
        env.parallel(missing, workers, "games") { engine, id ->
            val elo = engine.capabilities!!.eloRange!!.first
            val level = DifficultyLevel(
                DifficultyId(10), Strength.Elo(elo), SearchLimit(moveTimeMs = 40), 0, elo, DifficultyGroup.AMATEUR,
            )
            val opening = id % Openings.lines.size
            val moves = Openings.lines[opening].split(' ').toMutableList()
            var position = replay(moves)
            while (moves.size < MAX_PLIES) {
                if (env.rules.legalMoves(position).isEmpty() || env.rules.isInsufficientMaterial(position)) break
                val r = runBlocking { engine.bestMove(MoveRequest(Fen(CalibrationEnv.START_FEN), moves.map { Uci(it) }, level)) }
                val best = (r as ChessResult.Ok).value.best.value
                moves += best
                position = replay(moves)
            }
            synchronized(lock) { file.appendText("$id\t$opening\t${moves.joinToString(" ")}\n") }
        }
    }

    fun replay(moves: List<String>): Position {
        var position = env.rules.startPosition()
        for (m in moves) {
            val move = (env.rules.parseUci(position, m) as ChessResult.Ok).value
            position = (env.rules.apply(position, move) as ChessResult.Ok).value
        }
        return position
    }

    /** FEN позиції після [ply] півходів партії. */
    fun fenAt(game: Game, ply: Int): String = replay(game.moves.take(ply)).fen.value

    companion object {
        const val MAX_PLIES = 110
    }
}

/** Набір позицій для виміру: основні (вікна по 5 ходів однієї сторони) і тематичні (мат в 1, безкоштовна фігура). */
class PositionSets(
    /** Вікна: 5 позицій, де ходить та сама сторона, півходи p, p+2, …, p+8 з діапазону 8–60. */
    val windows: List<List<String>>,
    val mateInOne: List<String>,
    val freePiece: List<String>,
) {
    val main: List<String> get() = windows.flatten().distinct()
    val all: List<String> get() = (main + mateInOne + freePiece).distinct()
}

object PositionBuilder {
    const val MIN_PLY = 8
    const val MAX_PLY = 60
    const val THEMATIC_TARGET = 120
    const val THEMATIC_MIN = 100
    const val SCAN_DEPTH = 6

    fun build(env: CalibrationEnv, seed: Long, games: Int, workers: Int, maxGames: Int = 800): PositionSets {
        val gen = Games(env)
        var total = games
        while (true) {
            gen.ensure(total, workers)
            val list = gen.load()
            val sets = tryBuild(env, gen, list, seed, workers)
            if (sets != null) return sets
            check(total < maxGames) { "Не вдалося набрати тематичні позиції за $maxGames партій" }
            total += 50
            println("Мало тематичних позицій, додаємо партії: $total")
        }
    }

    private fun tryBuild(env: CalibrationEnv, gen: Games, games: List<Games.Game>, seed: Long, workers: Int): PositionSets? {
        // Вікна основного набору.
        val windows = mutableListOf<List<String>>()
        for (g in games) {
            val last = minOf(MAX_PLY, g.moves.size - 1)
            val maxStart = last - 8
            if (maxStart < MIN_PLY) continue
            val start = Random(seed * 1_000_003 + g.id).nextInt(MIN_PLY, maxStart + 1)
            windows += (0..4).map { gen.fenAt(g, start + 2 * it) }
        }
        // Тематичні кандидати: швидкий скан усіх позицій партій від 8-го півходу (мат в 1 рідкісний, тому до кінця партії) (глибина 6, MultiPV 2).
        val scanList = games.flatMap { g -> (MIN_PLY..(g.moves.size - 1)).map { gen.fenAt(g, it) } }.distinct()
        println("Скан тематичних кандидатів: ${scanList.size} позицій")
        env.parallel(scanList, workers, "scan") { e, fen -> env.search(e, fen, SearchLimit(depth = SCAN_DEPTH), 2) }

        val mateCandidates = mutableListOf<String>()
        val pieceCandidates = mutableListOf<String>()
        for (fen in scanList) {
            val key = AnalysisCache.key(Fen(fen), emptyList(), SearchLimit(depth = SCAN_DEPTH), minOf(2, env.legalCount(fen)))
            val lines = env.cache.get(key)?.lines ?: continue
            val position = env.position(fen)
            val cands = lines.mapNotNull { HandicapEngineService.candidateOf(env.rules, it, position) }
            if (cands.isEmpty()) continue
            if (cands[0].scoreCp >= HandicapPolicy.MATE_CP - 10) mateCandidates += fen
            else if (cands.size >= 2 && cands[0].scoreCp < 5000 && cands[0].scoreCp - cands[1].scoreCp >= 250) pieceCandidates += fen
        }
        println("Кандидати: мат в 1 = ${mateCandidates.size}, взяття фігури = ${pieceCandidates.size}")

        // Підтвердження еталоном (глибина 12, усі ходи).
        val toConfirm = (mateCandidates + pieceCandidates).distinct()
        env.parallel(toConfirm.filterNot { env.hasReference(it) }, workers, "ref-thematic") { e, fen ->
            env.search(e, fen, SearchLimit(depth = CalibrationEnv.REF_DEPTH), env.legalCount(fen))
        }
        val mate = mateCandidates.filter { env.reference(it).isMateInOne }
        val piece = pieceCandidates.filter { env.reference(it).isFreePiece(env.rules) }
        println("Підтверджено еталоном: мат в 1 = ${mate.size}, безкоштовна фігура = ${piece.size}")
        if (mate.size < THEMATIC_MIN || piece.size < THEMATIC_MIN) return null
        // Фіксований seed і порядок: перші THEMATIC_TARGET за (id партії, півхід).
        return PositionSets(windows, mate.take(THEMATIC_TARGET), piece.take(THEMATIC_TARGET))
    }
}
