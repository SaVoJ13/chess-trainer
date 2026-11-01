// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.puzzle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.chesstrainer.app.R
import org.chesstrainer.app.puzzle.ContentAvailability
import org.chesstrainer.app.puzzle.MenuNotice
import org.chesstrainer.app.puzzle.PuzzleController
import org.chesstrainer.app.puzzle.PuzzleLevel
import org.chesstrainer.app.puzzle.PuzzlePlay
import org.chesstrainer.app.puzzle.PuzzleUiState
import org.chesstrainer.app.ui.game.BoardView
import org.chesstrainer.app.ui.game.ChessBoard
import org.chesstrainer.app.ui.game.PromotionDialog
import org.chesstrainer.core.model.Color as ChessColor
import org.chesstrainer.learning.puzzle.PuzzleMode
import org.chesstrainer.learning.puzzle.PuzzleState
import org.chesstrainer.learning.puzzle.ThemeId

private val MinTouch = 48.dp

/** Екран задач: вибір теми, складності й режиму, потім дошка (CONTRACTS 4.1). Логіка в [PuzzleController]. */
@Composable
fun PuzzleScreen(controller: PuzzleController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val play = state.play
    BackHandler { if (play != null) controller.backToMenu() else onBack() }

    Surface(Modifier.fillMaxSize()) {
        if (play == null) {
            SetupContent(state, controller, onBack)
        } else {
            PlayContent(play, controller)
        }
    }
}

// --- вибір ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupContent(state: PuzzleUiState, controller: PuzzleController, onBack: () -> Unit) {
    val setup = state.setup
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.puzzle_back))
            }
            Text(
                stringResource(R.string.puzzle_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }

        val stats = state.stats
        val statsDescription = stringResource(R.string.puzzle_stats_description, stats.attempted, stats.solved, stats.failed)
        Text(
            stringResource(R.string.puzzle_stats, stats.attempted, stats.solved, stats.failed),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = statsDescription },
        )

        when (state.availability) {
            ContentAvailability.LOADING -> Text(stringResource(R.string.puzzle_content_loading))
            ContentAvailability.UNAVAILABLE -> Notice(stringResource(R.string.puzzle_content_unavailable))
            ContentAvailability.READY -> Unit
        }
        state.notice?.let { Notice(stringResource(noticeText(it))) }

        SectionTitle(R.string.puzzle_setup_mode)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = setup.mode == PuzzleMode.NEW,
                onClick = { controller.selectMode(PuzzleMode.NEW) },
                label = { Text(stringResource(R.string.puzzle_mode_new)) },
            )
            FilterChip(
                selected = setup.mode == PuzzleMode.RETRY_FAILED,
                onClick = { controller.selectMode(PuzzleMode.RETRY_FAILED) },
                label = { Text(stringResource(R.string.puzzle_mode_retry)) },
            )
        }

        SectionTitle(R.string.puzzle_setup_level)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PuzzleLevel.values().forEach { level ->
                FilterChip(
                    selected = setup.level == level,
                    onClick = { controller.selectLevel(level) },
                    label = { Text(levelLabel(level)) },
                )
            }
        }
        Text(
            stringResource(R.string.puzzle_level_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle(R.string.puzzle_setup_theme)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = setup.theme == null,
                onClick = { controller.selectTheme(null) },
                label = { Text(stringResource(R.string.puzzle_theme_any)) },
            )
            controller.themes.forEach { theme ->
                FilterChip(
                    selected = setup.theme == theme,
                    onClick = { controller.selectTheme(theme) },
                    label = { Text(themeLabel(theme)) },
                )
            }
        }

        Button(
            onClick = controller::startPuzzle,
            enabled = state.availability == ContentAvailability.READY && !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
        ) {
            Text(stringResource(if (state.loading) R.string.puzzle_loading else R.string.puzzle_start))
        }
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Notice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private fun noticeText(notice: MenuNotice): Int = when (notice) {
    MenuNotice.NO_NEW_PUZZLES -> R.string.puzzle_notice_no_new
    MenuNotice.NO_FAILED_PUZZLES -> R.string.puzzle_notice_no_failed
    MenuNotice.PUZZLE_UNREADABLE -> R.string.puzzle_notice_unreadable
}

@Composable
private fun themeLabel(theme: ThemeId): String =
    themeNameRes(theme)?.let { stringResource(it) } ?: theme.value

