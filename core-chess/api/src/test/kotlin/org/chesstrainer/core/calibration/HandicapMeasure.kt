// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.calibration

import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.chesstrainer.core.engine.DefaultDifficultyPresets
import org.chesstrainer.core.engine.DifficultyIds
import org.chesstrainer.core.engine.HandicapEngineService
import org.chesstrainer.core.engine.HandicapPolicy
import org.chesstrainer.core.engine.HandicapCandidate
import org.chesstrainer.core.engine.HandicapProfile
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen

/**
 * ТІЛЬКИ ІНСТРУМЕНТ НА ПК (CONTRACTS 3.5 п. 9, 11): вимір метрики B300/B100/M1/H кроків Handicap на справжньому
 * Stockfish. Не входить у `./gradlew test` і CI. Запуск: `./gradlew :core-chess:api:handicapMeasure --args="..."`,
 * див. `core-chess/api/HANDICAP.md`.
 *
 * Команди:
 *  - `prepare [--games N] [--seed S] [--workers W]`: самогра, позиції, еталон, пошуки кроків (усе в кеші, відновлюється);
 *  - `tune`: аналітичні (точні, без вибірки) значення метрики для профілів із `.calibration/profiles.txt`;
 *  - `measure [--seed S] [--samples K] [--workers W]`: вибіркова метрика через справжні `HandicapEngineService`
 *    і `HandicapPolicy` та мінімальний `UCI_Elo`; таблиця в `.calibration/results/`.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: error("Команда: prepare | tune | measure")
    val opts = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val env = CalibrationEnv(CalibrationEnv.findRoot())
    val seed = opts["seed"]?.toLong() ?: 1L
    val workers = opts["workers"]?.toInt() ?: 4 // процеси Stockfish на телефоні (8 ядер, з них 4 швидкі)
    try {
        when (command) {
            "prepare" -> prepare(env, seed, opts["games"]?.toInt() ?: 100, workers)
            "tune" -> tune(env, seed, opts["games"]?.toInt() ?: 100, workers)
            "export-test-data" -> exportTestData(env, seed, opts["games"]?.toInt() ?: 100, workers, opts["out"] ?: error("Потрібен --out FILE.tsv.gz"))
            "measure" -> measure(env, seed, opts["games"]?.toInt() ?: 100, opts["samples"]?.toInt() ?: 20, workers)
            else -> error("Невідома команда $command")
        }
    } finally {
        env.close()
    }
}

/** Глибини, для яких кешуються пошуки кандидатів (профілі можуть мати будь-яку з них). */
private val SEARCH_DEPTHS = 1..7

private fun prepare(env: CalibrationEnv, seed: Long, games: Int, workers: Int): PositionSets {
    val sets = PositionBuilder.build(env, seed, games, workers)
    println("Основні позиції: ${sets.main.size} (вікон ${sets.windows.size}), мат в 1: ${sets.mateInOne.size}, фігура: ${sets.freePiece.size}")
    val all = sets.all
    env.parallel(all.filterNot { env.hasReference(it) }, workers, "reference") { e, fen ->
        env.search(e, fen, SearchLimit(depth = CalibrationEnv.REF_DEPTH), env.legalCount(fen))
    }
    for (depth in SEARCH_DEPTHS) {
        val todo = all.filter { fen ->
            val lines = minOf(env.legalCount(fen), HandicapPolicy.MAX_CANDIDATES)
            env.cache.get(AnalysisCache.key(Fen(fen), emptyList(), SearchLimit(depth = depth), lines)) == null
        }
        env.parallel(todo, workers, "search d$depth") { e, fen ->
            env.search(e, fen, SearchLimit(depth = depth), minOf(env.legalCount(fen), HandicapPolicy.MAX_CANDIDATES))
        }
    }
    println("Кеш пошуків: ${env.cache.size} записів. Готово.")
    return sets
}

