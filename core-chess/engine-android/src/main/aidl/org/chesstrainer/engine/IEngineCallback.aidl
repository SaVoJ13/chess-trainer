// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine;

/** Зворотний виклик від процесу `:engine`. `oneway` зберігає порядок рядків. */
oneway interface IEngineCallback {
    void onLine(String line);
    void onClosed(boolean crashed, String reason);
}
