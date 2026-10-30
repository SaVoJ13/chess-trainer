// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.chesstrainer.app.game.GameTermination
import org.chesstrainer.app.game.GameUiState

/**
 * Автозбереження партії проти рушія (CONTRACTS 5). Усі операції йдуть в одну чергу й виконуються по порядку
 * на [scope] (у застосунку це `Dispatchers.IO`), тому виклики не блокують головний потік, а запис не
 * випереджає попередній. Помилка сховища не зупиняє чергу: наступне збереження повторить запис.
 *
 * Зберігається одна «активна» партія; партія без жодного ходу не зберігається (наявний запис такої партії,
 * наприклад після скасування першого ходу, видаляється).
 */
class AutosaveGameSaver(
    private val repository: GameRepository,
    private val persistence: GamePersistence,
    scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val onError: (Throwable) -> Unit = {},
) : GameSaver {

    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Стан активного запису змінює лише єдина корутина-споживач черги.
    private var activeId: GameId? = null
    private var activeCreatedAt: Instant? = null

    init {
        scope.launch {
            for (job in queue) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    override fun save(state: GameUiState) {
        queue.trySend { write(state) }
    }

    override fun abandonActive() {
        queue.trySend { abandon(takeActive()) }
    }

    override suspend fun findResumable(): ResumableGame? = enqueue { find() }

    override fun resume(game: ResumableGame) {
        queue.trySend {
            activeId = game.id
            activeCreatedAt = game.createdAt
        }
    }

    override fun discard(game: ResumableGame) {
        queue.trySend {
            takeActive()
            abandon(game.id)
        }
    }

    /** Чекає, поки виконаються всі поставлені в чергу операції (для тестів). */
    suspend fun flush() = enqueue { }

    private suspend fun <T> enqueue(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        queue.trySend {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
                throw t
            }
        }
        return result.await()
    }

    private fun takeActive(): GameId? {
        val id = activeId
        activeId = null
        activeCreatedAt = null
        return id
    }

    private suspend fun write(state: GameUiState) {
        val id = activeId
        if (state.game.moves.isEmpty()) {
            if (id != null) {
                repository.delete(id)
                takeActive()
            }
            return
        }
        val now = clock()
        val createdAt = activeCreatedAt ?: now
        activeId = repository.save(persistence.encode(state, id, createdAt, now))
        activeCreatedAt = createdAt
    }

    private suspend fun abandon(id: GameId?) {
        val saved = repository.load(id ?: return) ?: return
        if (!saved.finished) close(saved)
    }

    private suspend fun close(saved: SavedGame) {
        repository.save(saved.copy(finished = true, termination = GameTermination.ABANDONED, updatedAt = clock()))
    }

    private suspend fun find(): ResumableGame? {
        takeActive()
        val saved = repository.lastUnfinished() ?: return null
        val id = saved.id
        val game = persistence.decode(saved)
        val color = saved.playerColor
        if (id == null || game == null || color == null) {
            // Пошкоджені дані: запис закрито, застосунок починає нову партію.
            close(saved)
            return null
        }
        return ResumableGame(id, saved.createdAt, game, color)
    }
}