/**
 * Компактний тестовий ресурс для JVM-тесту метрик на реальних позиціях (`RealPositionMetricsTest`, CI без телефона):
 * на позицію один рядок `вид \t еталон \t кандидати d1 \t d2 \t d3`, де вид `M` (основний), `T` (мат в 1), `F` (вільна фігура),
 * еталон це `хід:оцінка` усіх легальних ходів (глибина 12), кандидати це те, що бачить декоратор (`HandicapEngineService.candidateOf`).
 * Береться з кешу пошуків (`prepare` нічого нового не шукає); стискається gzip.
 */
private fun exportTestData(env: CalibrationEnv, seed: Long, games: Int, workers: Int, out: String) {
    val sets = prepare(env, seed, games, workers)
    fun enc(c: List<HandicapCandidate>) = c.joinToString(" ") { "${it.uci.value}:${it.scoreCp}" }
    val lines = ArrayList<String>()
    fun add(kind: String, fen: String) {
        val ref = env.reference(fen)
        val refText = ref.scores.entries.joinToString(" ") { "${it.key}:${it.value}" }
        lines += (listOf(kind, refText) + listOf(1, 2, 3).map { enc(env.candidates(fen, it)) }).joinToString("\t")
    }
    sets.main.forEach { add("M", it) }
    sets.mateInOne.forEach { add("T", it) }
    sets.freePiece.forEach { add("F", it) }
    val file = File(out).also { it.absoluteFile.parentFile.mkdirs() }
    java.util.zip.GZIPOutputStream(file.outputStream()).bufferedWriter().use { w -> lines.forEach { w.write(it); w.write("\n") } }
    println("Записано ${lines.size} позицій у ${file.absolutePath}: ${file.length()} байт")
}

/** Метрики за точним розподілом (без вибірки). Значення в частках. */
private class Metrics(val b300: Double, val b100: Double, val m1: Double, val h: Double, val series5: Double)

private fun expected(env: CalibrationEnv, sets: PositionSets, profile: HandicapProfile): Metrics {
    fun dist(fen: String) = HandicapPolicy.distribution(env.candidates(fen, profile.searchDepth), profile)
    fun probLoss(fen: String, threshold: Int): Double {
        val ref = env.reference(fen)
        return dist(fen).sumOf { (c, p) -> if (ref.loss(c.uci.value) >= threshold) p else 0.0 }
    }
    val main = sets.main
    val p300 = main.associateWith { probLoss(it, 300) }
    val b300 = main.sumOf { p300.getValue(it) } / main.size
    val b100 = main.sumOf { probLoss(it, 100) } / main.size
    val m1 = sets.mateInOne.sumOf { fen ->
        val ref = env.reference(fen)
        dist(fen).sumOf { (c, p) -> if (ref.givesMate(c.uci.value)) 0.0 else p }
    } / sets.mateInOne.size
    val h = sets.freePiece.sumOf { probLoss(it, 300) } / sets.freePiece.size
    val series = sets.windows.sumOf { w -> w.fold(1.0) { acc, fen -> acc * p300.getValue(fen) } } / sets.windows.size
    return Metrics(b300, b100, m1, h, series)
}

private fun pct(x: Double) = String.format(Locale.US, "%.1f", x * 100)

