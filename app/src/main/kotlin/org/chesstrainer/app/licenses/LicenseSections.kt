// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.licenses

import java.util.Locale

/** Ідентифікатор повного тексту GPL-3.0 (беруть з файлу `LICENSE` репозиторію під час збірки). */
const val GPL_TEXT_ID = "GPL-3.0"

/** Складає п'ять розділів екрана «Відкриті ліцензії» з даних звіту й каталогу компонентів (CONTRACTS 5.1, е). */
object LicenseSections {

    private const val MAX_AUTHORS = 3

    fun build(appName: String, appVersion: String, report: LibraryReport, catalog: ComponentCatalog): LicensesData {
        val app = LicenseSection(
            LicenseSectionId.APP,
            listOf(
                LicenseEntry(
                    key = "app",
                    title = appName,
                    version = appVersion,
                    authors = catalog.app.copyright,
                    licenseLabel = catalog.app.license,
                    textIds = listOf(GPL_TEXT_ID),
                    noticeId = null,
                    links = listOf(catalog.app.source),
                    noteIds = listOf("app_source"),
                    converted = false,
                ),
            ),
        )
        val libraries = libraries(report, catalog)
        return LicensesData(
            catalog.app,
            listOf(
                app,
                LicenseSection(LicenseSectionId.NATIVE, catalog.native.map(::entry)),
                libraries,
                LicenseSection(LicenseSectionId.CONTENT, catalog.content.map(::entry)),
                LicenseSection(LicenseSectionId.PIECE_SETS, catalog.pieceSets.map(::entry)),
            ),
        )
    }

    private fun libraries(report: LibraryReport, catalog: ComponentCatalog): LicenseSection {
        val entries = report.libraries
            .sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.uniqueId }))
            .map { lib ->
                LicenseEntry(
                    key = "lib:${lib.uniqueId}",
                    title = lib.name,
                    version = lib.version,
                    authors = authors(lib.developers),
                    licenseLabel = lib.licenseIds.joinToString(", "),
                    textIds = emptyList(),
                    noticeId = catalog.notices[lib.uniqueId],
                    links = emptyList(),
                    noteIds = emptyList(),
                    converted = false,
                )
            }
        // Кожен текст ліцензії показується в розділі один раз, а не під кожною з десятків бібліотек.
        val shared = report.libraries.flatMap { it.licenseIds }.distinct().map { id ->
            SharedText(id, report.licenses[id]?.name ?: id)
        }
        return LicenseSection(LicenseSectionId.LIBRARIES, entries, shared)
    }

    private fun entry(c: ComponentEntry) = LicenseEntry(
        key = c.id,
        title = c.name,
        version = c.version,
        authors = c.authors,
        licenseLabel = c.license,
        textIds = c.texts,
        noticeId = null,
        links = listOf(c.source) + c.links,
        noteIds = c.noteIds,
        converted = c.converted,
    )

    private fun authors(developers: List<String>): String {
        val shown = developers.take(MAX_AUTHORS).joinToString(", ")
        return if (developers.size > MAX_AUTHORS) "$shown, …" else shown
    }
}

/** Підготовка довгих текстів до показу. */
object LicenseTextFormat {

    /**
     * Ділить текст на абзаци за порожніми рядками й склеює жорсткі переноси всередині абзацу в пробіли,
     * щоб текст переносився за шириною екрана (також при великому шрифті). Слова й їхній порядок не змінюються.
     */
    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n"))
            .map { block -> block.lines().joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ").trim() }
            .filter { it.isNotEmpty() }

    /** Посилання відкриваються лише за https (дозвіл `INTERNET` застосунку не потрібен: URL відкриває система). */
    fun isOpenableLink(url: String): Boolean = url.startsWith("https://") && !url.any { it.isWhitespace() }
}
