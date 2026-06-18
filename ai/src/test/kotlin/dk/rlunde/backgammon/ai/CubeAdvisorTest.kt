package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubeAdvisorTest {
    @Test fun `even start is one half`() {
        assertEquals(0.5, CubeAdvisor.winProb(startingPosition(), Player.WHITE), 1e-6)
    }
    @Test fun `perspectives sum to one`() {
        val s = startingPosition()
        assertEquals(1.0, CubeAdvisor.winProb(s, Player.WHITE) + CubeAdvisor.winProb(s, Player.BLACK), 1e-6)
    }
    @Test fun `leader is well above half`() {
        // BLACK has borne off 13; WHITE none → BLACK win-prob ~1.
        val p = IntArray(26).also { it[24] = -2; it[6] = 8; it[8] = 7 }   // BLACK 2 on 24; WHITE 15 on 6/8
        val s = BoardState(p, mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 0, Player.BLACK to 13), Player.BLACK)
        assertTrue(CubeAdvisor.winProb(s, Player.BLACK) > 0.9, "leader win-prob")
    }
}
