// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

/** Канал UCI до нативного рушія: JNI у процесі застосунку або Binder до процесу `:engine`. */
internal interface UciTransport {
    interface Listener {
        fun onLine(line: String, readAtNs: Long)

        /** [crashed] = `true`, якщо рушій завершився не через [close]. */
        fun onClosed(crashed: Boolean, reason: String)
    }

    /** Запускає рушій і готує файл мережі. Блокує. Повертає шлях файлу мережі. */
    fun open(listener: Listener): String

    fun send(line: String)

    /** Зупиняє рушій (UCI `quit`) і чекає завершення. Блокує. */
    fun close()

    /** Налагодження (G3): скільки рядків `info … pv` прочитано з рушія з початку відкриття (на боці рушія). */
    fun emittedInfoLines(): Long

    /** Налагодження: аварійно завершує нативний код (`abort()`, лише debug). */
    fun crash()
}
