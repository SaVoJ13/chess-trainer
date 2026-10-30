// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.chesstrainer.app.R
import org.chesstrainer.app.game.GameUiState
import org.chesstrainer.app.ui.theme.BoardColors
import org.chesstrainer.core.model.Color as ChessColor
import org.chesstrainer.core.model.Piece
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Square

/** Стан перетягування: з якого поля й де зараз палець (у пікселях дошки). */
private data class Drag(val from: Square, val at: Offset)

/**
 * Дошка: торкання й перетягування. Малюється одним `Canvas`; для доступності поверх лежать
 * безпосередні семантичні вузли полів (вони не перехоплюють дотики).
 */
@Composable
fun ChessBoard(
    state: GameUiState,
    onSquareTap: (Square) -> Unit,
    onDragStart: (Square) -> Boolean,
    onDrop: (from: Square, to: Square?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottom = state.bottomColor
    val textMeasurer = rememberTextMeasurer()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val tap by rememberUpdatedState(onSquareTap)
    val dragStart by rememberUpdatedState(onDragStart)
    val drop by rememberUpdatedState(onDrop)
    var drag by remember { mutableStateOf<Drag?>(null) }
    val boardDescription = stringResource(R.string.game_cd_board)

    Box(modifier = modifier.aspectRatio(1f).semantics { contentDescription = boardDescription }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bottom, touchSlop) {
                    val boardPx = size.width.toFloat()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val origin = squareAt(down.position, boardPx, bottom)
                        var moved = false
                        var dragging = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (dragging) {
                                    drop(origin!!, squareAt(change.position, boardPx, bottom))
                                } else if (!moved && origin != null) {
                                    tap(origin)
                                }
                                break
                            }
                            if (!moved && (change.position - down.position).getDistance() > touchSlop) {
                                moved = true
                                dragging = origin != null && dragStart(origin)
                            }
                            if (dragging) {
                                drag = Drag(origin!!, change.position)
                                change.consume()
                            }
                        }
                        drag = null
                    }
                },
        ) {
            drawBoard(state, bottom, drag, textMeasurer)
        }
        SquareSemantics(state, bottom, onSquareTap)
    }
}

private fun squareAt(point: Offset, boardPx: Float, bottom: ChessColor): Square? {
    if (point.x < 0f || point.y < 0f || point.x >= boardPx || point.y >= boardPx) return null
    val cell = boardPx / 8f
    val column = (point.x / cell).toInt().coerceIn(0, 7)
    val row = (point.y / cell).toInt().coerceIn(0, 7)
    return if (bottom == ChessColor.WHITE) Square.of(column, 7 - row) else Square.of(7 - column, row)
}

private fun cellOrigin(square: Square, cell: Float, bottom: ChessColor): Offset {
    val column = if (bottom == ChessColor.WHITE) square.file else 7 - square.file
    val row = if (bottom == ChessColor.WHITE) 7 - square.rank else square.rank
    return Offset(column * cell, row * cell)
}

