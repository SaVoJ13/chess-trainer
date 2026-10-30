// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import android.util.Log
import java.io.File

/**
 * Легке трасування життєвого циклу рушія (G1): кільцевий буфер міток із часом і лічильники.
 * Жодного UCI в logcat: у logcat потрапляє лише вміст буфера в момент [dump] (зависання або запит),
 * після чого буфер скидається. Стосується одного процесу: `:engine` має власний екземпляр,
 * який додатково дублює мітки у файл (див. [mirrorTo]), бо з процесу застосунку його не прочитати,
 * коли `:engine` завис.
 */
internal object EngineTrace {
    private const val TAG = "EngineTrace"
    private const val SIZE = 256
    private const val MAX_FILE_BYTES = 64 * 1024L

    private val lock = Any()
    private val times = LongArray(SIZE)
    private val labels = arrayOfNulls<String>(SIZE)
    private val counters = HashMap<String, Int>()
    private var count = 0L
    private var mirror: File? = null

    /** Додає мітку; [label] має бути коротким і сталим (за ним ведеться лічильник). */
    fun mark(label: String, detail: String? = null) {
        val now = System.nanoTime()
        val text = if (detail == null) label else "$label $detail"
        val file: File?
        synchronized(lock) {
            val index = (count % SIZE).toInt()
            times[index] = now
            labels[index] = text
            count++
            counters[label] = (counters[label] ?: 0) + 1
            file = mirror
        }
        if (file != null) runCatching {
            if (file.length() > MAX_FILE_BYTES) file.writeText("")
            file.appendText("${System.currentTimeMillis()} $text\n")
        }
    }

    /** Дублює кожну мітку у [file] (процес `:engine`); файл обнуляється при старті процесу. */
    fun mirrorTo(file: File) {
        runCatching { file.writeText("") }
        synchronized(lock) { mirror = file }
    }

    fun counter(label: String): Int = synchronized(lock) { counters[label] ?: 0 }

    /** Пише буфер і лічильники в logcat (теґ [TAG]) і скидає їх. Повертає кількість записаних міток. */
    fun dump(reason: String): Int {
        val now = System.nanoTime()
        val snapshot = ArrayList<String>()
        val summary: String
        synchronized(lock) {
            val total = minOf(count, SIZE.toLong()).toInt()
            val first = (count - total)
            for (i in 0 until total) {
                val index = ((first + i) % SIZE).toInt()
                snapshot += "-%.3fs %s".format((now - times[index]) / 1e9, labels[index])
            }
            summary = counters.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
            count = 0
            counters.clear()
        }
        Log.w(TAG, "DUMP reason=$reason marks=${snapshot.size} counters: $summary")
        snapshot.forEach { Log.w(TAG, it) }
        return snapshot.size
    }
}
