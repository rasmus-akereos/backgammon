package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MoveTest {
    @Test fun `move carries its ordered sub-moves and total pips`() {
        val m = Move(listOf(
            SubMove(from = 13, to = 10, die = 3, isHit = false),
            SubMove(from = 24, to = 19, die = 5, isHit = true),
        ))
        assertEquals(2, m.subMoves.size)
        assertEquals(8, m.pipsUsed)
        assertEquals(5, m.subMoves[1].die)
    }

    @Test fun `empty move uses zero pips`() {
        assertEquals(0, Move(emptyList()).pipsUsed)
    }
}
