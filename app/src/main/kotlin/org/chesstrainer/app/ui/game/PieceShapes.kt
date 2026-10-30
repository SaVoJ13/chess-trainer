// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import org.chesstrainer.core.model.Color as ChessColor
import org.chesstrainer.core.model.Piece
import org.chesstrainer.core.model.PieceType

/*
 * Власний набір фігур проєкту (docs/THIRD_PARTY.md, AS-007): прості геометричні контури,
 * намальовані з нуля в системі координат 100×100. Сторонніх зображень чи контурів не використано.
 */

private class PieceShape(
    /** Заливка й контур у порядку малювання. */
    val parts: List<Path>,
    /** Внутрішні лінії (кольору деталей). */
    val details: List<Path> = emptyList(),
    /** Дрібні круглі деталі: центр і радіус. */
    val dots: List<Pair<Offset, Float>> = emptyList(),
)

private fun path(build: Path.() -> Unit) = Path().apply(build)

private fun polygon(vararg xy: Float) = path {
    moveTo(xy[0], xy[1])
    for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
    close()
}

private fun circle(x: Float, y: Float, r: Float) = path { addOval(Rect(Offset(x, y), r)) }

private fun base() = path { addRoundRect(RoundRect(22f, 79f, 78f, 90f, 3f, 3f)) }

private val shapes: Map<PieceType, PieceShape> by lazy {
    mapOf(
        PieceType.PAWN to PieceShape(
            parts = listOf(
                path {
                    moveTo(43f, 40f)
                    lineTo(57f, 40f)
                    cubicTo(58f, 54f, 68f, 64f, 70f, 79f)
                    lineTo(30f, 79f)
                    cubicTo(32f, 64f, 42f, 54f, 43f, 40f)
                    close()
                },
                circle(50f, 31f, 10.5f),
                base(),
            ),
        ),
        PieceType.ROOK to PieceShape(
            parts = listOf(
                polygon(35f, 46f, 65f, 46f, 69f, 79f, 31f, 79f),
                polygon(
                    28f, 20f, 38f, 20f, 38f, 27f, 45f, 27f, 45f, 20f, 55f, 20f, 55f, 27f,
                    62f, 27f, 62f, 20f, 72f, 20f, 72f, 40f, 66f, 46f, 34f, 46f, 28f, 40f,
                ),
                base(),
            ),
            details = listOf(path { moveTo(35f, 46f); lineTo(65f, 46f) }),
        ),
        PieceType.KNIGHT to PieceShape(
            parts = listOf(
                path {
                    moveTo(28f, 79f)
                    cubicTo(28f, 66f, 32f, 58f, 40f, 50f)
                    cubicTo(34f, 50f, 28f, 53f, 22f, 57f)
                    lineTo(20f, 50f)
                    cubicTo(22f, 42f, 30f, 34f, 38f, 28f)
                    lineTo(37f, 17f)
                    lineTo(45f, 23f)
                    cubicTo(64f, 22f, 76f, 38f, 74f, 60f)
                    cubicTo(73f, 67f, 72f, 73f, 72f, 79f)
                    close()
                },
                base(),
            ),
            details = listOf(path { moveTo(52f, 28f); cubicTo(64f, 34f, 66f, 48f, 62f, 62f) }),
            dots = listOf(Offset(34f, 40f) to 2.4f),
        ),
        PieceType.BISHOP to PieceShape(
            parts = listOf(
                path {
                    moveTo(41f, 58f)
                    lineTo(59f, 58f)
                    cubicTo(59f, 66f, 66f, 72f, 70f, 79f)
                    lineTo(30f, 79f)
                    cubicTo(34f, 72f, 41f, 66f, 41f, 58f)
                    close()
                },
                path {
                    moveTo(50f, 21f)
                    cubicTo(68f, 32f, 70f, 48f, 61f, 58f)
                    lineTo(39f, 58f)
                    cubicTo(30f, 48f, 32f, 32f, 50f, 21f)
                    close()
                },
                circle(50f, 16f, 5.5f),
                base(),
            ),
            details = listOf(path { moveTo(44f, 42f); lineTo(56f, 34f) }),
        ),
        PieceType.QUEEN to PieceShape(
            parts = listOf(
                polygon(30f, 64f, 70f, 64f, 73f, 79f, 27f, 79f),
                polygon(
                    27f, 32f, 34f, 52f, 38f, 28f, 44f, 50f, 50f, 24f, 56f, 50f, 62f, 28f, 66f, 52f,
                    73f, 32f, 70f, 64f, 30f, 64f,
                ),
                circle(27f, 30f, 3.8f),
                circle(38f, 26f, 3.8f),
                circle(50f, 22f, 3.8f),
                circle(62f, 26f, 3.8f),
                circle(73f, 30f, 3.8f),
                base(),
            ),
            details = listOf(path { moveTo(30f, 64f); lineTo(70f, 64f) }),
        ),
        PieceType.KING to PieceShape(
            parts = listOf(
                path {
                    moveTo(50f, 31f)
                    cubicTo(70f, 29f, 82f, 44f, 74f, 56f)
                    cubicTo(72f, 60f, 70f, 61f, 68f, 64f)
                    lineTo(72f, 79f)
                    lineTo(28f, 79f)
                    lineTo(32f, 64f)
                    cubicTo(30f, 61f, 28f, 60f, 26f, 56f)
                    cubicTo(18f, 44f, 30f, 29f, 50f, 31f)
                    close()
                },
                polygon(
                    47f, 7f, 53f, 7f, 53f, 13f, 59f, 13f, 59f, 19f, 53f, 19f, 53f, 30f, 47f, 30f,
                    47f, 19f, 41f, 19f, 41f, 13f, 47f, 13f,
                ),
                base(),
            ),
            details = listOf(path { moveTo(32f, 64f); lineTo(68f, 64f) }),
        ),
    )
}

private val whiteFill = Color(0xFFFBFBF8)
private val whiteLine = Color(0xFF2B2F35)
private val whiteDetail = Color(0xFF3A3F45)
private val blackFill = Color(0xFF3B4048)
private val blackLine = Color(0xFF0F1114)
private val blackDetail = Color(0xFFD6DCE2)

/** Малює фігуру в квадраті зі стороною [size] і лівим верхнім кутом [topLeft]. */
internal fun DrawScope.drawPiece(piece: Piece, topLeft: Offset, size: Float) {
    val white = piece.color == ChessColor.WHITE
    val fill = if (white) whiteFill else blackFill
    val line = if (white) whiteLine else blackLine
    val detail = if (white) whiteDetail else blackDetail
    val shape = shapes.getValue(piece.type)
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(size / 100f, size / 100f, pivot = Offset.Zero)
    }) {
        val outline = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        shape.parts.forEach {
            drawPath(it, fill)
            drawPath(it, line, style = outline)
        }
        val inner = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        shape.details.forEach { drawPath(it, detail, style = inner) }
        shape.dots.forEach { (center, radius) -> drawCircle(detail, radius, center) }
    }
}
