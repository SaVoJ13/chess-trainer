// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.licenses

import java.io.File
import org.chesstrainer.app.ui.settings.noteRes
import org.chesstrainer.app.ui.settings.sectionIntroRes
import org.chesstrainer.app.ui.settings.sectionTitleRes
import org.chesstrainer.learning.content.ContentFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Відображення даних звіту ліцензій і заголовки розділів (CONTRACTS 5.1). Тести читають справжні файли
 * проєкту (`app/licenses/…`, `LICENSE`), тож ловлять розбіжність звіту, каталогу й текстів ліцензій.
 */
class LicensesTest {

    private val report = LicenseParsing.parseReport(File("licenses/aboutlibraries.json").readText())
    private val catalog = LicenseParsing.parseComponents(File("licenses/components.json").readText())
    private val data = LicenseSections.build("Chess Trainer", "0.1.0", report, catalog)

    private fun section(id: LicenseSectionId) = data.sections.single { it.id == id }

    private fun textFile(id: String) =
        if (id == GPL_TEXT_ID) File("../LICENSE") else File("licenses/texts/$id.txt")

    // --- розділи ---

    @Test
    fun hasExactlyFiveSectionsInContractOrder() {
        assertEquals(
            listOf(
                LicenseSectionId.APP,
                LicenseSectionId.NATIVE,
                LicenseSectionId.LIBRARIES,
                LicenseSectionId.CONTENT,
                LicenseSectionId.PIECE_SETS,
            ),
            data.sections.map { it.id },
        )
    }

    @Test
    fun appSectionShowsNameVersionLicenseAndSourceLink() {
        val app = section(LicenseSectionId.APP).entries.single()
        assertEquals("Chess Trainer", app.title)
        assertEquals("0.1.0", app.version)
        assertEquals("GPL-3.0-or-later", app.licenseLabel)
        assertEquals(listOf(GPL_TEXT_ID), app.textIds)
        assertTrue(app.links.single().startsWith("https://github.com/"))
    }

    @Test
    fun nativeSectionHasEngineNetworkAndLibcxx() {
        val native = section(LicenseSectionId.NATIVE).entries
        assertEquals(listOf("SW-001", "SW-008", "SW-018"), native.map { it.key })
        val stockfish = native.first()
        assertEquals("Stockfish", stockfish.title)
        assertEquals("sf_19", stockfish.version)
        assertEquals("GPL-3.0-or-later", stockfish.licenseLabel)
        assertTrue("посилання на код тегу sf_19", stockfish.links.first().endsWith("/tree/sf_19"))
        val libcxx = native.last()
        assertEquals(listOf("Apache-2.0", "LLVM-exception"), libcxx.textIds)
        assertTrue(native[1].noteIds.contains("nnue_lc0"))
    }

    @Test
    fun contentAndPieceSetSectionsAreReady() {
        assertEquals(listOf("CT-001"), section(LicenseSectionId.CONTENT).entries.map { it.key })
        val pieces = section(LicenseSectionId.PIECE_SETS).entries
        assertEquals(listOf("chess_trainer"), pieces.map { it.title })
        assertFalse("власний набір не перетворювався", pieces.single().converted)
    }

    @Test
    fun externalPieceSetIsShownWithAuthorLicenseSourceAndConversionMark() {
        val external = catalog.copy(
            pieceSets = catalog.pieceSets + ComponentEntry(
                id = "AS-001", name = "cburnett", version = "", authors = "Some Author", license = "MIT",
                texts = listOf("MIT"), source = "https://example.org/set", links = emptyList(), noteIds = emptyList(),
                converted = true,
            ),
        )
        val entry = LicenseSections.build("X", "1", report, external)
            .sections.single { it.id == LicenseSectionId.PIECE_SETS }.entries.last()
        assertEquals("cburnett", entry.title)
        assertEquals("Some Author", entry.authors)
        assertEquals("MIT", entry.licenseLabel)
        assertEquals(listOf("https://example.org/set"), entry.links)
        assertTrue(entry.converted)
    }

    // --- звіт бібліотек ---

    @Test
    fun everyLibraryInTheReportIsShownExactlyOnce() {
        val shown = section(LicenseSectionId.LIBRARIES).entries
        assertTrue("у звіті має бути багато бібліотек", report.libraries.size >= 60)
        assertEquals(report.libraries.size, shown.size)
        assertEquals(report.libraries.map { "lib:${it.uniqueId}" }.toSet(), shown.map { it.key }.toSet())
        assertEquals(shown.size, shown.map { it.key }.toSet().size)
    }