/** `.calibration/profiles.txt`: рядок на крок: `T maxLoss blind‰ random‰ depth`. Рядок `solveT` перед профілями: підібрати T під середину діапазону B300. */
private fun tune(env: CalibrationEnv, seed: Long, games: Int, workers: Int) {
    val sets = prepare(env, seed, games, workers)
    val file = File(env.dir, "profiles.txt")
    if (!file.isFile) {
        file.writeText((1..HandicapPolicy.STEPS).joinToString("\n") { s ->
            val p = HandicapPolicy.profile(s)
            "${p.temperatureCp} ${p.maxLossCp} ${p.blindSpotPermille} ${p.randomMovePermille} ${p.searchDepth}"
        })
    }
    val lines = file.readLines().filter { it.isNotBlank() }
    val solve = lines.first().trim() == "solveT"
    val targets = listOf(0.275, 0.17, 0.10, 0.055, 0.03)
    println("| step | T | maxLoss | blind‰ | rnd‰ | depth | B300 % | B100 % | M1 miss % | H miss % | серія 5 % |")
    println("|---|---|---|---|---|---|---|---|---|---|---|")
    lines.filter { it.trim() != "solveT" }.forEachIndexed { i, line ->
        val v = line.trim().split(Regex("\\s+")).map { it.toInt() }
        val template = HandicapPolicy.profile(i + 1)
        fun make(t: Int) = template.copy(
            temperatureCp = t, maxLossCp = v[1], blindSpotPermille = v[2], randomMovePermille = v[3], searchDepth = v[4],
        )
        var t = v[0]
        if (solve) {
            var lo = 5
            var hi = 3000
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (expected(env, sets, make(mid)).b300 < targets[i]) lo = mid else hi = mid
            }
            t = hi
        }
        val p = make(t)
        val m = expected(env, sets, p)
        println(
            "| ${i + 1} | $t | ${p.maxLossCp} | ${p.blindSpotPermille} | ${p.randomMovePermille} | ${p.searchDepth} | " +
                "${pct(m.b300)} | ${pct(m.b100)} | ${pct(m.m1)} | ${pct(m.h)} | ${pct(m.series5)} |",
        )
    }
}

private fun lossOf(ref: Reference, uci: String) = ref.loss(uci)

