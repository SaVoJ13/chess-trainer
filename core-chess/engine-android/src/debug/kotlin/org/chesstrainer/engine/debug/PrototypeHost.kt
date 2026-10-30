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

    fun launch(context: Context, name: String, remote: Boolean, baseline: Boolean, repeat: Int, noWait: Boolean = false, onDone: () -> Unit) {
        val mode = if (remote) "remote" else "inproc"
        val key = "$mode/$baseline/$noWait"
        val request = "$name/$key/$repeat"
        if (!running.compareAndSet(null, request)) {
            Log.i("EnginePrototype", "ignored $request: ${running.get()} is still running")
            return
        }
        // Той самий екземпляр рушія живе між сценаріями одного режиму (потрібно для bgprep → bgcheck).
        val current = scenarios.get()?.takeIf { it.first == key }?.second
            ?: PrototypeScenarios(context.applicationContext, mode, baseline, noWait).also { scenarios.set(key to it) }
        scope.launch {
            val watchdog = startWatchdog(request)
            try {
                current.run(name, repeat)
            } finally {
                watchdog.interrupt()
                running.set(null)
                onDone()
            }
        }
    }
}

/** Debug: якщо сценарій не завершується, раз на 90 с пише стеки всіх потоків процесу застосунку. */
private fun startWatchdog(request: String): Thread = Thread({
    try {
        while (true) {
            Thread.sleep(90_000)
            Log.w("EnginePrototype", "WATCHDOG $request still running; thread dump follows")
            for ((thread, stack) in Thread.getAllStackTraces()) {
                Log.w("EnginePrototype", "WATCHDOG thread ${thread.name}: " + stack.take(12).joinToString(" <- "))
            }
        }
    } catch (_: InterruptedException) {
    }
}, "prototype-watchdog").also { it.isDaemon = true; it.start() }
