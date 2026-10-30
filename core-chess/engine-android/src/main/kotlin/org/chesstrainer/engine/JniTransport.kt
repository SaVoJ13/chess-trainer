// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** JNI у поточному процесі: читач вивід з нативної черги й віддає рядки слухачу. */
internal class JniTransport(
    context: Context,
    private val forceBaseline: () -> Boolean = { false },
) : UciTransport {
    private val appContext = context.applicationContext
    private var reader: Thread? = null
    private var closing = AtomicBoolean(false)
    private val infoLines = java.util.concurrent.atomic.AtomicLong()

    /** Трасування UCI: `adb shell setprop log.tag.SfUci DEBUG`. */
    private val trace get() = android.util.Log.isLoggable("SfUci", android.util.Log.DEBUG)

    override fun open(listener: UciTransport.Listener): String {
        NativeEngine.load(forceBaseline())
        val evalFile = NnueAsset.ensure(appContext)
        android.util.Log.i("EnginePrototype", "engine library ${NativeEngine.loadedLibrary} in pid ${android.os.Process.myPid()}")
        closing = AtomicBoolean(false)
        val expected = closing
        infoLines.set(0)
        val rc = NativeEngine.nativeStart()
        check(rc >= 0) { "nativeStart failed: $rc" }
        reader = Thread({
            while (true) {
                val line = NativeEngine.nativeReadLine(1000) ?: continue
                if (line == NativeEngine.EOF) {
                    val crashed = !expected.get()
                    listener.onClosed(crashed, if (crashed) "native engine exited unexpectedly" else "closed")
                    return@Thread
                }
                if (trace) android.util.Log.d("SfUci", "< $line")
                if (line.startsWith("info ") && line.contains(" pv ")) infoLines.incrementAndGet()
                listener.onLine(line, System.nanoTime())
            }
        }, "sf-reader").also { it.isDaemon = true; it.start() }
        return evalFile.absolutePath
    }

    override fun send(line: String) {
        if (trace) android.util.Log.d("SfUci", "> $line")
        NativeEngine.nativeSend(line)
    }

    override fun close() {
        closing.set(true)
        NativeEngine.nativeStop()
        reader?.join(2000)
        reader = null
    }

    override fun emittedInfoLines(): Long = infoLines.get()

    override fun crash() {
        NativeEngine.nativeCrash()
    }
}