/** Вибіркова метрика через справжній декоратор. */
private fun measure(env: CalibrationEnv, seed: Long, games: Int, samples: Int, workers: Int) {
    val sets = prepare(env, seed, games, workers)
    val refs = sets.all.associateWith { env.reference(it) }
    val presets = DefaultDifficultyPresets()
    val caps = env.newEngine().capabilities!!

    class Row(
        val name: String, val b300: Double, val b100: Double, val m1: Double, val h: Double, val series: Double,
        val moves: Int, val mainMoves: Int, val avgNodes: Double, val avgMs: Double,
    )

    fun summarize(
        name: String, pick: (String, Int) -> String, avgNodes: Double, avgMs: Double, perPos: Int,
    ): Row {
        // pick(fen, k) = k-й вибраний хід у позиції.
        val main = sets.main
        val mainLoss = main.associateWith { fen -> (0 until perPos).map { k -> refs.getValue(fen).loss(pick(fen, k)) } }
        val n = main.size * perPos
        val b300 = mainLoss.values.sumOf { l -> l.count { it >= 300 } }.toDouble() / n
        val b100 = mainLoss.values.sumOf { l -> l.count { it >= 100 } }.toDouble() / n
        val m1 = sets.mateInOne.sumOf { fen ->
            (0 until perPos).count { k -> !refs.getValue(fen).givesMate(pick(fen, k)) }
        }.toDouble() / (sets.mateInOne.size * perPos)
        val h = sets.freePiece.sumOf { fen ->
            (0 until perPos).count { k -> lossOf(refs.getValue(fen), pick(fen, k)) >= 300 }
        }.toDouble() / (sets.freePiece.size * perPos)
        val series = sets.windows.sumOf { w ->
            (0 until perPos).count { k -> w.all { fen -> mainLoss.getValue(fen)[k] >= 300 } }
        }.toDouble() / (sets.windows.size * perPos)
        val total = n + (sets.mateInOne.size + sets.freePiece.size) * perPos
        return Row(name, b300, b100, m1, h, series, total, n, avgNodes, avgMs)
    }

    val rows = mutableListOf<Row>()
    for (step in 1..HandicapPolicy.STEPS) {
        val level = presets.byId(DifficultyIds.all[step - 1], caps)!!
        val depth = level.limit.depth!!
        val picks = mutableMapOf<Pair<String, Int>, String>()
        val service = HandicapEngineService(env.newEngine(), env.rules, Random(seed * 7919 + step))
        runBlocking { service.start() }
        // Послідовно: Random декоратора спільний, порядок викликів фіксований => відтворювана таблиця.
        for (fen in sets.all) for (k in 0 until samples) {
            val r = runBlocking { service.bestMove(MoveRequest(Fen(fen), emptyList(), level)) }
            picks[fen to k] = (r as ChessResult.Ok).value.best.value
        }
        val entries = sets.main.map { fen ->
            val lines = minOf(env.legalCount(fen), HandicapPolicy.MAX_CANDIDATES)
            env.cache.get(AnalysisCache.key(Fen(fen), emptyList(), SearchLimit(depth = depth), lines))!!
        }
        rows += summarize(
            "Крок $step (d$depth)", { f, k -> picks.getValue(f to k) },
            entries.map { it.nodes }.average(), entries.map { it.ms }.average(), samples,
        )
        println("  крок $step готово")
    }

    // Мінімальний UCI_Elo: 3 ходи на позицію, кешуються у файлі (хід Elo-обмеженого Stockfish не детермінований).
    val eloLevel = presets.byId(DifficultyIds.ELO_MIN, caps)!!
    val eloFile = File(env.data, "elo_samples.tsv")
    val eloPicks = mutableMapOf<String, List<String>>()
    if (eloFile.isFile) eloFile.readLines().filter { it.isNotBlank() }.forEach { eloPicks[it.substringBefore('\t')] = it.substringAfter('\t').split(' ') }
    val eloSamples = 3
    val todo = sets.all.filter { (eloPicks[it]?.size ?: 0) < eloSamples }
    val lock = Any()
    env.parallel(todo, workers, "elo-min") { e, fen ->
        val moves = (1..eloSamples).map {
            (runBlocking { e.bestMove(MoveRequest(Fen(fen), emptyList(), eloLevel)) } as ChessResult.Ok).value.best.value
        }
        synchronized(lock) {
            eloPicks[fen] = moves
            eloFile.appendText("$fen\t${moves.joinToString(" ")}\n")
        }
    }
    rows += summarize("мін. UCI_Elo (${(eloLevel.strength as org.chesstrainer.core.engine.Strength.Elo).elo})", { f, k -> eloPicks.getValue(f)[k] }, 0.0, 0.0, eloSamples)

    val out = StringBuilder()
    out.appendLine("| Рівень | ходів (основні+тематичні) | B300 % | B100 % | M1 пропущено % | H пропущено % | серії з 5 грубих % | вузлів/пошук | мс/пошук (телефон, 1 потік) |")
    out.appendLine("|---|---|---|---|---|---|---|---|---|")
    rows.forEach {
        out.appendLine(
            "| ${it.name} | ${it.moves} (${it.mainMoves}) | ${pct(it.b300)} | ${pct(it.b100)} | ${pct(it.m1)} | ${pct(it.h)} | ${pct(it.series)} | " +
                (if (it.avgNodes > 0) "${it.avgNodes.toLong()} | ${it.avgMs.toLong()}" else "— | —") + " |",
        )
    }
    out.appendLine()
    out.appendLine("Основних позицій: ${sets.main.size} (вікон по 5: ${sets.windows.size}); мат в 1: ${sets.mateInOne.size}; безкоштовна фігура: ${sets.freePiece.size}; вибірок на позицію: кроки $samples, UCI_Elo $eloSamples; seed $seed; еталон: Stockfish sf_19, глибина ${CalibrationEnv.REF_DEPTH}, MultiPV по всіх легальних ходах.")
    println(out)
    val resultsDir = File(env.dir, "results").also { it.mkdirs() }
    File(resultsDir, "measure-seed$seed.md").writeText(out.toString())
}
