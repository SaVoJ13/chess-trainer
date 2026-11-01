// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content.tools

import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.TreeMap
import org.chesstrainer.core.rules.ChessRules
import org.chesstrainer.learning.content.ContentCodec
import org.chesstrainer.learning.content.Manifest
import org.chesstrainer.learning.content.PackEntry
import org.chesstrainer.learning.content.PuzzleFormat
import org.chesstrainer.learning.content.PuzzlePack
import org.chesstrainer.learning.content.PuzzleRecord
import org.chesstrainer.learning.content.TagDisposition
import org.chesstrainer.learning.content.ThemeCatalog

/** Параметри вибірки. Усі значення потрапляють у `params` пакета, тож вибірка відтворюється. */
data class BuildOptions(
    val input: Path,
    val outDir: Path,
    val packId: String,
    /** Скільки задач відібрати. */
    val total: Int = 4000,
    /** Рядок для детермінованого перемішування (SHA-256 від `seed:PuzzleId`). */
    val seed: String = "chess-trainer-1",
    /** Межі діапазонів рейтингу Lichess `[e0,e1) [e1,e2) …`; поза межами задача відкидається. */
    val bandEdges: List<Int> = listOf(400, 1000, 1400, 1800, 2200, 3200),
    /** Вага діапазону в частці `total`; довжина = bandEdges.size - 1. */
    val bandWeights: List<Double> = listOf(3.0, 3.0, 2.0, 1.5, 1.0),
    /** ThemeId тактичних тем для вибірки; null = усі тактичні. */
    val themes: List<String>? = null,
    val minPopularity: Int = 80,
    val maxRatingDeviation: Int = 90,
    val minPlays: Int = 500,
    /** Максимальна довжина `solution` (пів-ходів, непарна). */
    val maxSolutionPlies: Int = 7,
    val sourceId: String = "CT-001",
) {
    init {
        require(PuzzleFormat.PACK_ID_REGEX.matches(packId)) { "bad packId '$packId'" }
        require(bandEdges.size >= 2 && bandEdges == bandEdges.sorted()) { "bandEdges must be ascending" }
        require(bandWeights.size == bandEdges.size - 1) { "bandWeights size must be bandEdges.size - 1" }
        require(total > 0)
        require(maxSolutionPlies % 2 == 1) { "maxSolutionPlies must be odd" }
        themes?.forEach { require(it in ThemeCatalog.tacticIds) { "'$it' is not an accepted tactic theme" } }
    }

    fun toParams(): Map<String, String> = mapOf(
        "bandEdges" to bandEdges.joinToString(","),
        "bandWeights" to bandWeights.joinToString(","),
        "maxRatingDeviation" to maxRatingDeviation.toString(),
        "maxSolutionPlies" to maxSolutionPlies.toString(),
        "minPlays" to minPlays.toString(),
        "minPopularity" to minPopularity.toString(),
        "seed" to seed,
        "themes" to (themes ?: ThemeCatalog.tacticIds).joinToString(","),
        "total" to total.toString(),
    )
}

