// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.review

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import org.chesstrainer.app.pgn.ExportSelection
import org.chesstrainer.app.pgn.ImportResult
import org.chesstrainer.app.pgn.PgnExporter
import org.chesstrainer.app.pgn.PgnFiles
import org.chesstrainer.app.pgn.PgnImporter
import org.chesstrainer.app.pgn.TransferMessage
import org.chesstrainer.app.pgn.TransferState
import org.chesstrainer.app.storage.GameId
import org.chesstrainer.app.storage.GamePersistence
import org.chesstrainer.app.storage.GameStorage
import org.chesstrainer.app.storage.SavedGame
import org.chesstrainer.app.storage.SavedGameSummary
import org.chesstrainer.core.game.DefaultChessGameFactory
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.pgn.DefaultPgnCodec
import org.chesstrainer.core.rules.ChesslibRules

/** Що показує екран перегляду партії. */
sealed interface ReviewSlot {
    data object Loading : ReviewSlot

    /** Запис не знайдено або його PGN не вдається відтворити. */
    data object Failed : ReviewSlot

    data class Ready(val controller: ReviewController) : ReviewSlot
}

/** Список партій і перегляд партії. Живе, поки відкрито застосунок (переживає поворот екрана). */
class GamesViewModel(application: Application) : AndroidViewModel(application) {
    private val rules = ChesslibRules()
    private val repository = GameStorage.repository(application)
    private val saver = GameStorage.saver(application)
    private val persistence = GamePersistence(DefaultChessGameFactory(rules), DefaultPgnCodec(rules))

    /** `null`, доки список не прочитано з бази. */
    val games: StateFlow<List<SavedGameSummary>?> = repository.observeAll()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _review = MutableStateFlow<ReviewSlot>(ReviewSlot.Loading)
    val review: StateFlow<ReviewSlot> = _review
    private var reviewId: GameId? = null

    /** Завантажує партію для перегляду; повторний виклик для тієї самої партії нічого не змінює (зберігає позицію). */
    fun openReview(id: GameId) {
        if (reviewId == id && _review.value !is ReviewSlot.Failed) return
        reviewId = id
        _review.value = ReviewSlot.Loading
        viewModelScope.launch {
            val saved = try {
                repository.load(id)
            } catch (e: Exception) {
                null
            }
            val reviewed = saved?.let(persistence::decodeForReview)
            _review.value = if (saved == null || reviewed == null) {
                ReviewSlot.Failed
            } else {
                ReviewSlot.Ready(
                    ReviewController(id, rules, reviewed.game, reviewed.result, reviewed.headers, saved.playerColor ?: Color.WHITE),
                )
            }
        }
    }

    fun closeReview() {
        reviewId = null
        _review.value = ReviewSlot.Loading
    }

    /** Видаляє партію. `true`, якщо це була активна партія автозбереження (CONTRACTS 5): екран гри тоді скидають. */
    suspend fun delete(id: GameId): Boolean {
        if (reviewId == id) closeReview()
        return saver.delete(id)
    }

    // --- імпорт і експорт PGN (CONTRACTS 5) ---

    private val codec = DefaultPgnCodec(rules)
    private val files = PgnFiles(application)
    private val importer = PgnImporter(codec, repository)
    private val exporter = PgnExporter(codec)

    private val _transfer = MutableStateFlow<TransferState>(TransferState.Idle)
    val transfer: StateFlow<TransferState> = _transfer

    /** Вибір для збереження у файл, поки відкрито системний вибір файлу. */
    private var pendingSave: ExportSelection? = null

    fun importFrom(uri: Uri) {
        _transfer.value = TransferState.Busy
        viewModelScope.launch {
            val result = try {
                files.import(importer, uri)
            } catch (e: Exception) {
                ImportResult.ReadError
            }
            _transfer.value = TransferState.Message(TransferMessage.Import(result))
        }
    }

    /** Користувач обрав партію чи «усі»; далі він вирішує, зберегти файл чи поділитися. */
    fun requestExport(selection: ExportSelection) {
        _transfer.value = TransferState.ChooseExport(selection)
    }

    fun dismissTransfer() {
        pendingSave = null
        _transfer.value = TransferState.Idle
    }

    /** Системний вибір файлу відкривається для вибору, що чекає; повертає запропоноване ім'я файлу. */
    fun beginSave(): String? {
        val selection = (_transfer.value as? TransferState.ChooseExport)?.selection ?: return null
        pendingSave = selection
        _transfer.value = TransferState.Idle
        return fileName(selection)
    }

    /** Результат системного вибору файлу: записує PGN у вибраний файл. */
    fun saveTo(uri: Uri) {
        val selection = pendingSave ?: return
        pendingSave = null
        _transfer.value = TransferState.Busy
        viewModelScope.launch {
            val export = build(selection)
            _transfer.value = TransferState.Message(
                when {
                    export == null -> TransferMessage.ExportNothing
                    files.write(uri, export) -> TransferMessage.ExportSaved
                    else -> TransferMessage.ExportFailed
                },
            )
        }
    }

    /** Користувач передумав у системному виборі файлу. */
    fun cancelSave() {
        pendingSave = null
    }

    /**
     * Готує файл для системного меню «Поділитися». `null` з уже показаним повідомленням, якщо ділитися нічим
     * чи файл не вдалося підготувати.
     */
    suspend fun prepareShare(): Uri? {
        val selection = (_transfer.value as? TransferState.ChooseExport)?.selection ?: return null
        _transfer.value = TransferState.Busy
        val export = build(selection)
        val uri = export?.let { files.prepareShare(it, fileName(selection)) }
        _transfer.value = if (uri == null) {
            TransferState.Message(if (export == null) TransferMessage.ExportNothing else TransferMessage.ExportFailed)
        } else {
            TransferState.Idle
        }
        return uri
    }

    /** Текст PGN вибраних партій; `null`, якщо нічого експортувати (немає партій чи жодну не вдалося прочитати). */
    private suspend fun build(selection: ExportSelection): String? {
        val saved: List<SavedGame> = try {
            val ids = when (selection) {
                is ExportSelection.One -> listOf(selection.id)
                ExportSelection.All -> repository.observeAll().first().map { it.id }
            }
            ids.mapNotNull { repository.load(it) }
        } catch (e: Exception) {
            return null
        }
        val export = withContext(Dispatchers.Default) { exporter.export(saved) }
        return export.text.takeIf { export.exported > 0 }
    }

    private fun fileName(selection: ExportSelection): String {
        val date = LocalDate.now()
        return when (selection) {
            is ExportSelection.One -> "chess-trainer-game-$date.pgn"
            ExportSelection.All -> "chess-trainer-games-$date.pgn"
        }
    }
}
