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
import androidx.compose.ui.Modifier
import org.chesstrainer.app.game.GameViewModel
import org.chesstrainer.app.ui.game.GameScreen
import org.chesstrainer.app.ui.theme.ChessTheme

class MainActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChessTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GameScreen(viewModel.controller)
                }
            }
        }
    }

    // Рушій зупиняється, коли застосунок іде у фон, і запускається знову на поверненні (CONTRACTS 3.4).
    // Поворот екрана (зміна конфігурації) рушія не зачіпає: ViewModel живе далі.
    override fun onStart() {
        super.onStart()
        viewModel.controller.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.controller.onBackground()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) viewModel.controller.onTrimMemory()
    }
}
