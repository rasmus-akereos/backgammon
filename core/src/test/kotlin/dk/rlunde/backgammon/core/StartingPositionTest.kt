package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StartingPositionTest {
    @Test fun `canonical opening layout`() {
        val s = startingPosition()
        val expected = IntArray(26)
        expected[24] = 2; expected[13] = 5; expected[8] = 3; expected[6] = 5   // WHITE +
        expected[1] = -2; expected[12] = -5; expected[17] = -3; expected[19] = -5 // BLACK -
        assertEquals(expected.toList(), s.points.toList())
    }

    @Test fun `15 checkers per side, empty bar and off, white to move`() {
        val s = startingPosition()
        val white = (1..24).sumOf { s.count(Player.WHITE, it) }
        val black = (1..24).sumOf { s.count(Player.BLACK, it) }
        assertEquals(15, white)
        assertEquals(15, black)
        assertEquals(0, s.barCount(Player.WHITE))
        assertEquals(0, s.barCount(Player.BLACK))
        assertEquals(0, s.offCount(Player.WHITE))
        assertEquals(0, s.offCount(Player.BLACK))
        assertEquals(Player.WHITE, s.toMove)
    }
}
