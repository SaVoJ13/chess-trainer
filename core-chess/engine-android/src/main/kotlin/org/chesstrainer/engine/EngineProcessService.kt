// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Процес `:engine` (AndroidManifest): тримає JNI-рушій і передає UCI-рядки через Binder.
 * Прив'язаний Service без `startForeground`: живе, поки клієнт прив'язаний.
 */
class EngineProcessService : Service() {
    @Volatile private var baseline = false
    private val transport by lazy { JniTransport(this) { baseline } }

    private val binder = object : IEngineProcess.Stub() {
        override fun open(callback: IEngineCallback, forceBaseline: Boolean): String {
            baseline = forceBaseline
            try {
                return transport.open(object : UciTransport.Listener {
                    override fun onLine(line: String) {
                        runCatching { callback.onLine(line) }
                    }

                    override fun onClosed(crashed: Boolean, reason: String) {
                        runCatching { callback.onClosed(crashed, reason) }
                    }
                })
            } catch (t: Throwable) {
                throw IllegalStateException("engine process cannot start: $t")
            }
        }

        override fun send(line: String) = transport.send(line)

        override fun close() = transport.close()

        override fun crash() = transport.crash()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
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
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
