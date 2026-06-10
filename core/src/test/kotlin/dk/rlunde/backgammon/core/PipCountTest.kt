package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PipCountTest {
    private fun board(points: IntArray, bar: Map<Player, Int> = mapOf(Player.WHITE to 0, Player.BLACK to 0)) =
        BoardState(points, bar, mapOf(Player.WHITE to 0, Player.BLACK to 0), Player.WHITE)

    @Test fun `starting position is 167 per side`() {
        val s = startingPosition()
        assertEquals(167, Scoring.pipCount(s, Player.WHITE))
        assertEquals(167, Scoring.pipCount(s, Player.BLACK))
    }

    @Test fun `white counts point index, black counts 25 minus index`() {
        val p = IntArray(26)
        p[6] = 1    // WHITE on 6  -> 6 pips
        p[19] = -1  // BLACK on 19 -> 25-19 = 6 pips
        val s = board(p)
        assertEquals(6, Scoring.pipCount(s, Player.WHITE))
        assertEquals(6, Scoring.pipCount(s, Player.BLACK))
    }

    @Test fun `bar checkers count as 25 each`() {
        val p = IntArray(26)
        p[6] = 1 // 6 pips on board
        val s = board(p, bar = mapOf(Player.WHITE to 2, Player.BLACK to 0))
        assertEquals(6 + 2 * 25, Scoring.pipCount(s, Player.WHITE))
    }
}
