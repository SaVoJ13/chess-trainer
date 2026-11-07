// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.chesstrainer.app.R
import org.chesstrainer.app.game.EngineProblem
import org.chesstrainer.app.game.EngineStatus
import org.chesstrainer.app.game.GameController
import org.chesstrainer.app.game.GameOutcome
import org.chesstrainer.app.game.GameUiState
import org.chesstrainer.app.game.displayedApproxElo
import org.chesstrainer.app.game.groupedByGroup
import org.chesstrainer.app.ui.theme.LocalBoardPalette
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.game.AutoDrawReason
import org.chesstrainer.core.game.ClaimReason
import org.chesstrainer.core.model.Color as ChessColor
import org.chesstrainer.core.model.Piece
import org.chesstrainer.core.model.PieceType

private enum class GameDialog { NONE, RESIGN, NEW_GAME }

private val MinTouch = 48.dp

/** Екран гри «людина проти рушія». */
@Composable
fun GameScreen(
    controller: GameController,
    onOpenGames: () -> Unit = {},
    onOpenPuzzles: () -> Unit = {},
    onOpenAnalysis: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    var dialog by rememberSaveable { mutableStateOf(GameDialog.NONE) }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            val boardSide = minOf(maxHeight, maxWidth * 0.6f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Board(state, controller, boardSide)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusHeader(state, onOpenAnalysis)
                    Notices(state, controller)
                    MoveList(state, vertical = true, modifier = Modifier.weight(1f).fillMaxWidth())
                    ActionButtons(state, controller, onOpenGames, onOpenPuzzles, onOpenSettings, { dialog = GameDialog.RESIGN }, { dialog = GameDialog.NEW_GAME })
                }
            }
        } else {
            val boardSide = minOf(maxWidth, (maxHeight - 312.dp).coerceAtLeast(220.dp))
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusHeader(state, onOpenAnalysis, Modifier.fillMaxWidth())
                Board(state, controller, boardSide)
                Notices(state, controller)
                MoveList(state, vertical = false, modifier = Modifier.fillMaxWidth().height(44.dp))
                Spacer(Modifier.weight(1f))
                ActionButtons(state, controller, onOpenGames, onOpenPuzzles, onOpenSettings, { dialog = GameDialog.RESIGN }, { dialog = GameDialog.NEW_GAME })
            }
        }
    }

    when (dialog) {
        GameDialog.NONE -> Unit
        GameDialog.RESIGN -> ResignDialog(
            onConfirm = { dialog = GameDialog.NONE; controller.resign() },
            onDismiss = { dialog = GameDialog.NONE },
        )
        GameDialog.NEW_GAME -> NewGameDialog(
            inProgress = !state.isOver && state.game.moves.isNotEmpty(),
            levels = state.levels,
            suggested = remember(state.levels) { controller.suggestedDifficulty() },
            onChoose = { color, level -> dialog = GameDialog.NONE; controller.newGame(color, level) },
            onDismiss = { dialog = GameDialog.NONE },
        )
    }
    if (state.resumeOfferPlies != null) {
        ResumeDialog(onContinue = controller::continueSavedGame, onNewGame = controller::declineSavedGame)
    }
    state.pendingPromotion?.let {
        PromotionDialog(color = state.playerColor, onChoose = controller::choosePromotion, onDismiss = controller::cancelPromotion)
    }
}

@Composable
private fun Board(state: GameUiState, controller: GameController, side: Dp) {
    ChessBoard(
        state = state,
        onSquareTap = controller::onSquareTap,
        onDragStart = controller::onDragStart,
        onDrop = controller::onDrop,
        modifier = Modifier.size(side),
    )
}

/**
 * Рядок стану й кнопка входу в аналіз. Кнопка стоїть угорі поруч зі статусом, а не серед кнопок унизу: нижні кнопки
 * на низьких екранах і в альбомній орієнтації виштовхуються за край, а вхід в аналіз має бути видно завжди.
 */
@Composable
private fun StatusHeader(state: GameUiState, onOpenAnalysis: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusLine(state, Modifier.weight(1f))
        FilledTonalButton(onClick = onOpenAnalysis, modifier = Modifier.heightIn(min = MinTouch)) {
            Text(stringResource(R.string.analysis_action_open), maxLines = 1)
        }
    }
}

