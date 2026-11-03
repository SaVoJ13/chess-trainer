// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.contract

import kotlin.random.Random
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.FakeEngineService
import org.chesstrainer.core.engine.HandicapEngineService
import org.chesstrainer.core.rules.ChesslibRules

/** `FakeEngineService` проходить контрактні тести сам (CONTRACTS 7.3). */
class FakeEngineContractTest : EngineServiceContractTest() {
    override fun newHarness(): EngineHarness = object : EngineHarness {
        private val fake = FakeEngineService(ChesslibRules())
        override val engine: EngineService get() = fake
        override val isRaw = true
        override val canCrash = true
        override fun hold() = fake.holdSearches()
        override fun release() = fake.release()
        override fun crash() = fake.crash("contract test crash")
    }
}

/** `HandicapEngineService` поверх `FakeEngineService` (CONTRACTS 3.5: контрактні тести запускаються і для декоратора). */
class HandicapOverFakeContractTest : EngineServiceContractTest() {
    override fun newHarness(): EngineHarness = object : EngineHarness {
        private val rules = ChesslibRules()
        private val fake = FakeEngineService(rules)
        override val engine: EngineService = HandicapEngineService(fake, rules, Random(1))
        override val isRaw = false
        override val canCrash = true
        override fun hold() = fake.holdSearches()
        override fun release() = fake.release()
        override fun crash() = fake.crash("contract test crash")
    }
}
