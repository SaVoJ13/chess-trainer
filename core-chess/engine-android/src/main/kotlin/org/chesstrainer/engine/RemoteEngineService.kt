// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import android.content.Context
import org.chesstrainer.core.rules.ChessRules

/**
 * Stockfish в окремому процесі `:engine` (прив'язаний Service, не foreground), зв'язок через Binder.
 * Смерть процесу (`DeathRecipient`) дає `EngineState.Failed(EngineCrashed)`; повторний `start()` дозволено.
 *
 * @param forceBaseline налагодження: вантажити базову `armv8`; діє лише в debuggable-застосунку.
 */
class RemoteEngineService(
    context: Context,
    rules: ChessRules,
    forceBaseline: Boolean = false,
) : UciEngineService(RemoteTransport(context, forceBaseline), rules)
