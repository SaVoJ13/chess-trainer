// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.chesstrainer.core.rules.ChessRules

/** Одна знахідка перевірки: де (файл/задача) і що. */
data class Finding(val where: String, val message: String)

class ValidationReport(
    val findings: List<Finding>,
    val puzzleCounts: Map<String, Int>,
    /** Id пакетів, перелічених у манифесті (навіть якщо файл не вдалося прочитати). */
    val listedPackIds: Set<String> = emptySet(),
) {
    val ok: Boolean get() = findings.isEmpty()
}

/**
 * Статус джерел із `docs/THIRD_PARTY.md` (MASTER_SPEC 11: контент лише з перевіреним джерелом).
 * Читає рядки таблиць виду `| CT-001 | … | Статус | … |`.
 */
class SourceRegistry(private val statuses: Map<String, String>) {
    fun statusOf(sourceId: String): String? = statuses[sourceId]
    fun isVerified(sourceId: String): Boolean = statuses[sourceId] in VERIFIED

    companion object {
        private val VERIFIED = setOf("Перевірено", "Власний")

        fun parse(markdown: String): SourceRegistry {
            val map = HashMap<String, String>()
            for (line in markdown.lineSequence()) {
                if (!line.startsWith("| ")) continue
                val cells = line.trim().trim('|').split('|').map { it.trim() }
                if (cells.size < 7 || !SOURCE_ID.matches(cells[0])) continue
                map[cells[0]] = cells[6].replace("*", "").trim()
            }
            return SourceRegistry(map)
        }

        private val SOURCE_ID = Regex("[A-Z]{2}-\\d{3}")
    }
}

/**
 * Перевірка готових файлів `content/common` (CONTRACTS 6.1): `manifest.json` і `puzzles/<pack>.json`.
 * Цей клас запускатиме CI; підключення в `.github/` робить Android і UI.
 */
