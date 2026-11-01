// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.chesstrainer.app.R
import org.chesstrainer.app.storage.GameId
import org.chesstrainer.app.storage.GameMode
import org.chesstrainer.app.storage.SavedGameSummary
import org.chesstrainer.core.pgn.PgnResult

internal val MinTouch = 48.dp

/**
 * Екран «Партії» (CONTRACTS 5): список за часом оновлення спаданням. [games] = `null`, доки список читається.
 * [headerActions] розміщується під заголовком (імпорт і експорт підключає навігація).
 */
@Composable
fun GamesScreen(
    games: List<SavedGameSummary>?,
    openFailed: Boolean,
    onDismissOpenFailed: () -> Unit,
    onBack: () -> Unit,
    onOpen: (SavedGameSummary) -> Unit,
    onDelete: (GameId) -> Unit,
    onExport: ((GameId) -> Unit)? = null,
    headerActions: @Composable () -> Unit = {},
) {
    var pendingDelete by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.games_back))
            }
            Text(
                stringResource(R.string.games_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        headerActions()
        when {
            games == null -> Unit
            games.isEmpty() -> Text(
                stringResource(R.string.games_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            else -> {
                val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(games, key = { it.id.value }) { game ->
                        GameRow(
                            game = game,
                            updated = format.format(Date(game.updatedAt.toEpochMilli())),
                            onOpen = { onOpen(game) },
                            onExport = onExport?.let { export -> { export(game.id) } },
                            onDelete = { pendingDelete = game.id.value },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.games_delete_title)) },
            text = { Text(stringResource(R.string.games_delete_text)) },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(GameId(id)) }, modifier = Modifier.heightIn(min = MinTouch)) {
                    Text(stringResource(R.string.games_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, modifier = Modifier.heightIn(min = MinTouch)) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (openFailed) {
        AlertDialog(
            onDismissRequest = onDismissOpenFailed,
            text = { Text(stringResource(R.string.games_open_failed)) },
            confirmButton = {
                TextButton(onClick = onDismissOpenFailed, modifier = Modifier.heightIn(min = MinTouch)) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}

@Composable
private fun GameRow(
    game: SavedGameSummary,
    updated: String,
    onOpen: () -> Unit,
    onExport: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val moves = (game.plyCount + 1) / 2
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "${stringResource(modeText(game.mode))} · ${stringResource(statusText(game.finished))}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${stringResource(resultText(game.result))} · ${pluralStringResource(R.plurals.games_moves, moves, moves)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.games_updated, updated), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.padding(horizontal = 4.dp)) {
            if (onExport != null) {
                TextButton(onClick = onExport, modifier = Modifier.heightIn(min = MinTouch)) {
                    Text(stringResource(R.string.games_action_export))
                }
            }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.games_action_delete))
            }
        }
    }
}

private fun modeText(mode: GameMode): Int = when (mode) {
    GameMode.VS_ENGINE -> R.string.games_mode_vs_engine
    GameMode.IMPORTED -> R.string.games_mode_imported
    GameMode.ANALYSIS_BOARD -> R.string.games_mode_analysis
}

private fun statusText(finished: Boolean): Int =
    if (finished) R.string.games_status_finished else R.string.games_status_ongoing

internal fun resultText(result: PgnResult): Int = when (result) {
    PgnResult.WHITE_WINS -> R.string.games_result_white_wins
    PgnResult.BLACK_WINS -> R.string.games_result_black_wins
    PgnResult.DRAW -> R.string.games_result_draw
    PgnResult.UNKNOWN -> R.string.games_result_unknown
}
