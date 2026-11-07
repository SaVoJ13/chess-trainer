// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.chesstrainer.app.game.GameViewModel
import org.chesstrainer.app.puzzle.PuzzleViewModel
import org.chesstrainer.app.review.GamesViewModel
import org.chesstrainer.app.settings.SharedPrefsAppearancePreferences
import org.chesstrainer.app.ui.AppNavigation
import org.chesstrainer.app.ui.theme.BoardThemes
import org.chesstrainer.app.ui.theme.ChessTheme
import org.chesstrainer.app.ui.theme.LocalBoardPalette

class MainActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels()
    private val gamesViewModel: GamesViewModel by viewModels()
    private val puzzleViewModel: PuzzleViewModel by viewModels()
    private val appearancePreferences by lazy { SharedPrefsAppearancePreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChessTheme {
                val appearance by appearancePreferences.observe().collectAsState(appearancePreferences.current())
                CompositionLocalProvider(LocalBoardPalette provides BoardThemes.palette(appearance.boardTheme)) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(
                            viewModel, gamesViewModel, puzzles = { puzzleViewModel.controller },
                            appearance = appearance, onAppearanceChange = appearancePreferences::update,
                        )
                    }
                }
            }
        }
    }

    // Рушій зупиняється, коли застосунок іде у фон, і запускається знову на поверненні (CONTRACTS 3.4).
    // Поворот екрана (зміна конфігурації) рушія не зачіпає: ViewModel живе далі.
    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onBackground()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) viewModel.onTrimMemory(level)
    }
}
