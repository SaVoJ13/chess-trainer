// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/** JUnit 4 вимагає `void`, тому тіло корутини завжди повертає Unit. */
fun runTest(block: suspend CoroutineScope.() -> Unit) {
    runBlocking { block() }
}

inline fun <reified T> assertIs(value: Any?) {
    org.junit.Assert.assertTrue("Expected ${T::class.simpleName}, was $value", value is T)
}

inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return
        throw e
    }
    org.junit.Assert.fail("Expected ${T::class.simpleName}")
}