@Composable
private fun StatusLine(state: GameUiState, modifier: Modifier = Modifier) {
    val outcome = state.outcome
    val main = when {
        outcome != null -> outcomeText(outcome, state.playerColor)
        state.engine is EngineStatus.Thinking -> stringResource(R.string.game_status_engine_thinking)
        state.humanToMove -> stringResource(R.string.game_status_your_turn)
        else -> stringResource(R.string.game_status_engine_turn)
    }
    val text = if (outcome == null && state.checkSquare != null) {
        "$main  ${stringResource(R.string.game_status_check)}"
    } else {
        main
    }
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (outcome != null) FontWeight.Bold else FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.game_level_label, difficultyName(state.difficultyId)),
            // Помітний, але не головніший за рядок стану: titleSmall + колір primary з теми (запит Архітектора, косметика).
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.engine is EngineStatus.Starting) {
            Text(
                text = stringResource(R.string.game_status_engine_starting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun outcomeText(outcome: GameOutcome, player: ChessColor): String = stringResource(
    when (outcome) {
        is GameOutcome.Checkmate ->
            if (outcome.winner == player) R.string.game_result_checkmate_win else R.string.game_result_checkmate_loss
        GameOutcome.Stalemate -> R.string.game_result_stalemate
        GameOutcome.InsufficientMaterial -> R.string.game_result_insufficient_material
        GameOutcome.DrawClaimed -> R.string.game_result_draw_claimed
        is GameOutcome.DrawAutomatic -> when (outcome.reason) {
            AutoDrawReason.FIVEFOLD_REPETITION -> R.string.game_result_fivefold_repetition
            AutoDrawReason.SEVENTY_FIVE_MOVE_RULE -> R.string.game_result_seventy_five_moves
        }
        is GameOutcome.Resigned -> R.string.game_result_resigned
    },
)

/** Повідомлення про рушій і смуга вимоги нічиєї. */
@Composable
private fun Notices(state: GameUiState, controller: GameController) {
    val engine = state.engine
    if (engine is EngineStatus.Problem) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        when (engine.kind) {
                            EngineProblem.UNAVAILABLE -> R.string.game_engine_unavailable
                            EngineProblem.CRASHED -> R.string.game_engine_crashed
                            EngineProblem.UNEXPECTED -> R.string.game_engine_unexpected
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = controller::retryEngine, modifier = Modifier.heightIn(min = MinTouch)) {
                    Text(stringResource(R.string.game_action_retry))
                }
            }
        }
    }

    state.levelNotice?.let { notice ->
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.game_level_unavailable,
                        difficultyName(notice.requested),
                        difficultyName(notice.used),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = controller::dismissLevelNotice, modifier = Modifier.heightIn(min = MinTouch)) {
                    Text(stringResource(R.string.game_level_unavailable_ok))
                }
            }
        }
    }

    val reasons = state.claimReasons
    if (reasons.isNotEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                if (ClaimReason.THREEFOLD_REPETITION in reasons) {
                    Text(stringResource(R.string.game_claim_threefold_repetition), style = MaterialTheme.typography.bodyMedium)
                }
                if (ClaimReason.FIFTY_MOVE_RULE in reasons) {
                    Text(stringResource(R.string.game_claim_fifty_moves), style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = controller::dismissClaim, modifier = Modifier.heightIn(min = MinTouch)) {
                        Text(stringResource(R.string.game_action_continue))
                    }
                    TextButton(onClick = controller::claimDraw, modifier = Modifier.heightIn(min = MinTouch)) {
                        Text(stringResource(R.string.game_action_claim_draw), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveList(state: GameUiState, vertical: Boolean, modifier: Modifier = Modifier) {
    val rows = remember(state.game.moves) {
        state.game.moves.map { it.san }.chunked(2).mapIndexed { i, pair -> "${i + 1}. ${pair.joinToString(" ")}" }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }
    val description = stringResource(R.string.game_cd_moves)
    val itemStyle = MaterialTheme.typography.bodyLarge
    if (vertical) {
        LazyColumn(modifier.semantics { contentDescription = description }, state = listState) {
            itemsIndexed(rows) { _, row -> Text(row, style = itemStyle, modifier = Modifier.padding(vertical = 4.dp)) }
        }
    } else {
        LazyRow(
            modifier.semantics { contentDescription = description },
            state = listState,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(rows) { _, row -> Text(row, style = itemStyle) }
        }
    }
}

@Composable
private fun ActionButtons(
    state: GameUiState,
    controller: GameController,
    onOpenGames: () -> Unit,
    onOpenPuzzles: () -> Unit,
    onOpenSettings: () -> Unit,
    onResign: () -> Unit,
    onNewGame: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(R.string.game_action_undo, enabled = state.canUndo, onClick = controller::undo)
            ActionButton(R.string.game_action_flip, enabled = true, onClick = controller::flipBoard)
            ActionButton(R.string.games_action_open, enabled = true, onClick = onOpenGames)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(R.string.puzzle_action_open, enabled = true, onClick = onOpenPuzzles)
            ActionButton(R.string.game_action_resign, enabled = !state.isOver, onClick = onResign)
            ActionButton(R.string.game_action_new_game, enabled = true, primary = true, onClick = onNewGame)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(R.string.settings_action_open, enabled = true, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionButton(
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val modifier = Modifier.weight(1f).heightIn(min = MinTouch)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { ButtonLabel(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { ButtonLabel(label) }
    }
}

@Composable
private fun ButtonLabel(label: Int) {
    Text(stringResource(label), textAlign = TextAlign.Center, maxLines = 2)
}

@Composable
private fun ResignDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_dialog_resign_title)) },
        text = { Text(stringResource(R.string.game_dialog_resign_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.game_action_resign))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun NewGameDialog(
    inProgress: Boolean,
    levels: List<DifficultyLevel>?,
    suggested: DifficultyId,
    onChoose: (ChessColor, DifficultyId) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(suggested.value) }
    // Збережений вибір міг не потрапити в поточний список: тоді береться запропонований рівень.
    val current = levels?.firstOrNull { it.id.value == selected }?.id ?: suggested
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_dialog_new_game_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inProgress) Text(stringResource(R.string.game_dialog_new_game_in_progress))
                Text(
                    stringResource(R.string.game_dialog_level_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                if (levels == null) {
                    Text(
                        stringResource(R.string.game_dialog_level_loading, difficultyName(current)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LevelList(
                        levels = levels,
                        selected = current,
                        onSelect = { selected = it.value },
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    )
                }
                Button(
                    onClick = { onChoose(ChessColor.WHITE, current) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                ) { Text(stringResource(R.string.game_dialog_play_white)) }
                Button(
                    onClick = { onChoose(ChessColor.BLACK, current) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                ) { Text(stringResource(R.string.game_dialog_play_black)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** Рівні за групами `DifficultyGroup` у порядку від слабшого; число «≈ N» лише для рівнів `UCI_Elo`. */
@Composable
private fun LevelList(
    levels: List<DifficultyLevel>,
    selected: DifficultyId,
    onSelect: (DifficultyId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup()) {
        levels.groupedByGroup().forEach { (group, items) ->
            Text(
                stringResource(difficultyGroupRes(group)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            items.forEach { level ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouch)
                        .selectable(selected = level.id == selected, role = Role.RadioButton, onClick = { onSelect(level.id) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = level.id == selected, onClick = null)
                    Text(difficultyName(level.id), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    val approx = level.displayedApproxElo()
                    if (approx != null) {
                        Text(
                            stringResource(R.string.difficulty_approx_elo, approx),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PromotionDialog(color: ChessColor, onChoose: (PieceType) -> Unit, onDismiss: () -> Unit) {
    val squareColor = LocalBoardPalette.current.darkSquare
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_promotion_title)) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT).forEach { type ->
                    val piece = Piece(color, type)
                    val name = pieceName(piece)
                    Canvas(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(squareColor)
                            .clickable(role = Role.Button) { onChoose(type) }
                            .semantics { contentDescription = name },
                    ) {
                        drawPiece(piece, Offset.Zero, size.width)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** Пропозиція при запуску: продовжити збережену партію чи почати нову. Закрити, не обравши, не можна. */
@Composable
private fun ResumeDialog(onContinue: () -> Unit, onNewGame: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.game_resume_title)) },
        text = { Text(stringResource(R.string.game_resume_text)) },
        confirmButton = {
            Button(onClick = onContinue, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.game_resume_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onNewGame, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.game_resume_new_game))
            }
        },
    )
}
