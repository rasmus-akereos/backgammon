package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerTest {
    @Test fun `opponent flips`() {
        assertEquals(Player.BLACK, Player.WHITE.opponent)
        assertEquals(Player.WHITE, Player.BLACK.opponent)
    }

    @Test fun `sign is +1 for white and -1 for black`() {
        assertEquals(1, Player.WHITE.sign)
        assertEquals(-1, Player.BLACK.sign)
    }
}
