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
import org.chesstrainer.core.testing.Perft
import org.junit.Test
import org.junit.experimental.categories.Category

/** Швидкий perft (Category `Perft`). Еталони: chessprogramming.org/Perft_Results. */
@Category(Perft::class)
class PerftTest {
    @Test fun startPosition() = check(START, 1 to 20L, 2 to 400L, 3 to 8_902L, 4 to 197_281L, 5 to 4_865_609L)

    @Test fun kiwipete() = check(KIWIPETE, 1 to 48L, 2 to 2_039L, 3 to 97_862L, 4 to 4_085_603L)

    @Test fun position3() = check(POSITION_3, 1 to 14L, 2 to 191L, 3 to 2_812L, 4 to 43_238L, 5 to 674_624L, 6 to 11_030_083L)

    @Test fun position4() = check(POSITION_4, 1 to 6L, 2 to 264L, 3 to 9_467L, 4 to 422_333L, 5 to 15_833_292L)

    @Test fun position5() = check(POSITION_5, 1 to 44L, 2 to 1_486L, 3 to 62_379L, 4 to 2_103_487L)

    @Test fun position6() = check(POSITION_6, 1 to 46L, 2 to 2_079L, 3 to 89_890L, 4 to 3_894_594L)
}
