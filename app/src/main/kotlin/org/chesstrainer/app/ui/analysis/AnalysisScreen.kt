// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import org.chesstrainer.app.R
import org.chesstrainer.app.analysis.Advantage
import org.chesstrainer.app.analysis.AnalysisController
import org.chesstrainer.app.analysis.AnalysisEngine
import org.chesstrainer.app.analysis.AnalysisLine
import org.chesstrainer.app.analysis.AnalysisState
import org.chesstrainer.app.analysis.EvalDisplay
import org.chesstrainer.app.analysis.FenProblem
import org.chesstrainer.app.analysis.PositionVerdict
import org.chesstrainer.app.analysis.display
import org.chesstrainer.app.game.EngineProblem
import org.chesstrainer.app.review.moveRows
import org.chesstrainer.app.ui.game.BoardView
import org.chesstrainer.app.ui.game.ChessBoard
import org.chesstrainer.app.ui.game.PromotionDialog
import org.chesstrainer.core.model.Color as ChessColor

private val MinTouch = 48.dp

/**
 * Дошка аналізу (MASTER_SPEC 6): позиція з FEN чи партії, ходи за обидві сторони, оцінка й кращі лінії рушія.
 * Дошка й навігація стоять нерухомо, решта (оцінка, FEN, ходи) прокручується, щоб перетягування фігур не конфліктувало з прокруткою.
 */
@Composable
fun AnalysisScreen(controller: AnalysisController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    // Макет обирає фізична орієнтація екрана, а НЕ співвідношення розмірів у BoxWithConstraints: коли виїжджає клавіатура,
    // висота зменшується нижче ширини, макет перемикався на альбомний, поле FEN знищувалось і втрачало фокус, а клавіатура
    // одразу ховалась (доказ: лог `FenPanel DISPOSED/ENTER` при maxH 386 dp < maxW 387 dp, docs/project_log.md, сесія 12).
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Поки відкрита клавіатура, у портретному макеті дошку й навігацію ховаємо: на висоті ≈ 370 dp для поля FEN місця майже не лишається.
    // Це лише додає чи прибирає сусідні елементи: прокручувана колонка з полем лишається на тому самому місці й не перестворюється.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.games_back))
            }
            Text(
                stringResource(R.string.analysis_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (landscape) {
                val side = minOf(maxHeight, maxWidth * 0.5f)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Board(state, controller, side)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Navigation(state, controller)
                        Details(state, controller)
                    }
                }
            } else {
                val side = minOf(maxWidth, maxHeight * 0.5f)
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!imeVisible) {
                        Board(state, controller, side)
                        Navigation(state, controller)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Details(state, controller)
                    }
                }
            }
        }
    }
    state.pendingPromotion?.let {
        PromotionDialog(color = state.position.sideToMove, onChoose = controller::choosePromotion, onDismiss = controller::cancelPromotion)
    }
}

@Composable
private fun Board(state: AnalysisState, controller: AnalysisController, side: Dp) {
    ChessBoard(
        state = BoardView(
            position = state.position,
            bottomColor = state.bottomColor,
            lastMove = state.lastMove,
            selected = state.selected,
            targets = state.targets,
            checkSquare = state.checkSquare,
        ),
        onSquareTap = controller::onSquareTap,
        onDragStart = controller::onDragStart,
        onDrop = controller::onDrop,
        modifier = Modifier.size(side),
    )
}

@Composable
private fun Navigation(state: AnalysisState, controller: AnalysisController) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavButton(R.string.review_symbol_first, R.string.review_first, state.canGoBack, controller::first)
            NavButton(R.string.review_symbol_previous, R.string.review_previous, state.canGoBack, controller::previous)
            NavButton(R.string.review_symbol_next, R.string.review_next, state.canGoForward, controller::next)
            NavButton(R.string.review_symbol_last, R.string.review_last, state.canGoForward, controller::last)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    if (state.position.sideToMove == ChessColor.WHITE) R.string.analysis_side_white else R.string.analysis_side_black,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = controller::flip, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.game_action_flip))
            }
        }
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
private fun Details(state: AnalysisState, controller: AnalysisController) {
    EvaluationPanel(state, controller)
    FenPanel(state, controller)
    MovesPanel(state, controller)
}

// --- оцінка й лінії ---

