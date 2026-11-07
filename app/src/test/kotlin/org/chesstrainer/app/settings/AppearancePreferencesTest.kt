// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `AppearancePreferences` (CONTRACTS 5.1): значення за замовчуванням, збереження, невідомі значення. */
class AppearancePreferencesTest {

    @Test
    fun missingValuesGiveDefaults() {
        val prefs = SharedPrefsAppearancePreferences(FakePrefs())
        assertEquals(Appearance(), prefs.current())
        assertEquals("slate", prefs.current().boardTheme.value)
        assertEquals("chess_trainer", prefs.current().pieceSet.value)
        assertTrue(prefs.current().animateMoves)
    }

    @Test
    fun unknownValuesAreReplacedByDefaultsOnRead() {
        val store = FakePrefs(
            mapOf(
                SharedPrefsAppearancePreferences.KEY_BOARD_THEME to "removed_theme",
                SharedPrefsAppearancePreferences.KEY_PIECE_SET to "some_future_set",
                SharedPrefsAppearancePreferences.KEY_ANIMATE_MOVES to false,
            ),
        )
        val current = SharedPrefsAppearancePreferences(store).current()
        assertEquals(BoardThemeId("slate"), current.boardTheme)
        assertEquals(PieceSetId("chess_trainer"), current.pieceSet)
        assertFalse("перемикач анімацій зберігається незалежно", current.animateMoves)
    }

    @Test
    fun updateIsStoredUnderContractKeysAndSurvivesRecreation() {
        val store = FakePrefs()
        SharedPrefsAppearancePreferences(store).update(Appearance(animateMoves = false))
        assertEquals("slate", store.values["board_theme_id"])
        assertEquals("chess_trainer", store.values["piece_set_id"])
        assertEquals(false, store.values["animate_moves"])
        assertFalse(SharedPrefsAppearancePreferences(store).current().animateMoves)
    }

    @Test
    fun everyBoardThemeIsStoredAndReadBack() {
        for (id in listOf("slate", "brown", "green", "high_contrast")) {
            val store = FakePrefs()
            val prefs = SharedPrefsAppearancePreferences(store)
            prefs.update(prefs.current().copy(boardTheme = BoardThemeId(id)))
            assertEquals(id, prefs.current().boardTheme.value)
            assertEquals(id, store.values["board_theme_id"])
            assertEquals(BoardThemeId(id), SharedPrefsAppearancePreferences(store).current().boardTheme)
        }
    }

    @Test
    fun unknownBoardThemeInUpdateAndStoreReadsAsSlate() {
        val store = FakePrefs(mapOf(SharedPrefsAppearancePreferences.KEY_BOARD_THEME to "neon"))
        val prefs = SharedPrefsAppearancePreferences(store)
        assertEquals(BoardThemeId("slate"), prefs.current().boardTheme)
        prefs.update(Appearance(boardTheme = BoardThemeId("high_contrast")))
        prefs.update(Appearance(boardTheme = BoardThemeId("neon")))
        assertEquals(BoardThemeId("slate"), prefs.current().boardTheme)
        assertEquals(BoardThemeId("slate"), SharedPrefsAppearancePreferences(store).current().boardTheme)
    }

    @Test
    fun boardThemeChangeKeepsOtherAppearanceValues() {
        val prefs = SharedPrefsAppearancePreferences(FakePrefs())
        prefs.update(Appearance(animateMoves = false))
        prefs.update(prefs.current().copy(boardTheme = BoardThemeId("green")))
        assertFalse(prefs.current().animateMoves)
        assertEquals("chess_trainer", prefs.current().pieceSet.value)
    }

    @Test
    fun sameFileAsDifficultyPreferences() {
        assertEquals("settings", SharedPrefsAppearancePreferences.FILE_NAME)
    }

    /** Мінімальна заміна `SharedPreferences` для JVM-тестів (без Android). */
    private class FakePrefs(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
        val values = initial.toMutableMap()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any>()
            private val removed = mutableSetOf<String>()
            override fun putString(key: String, value: String?) = apply { if (value == null) removed += key else pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                removed.forEach { values.remove(it) }
                values.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
