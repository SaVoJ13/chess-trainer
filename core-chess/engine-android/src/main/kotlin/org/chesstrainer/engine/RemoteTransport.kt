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

/** Binder до [EngineProcessService] у процесі `:engine`. */
internal class RemoteTransport(
    context: Context,
    forceBaseline: Boolean,
) : UciTransport {
    private val appContext = context.applicationContext
    private val forceBaseline =
        forceBaseline && (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val lock = Any()
    private var connection: ServiceConnection? = null
    private var process: IEngineProcess? = null

    /** `true` після [close]: далі розрив зв'язку не вважається збоєм. */
    @Volatile private var closedByUs = AtomicBoolean(false)

    override fun open(listener: UciTransport.Listener): String {
        val connected = CountDownLatch(1)
        val closing = AtomicBoolean(false)
        val notified = AtomicBoolean(false)
        closedByUs = closing

        // Про закриття повідомляємо один раз: смерть процесу приходить кількома шляхами.
        fun notifyClosed(crashed: Boolean, reason: String) {
            if (notified.compareAndSet(false, true)) listener.onClosed(crashed, reason)
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val remote = IEngineProcess.Stub.asInterface(binder)
                runCatching {
                    binder.linkToDeath({ notifyClosed(!closing.get(), "engine process died") }, 0)
                }
                synchronized(lock) { process = remote }
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                notifyClosed(!closing.get(), "engine service disconnected")
            }

            override fun onBindingDied(name: ComponentName) {
                notifyClosed(!closing.get(), "engine binding died")
            }
        }
        val intent = Intent(appContext, EngineProcessService::class.java)
        check(appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) { "bindService failed" }
        synchronized(lock) { connection = conn }
        check(connected.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)) { "engine process did not connect" }

        val callback = object : IEngineCallback.Stub() {
            override fun onLine(line: String) = listener.onLine(line)

            override fun onClosed(crashed: Boolean, reason: String) = notifyClosed(crashed, reason)
        }
        val remote = synchronized(lock) { process }!!
        return remote.open(callback, forceBaseline)
    }

    override fun send(line: String) {
        val remote = synchronized(lock) { process } ?: return
        runCatching { remote.send(line) }
    }

    override fun close() {
        closedByUs.set(true)
        val remote: IEngineProcess?
        val conn: ServiceConnection?
        synchronized(lock) {
            remote = process
            conn = connection
            process = null
            connection = null
        }
        runCatching { remote?.close() }
        if (conn != null) runCatching { appContext.unbindService(conn) }
    }

    override fun crash() {
        val remote = synchronized(lock) { process } ?: return
        runCatching { remote.crash() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 10L
    }
}
