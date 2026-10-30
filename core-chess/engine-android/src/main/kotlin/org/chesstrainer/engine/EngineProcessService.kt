// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Процес `:engine` (AndroidManifest): тримає JNI-рушій і передає UCI-рядки через Binder.
 * Прив'язаний Service без `startForeground`: живе, поки клієнт прив'язаний.
 */
class EngineProcessService : Service() {
    @Volatile private var baseline = false
    private val transport by lazy { JniTransport(this) { baseline } }
    private val sawFirstLine = AtomicBoolean(false)

    private val binder = object : IEngineProcess.Stub() {
        override fun open(callback: IEngineCallback, forceBaseline: Boolean): String {
            baseline = forceBaseline
            EngineTrace.mark("engine.open.enter")
            try {
                return transport.open(object : UciTransport.Listener {
                    override fun onLine(line: String, readAtNs: Long) {
                        if (sawFirstLine.compareAndSet(false, true)) EngineTrace.mark("engine.first-line")
                        runCatching { callback.onLine(line, readAtNs) }
                    }

                    override fun onClosed(crashed: Boolean, reason: String) {
                        EngineTrace.mark("engine.closed", "crashed=$crashed")
                        runCatching { callback.onClosed(crashed, reason) }
                    }
                }).also { EngineTrace.mark("engine.open.exit") }
            } catch (t: Throwable) {
                throw IllegalStateException("engine process cannot start: $t")
            }
        }

        override fun send(line: String) = transport.send(line)

        override fun close() = transport.close()

        override fun crash() = transport.crash()

        override fun pid(): Int = android.os.Process.myPid()

        override fun emittedInfoLines(): Long = transport.emittedInfoLines()
    }

    override fun onCreate() {
        super.onCreate()
        // Мітки процесу `:engine` дублюються у файл, щоб їх можна було прочитати, поки процес завис (G1).
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            EngineTrace.mirrorTo(File(cacheDir, "engine-trace.log"))
        }
        EngineTrace.mark("engine.process-start", "pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder {
        EngineTrace.mark("engine.onBind")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        EngineTrace.mark("engine.onUnbind")
        transport.close()
        return false
    }

    /**
     * Stockfish не вміє перезапускати рушій у тому самому процесі (див. `sf_bridge.cpp`), а закриття
     * сервісу має повернути всю пам'ять мережі. Тому процес `:engine` завершується разом із сервісом;
     * наступний `bindService` створює свіжий процес.
     */
    override fun onDestroy() {
        super.onDestroy()
        EngineTrace.mark("engine.onDestroy.kill")
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
