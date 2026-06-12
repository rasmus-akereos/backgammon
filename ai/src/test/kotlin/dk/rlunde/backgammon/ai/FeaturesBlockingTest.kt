package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeaturesBlockingTest {
    private fun board(points: IntArray, toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 0, Player.BLACK to 0), toMove)

    // BLACK shooter at 10 (moves low->high), WHITE blot at 17 -> distance 7 (combination only).
    // Intermediate points for a 7 are 11..16. WHITE makes 13,14,15,16 (>=2) to block most combos.
    @Test fun `blocking intermediate points lowers the combination-shot blot penalty`() {
        val open = IntArray(26).also { it[17] = 1; it[10] = -1 }                 // lone WHITE blot, lone BLACK shooter
        val blocked = open.copyOf().also { it[13] = 2; it[14] = 2; it[15] = 2; it[16] = 2 } // WHITE made points
        val openPen = Features.blotPenalty(board(open), Player.WHITE, costWeighted = true)
        val blockedPen = Features.blotPenalty(board(blocked), Player.WHITE, costWeighted = true)
        assertTrue(blockedPen < openPen, "blocked combination path must reduce the hit penalty ($blockedPen vs $openPen)")
    }

    // A pure DIRECT shot (distance <=6) is never blocked by intermediates (there are none).
    @Test fun `direct shot penalty is unaffected by made points behind it`() {
        val open = IntArray(26).also { it[17] = 1; it[14] = -1 }                  // distance 3, direct
        val withPoints = open.copyOf().also { it[20] = 2 }                        // irrelevant made point
        val a = Features.blotPenalty(board(open), Player.WHITE, costWeighted = true)
        val b = Features.blotPenalty(board(withPoints), Player.WHITE, costWeighted = true)
        assertEquals(a, b, 1e-12)
    }

    // Unblocked combination distance reproduces the canonical open-path shot count (no regression
    // in the common no-block case). Distance 7 from a lone shooter = 6/36.
    @Test fun `unblocked combination reproduces the open-path probability`() {
        val s = board(IntArray(26).also { it[17] = 1; it[10] = -1 })
        assertEquals(6.0 / 36.0, Features.blockedHitProb(s, Player.WHITE, Player.BLACK, shooter = 10, target = 17), 1e-12)
    }
}
