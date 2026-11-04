// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.chesstrainer.app.analysis.AnalysisController
import org.chesstrainer.app.settings.SharedPrefsDifficultyPreferences
import org.chesstrainer.app.storage.GameStorage
import org.chesstrainer.core.engine.DefaultDifficultyPresets
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.HandicapEngineService
import org.chesstrainer.core.game.ChessGame
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

    /**
     * Дошка аналізу ділить із грою той самий рушій (CONTRACTS 3.4: один екземпляр на застосунок). Аналіз іде через
     * `analyse`, що завжди на повну силу, тому обгортка Handicap його не обмежує.
     */
    val analysis = AnalysisController(
        rules = rules,
        games = DefaultChessGameFactory(rules),
        engine = engine,
        scope = viewModelScope,
    )

    init {
        controller.start()
        controller.checkForSavedGame()
    }

    /** Переходи гра↔аналіз виконуються по черзі, щоб швидке подвійне натискання не віддало рушій двом власникам. */
    private var transition: Job? = null

    /** Дошку аналізу відкрито (виставляється одразу, ще до завершення переходу): навігація після відновлення стану за цим знає, чи показувати екран. */
    var analysisOpen = false
        private set

    /** Відкриває дошку аналізу: рушій гри віддається аналізу, після чого `open` запускає пошук. */
    fun openAnalysis(game: ChessGame? = null, ply: Int = 0) {
        analysisOpen = true
        val before = transition
        transition = viewModelScope.launch {
            before?.join()
            controller.holdEngine()
            if (game == null) analysis.openEmpty() else analysis.open(game, ply)
        }
    }

    /** Закриває дошку аналізу: рушій зупинено, гра відновлюється. Живе у `viewModelScope`, тож поворот екрана не перериває. */
    fun closeAnalysis() {
        analysisOpen = false
        val before = transition
        transition = viewModelScope.launch {
            before?.join()
            analysis.close()
            controller.releaseEngine()
        }
    }

    // Життєвий цикл застосунку: рушій належить або грі, або аналізу, але не обом одразу.
    fun onForeground() {
        controller.onForeground()
        analysis.onForeground()
    }

    fun onBackground() {
        controller.onBackground()
        analysis.onBackground()
    }

    fun onTrimMemory() {
        controller.onTrimMemory()
        analysis.onTrimMemory()
    }

    override fun onCleared() {
        // viewModelScope уже скасовано, тому зупинка рушія йде окремою областю й завершується сама.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            analysis.close()
            controller.shutdown()
        }
    }
}
