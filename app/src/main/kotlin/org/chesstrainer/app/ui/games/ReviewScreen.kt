// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.chesstrainer.app.R
import org.chesstrainer.app.review.MoveRow
import org.chesstrainer.app.review.ReviewController
import org.chesstrainer.app.review.ReviewSlot
import org.chesstrainer.app.review.ReviewState
import org.chesstrainer.app.review.moveRows
import org.chesstrainer.app.ui.game.BoardView
import org.chesstrainer.app.ui.game.ChessBoard

/**
 * Перегляд завершеної чи імпортованої партії без можливості ходити (CONTRACTS 5). Дошка й фігури ті самі, що
 * в грі; торкання дошки нічого не робить, ходи перелистуються кнопками й торканням запису у списку ходів.
 * [headerActions] розміщується поруч із заголовком (експорт підключає навігація).
 */
@Composable
fun ReviewScreen(slot: ReviewSlot, onBack: () -> Unit, headerActions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.games_back))
            }
            Text(
                stringResource(R.string.review_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            headerActions()
        }
        when (slot) {
            ReviewSlot.Loading -> Unit
            ReviewSlot.Failed -> Text(stringResource(R.string.review_load_failed), style = MaterialTheme.typography.bodyLarge)
            is ReviewSlot.Ready -> ReviewContent(slot.controller, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun ReviewContent(controller: ReviewController, modifier: Modifier) {
    val state by controller.state.collectAsState()
    val rows = remember(state.game) { moveRows(state.game) }
    BoxWithConstraints(modifier) {
        if (maxWidth > maxHeight) {
            val boardSide = minOf(maxHeight, maxWidth * 0.55f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReviewBoard(state, boardSide)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Info(state)
                    Controls(state, controller)
                    MovesList(state, rows, controller, Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            val boardSide = minOf(maxWidth, (maxHeight - 280.dp).coerceAtLeast(200.dp))
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Info(state)
                ReviewBoard(state, boardSide)
                Controls(state, controller)
                MovesList(state, rows, controller, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ReviewBoard(state: ReviewState, side: Dp) {
    ChessBoard(
        state = BoardView(
            position = state.position,
            bottomColor = state.bottomColor,
            lastMove = state.lastMove,
            checkSquare = state.checkSquare,
        ),
        onSquareTap = {},
        onDragStart = { false },
        onDrop = { _, _ -> },
        modifier = Modifier.size(side),
    )
}

/** Гравці й результат (теги PGN це дані) та номер показаної позиції. */
@Composable
private fun Info(state: ReviewState) {
    val white = state.headers["White"] ?: "?"
    val black = state.headers["Black"] ?: "?"
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.review_players, white, black), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(resultText(state.result)), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (state.ply == 0) {
                stringResource(R.string.review_position_start)
            } else {
                stringResource(R.string.review_position, state.ply, state.plyCount)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Controls(state: ReviewState, controller: ReviewController) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NavButton(R.string.review_symbol_first, R.string.review_first, state.canGoBack, controller::first)
        NavButton(R.string.review_symbol_previous, R.string.review_previous, state.canGoBack, controller::previous)
        NavButton(R.string.review_symbol_next, R.string.review_next, state.canGoForward, controller::next)
        NavButton(R.string.review_symbol_last, R.string.review_last, state.canGoForward, controller::last)
    }
    TextButton(onClick = controller::flip, modifier = Modifier.heightIn(min = MinTouch)) {
        Text(stringResource(R.string.game_action_flip))
    }
}

@Composable
private fun RowScope.NavButton(symbol: Int, description: Int, enabled: Boolean, onClick: () -> Unit) {
    val cd = stringResource(description)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).heightIn(min = MinTouch).semantics { contentDescription = cd },
    ) {
        Text(stringResource(symbol), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun MovesList(state: ReviewState, rows: List<MoveRow>, controller: ReviewController, modifier: Modifier) {
    val listState = rememberLazyListState()
    val currentRow = remember(state.ply, rows) { rows.indexOfFirst { it.white == state.ply || it.black == state.ply } }
    LaunchedEffect(currentRow) {
        if (currentRow >= 0) listState.animateScrollToItem(currentRow)
    }
    val description = stringResource(R.string.game_cd_moves)
    LazyColumn(modifier.semantics { contentDescription = description }, state = listState) {
        itemsIndexed(rows) { _, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (row.white == null) "${row.number}…" else "${row.number}.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(52.dp),
                )
                MoveCell(state, row.white, controller, Modifier.weight(1f))
                MoveCell(state, row.black, controller, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MoveCell(state: ReviewState, ply: Int?, controller: ReviewController, modifier: Modifier) {
    if (ply == null) {
        Box(modifier)
        return
    }
    val san = state.game.moves[ply - 1].san
    val current = state.ply == ply
    val currentDescription = stringResource(R.string.review_move_current, san)
    Box(
        modifier
            .heightIn(min = MinTouch)
            .clip(RoundedCornerShape(8.dp))
            .background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(role = Role.Button) { controller.goTo(ply) }
            .semantics { selected = current; if (current) contentDescription = currentDescription }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            san,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
