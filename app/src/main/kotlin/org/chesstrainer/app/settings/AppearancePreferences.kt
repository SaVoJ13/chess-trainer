// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Тема дошки (CONTRACTS 5.1): `snake_case`, значення стабільні й не перевикористовуються; назва в UI `board_theme_<id>`. */
@JvmInline
value class BoardThemeId(val value: String)

/** Набір фігур (CONTRACTS 5.1): `snake_case`, назва в UI `piece_set_<id>`. */
@JvmInline
value class PieceSetId(val value: String)

data class Appearance(
    val boardTheme: BoardThemeId = BoardThemeId("slate"),
    val pieceSet: PieceSetId = PieceSetId("chess_trainer"),
    val animateMoves: Boolean = true,
)

/** Вигляд це налаштування пристрою, а не партії (CONTRACTS 5.1): Room не змінюється, старі партії відкриваються з поточним виглядом. */
interface AppearancePreferences {
    /** Невідомі чи відсутні значення дають значення за замовчуванням. */
    fun current(): Appearance

    fun observe(): Flow<Appearance>

    fun update(appearance: Appearance)
}

/**
 * Значення, які застосунок зараз уміє показати. Прибраний чи ще не підтримуваний варіант при читанні
 * замінюється значенням за замовчуванням. Наступні роботи (теми дошки, набори фігур) розширюють лише ці переліки.
 */
object AppearanceOptions {
    val defaults = Appearance()

    val boardThemes: Set<BoardThemeId> = setOf(BoardThemeId("slate"))
    val pieceSets: Set<PieceSetId> = setOf(PieceSetId("chess_trainer"))
}

/** `SharedPreferences`, той самий файл `settings`, що й [SharedPrefsDifficultyPreferences]; нових залежностей немає. */
class SharedPrefsAppearancePreferences(private val prefs: SharedPreferences) : AppearancePreferences {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())

    override fun current(): Appearance = state.value

    override fun observe(): Flow<Appearance> = state.asStateFlow()

    override fun update(appearance: Appearance) {
        prefs.edit()
            .putString(KEY_BOARD_THEME, appearance.boardTheme.value)
            .putString(KEY_PIECE_SET, appearance.pieceSet.value)
            .putBoolean(KEY_ANIMATE_MOVES, appearance.animateMoves)
            .apply()
        state.value = sanitize(appearance)
    }

    private fun read(): Appearance = sanitize(
        Appearance(
            boardTheme = BoardThemeId(prefs.getString(KEY_BOARD_THEME, null).orEmpty()),
            pieceSet = PieceSetId(prefs.getString(KEY_PIECE_SET, null).orEmpty()),
            animateMoves = prefs.getBoolean(KEY_ANIMATE_MOVES, AppearanceOptions.defaults.animateMoves),
        ),
    )

    private fun sanitize(value: Appearance) = Appearance(
        boardTheme = value.boardTheme.takeIf { it in AppearanceOptions.boardThemes } ?: AppearanceOptions.defaults.boardTheme,
        pieceSet = value.pieceSet.takeIf { it in AppearanceOptions.pieceSets } ?: AppearanceOptions.defaults.pieceSet,
        animateMoves = value.animateMoves,
    )

    companion object {
        const val FILE_NAME = "settings"
        const val KEY_BOARD_THEME = "board_theme_id"
        const val KEY_PIECE_SET = "piece_set_id"
        const val KEY_ANIMATE_MOVES = "animate_moves"
    }
}
