package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderingAndBearOffTest {
    private fun board(points: IntArray, toMove: Player = Player.WHITE) = BoardState(
        points,
        mapOf(Player.WHITE to 0, Player.BLACK to 0),
        mapOf(Player.WHITE to 0, Player.BLACK to 0),
        toMove,
    )

    @Test fun `playable only in one die order is still found`() {
        val p = IntArray(26)
        p[9] = 1
        p[4] = -2 // blocks the die-5 move from 9 (9->4); die 5 cannot go first
        val moves = MoveGenerator.legalMoves(board(p), Dice(4, 5))
        assertTrue(moves.any { m -> m.subMoves.size == 2 && m.subMoves.last().to == 0 })
    }

    @Test fun `overflow bear-off counts die face value, not distance`() {
        val p = IntArray(26); p[2] = 1
        val moves = MoveGenerator.legalMoves(board(p), Dice(6, 6))
        val best = moves.single()
        assertEquals(1, best.subMoves.size)
        assertEquals(SubMove(from = 2, to = 0, die = 6, isHit = false), best.subMoves.single())
    }

    @Test fun `in-home move vs bear-off - max pips forces the fuller play`() {
        val p = IntArray(26); p[6] = 1; p[5] = 1
        val moves = MoveGenerator.legalMoves(board(p), Dice(1, 2))
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.subMoves.size == 2 })
    }
}
