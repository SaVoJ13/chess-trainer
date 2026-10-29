// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.rules

import org.chesstrainer.core.rules.PerftSupport.KIWIPETE
import org.chesstrainer.core.rules.PerftSupport.POSITION_3
import org.chesstrainer.core.rules.PerftSupport.POSITION_4
import org.chesstrainer.core.rules.PerftSupport.POSITION_5
import org.chesstrainer.core.rules.PerftSupport.POSITION_6
import org.chesstrainer.core.rules.PerftSupport.START
import org.chesstrainer.core.rules.PerftSupport.check
import org.chesstrainer.core.testing.PerftDeep
import org.junit.Test
import org.junit.experimental.categories.Category

/** Глибокий perft (Category `PerftDeep`), лише задача `perftDeep`. Еталони: chessprogramming.org/Perft_Results. */
@Category(PerftDeep::class)
class PerftDeepTest {
    @Test fun startPosition() = check(START, 6 to 119_060_324L)

    @Test fun kiwipete() = check(KIWIPETE, 5 to 193_690_690L)

    @Test fun position3() = check(POSITION_3, 7 to 178_633_661L)

    @Test fun position4() = check(POSITION_4, 6 to 706_045_033L)

    @Test fun position5() = check(POSITION_5, 5 to 89_941_194L)

    @Test fun position6() = check(POSITION_6, 5 to 164_075_551L)
}
