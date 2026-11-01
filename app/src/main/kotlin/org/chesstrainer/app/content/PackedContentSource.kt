// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.content

import java.io.InputStream
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.chesstrainer.learning.content.ContentLoadResult
import org.chesstrainer.learning.content.ContentManifest
import org.chesstrainer.learning.content.ContentProblem
import org.chesstrainer.learning.content.ContentSource
import org.chesstrainer.learning.content.InMemoryContentSource
import org.chesstrainer.learning.content.SchemaVersion
import org.chesstrainer.learning.puzzle.Puzzle
import org.chesstrainer.learning.puzzle.PuzzleFilter
import org.chesstrainer.learning.puzzle.PuzzleId

/** Що відомо про контент після спроби завантаження. */
sealed interface ContentStatus {
    /** Контент придатний. [problems] це пропущені пакети й задачі (їх видно в журналі, гравцю не показуються). */
    data class Ready(val puzzleCount: Int, val problems: List<ContentProblem>) : ContentStatus

    /** Манифест непридатний (пошкоджений або незнайомий MAJOR): задач немає, застосунок працює далі. */
    data class Unavailable(val problem: ContentProblem) : ContentStatus
}

/** Вимір одного завантаження (CONTRACTS 6.1, бюджет ≤ 300 мс і ≤ 15 МБ). [heapBytes] лише якщо вимір пам'яті ввімкнено. */
data class ContentLoadMeasurement(val puzzleCount: Int, val elapsedMs: Long, val heapBytes: Long?)

/**
 * [ContentSource] над пакетами в assets (CONTRACTS 6.1): один раз читає й розбирає їх поза головним потоком
 * (режим STRUCTURE задає [load]), далі тримає результат у пам'яті. Помилка контенту не кидається: [status]
 * повертає [ContentStatus.Unavailable], а запити дають порожній результат.
 *
 * @param load блокуюче читання й розбір (виконується на [dispatcher]).
 * @param measureHeap якщо true, до й після завантаження викликається `System.gc()` для виміру утримуваної пам'яті
 *   (лише для налагодження, не для звичайної роботи).
 * @param onMeasured отримує вимір після кожного (одного) завантаження.
 */
class PackedContentSource(
    private val load: () -> ContentLoadResult,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val measureHeap: Boolean = false,
    private val onMeasured: (ContentLoadMeasurement) -> Unit = {},
) : ContentSource {

    private class Loaded(val status: ContentStatus, val source: ContentSource)

    private val mutex = Mutex()

    @Volatile
    private var loaded: Loaded? = null

    suspend fun status(): ContentStatus = ensureLoaded().status

    override suspend fun manifest(): ContentManifest = ensureLoaded().source.manifest()

    override suspend fun puzzles(filter: PuzzleFilter): List<Puzzle> = ensureLoaded().source.puzzles(filter)

    override suspend fun puzzle(id: PuzzleId): Puzzle? = ensureLoaded().source.puzzle(id)

    private suspend fun ensureLoaded(): Loaded {
        loaded?.let { return it }
        return mutex.withLock {
            loaded ?: withContext(dispatcher) { measured() }.also { loaded = it }
        }
    }

    private fun measured(): Loaded {
        val heapBefore = if (measureHeap) usedHeap() else 0L
        val started = TimeSource.Monotonic.markNow()
        val result = try {
            load()
        } catch (e: RuntimeException) {
            // Непередбачена помилка розбору не має валити застосунок.
            ContentLoadResult.Failed(ContentProblem.Malformed("content", e.message ?: e.javaClass.simpleName))
        }
        val elapsed = started.elapsedNow().inWholeMilliseconds
        val out = when (result) {
            is ContentLoadResult.Loaded -> Loaded(
                ContentStatus.Ready(result.content.puzzles.size, result.content.problems),
                InMemoryContentSource(result.content.manifest, result.content.puzzles),
            )
            is ContentLoadResult.Failed -> Loaded(
                ContentStatus.Unavailable(result.problem),
                InMemoryContentSource(ContentManifest(SchemaVersion(0, 0), emptyList()), emptyList()),
            )
        }
        val count = (out.status as? ContentStatus.Ready)?.puzzleCount ?: 0
        val heap = if (measureHeap) usedHeap() - heapBefore else null
        onMeasured(ContentLoadMeasurement(count, elapsed, heap))
        return out
    }

    private fun usedHeap(): Long {
        System.gc()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }
}

/** Шляхи файлів у assets (див. `prepareContentAssets` у `app/build.gradle.kts`). */
object ContentAssets {
    const val ROOT = "content/common"
    fun manifestPath() = "$ROOT/manifest.json"
    fun packPath(relative: String) = "$ROOT/$relative"
}

/** Відкриває файли контенту; на Android це `assets.open(path)`. */
fun interface AssetOpener {
    fun open(path: String): InputStream
}

/**
 * Збирає [PackedContentSource] над файлами контенту (режим STRUCTURE, CONTRACTS 6.1 v0.16).
 * Відсутній манифест дає [ContentStatus.Unavailable], а не виняток.
 */
fun packedContentSource(
    opener: AssetOpener,
    rules: org.chesstrainer.core.rules.ChessRules,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    measureHeap: Boolean = false,
    onMeasured: (ContentLoadMeasurement) -> Unit = {},
): PackedContentSource = PackedContentSource(
    load = {
        val loader = org.chesstrainer.learning.content.ContentLoader(rules, org.chesstrainer.learning.content.LoadValidation.STRUCTURE)
        val manifest = try {
            opener.open(ContentAssets.manifestPath())
        } catch (e: java.io.IOException) {
            return@PackedContentSource ContentLoadResult.Failed(
                ContentProblem.MissingPack(org.chesstrainer.learning.content.PuzzleFormat.MANIFEST_FILE),
            )
        }
        loader.load(manifest) { packId ->
            opener.open(ContentAssets.packPath(org.chesstrainer.learning.content.PuzzleFormat.packPath(packId)))
        }
    },
    dispatcher = dispatcher,
    measureHeap = measureHeap,
    onMeasured = onMeasured,
)
