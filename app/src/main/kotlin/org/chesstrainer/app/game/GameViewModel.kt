// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.chesstrainer.app.storage.GameStorage
import org.chesstrainer.core.engine.EngineService
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

    // TODO(difficulty): CONTRACTS 3.5 вимагає завжди загортати «сирий» рушій у HandicapEngineService
    //  (`HandicapEngineService(raw, rules, random)`); його ще немає в :core-chess:api. Поки що рушій без обгортки,
    //  а рівень фіксований (FixedLevel.kt) і не використовує Strength.Handicap.
    private val engine: EngineService = RemoteEngineService(application, rules)

    val controller = GameController(
        rules = rules,
        games = DefaultChessGameFactory(rules),
        engine = engine,
        scope = viewModelScope,
        levelFor = ::fixedLevel,
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
