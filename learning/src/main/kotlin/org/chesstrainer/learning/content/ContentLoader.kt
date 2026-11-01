// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

import java.io.IOException
import java.io.InputStream
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules
import org.chesstrainer.learning.puzzle.Puzzle
import org.chesstrainer.learning.puzzle.PuzzleId
import org.chesstrainer.learning.puzzle.ThemeId

/** Проблема, знайдена під час завантаження контенту. */
sealed interface ContentProblem {
    val where: String

    /** Версія файлу має незнайомий MAJOR (читаємо лише MAJOR 1). */
    data class UnsupportedSchema(override val where: String, val version: String) : ContentProblem

    /** Файл не JSON або не відповідає схемі. */
    data class Malformed(override val where: String, val reason: String) : ContentProblem

    /** Файл з манифесту не знайдено або не вдалося прочитати. */
    data class MissingPack(override val where: String) : ContentProblem

    /** Вид пакета, якого ця версія не знає (запис пропущено). */
    data class UnknownKind(override val where: String, val kind: String) : ContentProblem

    data class ItemCountMismatch(override val where: String, val declared: Int, val actual: Int) : ContentProblem

    /** Задачу пропущено: [issues] це коди `PuzzleProblem` з деталями. */
    data class InvalidPuzzle(override val where: String, val issues: List<String>) : ContentProblem

    data class DuplicatePuzzle(override val where: String) : ContentProblem
}

/** Результат завантаження: придатні задачі й усе, що довелося пропустити. */
class LoadedContent(
    val manifest: ContentManifest,
    val puzzles: List<Puzzle>,
    val problems: List<ContentProblem>,
) {
    fun source(): ContentSource = InMemoryContentSource(manifest, puzzles)
}

sealed interface ContentLoadResult {
    data class Loaded(val content: LoadedContent) : ContentLoadResult

    /** Манифест непридатний (нечитабельний або незнайомий MAJOR): контенту немає, застосунок не падає. */
    data class Failed(val problem: ContentProblem) : ContentLoadResult
}

/** Рівень перевірки задач під час завантаження. */
enum class LoadValidation {
    /** Дешево: форма id/FEN/ходів, теми, рейтинг. Правила шахів не викликаються. */
    STRUCTURE,

    /** Те саме, що `validateContent`: усі ходи через `ChessRules`, мат для mate_in_N (повільніше; для тестів і ПК). */
    FULL,
}

/**
 * Розбір `manifest.json` і файлів пакетів `puzzles/<id>.json` у типи контракту (CONTRACTS 6.1). Чиста JVM, без залежності від android
 * Спосіб читання файлів задає виклик: Android і UI подає потоки з assets, тести рядки.
 *
 * Правила: незнайомий MAJOR манифесту дає [ContentLoadResult.Failed]; незнайомий MAJOR або поламаний файл пакета
 * призводить до пропуску пакета з проблемою; невалідні задачі й повтори id пропускаються; `PuzzleRecord.fen`
 * стає `Puzzle.startFen`, `"1.0"` стає `SchemaVersion(1, 0)`, `sourceId` пакета переноситься в кожну задачу.
 * Читання файлів блокуюче: викликати поза головним потоком.
 */