@Composable
private fun EvaluationPanel(state: AnalysisState, controller: AnalysisController) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val verdict = state.verdict
            val engine = state.engine
            when {
                verdict !is PositionVerdict.Playable -> Text(
                    stringResource(verdictText(verdict)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                engine is AnalysisEngine.Problem -> {
                    Text(
                        stringResource(
                            when (engine.kind) {
                                EngineProblem.UNAVAILABLE -> R.string.game_engine_unavailable
                                EngineProblem.CRASHED -> R.string.game_engine_crashed
                                EngineProblem.UNEXPECTED -> R.string.game_engine_unexpected
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Button(onClick = controller::startAnalysis, modifier = Modifier.heightIn(min = MinTouch)) {
                        Text(stringResource(R.string.game_action_retry))
                    }
                }
                state.lines.isEmpty() -> Text(
                    stringResource(
                        when (engine) {
                            AnalysisEngine.Idle -> R.string.analysis_status_idle
                            AnalysisEngine.Starting -> R.string.analysis_status_starting
                            else -> R.string.analysis_waiting
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {
                    state.lines.forEachIndexed { index, line -> LineRow(line, main = index == 0) }
                    Text(
                        stringResource(if (state.running) R.string.analysis_status_running else R.string.analysis_status_idle),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.running) {
                    OutlinedButton(onClick = controller::stopAnalysis, modifier = Modifier.heightIn(min = MinTouch)) {
                        Text(stringResource(R.string.analysis_stop))
                    }
                } else {
                    Button(
                        onClick = controller::startAnalysis,
                        enabled = state.canAnalyse && state.engine !is AnalysisEngine.Problem,
                        modifier = Modifier.heightIn(min = MinTouch),
                    ) { Text(stringResource(R.string.analysis_start)) }
                }
                MultiPvChooser(state, controller)
            }
            Text(stringResource(R.string.analysis_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LineRow(line: AnalysisLine, main: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val eval = remember(line.evaluation, locale) { line.evaluation.display(locale) }
    val headline: String
    val caption: String
    when (eval) {
        is EvalDisplay.Pawns -> {
            headline = eval.signed
            caption = stringResource(
                when (eval.advantage) {
                    Advantage.WHITE -> R.string.analysis_adv_white
                    Advantage.BLACK -> R.string.analysis_adv_black
                    Advantage.EQUAL -> R.string.analysis_adv_equal
                },
            )
        }
        is EvalDisplay.Mate -> {
            headline = stringResource(R.string.analysis_mate_in, eval.moves)
            caption = stringResource(if (eval.winner == ChessColor.WHITE) R.string.analysis_mate_white else R.string.analysis_mate_black)
        }
    }
    val depth = stringResource(R.string.analysis_depth, line.depth)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$headline, $caption, ${line.pv}" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(if (main) 120.dp else 104.dp)) {
            Text(
                headline,
                style = if (main) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(caption, style = MaterialTheme.typography.bodySmall)
            Text(depth, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            line.pv,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MultiPvChooser(state: AnalysisState, controller: AnalysisController) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.analysis_lines_label), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in 1..state.maxMultiPv) {
                val chosen = n == state.multiPv
                val cd = stringResource(R.string.analysis_cd_lines, n)
                Box(
                    Modifier
                        .size(MinTouch)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { controller.setMultiPv(n) })
                        .semantics { contentDescription = cd },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        n.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

private fun verdictText(verdict: PositionVerdict): Int = when (verdict) {
    PositionVerdict.Playable -> R.string.analysis_waiting
    is PositionVerdict.Checkmate ->
        if (verdict.winner == ChessColor.WHITE) R.string.analysis_verdict_checkmate_white else R.string.analysis_verdict_checkmate_black
    PositionVerdict.Stalemate -> R.string.analysis_verdict_stalemate
    PositionVerdict.InsufficientMaterial -> R.string.analysis_verdict_insufficient
}

// --- FEN ---

@Composable
private fun FenPanel(state: AnalysisState, controller: AnalysisController) {
    val focus = LocalFocusManager.current
    // Завантаження ховає клавіатуру: фокус знімається, щоб дошка й оцінка були видні.
    val load = { focus.clearFocus(); controller.loadFen() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = state.fenText,
            onValueChange = controller::onFenTextChange,
            label = { Text(stringResource(R.string.analysis_fen_label)) },
            isError = state.fenProblem != null,
            supportingText = state.fenProblem?.let {
                {
                    Text(
                        stringResource(if (it == FenProblem.EMPTY) R.string.analysis_fen_empty else R.string.analysis_fen_invalid),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { load() }),
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = load, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.analysis_fen_load))
            }
            OutlinedButton(
                onClick = { focus.clearFocus(); controller.loadStartPosition() },
                modifier = Modifier.heightIn(min = MinTouch),
            ) {
                Text(stringResource(R.string.analysis_fen_start))
            }
        }
    }
}

// --- ходи ---

@Composable
private fun MovesPanel(state: AnalysisState, controller: AnalysisController) {
    val rows = remember(state.game) { moveRows(state.game) }
    val description = stringResource(R.string.game_cd_moves)
    if (rows.isEmpty()) {
        Text(stringResource(R.string.analysis_moves_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        rows.forEach { row ->
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
private fun MoveCell(state: AnalysisState, ply: Int?, controller: AnalysisController, modifier: Modifier) {
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
        Text(san, style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
    }
}