    @Test
    fun everyLibraryHasALicenseAndEveryLicenseHasAnOfflineText() {
        for (lib in report.libraries) {
            assertTrue("${lib.uniqueId} без ліцензії (POM порожній? див. licenses/config)", lib.licenseIds.isNotEmpty())
            for (id in lib.licenseIds) {
                assertNotNull("ліцензії $id немає в розділі licenses звіту", report.licenses[id])
                assertTrue("немає офлайн-тексту ліцензії $id", textFile(id).isFile)
            }
        }
    }

    @Test
    fun chesslibLicenseComesFromManualConfigNotFromPom() {
        val chesslib = report.libraries.single { it.uniqueId == "com.github.bhlangonijr:chesslib" }
        assertEquals(listOf("Apache-2.0"), chesslib.licenseIds)
        assertEquals("1.3.7", chesslib.version)
    }

    @Test
    fun librariesSectionListsEachLicenseTextOnceAndIsSortedByName() {
        val libs = section(LicenseSectionId.LIBRARIES)
        assertEquals(libs.sharedTexts.map { it.id }.toSet().size, libs.sharedTexts.size)
        assertTrue(libs.sharedTexts.any { it.id == "Apache-2.0" })
        val names = libs.entries.map { it.title.lowercase() }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun commonsLangNoticeIsAttachedAndPresentOffline() {
        val lang = section(LicenseSectionId.LIBRARIES).entries.single { it.key == "lib:org.apache.commons:commons-lang3" }
        assertEquals("NOTICE-commons-lang3", lang.noticeId)
        assertTrue(textFile(lang.noticeId!!).readText().contains("The Apache Software Foundation"))
    }

    // --- тексти й посилання ---

    @Test
    fun everyTextIdOfTheCatalogExistsAndGplIsTheRepositoryLicenseFile() {
        val ids = (catalog.native + catalog.content + catalog.pieceSets).flatMap { it.texts } + GPL_TEXT_ID + catalog.notices.values
        for (id in ids.distinct()) assertTrue("немає тексту $id", textFile(id).isFile)
        assertTrue(File("../LICENSE").readText().contains("GNU GENERAL PUBLIC LICENSE"))
        assertEquals("licenses/GPL-3.0.txt", LicenseTexts.assetPath(GPL_TEXT_ID))
        assertEquals("licenses/texts/Apache-2.0.txt", LicenseTexts.assetPath("Apache-2.0"))
    }

    @Test
    fun allLinksAreHttpsSoTheSystemCanOpenThemOnTap() {
        val links = data.sections.flatMap { s -> s.entries.flatMap { it.links } }
        assertTrue(links.isNotEmpty())
        for (url in links) assertTrue("не https: $url", LicenseTextFormat.isOpenableLink(url))
        assertFalse(LicenseTextFormat.isOpenableLink("http://example.org"))
        assertFalse(LicenseTextFormat.isOpenableLink("file:///etc/passwd"))
        assertFalse(LicenseTextFormat.isOpenableLink("https://exa mple.org"))
    }

    @Test
    fun everyNoteIdHasATranslatableString() {
        val notes = data.sections.flatMap { s -> s.entries.flatMap { it.noteIds } }.toSet()
        assertTrue(notes.isNotEmpty())
        for (id in notes) {
            assertNotNull("немає noteRes для $id", noteRes(id))
            assertTrue("немає en licenses_note_$id", en.containsKey("licenses_note_$id"))
            assertTrue("немає uk licenses_note_$id", uk.containsKey("licenses_note_$id"))
        }
        assertNull(noteRes("unknown"))
    }

    // --- заголовки розділів ---

    private fun keys(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val en = keys("src/main/res/values/strings.xml")
    private val uk = keys("src/main/res/values-uk/strings.xml")

    @Test
    fun everySectionHasATitleAndIntroInBothLanguages() {
        val titles = LicenseSectionId.entries.map { sectionTitleRes(it) }
        assertEquals("заголовки різні", titles.size, titles.toSet().size)
        assertEquals(LicenseSectionId.entries.size, LicenseSectionId.entries.map { sectionIntroRes(it) }.toSet().size)
        val names = mapOf(
            LicenseSectionId.APP to "app",
            LicenseSectionId.NATIVE to "native",
            LicenseSectionId.LIBRARIES to "libraries",
            LicenseSectionId.CONTENT to "content",
            LicenseSectionId.PIECE_SETS to "piece_sets",
        )
        for ((id, name) in names) {
            for ((lang, strings) in listOf("en" to en, "uk" to uk)) {
                assertTrue("$lang: немає заголовка $id", strings["licenses_section_$name"].orEmpty().isNotBlank())
                assertTrue("$lang: немає пояснення $id", strings["licenses_section_${name}_intro"].orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun settingsAboutAndLicensesStringsMatchInBothLanguagesIncludingPlaceholders() {
        val prefixes = listOf("settings_", "about_", "licenses_", "link_")
        fun pick(m: Map<String, String>) = m.filterKeys { k -> prefixes.any { k.startsWith(it) } }
        val e = pick(en)
        val u = pick(uk)
        assertEquals(e.keys, u.keys)
        assertTrue(e.size >= 40)
        val placeholder = Regex("%\\d\\\$s")
        for (key in e.keys) {
            assertEquals("плейсхолдери $key", placeholder.findAll(e.getValue(key)).map { it.value }.toList(),
                placeholder.findAll(u.getValue(key)).map { it.value }.toList())
            assertTrue("порожній $key", e.getValue(key).isNotBlank() && u.getValue(key).isNotBlank())
        }
    }

    @Test
    fun ukrainianTextsAreNotLeftInEnglishAndViceVersa() {
        assertEquals("Відкриті ліцензії", uk["settings_item_licenses"])
        assertEquals("Open-source licenses", en["settings_item_licenses"])
        assertEquals("Про застосунок", uk["settings_item_about"])
        assertEquals("About the app", en["settings_item_about"])
    }

    // --- розбір ---

    @Test
    fun parserToleratesMissingOptionalFieldsAndKeepsOrderOfLicenses() {
        val parsed = LicenseParsing.parseReport(
            """{"libraries":[{"uniqueId":"a:b","licenses":["X"]}],"licenses":{"X":{"name":"X license","url":"https://x"}}}""",
        )
        val lib = parsed.libraries.single()
        assertEquals("a:b", lib.name)
        assertEquals("", lib.version)
        assertEquals(emptyList<String>(), lib.developers)
        assertEquals(LicenseInfo("X", "X license", "https://x"), parsed.licenses["X"])
    }

    @Test
    fun brokenInputRaisesFormatExceptionInsteadOfCrashing() {
        for (bad in listOf("", "[", """{"libraries":{}}""", """{"libraries":[{"name":"no id"}]}""", """{"libraries":[1]}""")) {
            try {
                LicenseParsing.parseReport(bad)
                fail("очікувався ContentFormatException для: $bad")
            } catch (_: ContentFormatException) {
            }
        }
        try {
            LicenseParsing.parseComponents("""{"schema":1}""")
            fail("очікувався ContentFormatException: немає app")
        } catch (_: ContentFormatException) {
        }
    }

    @Test
    fun longDeveloperListIsShortenedToThreeNames() {
        val r = LibraryReport(
            listOf(LibraryEntry("a:b", "B", "1", listOf("1", "2", "3", "4"), listOf("Apache-2.0"))),
            mapOf("Apache-2.0" to LicenseInfo("Apache-2.0", "The Apache License, Version 2.0", null)),
        )
        val entry = LicenseSections.build("A", "1", r, catalog).sections.single { it.id == LicenseSectionId.LIBRARIES }.entries.single()
        assertEquals("1, 2, 3, …", entry.authors)
    }

    // --- показ довгого тексту ---

    @Test
    fun paragraphsJoinHardWrapsAndKeepEveryWord() {
        val text = "One two\n  three\r\n\n\n   Four\nfive  six\n\n"
        assertEquals(listOf("One two three", "Four five six"), LicenseTextFormat.paragraphs(text))
        for (id in listOf("Apache-2.0", "LLVM-exception", "GPL-3.0")) {
            val original = textFile(id).readText()
            val words = original.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val shown = LicenseTextFormat.paragraphs(original).joinToString(" ").split(" ").filter { it.isNotEmpty() }
            assertEquals("слова $id змінились", words, shown)
        }
        assertTrue(LicenseTextFormat.paragraphs(textFile("Apache-2.0").readText()).size > 30)
    }
}
