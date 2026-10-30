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
 * Єдине на процес автозбереження: черга записів не залежить від життя екрана, тому зберігання, розпочате
 * при виході з екрана, завершується, а новий екран бачить усі попередні записи.
 */
object GameStorage {
    private const val TAG = "GameStorage"

    @Volatile
    private var saver: GameSaver? = null

    fun saver(context: Context): GameSaver =
        saver ?: synchronized(this) { saver ?: create(context.applicationContext).also { saver = it } }

    private fun create(context: Context): GameSaver {
        val rules = ChesslibRules()
        return AutosaveGameSaver(
            repository = RoomGameRepository(GameDatabase.create(context).games()),
            persistence = GamePersistence(DefaultChessGameFactory(rules), DefaultPgnCodec(rules)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            onError = { Log.w(TAG, "Autosave operation failed", it) },
        )
    }
}
