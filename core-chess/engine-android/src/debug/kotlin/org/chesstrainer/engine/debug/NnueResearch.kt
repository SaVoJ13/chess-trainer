// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine.debug

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.chesstrainer.core.engine.DifficultyGroup
import org.chesstrainer.core.engine.DifficultyId
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.engine.Strength
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.engine.NativeEngine
import org.chesstrainer.engine.UciEngineService
import org.chesstrainer.engine.UciTransport

/**
 * Дослідження CONTRACTS 8, п. 15: чи можна обійтися без копії мережі NNUE у `filesDir`.
 * Експеримент: Stockfish читає мережу послідовно (`std::ifstream`, лише `read` і `peek` в кінці), тому
 * замість файлу йому можна дати іменований канал (FIFO), у який окремий потік стрімить asset з APK.
 * Лише debug; політику розповсюдження мережі не змінює.
 */
internal class FifoJniTransport(private val context: Context, private val bytesStreamed: AtomicLong) : UciTransport {
    private var reader: Thread? = null
    private var closing = AtomicBoolean(false)

    override fun open(listener: UciTransport.Listener): String {
        NativeEngine.load(false)
        val fifo = File(context.cacheDir, "nnue.fifo")
        fifo.delete()
        Os.mkfifo(fifo.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        Thread({
            // open() для запису блокується, доки Stockfish не відкриє канал на читання.
            FileOutputStream(fifo).use { out ->
                context.assets.open("nnue/nn-1a298aa575a0.nnue").use { input ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        bytesStreamed.addAndGet(n.toLong())
                    }
                }
            }
        }, "nnue-feeder").also { it.isDaemon = true; it.start() }
        closing = AtomicBoolean(false)
        val expected = closing
        check(NativeEngine.nativeStart() >= 0) { "nativeStart failed" }
        reader = Thread({
            while (true) {
                val line = NativeEngine.nativeReadLine(1000) ?: continue
                if (line == NativeEngine.EOF) {
                    listener.onClosed(!expected.get(), "closed")
                    return@Thread
                }
                listener.onLine(line, System.nanoTime())
            }
        }, "sf-reader").also { it.isDaemon = true; it.start() }
        return fifo.absolutePath
    }

    override fun send(line: String) = NativeEngine.nativeSend(line)

    override fun close() {
        closing.set(true)
        NativeEngine.nativeStop()
        reader?.join(2000)
    }

    override fun emittedInfoLines(): Long = 0

    override fun crash() {}
}

class NnueResearch(private val context: Context) {
    suspend fun fifo() {
        val copy = File(context.filesDir, "nnue")
        val copyBefore = copy.exists()
        val streamed = AtomicLong()
        val engine = UciEngineService(FifoJniTransport(context, streamed), ChesslibRules())
        val t = SystemClock.elapsedRealtimeNanos()
        val started = engine.start(EngineConfig(1, 16))
        val startMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
        val level = DifficultyLevel(DifficultyId(1), Strength.Full, SearchLimit(depth = 8), 0, null, DifficultyGroup.MAX)
        val move = if (started is ChessResult.Ok) {
            engine.bestMove(MoveRequest(Fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"), emptyList(), level))
        } else null
        val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        Log.i(
            "EnginePrototype",
            "RESULT scenario=nnuefifo start_ok=${started is ChessResult.Ok} start_ms=${"%.0f".format(startMs)} " +
                "move_ok=${move is ChessResult.Ok} bytes_streamed=${streamed.get()} filesdir_copy_before=$copyBefore " +
                "filesdir_copy_after=${copy.exists()} own_pss_kb=$pss",
        )
        engine.stop()
    }
}
