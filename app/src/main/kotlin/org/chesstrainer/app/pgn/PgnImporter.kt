// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.pgn

import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chesstrainer.app.storage.GameMode
import org.chesstrainer.app.storage.GameRepository
import org.chesstrainer.app.storage.SavedGame
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.pgn.PgnCodec
import org.chesstrainer.core.pgn.PgnGame

/** Підсумок імпорту PGN (CONTRACTS 5). */
sealed interface ImportResult {
    /** [skipped] це номери партій у файлі (з 1), які не вдалося прочитати (синтаксис чи нелегальний хід). */
    data class Done(val imported: Int, val skipped: List<Int>) : ImportResult

    data object NoGames : ImportResult

    /** Файл більший за [PgnImporter.MAX_BYTES]; нічого не імпортовано. */
    data object TooLarge : ImportResult

    /** У файлі більше за [PgnImporter.MAX_GAMES] партій; нічого не імпортовано. */
    data object TooMany : ImportResult

    data object ReadError : ImportResult
}

/**
 * Імпорт PGN із потоку (CONTRACTS 5). Розбір лише через [PgnCodec]. Ліміти: файл до [MAX_BYTES], партій до
 * [MAX_GAMES]; більше це відмова без часткового імпорту (до сховища нічого не потрапляє, поки файл не
 * розібрано цілком). Читання й розбір ідуть поза головним потоком.
 *
 * Партії з помилками пропускаються. Збережені партії мають `GameMode.IMPORTED`, `finished = true`,
 * `termination = null`, без кольору й рівня; дедуплікації немає. Час оновлення зменшується на мілісекунду на
 * кожну наступну партію файлу, тож у списку (новіші зверху) партії йдуть у порядку файлу.
 */
class PgnImporter(
    private val codec: PgnCodec,
    private val repository: GameRepository,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun import(input: InputStream): ImportResult {
        val bytes = try {
            withContext(io) { readLimited(input) }
        } catch (e: IOException) {
            return ImportResult.ReadError
        } ?: return ImportResult.TooLarge
        val parsed = try {
            withContext(cpu) { parse(String(bytes, Charsets.UTF_8).removePrefix(BOM)) }
        } catch (e: RuntimeException) {
            return ImportResult.ReadError
        } ?: return ImportResult.TooMany

        if (parsed.isEmpty()) return ImportResult.NoGames
        val now = clock()
        val skipped = mutableListOf<Int>()
        var imported = 0
        parsed.forEachIndexed { index, result ->
            when (result) {
                is ChessResult.Err -> skipped += index + 1
                is ChessResult.Ok -> {
                    repository.save(imported(result.value, now.minusMillis(index.toLong())))
                    imported++
                }
            }
        }
        return ImportResult.Done(imported, skipped)
    }

    /** `null`, якщо файл перевищує ліміт. Читає не більше [MAX_BYTES] + 1 байт. */
    private fun readLimited(input: InputStream): ByteArray? = input.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) return null
        }
        out.toByteArray()
    }

    /** `null`, якщо партій більше за [MAX_GAMES]. */
    private fun parse(text: String): List<ChessResult<PgnGame>>? {
        val results = ArrayList<ChessResult<PgnGame>>()
        for (result in codec.parse(text)) {
            if (results.size >= MAX_GAMES) return null
            results += result
        }
        return results
    }

    private fun imported(game: PgnGame, at: Instant) = SavedGame(
        id = null,
        pgn = codec.write(game),
        mode = GameMode.IMPORTED,
        playerColor = null,
        difficultyId = null,
        finished = true,
        termination = null,
        createdAt = at,
        updatedAt = at,
    )

    companion object {
        /** 2 МБ (CONTRACTS 5). */
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_GAMES = 500

        /** Позначка порядку байтів (U+FEFF) на початку файлу, яку додають деякі редактори. */
        private val BOM = 0xFEFF.toChar().toString()
    }
}
