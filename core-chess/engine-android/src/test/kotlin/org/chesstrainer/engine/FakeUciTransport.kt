// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Сценарний UCI-канал для тестів на JVM (без Android і без справжнього Stockfish).
 * Поведінку задають прапорці; їх можна змінювати між `open` (наприклад, перший запуск мовчить, другий ні).
 * Усі відповіді йдуть синхронно з `send`, окрім `bestmove` з [bestmoveDelayMs] (окремий потік).
 */
internal class FakeUciTransport : UciTransport {
    /** Не відповідати на `uci` (немає `uciok`). */
    @Volatile var silentOnUci = false

    /** Мовчати на все після відкриття, зокрема на `isready` (рушій завис). */
    @Volatile var mute = false

    /** Не відповідати на `go` (пошук ніколи не завершується), але `isready` і `stop` обробляти. */
    @Volatile var ignoreGo = false

    /** Не відповідати на `stop` (рушій не підтверджує скасування). */
    @Volatile var ignoreStop = false

    /** Затримка `bestmove` після `go`; `null` = відповідати одразу. */
    @Volatile var bestmoveDelayMs: Long? = null

    /** `open` кидає виняток. */
    @Volatile var failOpen = false

    val sent = CopyOnWriteArrayList<String>()
    @Volatile var opens = 0
    @Volatile var closes = 0

    @Volatile private var listener: UciTransport.Listener? = null
    @Volatile private var searching = false

    override fun open(listener: UciTransport.Listener): String {
        if (failOpen) throw IllegalStateException("fake open failure")
        opens++
        searching = false
        this.listener = listener
        return "fake.nnue"
    }

    override fun send(line: String) {
        sent += line
        val out = listener ?: return
        if (mute) return
        fun reply(text: String) = out.onLine(text, System.nanoTime())
        when {
            line == "uci" -> if (!silentOnUci) {
                reply("id name FakeFish 1")
                reply("option name Hash type spin default 16 min 1 max 4096")
                reply("option name Threads type spin default 1 min 1 max 8")
                reply("option name MultiPV type spin default 1 min 1 max 256")
                reply("option name UCI_Elo type spin default 1320 min 1320 max 3190")
                reply("option name Skill Level type spin default 20 min 0 max 20")
                reply("uciok")
            }
            line == "isready" -> reply("readyok")
            line.startsWith("go") -> {
                searching = true
                if (!ignoreGo) {
                    val delay = bestmoveDelayMs
                    if (delay == null) finishSearch(out) else Thread {
                        Thread.sleep(delay)
                        finishSearch(out)
                    }.also { it.isDaemon = true }.start()
                }
            }
            line == "stop" -> if (!ignoreStop && searching) finishSearch(out)
        }
    }

    private fun finishSearch(out: UciTransport.Listener) {
        if (!searching) return
        searching = false
        out.onLine("bestmove e2e4", System.nanoTime())
    }

    override fun close() {
        closes++
        listener = null
    }

    override fun emittedInfoLines(): Long = 0

    override fun crash() {
        listener?.onClosed(crashed = true, reason = "fake crash")
    }
}