class BuildReport(
    val rowsRead: Long,
    /** Причина → кількість рядків дампа, відхилених фільтрами до перевірки правил. */
    val prefilterRejects: Map<String, Long>,
    val unknownTags: Map<String, Long>,
    /** Код проблеми → кількість кандидатів, відхилених перевіркою `ChessRules`. */
    val invalidByProblem: Map<String, Long>,
    val candidatesChecked: Long,
    val shortfall: Int,
    val selected: List<PuzzleRecord>,
    val bandEdges: List<Int>,
) {
    fun render(): String = buildString {
        appendLine("rows read: $rowsRead")
        appendLine("prefilter rejects:")
        prefilterRejects.forEach { (k, v) -> appendLine("  $k: $v") }
        if (unknownTags.isNotEmpty()) appendLine("unknown tags: " + unknownTags.entries.joinToString { "${it.key}=${it.value}" })
        appendLine("candidates checked with ChessRules: $candidatesChecked")
        appendLine("invalid (rejected by ChessRules): ${invalidByProblem.values.sum()}")
        invalidByProblem.forEach { (k, v) -> appendLine("  $k: $v") }
        appendLine("selected: ${selected.size} (shortfall vs quota: $shortfall)")
        appendLine(distribution())
    }

    /** Таблиця «головна тема × діапазон» і підсумки за всіма темами. */
    fun distribution(): String = buildString {
        val bands = bandEdges.zipWithNext { a, b -> "$a-${b - 1}" }
        val byTheme = TreeMap<String, IntArray>()
        val allThemes = TreeMap<String, Int>()
        for (p in selected) {
            val band = bandIndex(bandEdges, p.rating)
            val primary = p.themes.filter { ThemeCatalog.info(it)?.kind?.name == "TACTIC" }.minBy { ThemeCatalog.priority(it) }
            byTheme.getOrPut(primary) { IntArray(bands.size) }[band]++
            p.themes.forEach { allThemes.merge(it, 1, Int::plus) }
        }
        appendLine("primary theme x rating band:")
        appendLine("  " + "theme".padEnd(18) + bands.joinToString("") { it.padStart(10) } + "     total")
        byTheme.forEach { (t, a) -> appendLine("  " + t.padEnd(18) + a.joinToString("") { it.toString().padStart(10) } + a.sum().toString().padStart(10)) }
        val totals = IntArray(bands.size)
        byTheme.values.forEach { a -> a.forEachIndexed { i, v -> totals[i] += v } }
        appendLine("  " + "ALL".padEnd(18) + totals.joinToString("") { it.toString().padStart(10) } + totals.sum().toString().padStart(10))
        appendLine("all themes (a puzzle may carry several): " + allThemes.entries.joinToString { "${it.key}=${it.value}" })
    }
}

internal fun bandIndex(edges: List<Int>, rating: Int): Int {
    if (rating < edges.first() || rating >= edges.last()) return -1
    for (i in 0 until edges.size - 1) if (rating < edges[i + 1]) return i
    return -1
}

/**
 * Відтворюваний відбір задач з дампа Lichess (два проходи по CSV, пам'ять обмежена):
 * 1) підрахунок придатних за дешевими фільтрами по клітинках «головна тема × діапазон»;
 * 2) квоти по діапазонах за вагами й вирівнювання між темами (клітинки з нестачею віддають залишок іншим),
 *    вибір найменших за хешем кандидатів кожної клітинки, перевірка через [ChessRules], докомплектація з запасу.
 */
class PuzzlePackBuilder(private val rules: ChessRules, private val opt: BuildOptions) {
    private val converter = LichessConverter(rules)
    private val selectedThemes: Set<String> = (opt.themes ?: ThemeCatalog.tacticIds).toSet()

    private sealed interface Pre {
        data class Reject(val reason: String, val unknownTag: String? = null) : Pre
        data class Pass(val row: LichessRow, val themes: List<String>, val primary: String, val band: Int) : Pre
    }

    private data class Cell(val primary: String, val band: Int)
    private class Cand(val hash: Long, val line: String)

