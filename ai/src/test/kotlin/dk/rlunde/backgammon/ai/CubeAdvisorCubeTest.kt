package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals

class CubeAdvisorCubeTest {
    /** WHITE far ahead in a pure race: a clear double for WHITE; a hopeless race (easy drop) for BLACK. */
    private fun whiteWayAhead(): BoardState {
        val pts = IntArray(26)
        pts[1] = 2; pts[2] = 2
        pts[24] = -2; pts[23] = -2; pts[22] = -2
        return BoardState(
            points = pts,
            bar = mapOf(Player.WHITE to 0, Player.BLACK to 0),
            off = mapOf(Player.WHITE to 11, Player.BLACK to 9),
            toMove = Player.WHITE,
        )
    }

    @Test fun `equities winProb equals the winProb facade (single source)`() {
        val s = whiteWayAhead()
        val eqP = CubeAdvisor.equities(s, Player.WHITE, CubeOwner.CENTERED).winProb
        assertEquals(eqP, CubeAdvisor.winProb(s, Player.WHITE), 1e-12)
    }

    @Test fun `responseVerdict is invariant to whose turn it is`() {
        val s = whiteWayAhead()
        val asWhiteToMove = CubeAdvisor.responseVerdict(s, Player.BLACK)
        val flipped = s.copy(toMove = Player.BLACK)
        val asBlackToMove = CubeAdvisor.responseVerdict(flipped, Player.BLACK)
        assertEquals(asWhiteToMove, asBlackToMove)
    }

    @Test fun `the trailing side facing a double from a hopeless race drops`() {
        val s = whiteWayAhead()
        assertEquals(ResponseVerdict.DROP, CubeAdvisor.responseVerdict(s, Player.BLACK))
    }
}