private fun DrawScope.drawBoard(state: GameUiState, bottom: ChessColor, drag: Drag?, textMeasurer: TextMeasurer) {
    val cell = size.width / 8f
    val cellSize = Size(cell, cell)
    val position = state.position
    val last = state.lastMove

    for (rank in 0..7) {
        for (file in 0..7) {
            val square = Square.of(file, rank)
            val origin = cellOrigin(square, cell, bottom)
            val isLight = (file + rank) % 2 == 1
            drawRect(if (isLight) BoardColors.lightSquare else BoardColors.darkSquare, origin, cellSize)
            if (last != null && (square == last.from || square == last.to)) {
                drawRect(BoardColors.lastMove, origin, cellSize)
            }
            if (square == state.selected) drawRect(BoardColors.selected, origin, cellSize)
            if (square == state.checkSquare) {
                drawCircle(BoardColors.check, radius = cell * 0.46f, center = origin + Offset(cell / 2, cell / 2))
            }
        }
    }

    // Координати: літери під нижнім рядом, цифри в лівому стовпці.
    val label = TextStyle(fontSize = (cell * 0.22f).toSp())
    for (i in 0..7) {
        val bottomSquare = if (bottom == ChessColor.WHITE) Square.of(i, 0) else Square.of(7 - i, 7)
        val fileLetter = bottomSquare.name.take(1)
        val onLight = (bottomSquare.file + bottomSquare.rank) % 2 == 1
        val fileOrigin = cellOrigin(bottomSquare, cell, bottom)
        drawText(
            textMeasurer, fileLetter, fileOrigin + Offset(cell * 0.76f, cell * 0.72f),
            label.copy(color = if (onLight) BoardColors.coordinateOnLight else BoardColors.coordinateOnDark),
        )
        val leftSquare = if (bottom == ChessColor.WHITE) Square.of(0, 7 - i) else Square.of(7, i)
        val rankOnLight = (leftSquare.file + leftSquare.rank) % 2 == 1
        drawText(
            textMeasurer, (leftSquare.rank + 1).toString(), cellOrigin(leftSquare, cell, bottom) + Offset(cell * 0.06f, cell * 0.03f),
            label.copy(color = if (rankOnLight) BoardColors.coordinateOnLight else BoardColors.coordinateOnDark),
        )
    }

    for (index in 0..63) {
        val square = Square(index)
        val piece = position.pieceAt(square) ?: continue
        if (drag != null && drag.from == square) continue
        drawPiece(piece, cellOrigin(square, cell, bottom) + Offset(cell * 0.04f, cell * 0.04f), cell * 0.92f)
    }

    // Підказки ходів: крапка на порожньому полі, кільце на зайнятому.
    state.targets.forEach { target ->
        val center = cellOrigin(target, cell, bottom) + Offset(cell / 2, cell / 2)
        if (position.pieceAt(target) == null) {
            drawCircle(BoardColors.targetHint, radius = cell * 0.16f, center = center)
        } else {
            drawCircle(BoardColors.targetHint, radius = cell * 0.46f, center = center, style = Stroke(width = cell * 0.09f))
        }
    }

    if (drag != null) {
        position.pieceAt(drag.from)?.let { piece ->
            val big = cell * 1.25f
            drawPiece(piece, drag.at - Offset(big / 2, big / 2), big)
        }
    }
}

/** Семантичні вузли полів для екранних читачів: не беруть участі в обробці дотиків. */
@Composable
private fun SquareSemantics(state: GameUiState, bottom: ChessColor, onSquareTap: (Square) -> Unit) {
    val last = state.lastMove
    Column(Modifier.fillMaxSize()) {
        for (row in 0..7) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (column in 0..7) {
                    val square = if (bottom == ChessColor.WHITE) Square.of(column, 7 - row) else Square.of(7 - column, row)
                    val piece = state.position.pieceAt(square)
                    val description = when {
                        square == state.selected -> stringResource(R.string.game_cd_square_selected, square.name)
                        square in state.targets -> stringResource(R.string.game_cd_square_target, square.name)
                        last != null && (square == last.from || square == last.to) ->
                            stringResource(R.string.game_cd_square_last_move, square.name)
                        else -> square.name
                    }
                    val full = if (piece != null) {
                        stringResource(R.string.game_cd_square_piece, description, pieceName(piece))
                    } else if (description == square.name) {
                        stringResource(R.string.game_cd_square_empty, square.name)
                    } else {
                        description
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clearAndSetSemantics {
                                contentDescription = full
                                role = Role.Button
                                onClick { onSquareTap(square); true }
                            },
                    )
                }
            }
        }
    }
}

@Composable
internal fun pieceName(piece: Piece): String = stringResource(
    when (piece.color) {
        ChessColor.WHITE -> when (piece.type) {
            PieceType.KING -> R.string.piece_white_king
            PieceType.QUEEN -> R.string.piece_white_queen
            PieceType.ROOK -> R.string.piece_white_rook
            PieceType.BISHOP -> R.string.piece_white_bishop
            PieceType.KNIGHT -> R.string.piece_white_knight
            PieceType.PAWN -> R.string.piece_white_pawn
        }
        ChessColor.BLACK -> when (piece.type) {
            PieceType.KING -> R.string.piece_black_king
            PieceType.QUEEN -> R.string.piece_black_queen
            PieceType.ROOK -> R.string.piece_black_rook
            PieceType.BISHOP -> R.string.piece_black_bishop
            PieceType.KNIGHT -> R.string.piece_black_knight
            PieceType.PAWN -> R.string.piece_black_pawn
        }
    },
)
