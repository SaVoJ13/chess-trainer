// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.puzzle

import java.io.File
import org.chesstrainer.app.ui.puzzle.themeNameRes
import org.chesstrainer.learning.content.ThemeCatalog
import org.chesstrainer.learning.puzzle.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Назви тем і тексти екрана задач існують в обох мовах (MASTER_SPEC 3: uk і en). */
class PuzzleStringsTest {

    private fun keys(path: String): Map<String, String> {
        val text = File(path).readText()
        return Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val en = keys("src/main/res/values/strings.xml")
    private val uk = keys("src/main/res/values-uk/strings.xml")

    @Test
    fun everyCatalogThemeHasAResourceAndBothTranslations() {
        for (info in ThemeCatalog.accepted) {
            assertNotNull("немає themeNameRes для ${info.id}", themeNameRes(ThemeId(info.id)))
            assertTrue("немає en puzzle_theme_${info.id}", en["puzzle_theme_${info.id}"].orEmpty().isNotBlank())
            assertTrue("немає uk puzzle_theme_${info.id}", uk["puzzle_theme_${info.id}"].orEmpty().isNotBlank())
        }
    }

    @Test
    fun ownerDecidedNamesAreUsed() {
        assertEquals("Наскрізний удар", uk["puzzle_theme_skewer"])
        assertEquals("Міттельшпіль", uk["puzzle_theme_middlegame"])
    }

    @Test
    fun puzzleStringsExistInBothLanguages() {
        val enPuzzle = en.keys.filter { it.startsWith("puzzle_") }.toSet()
        val ukPuzzle = uk.keys.filter { it.startsWith("puzzle_") }.toSet()
        assertEquals(enPuzzle, ukPuzzle)
        assertTrue(enPuzzle.size >= 40)
    }

    @Test
    fun uiNeverCallsLevelsLichessRating() {
        (en.filterKeys { it.startsWith("puzzle_") } + uk.filterKeys { it.startsWith("puzzle_") }).forEach { (k, v) ->
            assertTrue("$k згадує Lichess", !v.contains("lichess", ignoreCase = true))
        }
    }
}
