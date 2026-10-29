// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.model

/** Завжди з погляду білих (перетворення з UCI робить ядро). */
sealed interface Evaluation {
    data class Centipawns(val cp: Int) : Evaluation

    /**
     * >0: білі матують за N ходів; <0: чорні. Ніколи 0: позицію з матом на дошці рушій не оцінює,
     * її описує `GameStatus`.
     */
    data class Mate(val moves: Int) : Evaluation {
        init {
            require(moves != 0) { "Mate(0) is not allowed" }
        }
    }
}
