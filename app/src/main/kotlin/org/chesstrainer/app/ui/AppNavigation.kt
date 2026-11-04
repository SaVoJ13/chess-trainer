// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.chesstrainer.app.game.GameViewModel
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.app.review.GamesViewModel
import org.chesstrainer.app.review.ReviewSlot
import org.chesstrainer.app.storage.GameId
import org.chesstrainer.app.storage.GameMode
import org.chesstrainer.app.puzzle.PuzzleController
import org.chesstrainer.app.ui.analysis.AnalysisScreen
import org.chesstrainer.app.ui.game.GameScreen
import org.chesstrainer.app.ui.puzzle.PuzzleScreen
import org.chesstrainer.app.ui.games.GamesScreen
import org.chesstrainer.app.ui.games.ReviewScreen
import org.chesstrainer.app.ui.games.rememberPgnTransfer
import org.chesstrainer.app.pgn.ExportSelection
import org.chesstrainer.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private enum class Screen { GAME, GAMES, REVIEW, PUZZLES, ANALYSIS }

/**
 * Мінімальна навігація без окремої бібліотеки: пʼять екранів (гра, партії, перегляд, задачі, аналіз), назад повертає на попередній. Стан екрана гри
 * тримає [GameController], тому перехід на список і назад його не губить.
 */
@Composable
fun AppNavigation(viewModel: GameViewModel, games: GamesViewModel, puzzles: () -> PuzzleController) {
    val controller = viewModel.controller
    var screen by rememberSaveable { mutableStateOf(Screen.GAME) }
    var analysisReturn by rememberSaveable { mutableStateOf(Screen.GAME) }
    // Після відновлення процесу ViewModel порожній і рушій аналізу не віддано: екран аналізу без нього не показуємо.
    LaunchedEffect(Unit) { if (screen == Screen.ANALYSIS && !viewModel.analysisOpen) screen = Screen.GAME }
    fun openAnalysis(from: Screen, game: ChessGame?, ply: Int) {
        analysisReturn = from
        screen = Screen.ANALYSIS
        viewModel.openAnalysis(game, ply)
    }
    fun closeAnalysis() {
        viewModel.closeAnalysis()
        screen = analysisReturn
    }
    var reviewId by rememberSaveable { mutableLongStateOf(0L) }
    var openFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val transfer = rememberPgnTransfer(games)

    BackHandler(enabled = screen != Screen.GAME) {
        when (screen) {
            Screen.REVIEW -> screen = Screen.GAMES
            Screen.ANALYSIS -> closeAnalysis()
            else -> screen = Screen.GAME
        }
    }

    when (screen) {
        Screen.GAME -> GameScreen(
            controller,
            onOpenGames = { screen = Screen.GAMES },
            onOpenPuzzles = { screen = Screen.PUZZLES },
            onOpenAnalysis = {
                val game = controller.state.value.game
                openAnalysis(Screen.GAME, game, game.moves.size)
            },
        )
        Screen.ANALYSIS -> AnalysisScreen(viewModel.analysis, onBack = ::closeAnalysis)
        // Контролер задач створюється (і контент читається) лише при першому вході на екран.
        Screen.PUZZLES -> PuzzleScreen(puzzles(), onBack = { screen = Screen.GAME })
        Screen.GAMES -> {
            val list by games.games.collectAsState()
            GamesScreen(
                games = list,
                openFailed = openFailed,
                onDismissOpenFailed = { openFailed = false },
                onBack = { screen = Screen.GAME },
                onOpen = { game ->
                    if (game.mode == GameMode.VS_ENGINE && !game.finished) {
                        scope.launch {
                            if (controller.openSavedGame(game.id)) screen = Screen.GAME else openFailed = true
                        }
                    } else {
                        reviewId = game.id.value
                        screen = Screen.REVIEW
                    }
                },
                onExport = { id -> transfer.export(ExportSelection.One(id)) },
                headerActions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = transfer.importFile, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.games_action_import))
                        }
                        TextButton(
                            onClick = { transfer.export(ExportSelection.All) },
                            enabled = !list.isNullOrEmpty(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.games_action_export_all)) }
                    }
                },
                onDelete = { id ->
                    scope.launch { if (games.delete(id)) controller.onActiveGameDeleted() }
                },
            )
        }
        Screen.REVIEW -> {
            LaunchedEffect(reviewId) { games.openReview(GameId(reviewId)) }
            val slot by games.review.collectAsState()
            ReviewScreen(
                slot = slot,
                onBack = { screen = Screen.GAMES },
                headerActions = {
                    TextButton(
                        onClick = {
                            val ready = slot as? ReviewSlot.Ready
                            if (ready != null) {
                                val review = ready.controller.state.value
                                openAnalysis(Screen.REVIEW, review.game, review.ply)
                            }
                        },
                        enabled = slot is ReviewSlot.Ready,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.analysis_action_open)) }
                    TextButton(
                        onClick = { transfer.export(ExportSelection.One(GameId(reviewId))) },
                        enabled = slot is ReviewSlot.Ready,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.games_action_export)) }
                },
            )
        }
    }
}
