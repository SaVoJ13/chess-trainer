// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.chesstrainer.core.game.DefaultChessGameFactory
import org.chesstrainer.core.pgn.DefaultPgnCodec
import org.chesstrainer.core.rules.ChesslibRules

/**
 * Єдині на процес сховище й автозбереження: черга записів не залежить від життя екрана, тому зберігання,
 * розпочате при виході з екрана, завершується, а новий екран бачить усі попередні записи. Список партій
 * і автозбереження працюють з одним [GameRepository].
 */
object GameStorage {
    private const val TAG = "GameStorage"

    @Volatile
    private var holder: Holder? = null

    private class Holder(val repository: GameRepository, val saver: GameSaver)

    fun saver(context: Context): GameSaver = holder(context).saver

    fun repository(context: Context): GameRepository = holder(context).repository

    private fun holder(context: Context): Holder =
        holder ?: synchronized(this) { holder ?: create(context.applicationContext).also { holder = it } }

    private fun create(context: Context): Holder {
        val rules = ChesslibRules()
        val codec = DefaultPgnCodec(rules)
        val repository = RoomGameRepository(GameDatabase.create(context).games(), codec)
        val saver = AutosaveGameSaver(
            repository = repository,
            persistence = GamePersistence(DefaultChessGameFactory(rules), codec),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            onError = { Log.w(TAG, "Autosave operation failed", it) },
        )
        return Holder(repository, saver)
    }
}
