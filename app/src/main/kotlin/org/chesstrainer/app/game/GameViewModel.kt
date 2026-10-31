// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.chesstrainer.app.settings.SharedPrefsDifficultyPreferences
import org.chesstrainer.app.storage.GameStorage
import org.chesstrainer.core.engine.DefaultDifficultyPresets
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.HandicapEngineService
import org.chesstrainer.core.game.DefaultChessGameFactory
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.engine.RemoteEngineService

/**
 * Живе, поки екран гри відкрито (переживає поворот екрана). Вхід на екран запускає рушій, вихід зупиняє.
 *
 * Це composition root екрана: рушій лише `RemoteEngineService` (окремий процес `:engine`, CONTRACTS 3.4).
 */
class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val rules = ChesslibRules()

    // CONTRACTS 3.5: «сирий» рушій завжди загорнуто в HandicapEngineService; решта app бачить лише EngineService.
    private val engine: EngineService = HandicapEngineService(RemoteEngineService(application, rules), rules, Random.Default)

    val controller = GameController(
        rules = rules,
        games = DefaultChessGameFactory(rules),
        engine = engine,
        scope = viewModelScope,
        presets = DefaultDifficultyPresets(),
        preferences = SharedPrefsDifficultyPreferences(application),
        saver = GameStorage.saver(application),
    )

    init {
        controller.start()
        controller.checkForSavedGame()
    }

    override fun onCleared() {
        // viewModelScope уже скасовано, тому зупинка рушія йде окремою областю й завершується сама.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { controller.shutdown() }
    }
}