class ContentValidator(
    rules: ChessRules,
    private val registry: SourceRegistry? = null,
    private val requireVerifiedSources: Boolean = false,
) {
    private val checker = PuzzleChecker(rules)

    /** @param commonDir каталог `content/common`. */
    fun validateDirectory(commonDir: Path): ValidationReport {
        val manifestFile = commonDir.resolve(PuzzleFormat.MANIFEST_FILE)
        if (!Files.isRegularFile(manifestFile)) {
            return ValidationReport(listOf(Finding(PuzzleFormat.MANIFEST_FILE, "file not found")), emptyMap())
        }
        val findings = ArrayList<Finding>()
        val report = validate(
            read(manifestFile),
            { packId ->
                val f = commonDir.resolve(PuzzleFormat.packPath(packId))
                if (Files.isRegularFile(f)) read(f) else null
            },
        )
        findings += report.findings
        // Файли пакетів, яких немає в маніфесті, також помилка: інакше зіпсований файл лишиться непоміченим.
        val puzzlesDir = commonDir.resolve(PuzzleFormat.PUZZLES_DIR)
        if (Files.isDirectory(puzzlesDir)) {
            val known = report.listedPackIds
            Files.list(puzzlesDir).use { s ->
                s.map { it.fileName.toString() }.sorted().forEach { name ->
                    if (name.endsWith(".json") && name.removeSuffix(".json") !in known) {
                        findings += Finding("puzzles/$name", "pack file is not listed in manifest.json")
                    }
                }
            }
        }
        return ValidationReport(findings, report.puzzleCounts, report.listedPackIds)
    }

    /** @param packText повертає вміст `puzzles/<packId>.json` або null, якщо файлу немає. */
    fun validate(manifestText: String, packText: (String) -> String?): ValidationReport {
        val findings = ArrayList<Finding>()
        val counts = LinkedHashMap<String, Int>()
        val manifestName = PuzzleFormat.MANIFEST_FILE

        val version = try {
            checkVersion(ContentCodec.peekSchemaVersion(manifestText), manifestName, findings)
        } catch (e: ContentFormatException) {
            findings += Finding(manifestName, e.message.orEmpty())
            return ValidationReport(findings, counts, emptySet())
        }
        if (!version) return ValidationReport(findings, counts, emptySet())

        val manifest = try {
            ContentCodec.decodeManifest(manifestText)
        } catch (e: ContentFormatException) {
            findings += Finding(manifestName, e.message.orEmpty())
            return ValidationReport(findings, counts, emptySet())
        }

        val seenPackIds = LinkedHashSet<String>()
        val seenPuzzleIds = HashMap<String, String>()
        for (entry in manifest.packs) {
            val where = "$manifestName: pack '${entry.id}'"
            if (!PuzzleFormat.PACK_ID_REGEX.matches(entry.id)) findings += Finding(where, "bad pack id")
            if (!seenPackIds.add(entry.id)) findings += Finding(where, "duplicate pack id")
            if (entry.kind != PuzzleFormat.KIND_PUZZLES) {
                // Інші види (LESSONS, OPENINGS) цією версією перевірки не покриваються: не помилка.
                continue
            }
            val file = PuzzleFormat.packPath(entry.id)
            val text = packText(entry.id)
            if (text == null) {
                findings += Finding(where, "file $file not found")
                continue
            }
            val pack = readPack(file, text, findings) ?: continue
            counts[entry.id] = pack.puzzles.size
            if (pack.packId != entry.id) findings += Finding(file, "packId '${pack.packId}' differs from manifest id '${entry.id}'")
            if (entry.itemCount != pack.puzzles.size) {
                findings += Finding(file, "manifest itemCount ${entry.itemCount} differs from ${pack.puzzles.size} puzzles")
            }
            if (entry.sourceIds != listOf(pack.sourceId)) {
                findings += Finding(file, "manifest sourceIds ${entry.sourceIds} differ from pack sourceId '${pack.sourceId}'")
            }
            checkSource(pack.sourceId, file, findings)
            for (p in pack.puzzles) {
                val at = "$file: ${p.id}"
                seenPuzzleIds.put(p.id, entry.id)?.let { findings += Finding(at, "duplicate puzzle id (also in pack '$it')") }
                for (issue in checker.check(p)) findings += Finding(at, "${issue.problem}: ${issue.detail}")
            }
        }
        return ValidationReport(findings, counts, seenPackIds)
    }

    private fun readPack(file: String, text: String, findings: MutableList<Finding>): PuzzlePack? =
        try {
            if (!checkVersion(ContentCodec.peekSchemaVersion(text), file, findings)) null else ContentCodec.decodePack(text)
        } catch (e: ContentFormatException) {
            findings += Finding(file, e.message.orEmpty())
            null
        }

    /** true = версію можна читати. */
    private fun checkVersion(text: String, where: String, findings: MutableList<Finding>): Boolean {
        val v = FormatVersion.parse(text)
        if (v == null) {
            findings += Finding(where, "schemaVersion '$text' is not MAJOR.MINOR")
            return false
        }
        if (v.major != PuzzleFormat.SUPPORTED.major) {
            findings += Finding(where, "unsupported schema MAJOR ${v.major} (supported ${PuzzleFormat.SUPPORTED.major}.x)")
            return false
        }
        return true
    }

    private fun checkSource(sourceId: String, where: String, findings: MutableList<Finding>) {
        if (!PuzzleFormat.SOURCE_ID_REGEX.matches(sourceId)) {
            findings += Finding(where, "sourceId '$sourceId' is not a THIRD_PARTY id")
            return
        }
        val reg = registry ?: return
        val status = reg.statusOf(sourceId)
        when {
            status == null -> findings += Finding(where, "sourceId $sourceId is not in THIRD_PARTY.md")
            requireVerifiedSources && !reg.isVerified(sourceId) ->
                findings += Finding(where, "sourceId $sourceId has status '$status' (need Перевірено or Власний)")
        }
    }

    private fun read(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

}
