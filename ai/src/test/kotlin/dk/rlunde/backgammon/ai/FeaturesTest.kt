package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeaturesTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                      whiteOff: Int = 0, blackOff: Int = 0, toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff), toMove)

    @Test fun `pip differential is positive when perspective is ahead`() {
        val p = IntArray(26); p[1] = 2; p[24] = -2 // WHITE pip 2, BLACK pip 2 -> even
        assertEquals(0, Features.pipDifferential(board(p), Player.WHITE))
        val p2 = IntArray(26); p2[1] = 2; p2[2] = -2 // WHITE pip 2, BLACK pip 23
        assertTrue(Features.pipDifferential(board(p2), Player.WHITE) > 0)
    }

    @Test fun `off differential favours the side with more borne off`() {
        val p = IntArray(26)
        assertTrue(Features.offDifferential(board(p, whiteOff = 3), Player.WHITE) > 0)
    }

    @Test fun `bar term penalises own checkers on the bar`() {
        val p = IntArray(26)
        assertTrue(Features.barTerm(board(p, whiteBar = 1), Player.WHITE) < 0)
        assertTrue(Features.barTerm(board(p, blackBar = 1), Player.WHITE) > 0)
    }

    @Test fun `home points made counts made points in own home`() {
        val p = IntArray(26); p[3] = 2; p[5] = 2 // WHITE made 3-pt and 5-pt
        assertEquals(2, Features.homePointsMade(board(p), Player.WHITE))
        assertEquals(0, Features.homePointsMade(board(p), Player.BLACK))
    }

    @Test fun `prime length finds the longest consecutive run`() {
        val p = IntArray(26); p[4] = 2; p[5] = 2; p[6] = 2; p[8] = 2 // run 4-5-6 = 3
        assertEquals(3, Features.primeLength(board(p), Player.WHITE))
    }

    @Test fun `back checkers counts perspective checkers deep in opponent home`() {
        val p = IntArray(26); p[23] = 2 // WHITE checkers at 23 (in BLACK home 19-24)
        assertEquals(2, Features.backCheckers(board(p), Player.WHITE))
    }

    @Test fun `blot exposure penalty grows with a nearer opposing shooter`() {
        val near = IntArray(26); near[12] = 1; near[6] = -1  // BLACK shooter at 6 => distance 6
        val far = IntArray(26); far[12] = 1; far[1] = -1      // BLACK shooter at 1 => distance 11
        val pen = Features.blotPenalty(board(near), Player.WHITE, costWeighted = true)
        val penFar = Features.blotPenalty(board(far), Player.WHITE, costWeighted = true)
        assertTrue(pen > penFar, "nearer shooter must penalise more ($pen vs $penFar)")
        assertTrue(pen > 0)
    }

    @Test fun `no contact is detected when armies have passed`() {
        val race = IntArray(26); race[3] = 2; race[22] = -2 // white low, black high, passed
        assertTrue(Features.noContact(board(race)))
        val contact = IntArray(26); contact[24] = 2; contact[1] = -2 // opening-like overlap
        assertTrue(!Features.noContact(board(contact)))
    }
}