@Composable
private fun levelLabel(level: PuzzleLevel): String = when (level) {
    PuzzleLevel.ANY -> stringResource(R.string.puzzle_level_any)
    PuzzleLevel.EASY -> stringResource(R.string.puzzle_level_easy, level.to!! + 1)
    PuzzleLevel.MEDIUM -> stringResource(R.string.puzzle_level_medium, level.from!!, level.to!!)
    PuzzleLevel.HARD -> stringResource(R.string.puzzle_level_hard, level.from!!)
}

// --- задача ---

@Composable
private fun PlayContent(play: PuzzlePlay, controller: PuzzleController) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            val boardSide = minOf(maxHeight, maxWidth * 0.6f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Board(play, controller, boardSide)
                Column(
                    Modifier.weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { Controls(play, controller) }
            }
        } else {
            val boardSide = minOf(maxWidth, (maxHeight - 280.dp).coerceAtLeast(220.dp))
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusText(play, Modifier.fillMaxWidth())
                Board(play, controller, boardSide)
                Controls(play, controller, showStatus = false)
            }
        }
    }

    play.pendingPromotion?.let {
        PromotionDialog(
            color = play.playerColor,
            onChoose = controller::choosePromotion,
            onDismiss = controller::cancelPromotion,
        )
    }
}

@Composable
private fun Board(play: PuzzlePlay, controller: PuzzleController, side: Dp) {
    val review = play.review
    val hintMove = play.hint?.move
    val view = if (review != null) {
        BoardView(review.position, play.playerColor, lastMove = review.lastMove)
    } else {
        BoardView(
            position = play.position,
            bottomColor = play.playerColor,
            lastMove = hintMove ?: play.lastMove,
            selected = play.selected ?: play.hint?.from,
            targets = play.targets,
            checkSquare = play.checkSquare,
        )
    }
    ChessBoard(
        state = view,
        onSquareTap = controller::onSquareTap,
        onDragStart = controller::onDragStart,
        onDrop = controller::onDrop,
        modifier = Modifier.size(side),
    )
}

@Composable
private fun StatusText(play: PuzzlePlay, modifier: Modifier = Modifier) {
    val text = when {
        play.review != null -> stringResource(R.string.puzzle_status_revealed)
        play.over && play.state == PuzzleState.SOLVED -> stringResource(R.string.puzzle_status_solved)
        play.over -> stringResource(R.string.puzzle_status_failed)
        play.wrongMove -> stringResource(R.string.puzzle_status_wrong)
        play.playerColor == ChessColor.WHITE -> stringResource(R.string.puzzle_status_white)
        else -> stringResource(R.string.puzzle_status_black)
    }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Controls(play: PuzzlePlay, controller: PuzzleController, showStatus: Boolean = true) {
    if (showStatus) StatusText(play, Modifier.fillMaxWidth())

    play.hint?.let { hint ->
        val text = when (hint.step) {
            1 -> stringResource(R.string.puzzle_hint_theme, hint.theme?.let { themeLabel(it) }.orEmpty())
            2 -> stringResource(R.string.puzzle_hint_piece)
            else -> stringResource(R.string.puzzle_hint_move)
        }
        Notice(text)
    }

    play.review?.let { review ->
        Text(stringResource(R.string.puzzle_solution_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(R.string.puzzle_solution_moves, review.san.take(review.index).joinToString(" ").ifEmpty { "—" }),
            style = MaterialTheme.typography.bodyMedium,
        )
        val back = stringResource(R.string.puzzle_solution_step_back)
        val forward = stringResource(R.string.puzzle_solution_step_forward)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { controller.reviewStep(-1) },
                enabled = review.canBack,
                modifier = Modifier.weight(1f).heightIn(min = MinTouch).semantics { contentDescription = back },
            ) { Text("◀") }
            OutlinedButton(
                onClick = { controller.reviewStep(1) },
                enabled = review.canForward,
                modifier = Modifier.weight(1f).heightIn(min = MinTouch).semantics { contentDescription = forward },
            ) { Text("▶") }
        }
    }

    if (!play.over) {
        // Підказку ховаємо після завершення: hint() тоді кидає IllegalStateException (CONTRACTS 4.1, v0.16).
        OutlinedButton(
            onClick = controller::hint,
            enabled = play.canHint,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
        ) { Text(stringResource(R.string.puzzle_hint_button)) }
    }
    if (play.review == null) {
        OutlinedButton(
            onClick = controller::revealSolution,
            enabled = !play.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
        ) { Text(stringResource(R.string.puzzle_reveal_button)) }
    }
    if (play.over) {
        Button(
            onClick = controller::nextPuzzle,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
        ) { Text(stringResource(R.string.puzzle_next_button)) }
    }
    TextButton(
        onClick = controller::backToMenu,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
    ) { Text(stringResource(R.string.puzzle_menu_button)) }
}
