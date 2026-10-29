// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import android.content.Context
import java.io.File

/**
 * Мережа NNUE лежить в APK як asset (одна копія для обох `.so`), а Stockfish читає її з файлу.
 * Файл копіюється у `filesDir/nnue/` один раз; імена несуть SHA-256, тож стару версію не сплутати.
 */
internal object NnueAsset {
    const val NAME = "nn-1a298aa575a0.nnue"

    /** Розмір файлу `nn-1a298aa575a0.nnue` (виміряно під час завантаження). */
    private const val SIZE = 98_511_183L

    /** Шлях до файлу мережі; копіює з asset, якщо файлу ще немає. */
    fun ensure(context: Context): File {
        val dir = File(context.filesDir, "nnue").apply { mkdirs() }
        val target = File(dir, NAME)
        if (target.isFile && target.length() == SIZE) return target
        // Тимчасовий файл із pid: основний процес і :engine можуть копіювати одночасно.
        val temp = File(dir, "$NAME.${android.os.Process.myPid()}.tmp")
        context.assets.open("nnue/$NAME").use { input ->
            temp.outputStream().use { input.copyTo(it, bufferSize = 1 shl 20) }
        }
        check(temp.length() == SIZE) { "unexpected network size ${temp.length()}" }
        if (!temp.renameTo(target)) {
            temp.delete()
            check(target.isFile && target.length() == SIZE) { "cannot install network file" }
        }
        return target
    }
}
