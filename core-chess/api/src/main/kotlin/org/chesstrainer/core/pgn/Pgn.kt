// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.pgn

import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Uci

enum class PgnResult(val tag: String) {
    WHITE_WINS("1-0"),
    BLACK_WINS("0-1"),
    DRAW("1/2-1/2"),
    UNKNOWN("*"),
}

data class PgnMove(
    val san: String,
    val uci: Uci,
    val comment: String? = null,
    val nags: List<Int> = emptyList(),
)

data class PgnGame(
    /** Порядок: спершу Seven Tag Roster, далі решта. */
    val headers: Map<String, String>,
    /** З тегів SetUp/FEN. */
    val startFen: Fen?,
    val moves: List<PgnMove>,
    val result: PgnResult,
)

interface PgnCodec {
    /** Ліниво; помилка в одній партії не зупиняє наступні. */
    fun parse(text: String): Sequence<ChessResult<PgnGame>>

    /** UTF-8, рядки ≤ 80 символів. */
    fun write(game: PgnGame): String
}
