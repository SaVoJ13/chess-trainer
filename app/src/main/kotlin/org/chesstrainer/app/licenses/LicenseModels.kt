// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.licenses

/** П'ять розділів екрана «Відкриті ліцензії» у порядку показу (CONTRACTS 5.1, е). */
enum class LicenseSectionId { APP, NATIVE, LIBRARIES, CONTENT, PIECE_SETS }

/** Ліцензія зі звіту AboutLibraries: [id] це ключ у `licenses` звіту (SPDX, якщо відомий). */
data class LicenseInfo(val id: String, val name: String, val url: String?)

/** Бібліотека Gradle зі звіту (SW-006). Назви, автори й ідентифікатори ліцензій не перекладаються. */
data class LibraryEntry(
    val uniqueId: String,
    val name: String,
    val version: String,
    val developers: List<String>,
    val licenseIds: List<String>,
)

/** Звіт AboutLibraries, прочитаний з `app/licenses/aboutlibraries.json`. */
data class LibraryReport(val libraries: List<LibraryEntry>, val licenses: Map<String, LicenseInfo>)

/**
 * Компонент поза Gradle (нативний, вміст, набір фігур) з `app/licenses/components.json`.
 *
 * [texts] це ідентифікатори текстів ліцензій, що лежать у застосунку ([LicenseTexts]); [noteIds] ключі коротких
 * пояснень, які перекладаються (uk, en) у `strings.xml`; решта полів не перекладається.
 */
data class ComponentEntry(
    val id: String,
    val name: String,
    val version: String,
    val authors: String,
    val license: String,
    val texts: List<String>,
    val source: String,
    val links: List<String>,
    val noteIds: List<String>,
    val converted: Boolean,
)

/** Відомості про сам застосунок (розділ 1). */
data class AppLicenseInfo(val license: String, val copyright: String, val source: String)

data class ComponentCatalog(
    val app: AppLicenseInfo,
    val native: List<ComponentEntry>,
    val content: List<ComponentEntry>,
    val pieceSets: List<ComponentEntry>,
    /** `uniqueId` бібліотеки зі звіту → ідентифікатор тексту її NOTICE (Apache-2.0 §4(d), правило 14 реєстру). */
    val notices: Map<String, String>,
)

/** Один запис екрана: бібліотека чи компонент, приведені до спільного вигляду. */
data class LicenseEntry(
    val key: String,
    val title: String,
    val version: String,
    val authors: String,
    /** Підпис ліцензії як у джерелі (не перекладається). */
    val licenseLabel: String,
    /** Тексти ліцензій, які можна розгорнути під записом. */
    val textIds: List<String>,
    /** Текст NOTICE, якщо він є. */
    val noticeId: String?,
    val links: List<String>,
    val noteIds: List<String>,
    val converted: Boolean,
)

class LicenseSection(
    val id: LicenseSectionId,
    val entries: List<LicenseEntry>,
    /** Тексти ліцензій, спільні для всього розділу (розділ бібліотек: кожен текст один раз). */
    val sharedTexts: List<SharedText> = emptyList(),
)

/** Спільний текст ліцензії: [label] береться зі звіту (назва ліцензії), [id] ключ тексту. */
data class SharedText(val id: String, val label: String)

class LicensesData(val app: AppLicenseInfo, val sections: List<LicenseSection>)

/** Дані екрана «Про застосунок». [license] і [sourceUrl] не перекладаються. */
data class AboutInfo(val name: String, val version: String, val license: String, val sourceUrl: String)
