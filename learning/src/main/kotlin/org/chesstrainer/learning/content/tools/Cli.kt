// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess
import org.chesstrainer.core.rules.ChesslibRules

internal fun parseArgs(args: Array<String>): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        require(a.startsWith("--")) { "unexpected argument '$a'" }
        val key = a.removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) { m[key] = args[i + 1]; i += 2 } else { m[key] = "true"; i++ }
    }
    return m
}

internal fun usageBuild() = """
Використання (з кореня репозиторію):
  ./gradlew :learning:buildPuzzlePack --args="--input .content-cache/lichess_db_puzzle.csv --pack-id tactics-sample --total 200"

Обов'язкові: --input <csv>  --pack-id <id>
Необов'язкові (типові значення в дужках):
  --out <каталог>            (content/common)
  --total <N>                кількість задач (4000)
  --seed <рядок>             (chess-trainer-1)
  --band-edges <a,b,c,…>     межі рейтингу (400,1000,1400,1800,2200,3200)
  --band-weights <w1,w2,…>   частка діапазонів (3,3,2,1.5,1)
  --themes <id,id,…>         ThemeId тактичних тем (усі тактичні)
  --min-popularity <N>       (80)   --max-rating-deviation <N> (90)   --min-plays <N> (500)
  --max-solution-plies <N>   непарне (7)
  --source-id <CT-xxx>       (CT-001)
  --report <файл>            записати звіт у файл
  --audit                    лише перевірити весь дамп правилами, нічого не писати
""".trimIndent()

/** Точка входу `:learning:buildPuzzlePack`. */
object BuildPuzzlePackMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val a = try { parseArgs(args) } catch (e: IllegalArgumentException) { System.err.println(e.message); System.err.println(usageBuild()); exitProcess(2) }
        if ("help" in a || "input" !in a) { println(usageBuild()); exitProcess(if ("help" in a) 0 else 2) }
        try {
            val rules = ChesslibRules()
            val input = Paths.get(a.getValue("input"))
            if ("audit" in a) { print(DumpAudit(rules, input).run()); return }
            val defaults = BuildOptions(input, Paths.get("content/common"), "x")
            val opt = BuildOptions(
                input = input,
                outDir = Paths.get(a["out"] ?: "content/common"),
                packId = a["pack-id"] ?: error("--pack-id is required"),
                total = a["total"]?.toInt() ?: defaults.total,
                seed = a["seed"] ?: defaults.seed,
                bandEdges = a["band-edges"]?.split(',')?.map { it.trim().toInt() } ?: defaults.bandEdges,
                bandWeights = a["band-weights"]?.split(',')?.map { it.trim().toDouble() } ?: defaults.bandWeights,
                themes = a["themes"]?.split(',')?.map { it.trim() },
                minPopularity = a["min-popularity"]?.toInt() ?: defaults.minPopularity,
                maxRatingDeviation = a["max-rating-deviation"]?.toInt() ?: defaults.maxRatingDeviation,
                minPlays = a["min-plays"]?.toInt() ?: defaults.minPlays,
                maxSolutionPlies = a["max-solution-plies"]?.toInt() ?: defaults.maxSolutionPlies,
                sourceId = a["source-id"] ?: defaults.sourceId,
            )
            val builder = PuzzlePackBuilder(rules, opt)
            val report = builder.build()
            builder.write(report)
            val text = report.render()
            print(text)
            a["report"]?.let { Files.write(Paths.get(it), text.toByteArray(StandardCharsets.UTF_8)) }
        } catch (e: IllegalArgumentException) {
            System.err.println("Помилка: ${e.message}")
            exitProcess(2)
        }
    }
}