    fun build(): BuildReport {
        val rejects = TreeMap<String, Long>()
        val unknown = TreeMap<String, Long>()
        val counts = HashMap<Cell, Long>()
        var rows = 0L

        forEachDataLine { line ->
            rows++
            when (val r = prefilter(line)) {
                is Pre.Reject -> {
                    rejects.merge(r.reason, 1L, Long::plus)
                    r.unknownTag?.let { unknown.merge(it, 1L, Long::plus) }
                }
                is Pre.Pass -> counts.merge(Cell(r.primary, r.band), 1L, Long::plus)
            }
        }

        val quotas = allocate(counts)
        val heaps = HashMap<Cell, PriorityQueue<Cand>>()
        forEachDataLine { line ->
            val p = prefilter(line)
            if (p is Pre.Pass) {
                val cell = Cell(p.primary, p.band)
                val quota = quotas[cell] ?: 0
                if (quota > 0) {
                    val keep = quota + quota / 2 + 20
                    val heap = heaps.getOrPut(cell) { PriorityQueue(compareByDescending<Cand> { it.hash }) }
                    val h = hash(p.row.id)
                    if (heap.size < keep) heap.add(Cand(h, line)) else if (h < heap.peek().hash) {
                        heap.poll()
                        heap.add(Cand(h, line))
                    }
                }
            }
        }

        val selected = ArrayList<PuzzleRecord>()
        val invalid = TreeMap<String, Long>()
        var checked = 0L
        var shortfall = 0
        for ((cell, quota) in quotas.entries.sortedWith(compareBy({ it.key.band }, { it.key.primary }))) {
            if (quota <= 0) continue
            val ordered = (heaps[cell] ?: PriorityQueue()).sortedBy { it.hash }
            var taken = 0
            for (cand in ordered) {
                if (taken >= quota) break
                val pre = prefilter(cand.line) as Pre.Pass
                checked++
                when (val c = converter.convert(pre.row, pre.themes)) {
                    is Conversion.Ok -> { selected += c.puzzle; taken++ }
                    is Conversion.Rejected -> c.issues.forEach { invalid.merge(it.problem.name, 1L, Long::plus) }
                }
            }
            shortfall += quota - taken
        }
        selected.sortWith(compareBy<PuzzleRecord> { it.rating }.thenBy { it.id })
        return BuildReport(rows, rejects, unknown, invalid, checked, shortfall, selected, opt.bandEdges)
    }

