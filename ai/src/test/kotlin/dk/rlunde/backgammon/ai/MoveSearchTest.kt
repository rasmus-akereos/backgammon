package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveSearchTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                      toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to 0, Player.BLACK to 0), toMove)

    @Test fun `picks the move that maximises post-move eval`() {
        val p = IntArray(26); p[13] = 2; p[24] = 2; p[1] = -2
        val s = board(p)
        val dice = Dice(3, 5)
        val legal = MoveGenerator.legalMoves(s, dice)
        assertTrue(legal.isNotEmpty())
        val best = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        val bestScore = Evaluator.evaluate(MoveGenerator.apply(s, best), s.toMove, Weights.FULL)
        for (m in legal) {
            val score = Evaluator.evaluate(MoveGenerator.apply(s, m), s.toMove, Weights.FULL)
            assertTrue(score <= bestScore + 1e-9, "found a better move than bestMove returned")
        }
    }

    @Test fun `prefers hitting an opponent blot over an equal non-hitting move`() {
        val p = IntArray(26); p[8] = 1 /*WHITE*/; p[5] = -1 /*BLACK blot, hit by a 3 from 8*/
        p[13] = 2
        val s = board(p)
        val dice = Dice(3, 4)
        val legal = MoveGenerator.legalMoves(s, dice)
        val best = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        assertTrue(best.subMoves.any { it.isHit }, "AI should choose the hitting turn")
    }

    @Test fun `tie-break is deterministic`() {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val s = board(p); val dice = Dice(2, 2)
        val legal = MoveGenerator.legalMoves(s, dice)
        val a = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        val b = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        assertEquals(a, b)
    }
}
