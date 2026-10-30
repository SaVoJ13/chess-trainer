// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine;

/** Зворотний виклик від процесу `:engine`. `oneway` зберігає порядок рядків. */
oneway interface IEngineCallback {
    /** [readAtNs]: `System.nanoTime()` у процесі `:engine` при прочитанні рядка (спільний монотонний годинник; G3). */
    void onLine(String line, long readAtNs);
    void onClosed(boolean crashed, String reason);
}