    /** Записує пакет і оновлює `manifest.json` (запис із тим самим id замінюється). */
    fun write(report: BuildReport) {
        Files.createDirectories(opt.outDir.resolve(PuzzleFormat.PUZZLES_DIR))
        val pack = PuzzlePack(PuzzleFormat.SUPPORTED.toString(), opt.packId, opt.sourceId, opt.toParams(), report.selected)
        writeUtf8(opt.outDir.resolve(PuzzleFormat.packPath(opt.packId)), ContentCodec.encodePack(pack))

        val manifestPath = opt.outDir.resolve(PuzzleFormat.MANIFEST_FILE)
        val old = if (Files.isRegularFile(manifestPath)) {
            ContentCodec.decodeManifest(String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8)).packs
        } else {
            emptyList()
        }
        val entry = PackEntry(opt.packId, PuzzleFormat.KIND_PUZZLES, emptyList(), report.selected.size, listOf(opt.sourceId))
        val packs = (old.filter { it.id != opt.packId } + entry).sortedBy { it.id }
        writeUtf8(manifestPath, ContentCodec.encodeManifest(Manifest(PuzzleFormat.SUPPORTED.toString(), packs)))
    }

    // ---------------------------------------------------------------- внутрішнє

    private fun prefilter(line: String): Pre {
        val row = LichessRow.parse(line) ?: return Pre.Reject("malformed_row")
        if (row.moves.size < 2 || row.moves.size % 2 != 0) return Pre.Reject("moves_count_not_even")
        if (row.moves.size - 1 > opt.maxSolutionPlies) return Pre.Reject("solution_too_long")
        if (row.ratingDeviation > opt.maxRatingDeviation) return Pre.Reject("rating_deviation_too_high")
        if (row.popularity < opt.minPopularity) return Pre.Reject("popularity_too_low")
        if (row.plays < opt.minPlays) return Pre.Reject("too_few_plays")
        val band = bandIndex(opt.bandEdges, row.rating)
        if (band < 0) return Pre.Reject("rating_out_of_range")
        val themes = LinkedHashSet<String>()
        var tooComplex = false
        for (tag in row.tags) {
            when (ThemeCatalog.classify(tag)) {
                TagDisposition.ACCEPTED -> themes += ThemeCatalog.themeForTag(tag)!!.id
                TagDisposition.STRIPPED -> {}
                TagDisposition.TOO_COMPLEX -> tooComplex = true
                null -> return Pre.Reject("unknown_theme_tag", tag)
            }
        }
        if (tooComplex) return Pre.Reject("too_complex_theme")
        val primary = themes.filter { it in selectedThemes }.minByOrNull { ThemeCatalog.priority(it) }
            ?: return Pre.Reject("no_selected_tactic_theme")
        return Pre.Pass(row, themes.sortedBy { ThemeCatalog.priority(it) }, primary, band)
    }

    /**
     * Квоти клітинок: частка діапазону за вагою, у діапазоні вирівнювання «по колу» з обмеженням наявністю;
     * залишок (діапазон вичерпано) спершу йде в наступні діапазони, а що лишилось після всіх, розподіляється
     * по всіх клітинках з вільними задачами.
     */
    private fun allocate(counts: Map<Cell, Long>): Map<Cell, Int> {
        val taken = HashMap<Cell, Int>()
        val bands = opt.bandEdges.size - 1
        val weightSum = opt.bandWeights.sum()
        var carry = 0
        var assigned = 0
        for (band in 0 until bands) {
            val share = if (band == bands - 1) opt.total - assigned else Math.round(opt.total * opt.bandWeights[band] / weightSum).toInt()
            assigned += share
            val budget = share + carry
            carry = waterFill(budget, counts.keys.filter { it.band == band }, counts, taken)
        }
        val left = opt.total - taken.values.sum()
        if (left > 0) waterFill(left, counts.keys.toList(), counts, taken)
        return taken.filterValues { it > 0 }
    }

    /** Розподіляє [budget] порівну між [cells] у межах наявності; повертає нерозподілений залишок. */
    private fun waterFill(budget: Int, cells: List<Cell>, counts: Map<Cell, Long>, taken: MutableMap<Cell, Int>): Int {
        var left = budget
        var active = cells.sortedWith(compareBy({ it.band }, { ThemeCatalog.priority(it.primary) }))
            .filter { counts.getValue(it) > taken.getOrDefault(it, 0) }
        while (left > 0 && active.isNotEmpty()) {
            val share = maxOf(1, left / active.size)
            for (c in active) {
                if (left <= 0) break
                val room = counts.getValue(c) - taken.getOrDefault(c, 0)
                val give = minOf(share.toLong(), room, left.toLong()).toInt()
                taken.merge(c, give, Int::plus)
                left -= give
            }
            active = active.filter { counts.getValue(it) > taken.getOrDefault(it, 0) }
        }
        return left
    }

    private fun hash(id: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest("${opt.seed}:$id".toByteArray(StandardCharsets.UTF_8))
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xFF)
        return h
    }

    private fun forEachDataLine(action: (String) -> Unit) {
        Files.newBufferedReader(opt.input, StandardCharsets.UTF_8).use { r: BufferedReader ->
            var first = true
            while (true) {
                val line = r.readLine() ?: break
                if (first) {
                    first = false
                    require(line.startsWith(LichessRow.HEADER_START)) { "unexpected CSV header: $line" }
                    continue
                }
                if (line.isNotBlank()) action(line)
            }
        }
    }

    private fun writeUtf8(path: Path, text: String) {
        Files.write(path, text.toByteArray(StandardCharsets.UTF_8)) // завжди LF, без BOM
    }
}

/** Перевірка всього дампа правилами (без відбору): скільки рядків Lichess структурно невалідні. */
class DumpAudit(rules: ChessRules, private val input: Path) {
    private val converter = LichessConverter(rules)

    fun run(): String {
        val invalid = TreeMap<String, Long>()
        var rows = 0L
        var bad = 0L
        Files.newBufferedReader(input, StandardCharsets.UTF_8).use { r ->
            r.readLine() // заголовок
            while (true) {
                val line = r.readLine() ?: break
                rows++
                val row = LichessRow.parse(line)
                if (row == null) { bad++; invalid.merge("malformed_row", 1L, Long::plus); continue }
                // Теми не перевіряються: лише FEN, ходи, довжина.
                val themes = listOf("fork")
                val res = converter.convert(row, themes)
                if (res is Conversion.Rejected) {
                    bad++
                    res.issues.filter { it.problem.name != "MATE_THEME_MISMATCH" }.forEach { invalid.merge(it.problem.name, 1L, Long::plus) }
                }
            }
        }
        return buildString {
            appendLine("rows: $rows")
            appendLine("structurally invalid rows: $bad")
            invalid.forEach { (k, v) -> appendLine("  $k: $v") }
        }
    }
}
