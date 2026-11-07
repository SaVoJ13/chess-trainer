// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.math.pow
import org.chesstrainer.app.settings.AppearanceOptions
import org.chesstrainer.app.settings.BoardThemeId
import org.chesstrainer.app.ui.settings.boardThemeNameRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Теми дошки (CONTRACTS 5.1): перелік, стабільні id, назви в обох мовах, контраст `high_contrast` ≥ 3:1. */
class BoardThemesTest {

    private val high = BoardThemes.highContrast.palette

    @Test
    fun contractThemesInContractOrderAndSlateIsDefault() {
        assertEquals(listOf("slate", "brown", "green", "high_contrast"), BoardThemes.all.map { it.id.value })
        assertEquals(BoardThemeId("slate"), BoardThemes.default.id)
        assertEquals(org.chesstrainer.app.settings.Appearance().boardTheme, BoardThemes.default.id)
    }

    @Test
    fun slateKeepsTheOriginalColors() {
        val slate = BoardThemes.slate.palette
        assertEquals(Color(0xFFDDE4EB), slate.lightSquare)
        assertEquals(Color(0xFF7F97AE), slate.darkSquare)
        assertEquals(Color(0x66E8C547), slate.lastMove.onLight)
        assertEquals(Color(0x8855A67A), slate.selected.onDark)
        assertEquals(Color(0xCCD9453D), slate.check.onLight)
    }

    @Test
    fun unknownIdFallsBackToSlate() {
        assertEquals(BoardThemes.slate.palette, BoardThemes.palette(BoardThemeId("removed")))
        assertEquals(BoardThemes.slate.palette, BoardThemes.palette(BoardThemeId("")))
    }

    @Test
    fun noDuplicateIdsOrNameKeys() {
        val ids = BoardThemes.all.map { it.id.value }
        assertEquals(ids.size, ids.toSet().size)
        val keys = ids.map { "board_theme_$it" }
        assertEquals(keys.size, keys.toSet().size)
        val nameRes = BoardThemes.all.map { boardThemeNameRes(it.id) }
        assertEquals("кожна тема має власну назву", nameRes.size, nameRes.toSet().size)
        ids.forEach { assertTrue("id у snake_case: $it", Regex("[a-z]+(_[a-z]+)*").matches(it)) }
    }

    @Test
    fun acceptedValuesMatchThemeList() {
        assertEquals(BoardThemes.all.map { it.id }.toSet(), AppearanceOptions.boardThemes)
    }

    @Test
    fun everyThemeHasNamesInBothLanguages() {
        val en = strings("src/main/res/values/strings.xml")
        val uk = strings("src/main/res/values-uk/strings.xml")
        for (theme in BoardThemes.all) {
            val key = "board_theme_${theme.id.value}"
            assertTrue("немає en $key", en[key].orEmpty().isNotBlank())
            assertTrue("немає uk $key", uk[key].orEmpty().isNotBlank())
        }
        assertTrue(en["settings_group_board_theme"].orEmpty().isNotBlank())
        assertTrue(uk["settings_group_board_theme"].orEmpty().isNotBlank())
    }

    @Test
    fun squaresAreDistinctInEveryTheme() {
        for (theme in BoardThemes.all) {
            assertNotEquals(theme.id.value, theme.palette.lightSquare, theme.palette.darkSquare)
        }
    }

    @Test
    fun highContrastSquaresHaveContrastOfAtLeastThree() {
        assertContrast("світла/темна клітинка", high.lightSquare, high.darkSquare)
    }

    @Test
    fun highContrastHighlightsStandOutOnBothSquareColors() {
        for ((isLight, square) in listOf(true to high.lightSquare, false to high.darkSquare)) {
            val side = if (isLight) "світла" else "темна"
            assertContrast("останній хід, $side", over(high.lastMove.on(isLight), square), square)
            assertContrast("вибір, $side", over(high.selected.on(isLight), square), square)
            assertContrast("шах, $side", over(high.check.on(isLight), square), square)
            assertContrast("крапка ходу, $side", over(high.targetHint, square), square)
        }
    }

    @Test
    fun highContrastHighlightsAreOpaqueAndDistinctFromEachOther() {
        for (isLight in listOf(true, false)) {
            val tints = listOf(high.lastMove.on(isLight), high.selected.on(isLight), high.check.on(isLight))
            tints.forEach { assertEquals(1f, it.alpha, 0f) }
            assertEquals(3, tints.toSet().size)
        }
    }

    @Test
    fun highContrastCoordinatesAreReadableOnSquaresAndHighlights() {
        val light = listOf(high.lightSquare) + listOf(high.lastMove, high.selected, high.check).map { it.onLight }
        val dark = listOf(high.darkSquare) + listOf(high.lastMove, high.selected, high.check).map { it.onDark }
        light.forEach { assertContrast("підпис на світлій", high.coordinateOnLight, it) }
        dark.forEach { assertContrast("підпис на темній", high.coordinateOnDark, it) }
    }

    @Test
    fun otherThemesKeepHighlightsVisibleAndSquaresDistinct() {
        // Для звичайних тем ≥ 3:1 не вимагається, але підсвітка має змінювати вигляд клітинки.
        for (theme in BoardThemes.all) {
            val p = theme.palette
            for ((isLight, square) in listOf(true to p.lightSquare, false to p.darkSquare)) {
                for (tint in listOf(p.lastMove, p.selected, p.check)) {
                    assertNotEquals("${theme.id.value}: підсвітка збігається з клітинкою", square, over(tint.on(isLight), square))
                }
            }
        }
    }

    @Test
    fun contrastFormulaMatchesKnownValues() {
        assertEquals(21.0, contrast(Color.White, Color.Black), 0.01)
        assertEquals(1.0, contrast(Color.White, Color.White), 0.0001)
        assertEquals(1.0, luminance(Color.White), 0.0001)
        assertEquals(0.0, luminance(Color.Black), 0.0001)
    }

    private fun assertContrast(what: String, a: Color, b: Color) {
        val ratio = contrast(a, b)
        assertTrue("$what: контраст ${"%.2f".format(ratio)} < 3:1", ratio >= 3.0)
    }

    /** Колір [top] з його прозорістю поверх непрозорого [base]. */
    private fun over(top: Color, base: Color): Color {
        val a = top.alpha
        return Color(
            red = top.red * a + base.red * (1 - a),
            green = top.green * a + base.green * (1 - a),
            blue = top.blue * a + base.blue * (1 - a),
        )
    }

    /** Відносна яскравість за WCAG 2.x. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
}