class ContentLoader(
    rules: ChessRules,
    private val validation: LoadValidation = LoadValidation.STRUCTURE,
) {
    private val checker = PuzzleChecker(rules)

    /** @param packText повертає текст `puzzles/<packId>.json` або null, якщо файлу немає. */
    fun load(manifestText: String, packText: (packId: String) -> String?): ContentLoadResult {
        val name = PuzzleFormat.MANIFEST_FILE
        val manifest: Manifest
        val version: FormatVersion
        try {
            version = FormatVersion.parse(ContentCodec.peekSchemaVersion(manifestText))
                ?: return failed(ContentProblem.Malformed(name, "schemaVersion is not MAJOR.MINOR"))
            if (version.major != PuzzleFormat.SUPPORTED.major) {
                return failed(ContentProblem.UnsupportedSchema(name, version.toString()))
            }
            manifest = ContentCodec.decodeManifest(manifestText)
        } catch (e: ContentFormatException) {
            return failed(ContentProblem.Malformed(name, e.message.orEmpty()))
        }

        val problems = ArrayList<ContentProblem>()
        val packs = ArrayList<ContentPack>()
        val puzzles = ArrayList<Puzzle>()
        val seen = HashSet<String>()
        for (entry in manifest.packs) {
            val kind = ContentKind.values().firstOrNull { it.name == entry.kind }
            if (kind == null) {
                problems += ContentProblem.UnknownKind("$name: pack '${entry.id}'", entry.kind)
                continue
            }
            packs += ContentPack(entry.id, kind, entry.languages.toSet(), entry.itemCount, entry.sourceIds.toSet())
            if (kind == ContentKind.PUZZLES) loadPack(entry, packText, seen, puzzles, problems)
        }
        return ContentLoadResult.Loaded(
            LoadedContent(ContentManifest(SchemaVersion(version.major, version.minor), packs), puzzles, problems),
        )
    }

    /**
     * То саме з потоками (UTF-8), напр. `assets.open("content/common/manifest.json")`. [openPack] повертає потік
     * для `puzzles/<packId>.json` або null. Потоки закриваються тут. Помилка читання пакета дає
     * [ContentProblem.MissingPack], помилка читання манифесту дає [ContentLoadResult.Failed].
     */
    fun load(manifest: InputStream, openPack: (packId: String) -> InputStream?): ContentLoadResult {
        val manifestText = try {
            manifest.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: IOException) {
            return failed(ContentProblem.MissingPack(PuzzleFormat.MANIFEST_FILE))
        }
        return load(manifestText) { id ->
            try {
                openPack(id)?.use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (e: IOException) {
                null
            }
        }
    }

    private fun loadPack(
        entry: PackEntry,
        packText: (String) -> String?,
        seen: MutableSet<String>,
        out: MutableList<Puzzle>,
        problems: MutableList<ContentProblem>,
    ) {
        val file = PuzzleFormat.packPath(entry.id)
        val text = packText(entry.id)
        if (text == null) {
            problems += ContentProblem.MissingPack(file)
            return
        }
        val pack = try {
            val v = FormatVersion.parse(ContentCodec.peekSchemaVersion(text))
            if (v == null) {
                problems += ContentProblem.Malformed(file, "schemaVersion is not MAJOR.MINOR")
                return
            }
            if (v.major != PuzzleFormat.SUPPORTED.major) {
                problems += ContentProblem.UnsupportedSchema(file, v.toString())
                return
            }
            ContentCodec.decodePack(text)
        } catch (e: ContentFormatException) {
            problems += ContentProblem.Malformed(file, e.message.orEmpty())
            return
        }
        if (pack.packId != entry.id) {
            problems += ContentProblem.Malformed(file, "packId '${pack.packId}' differs from manifest id '${entry.id}'")
            return
        }
        if (entry.itemCount != pack.puzzles.size) {
            problems += ContentProblem.ItemCountMismatch(file, entry.itemCount, pack.puzzles.size)
        }
        for (record in pack.puzzles) {
            val at = "$file: ${record.id}"
            val issues = issuesOf(record)
            when {
                issues.isNotEmpty() -> problems += ContentProblem.InvalidPuzzle(at, issues)
                !seen.add(record.id) -> problems += ContentProblem.DuplicatePuzzle(at)
                else -> out += record.toPuzzle(pack.sourceId)
            }
        }
    }

    private fun issuesOf(r: PuzzleRecord): List<String> = when (validation) {
        LoadValidation.FULL -> checker.check(r).map { "${it.problem}: ${it.detail}" }
        LoadValidation.STRUCTURE -> structureIssues(r)
    }

    private fun structureIssues(r: PuzzleRecord): List<String> {
        val issues = ArrayList<String>()
        if (!ID_REGEX.matches(r.id)) issues += "${PuzzleProblem.BAD_ID}: ${r.id}"
        if (r.fen.split(' ').size != 6) issues += "${PuzzleProblem.INVALID_FEN}: ${r.fen}"
        if (r.solution.isEmpty()) {
            issues += "${PuzzleProblem.EMPTY_SOLUTION}: "
        } else if (r.solution.size % 2 == 0) {
            issues += "${PuzzleProblem.EVEN_SOLUTION_LENGTH}: ${r.solution.size}"
        }
        r.solution.firstOrNull { !UCI_REGEX.matches(it) }?.let { issues += "${PuzzleProblem.ILLEGAL_MOVE}: $it" }
        checker.checkMeta(r.themes, r.rating).mapTo(issues) { "${it.problem}: ${it.detail}" }
        return issues
    }

    private fun failed(p: ContentProblem) = ContentLoadResult.Failed(p)

    private companion object {
        val ID_REGEX = Regex("[A-Za-z0-9_-]{1,32}")
        val UCI_REGEX = Regex("[a-h][1-8][a-h][1-8][qrbn]?")
    }
}

/** Запис файлу → `Puzzle` (CONTRACTS 6.1): `fen` → `startFen`, `sourceId` береться з пакета. */
fun PuzzleRecord.toPuzzle(sourceId: String): Puzzle = Puzzle(
    id = PuzzleId(id),
    startFen = Fen(fen),
    solution = solution.map { Uci(it) },
    themes = themes.mapTo(LinkedHashSet()) { ThemeId(it) },
    rating = rating,
    sourceId = sourceId,
)
