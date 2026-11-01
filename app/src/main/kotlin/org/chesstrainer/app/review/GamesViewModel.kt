// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.chesstrainer.app.storage.GameId
import org.chesstrainer.app.storage.GamePersistence
import org.chesstrainer.app.storage.GameStorage
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
}
