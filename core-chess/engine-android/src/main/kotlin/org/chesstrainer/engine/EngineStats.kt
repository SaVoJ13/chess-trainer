// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * Лічильники потоку `analyse` (G3, CONTRACTS 3.4 v0.8): скільки рядків `info … pv` отримано від транспорту,
 * скільки з них стало `Update` і скільки віддано споживачеві, плюс затримка від прочитання рядка рушієм до
 * передачі події споживачеві. Лише лічильники й масив у пам'яті, на потоці `analyse` це десятки операцій за секунду.
 */
internal object EngineStats {
    private val received = AtomicLong()
    private val parsed = AtomicLong()
    private val skipped = AtomicLong()
    private val latenciesNs = ArrayList<Long>()

    fun received() { received.incrementAndGet() }

    fun parsed() { parsed.incrementAndGet() }

    /** Рядок із `pv`, який не став `Update` за контрактом (межі `lowerbound`/`upperbound`, `mate 0`). */
    fun skipped() { skipped.incrementAndGet() }

    fun delivered(latencyNs: Long) {
        synchronized(latenciesNs) { latenciesNs += latencyNs }
    }

    class Snapshot(val received: Long, val parsed: Long, val skipped: Long, val latenciesNs: List<Long>)

    fun snapshot() = Snapshot(received.get(), parsed.get(), skipped.get(), synchronized(latenciesNs) { latenciesNs.toList() })

    fun reset() {
        received.set(0)
        parsed.set(0)
        skipped.set(0)
        synchronized(latenciesNs) { latenciesNs.clear() }
    }
}
