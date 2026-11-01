// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.settings

import android.content.Context
import org.chesstrainer.core.engine.DifficultyId

/** Налаштування застосунку, пов'язані з рівнем складності (CONTRACTS 5, v0.12). */
interface DifficultyPreferences {
    /** Рівень, обраний при старті останньої партії; `null` до першої партії. */
    fun lastUsed(): DifficultyId?

    fun rememberUsed(id: DifficultyId)

    /** Без збереження (тести, екрани без налаштувань). */
    data object None : DifficultyPreferences {
        override fun lastUsed(): DifficultyId? = null

        override fun rememberUsed(id: DifficultyId) = Unit
    }
}

/** SharedPreferences: нових залежностей не потрібно; запис асинхронний (`apply`). */
class SharedPrefsDifficultyPreferences(context: Context) : DifficultyPreferences {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun lastUsed(): DifficultyId? =
        if (prefs.contains(KEY_LAST_DIFFICULTY)) DifficultyId(prefs.getInt(KEY_LAST_DIFFICULTY, 0)) else null

    override fun rememberUsed(id: DifficultyId) {
        prefs.edit().putInt(KEY_LAST_DIFFICULTY, id.value).apply()
    }

    private companion object {
        const val FILE_NAME = "settings"
        const val KEY_LAST_DIFFICULTY = "last_difficulty_id"
    }
}
