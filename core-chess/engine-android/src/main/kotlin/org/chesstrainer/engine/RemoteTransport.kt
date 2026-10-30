// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binder до [EngineProcessService] у процесі `:engine`.
 *
 * Кожне відкриття має власний [Link]: пізній `close()` старого з'єднання не зачіпає нове. Закриття
 * обмежене в часі й за потреби вбиває процес `:engine` (CONTRACTS 3.4, v0.8: ресурси звільнено).
 */
internal class RemoteTransport(
    context: Context,
    forceBaseline: Boolean,
) : UciTransport {
    private val appContext = context.applicationContext
    private val forceBaseline =
        forceBaseline && (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Одне відкриття: з'єднання, Binder, pid процесу `:engine` і сигнал його смерті. */
    private class Link(val closing: AtomicBoolean) {
        @Volatile var connection: ServiceConnection? = null
        @Volatile var process: IEngineProcess? = null
        @Volatile var pid = -1
        val dead = CountDownLatch(1)
    }

    private val lock = Any()
    private var link: Link? = null

    /** Останнє закрите з'єднання: наступний [open] чекає смерті його процесу (гіпотеза гонки G1). */
    private var previous: Link? = null

    override fun open(listener: UciTransport.Listener): String {
        val old = synchronized(lock) { previous }
        if (awaitOldProcessDeath && old != null && !old.dead.await(DEATH_WAIT_MS, TimeUnit.MILLISECONDS)) {
            EngineTrace.mark("open.old-process-still-alive", "pid=${old.pid}")
        }

        // Про закриття повідомляємо один раз: смерть процесу приходить кількома шляхами.
        val notified = AtomicBoolean(false)
        val notifyClosed = { crashed: Boolean, reason: String ->
            if (notified.compareAndSet(false, true)) listener.onClosed(crashed, reason)
        }

        // bindService під час завершення попереднього :engine може лишитися без onServiceConnected (G1):
        // зв'язок «втрачено», чекати марно. Тому коротке очікування й одна повторна спроба.
        var mine: Link? = null
        for (attempt in 1..BIND_ATTEMPTS) {
            mine = bindOnce(notifyClosed)
            if (mine != null) break
            EngineTrace.mark("bind.retry", "attempt=$attempt")
        }
        check(mine != null) { "engine process did not connect" }

        val callback = object : IEngineCallback.Stub() {
            override fun onLine(line: String, readAtNs: Long) = listener.onLine(line, readAtNs)

            override fun onClosed(crashed: Boolean, reason: String) = notifyClosed(crashed, reason)
        }
        val remote = mine.process!!
        mine.pid = remote.pid()
        EngineTrace.mark("open.rpc", "pid=${mine.pid}")
        return remote.open(callback, forceBaseline).also { EngineTrace.mark("open.rpc.done") }
    }

    /** Одна спроба `bindService`; `null`, якщо з'єднання не встановлено за [CONNECT_TIMEOUT_MS] (тоді відв'язано). */
    private fun bindOnce(notifyClosed: (Boolean, String) -> Unit): Link? {
        val mine = Link(AtomicBoolean(false))
        val connected = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                EngineTrace.mark("svc.connected")
                val remote = IEngineProcess.Stub.asInterface(binder)
                runCatching {
                    binder.linkToDeath({
                        EngineTrace.mark("binder.died", "pid=${mine.pid}")
                        mine.dead.countDown()
                        notifyClosed(!mine.closing.get(), "engine process died")
                    }, 0)
                }.onFailure { mine.dead.countDown() }
                mine.process = remote
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                EngineTrace.mark("svc.disconnected")
                notifyClosed(!mine.closing.get(), "engine service disconnected")
            }

            override fun onBindingDied(name: ComponentName) {
                EngineTrace.mark("binding.died")
                notifyClosed(!mine.closing.get(), "engine binding died")
            }
        }
        mine.connection = conn
        synchronized(lock) { link = mine }

        val intent = Intent(appContext, EngineProcessService::class.java)
        EngineTrace.mark("bind.call")
        check(appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) { "bindService failed" }
        if (connected.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return mine
        mine.closing.set(true)
        synchronized(lock) { if (link === mine) link = null }
        runCatching { appContext.unbindService(conn) }
        Thread.sleep(RETRY_PAUSE_MS)
        return null
    }

    override fun send(line: String) {
        val remote = synchronized(lock) { link }?.process ?: return
        runCatching { remote.send(line) }
    }

    override fun close() {
        val mine = synchronized(lock) {
            val current = link
            link = null
            if (current != null) previous = current
            current
        } ?: return
        EngineTrace.mark("close.begin", "pid=${mine.pid}")
        mine.closing.set(true)

        // Binder-виклик до зависшого процесу не повертається: виконуємо його окремо й чекаємо обмежений час.
        val rpc = Thread({ runCatching { mine.process?.close() } }, "engine-close-rpc").also { it.isDaemon = true; it.start() }
        rpc.join(CLOSE_RPC_MS)
        val rpcDone = !rpc.isAlive
        EngineTrace.mark(if (rpcDone) "close.rpc.done" else "close.rpc.hung")

        mine.connection?.let { conn ->
            EngineTrace.mark("unbind.call")
            runCatching { appContext.unbindService(conn) }
        }
        if (!awaitOldProcessDeath) {
            EngineTrace.mark("close.end", "no-wait (prototype behaviour)")
            return
        }
        if (!mine.dead.await(DEATH_WAIT_MS, TimeUnit.MILLISECONDS)) {
            // Сервіс не завершив процес сам (завис або не встиг): вбиваємо, щоб звільнити пам'ять і слот.
            EngineTrace.mark("close.force-kill", "pid=${mine.pid}")
            if (mine.pid > 0) android.os.Process.killProcess(mine.pid)
            mine.dead.await(DEATH_WAIT_MS, TimeUnit.MILLISECONDS)
        }
        EngineTrace.mark("close.end", "dead=${mine.dead.count == 0L}")
    }

    override fun emittedInfoLines(): Long = runCatching { synchronized(lock) { link }?.process?.emittedInfoLines() }.getOrNull() ?: -1

    override fun crash() {
        val remote = synchronized(lock) { link }?.process ?: return
        runCatching { remote.crash() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val BIND_ATTEMPTS = 2
        private const val RETRY_PAUSE_MS = 300L
        private const val CLOSE_RPC_MS = 3_000L
        private const val DEATH_WAIT_MS = 3_000L

        /**
         * Налагодження G1: `false` вимикає очікування смерті старого `:engine` перед новим `bindService`
         * (поведінка прототипу), щоб перевірити гіпотезу гонки. У продакшні завжди `true`.
         */
        @Volatile var awaitOldProcessDeath = true
    }
}
