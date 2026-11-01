// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.pgn

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.chesstrainer.app.storage.GameMode
import org.chesstrainer.app.storage.SavedGame
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.pgn.PgnCodec
import org.chesstrainer.core.pgn.PgnGame

/** Текст PGN для експорту й кількість партій, яких не вдалося прочитати. */
data class PgnExport(val text: String, val exported: Int, val unreadable: Int)

/**
 * Експорт збережених партій в один PGN (UTF-8) через [PgnCodec.write] (CONTRACTS 5). Теги PGN це дані, а не
 * тексти інтерфейсу, тому англійські значення за замовчуванням не локалізуються.
 *
 * Партії проти рушія отримують `Event` «Chess Trainer», `White`/`Black` «Player» і «Stockfish (level N)»
 * (N це `DifficultyId`), `Date` за часом створення, якщо тегу немає. Імпортовані партії експортуються з тими
 * тегами, які були у файлі (відсутні семитегові значення кодек доповнює за стандартом: «?»).
 */
class PgnExporter(private val codec: PgnCodec) {

    fun export(games: List<SavedGame>): PgnExport {
        val text = StringBuilder()
        var exported = 0
        var unreadable = 0
        for (saved in games) {
            val game = parse(saved)
            if (game == null) {
                unreadable++
            } else {
                text.append(codec.write(withDefaults(game, saved)))
                exported++
            }
        }
        return PgnExport(text.toString(), exported, unreadable)
    }

    private fun parse(saved: SavedGame): PgnGame? = try {
        (codec.parse(saved.pgn).firstOrNull() as? ChessResult.Ok)?.value
    } catch (e: RuntimeException) {
        null
    }

    private fun withDefaults(game: PgnGame, saved: SavedGame): PgnGame {
        val headers = LinkedHashMap(game.headers)
        if (saved.mode == GameMode.VS_ENGINE) {
            val engine = "Stockfish" + (saved.difficultyId?.let { " (level ${it.value})" } ?: "")
            headers["Event"] = "Chess Trainer"
            headers["White"] = if (saved.playerColor == Color.BLACK) engine else "Player"
            headers["Black"] = if (saved.playerColor == Color.BLACK) "Player" else engine
        }
        if (headers["Date"].isNullOrBlank() || headers["Date"] == "????.??.??") {
            if (saved.mode != GameMode.IMPORTED) headers["Date"] = DATE_FORMAT.format(saved.createdAt.atOffset(ZoneOffset.UTC))
        }
        return game.copy(headers = headers)
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    }
}
