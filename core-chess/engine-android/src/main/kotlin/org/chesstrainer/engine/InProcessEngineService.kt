// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import android.content.Context
import org.chesstrainer.core.rules.ChessRules

/**
 * Stockfish через JNI в процесі застосунку (CONTRACTS 3.4, режим in-process).
 * Збій нативного коду на рівні процесу (SIGSEGV, `abort()`) знищує і застосунок.
 *
 * @param forceBaseline налагодження: вантажити базову `armv8` навіть на процесорі з dotprod.
 */
class InProcessEngineService(
    context: Context,
    rules: ChessRules,
    forceBaseline: Boolean = false,
) : UciEngineService(JniTransport(context) { forceBaseline }, rules)
