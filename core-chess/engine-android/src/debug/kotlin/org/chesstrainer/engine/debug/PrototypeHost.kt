// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine.debug

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Живе стільки, скільки процес: сценарії не залежать від життєвого циклу Activity (її система може
 * перестворити, зокрема над екраном блокування). Повторно доставлений той самий запит ігнорується.
 */
internal object PrototypeHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scenarios = AtomicReference<Pair<String, PrototypeScenarios>?>(null)
    private val running = AtomicReference<String?>(null)

    fun launch(context: Context, name: String, remote: Boolean, baseline: Boolean, repeat: Int, onDone: () -> Unit) {
        val mode = if (remote) "remote" else "inproc"
        val key = "$mode/$baseline"
        val request = "$name/$key/$repeat"
        if (!running.compareAndSet(null, request)) {
            Log.i("EnginePrototype", "ignored $request: ${running.get()} is still running")
            return
        }
        // Той самий екземпляр рушія живе між сценаріями одного режиму (потрібно для bgprep → bgcheck).
        val current = scenarios.get()?.takeIf { it.first == key }?.second
            ?: PrototypeScenarios(context.applicationContext, mode, baseline).also { scenarios.set(key to it) }
        scope.launch {
            try {
                current.run(name, repeat)
            } finally {
                running.set(null)
                onDone()
            }
        }
    }
}
