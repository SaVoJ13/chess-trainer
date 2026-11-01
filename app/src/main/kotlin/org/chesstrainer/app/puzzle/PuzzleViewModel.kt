// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.puzzle

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.chesstrainer.app.content.AssetOpener
import org.chesstrainer.app.content.packedContentSource
import org.chesstrainer.app.storage.GameStorage
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.learning.puzzle.DefaultPuzzleService

/**
 * Composition root екрана задач. Контент читається один раз поза головним потоком (STRUCTURE, CONTRACTS 6.1);
 * у debug-збірці вимір часу й пам'яті пишеться в лог з тегом [CONTENT_TAG] (інструкція в `docs/project_log.md`).
 */
class PuzzleViewModel(application: Application) : AndroidViewModel(application) {
    private val rules = ChesslibRules()
    private val debuggable = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) ||
        measureLogEnabled(application)

    private val content = packedContentSource(
        opener = AssetOpener { path -> application.assets.open(path) },
        rules = rules,
        measureHeap = debuggable,
        onMeasured = { m ->
            if (debuggable) {
                Log.i(
                    CONTENT_TAG,
                    "loaded ${m.puzzleCount} puzzles in ${m.elapsedMs} ms, retained heap ${m.heapBytes?.div(1024)} KiB",
                )
            }
        },
    )
    private val progress = GameStorage.puzzleProgress(application)

    val controller = PuzzleController(
        service = DefaultPuzzleService(content, progress, rules),
        progress = progress,
        rules = rules,
        contentStatus = content::status,
        scope = viewModelScope,
        onError = { Log.w(CONTENT_TAG, "Recording the puzzle attempt failed", it) },
    )

    init {
        controller.start()
    }

    private fun measureLogEnabled(app: Application): Boolean {
        // Ресурс задає лише buildType `measure` (app/build.gradle.kts); в інших збірках його немає.
        val id = app.resources.getIdentifier("content_measure_log", "bool", app.packageName)
        return id != 0 && app.resources.getBoolean(id)
    }

    private companion object {
        const val CONTENT_TAG = "PuzzleContent"
    }
}
