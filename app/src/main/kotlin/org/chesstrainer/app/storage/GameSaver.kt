// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import org.chesstrainer.app.game.GameUiState

/**
 * Те, що знає `GameController` про збереження (CONTRACTS 5). Усі методи, крім [findResumable], не блокують
 * виклик: запис виконується поза головним потоком.
 */
interface GameSaver {
    /** Стан партії змінився (півхід, скасування ходу, здача, нічия) або застосунок іде у фон. */
    fun save(state: GameUiState)

    /** Почато нову партію: активну незавершену партію закрито як `ABANDONED`. */
    fun abandonActive()

    /** Остання незавершена партія для пропозиції «Продовжити»; пошкоджені записи закриваються й пропускаються. */
    suspend fun findResumable(): ResumableGame?

    /** Користувач обрав «Продовжити»: наступні збереження оновлюють цей запис. */
    fun resume(game: ResumableGame)

    /** Користувач обрав «Нова партія»: запис закрито як `ABANDONED`, але лишається в сховищі. */
    fun discard(game: ResumableGame)

    /** Без збереження (тести, екрани без автозбереження). */
    data object None : GameSaver {
        override fun save(state: GameUiState) = Unit

        override fun abandonActive() = Unit

        override suspend fun findResumable(): ResumableGame? = null

        override fun resume(game: ResumableGame) = Unit

        override fun discard(game: ResumableGame) = Unit
    }
}
