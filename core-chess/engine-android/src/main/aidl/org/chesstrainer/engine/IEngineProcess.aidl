// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine;

import org.chesstrainer.engine.IEngineCallback;

interface IEngineProcess {
    /** Запускає рушій у процесі `:engine`. Повертає шлях файлу мережі; кидає IllegalStateException. */
    String open(IEngineCallback callback, boolean forceBaseline);
    oneway void send(String line);
    void close();
    /** Налагодження: `abort()` у нативному коді процесу `:engine` (лише debug-збірка). */
    oneway void crash();
}
