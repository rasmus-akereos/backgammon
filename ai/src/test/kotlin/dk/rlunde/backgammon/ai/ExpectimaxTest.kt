package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpectimaxTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                      toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to 0, Player.BLACK to 0), toMove)

    @Test fun `distinct rolls are 21 entries weighted to one`() {
        assertEquals(21, Expectimax.DISTINCT_ROLLS.size)
        val doubles = Expectimax.DISTINCT_ROLLS.filter { it.first.isDouble }
        val nonDoubles = Expectimax.DISTINCT_ROLLS.filter { !it.first.isDouble }
        assertEquals(6, doubles.size)
        assertEquals(15, nonDoubles.size)
        doubles.forEach { assertEquals(1.0 / 36.0, it.second, 1e-12) }
        nonDoubles.forEach { assertEquals(2.0 / 36.0, it.second, 1e-12) }
        assertEquals(1.0, Expectimax.DISTINCT_ROLLS.sumOf { it.second }, 1e-9)
    }

    @Test fun `depth-0 picks the move that maximises post-move eval`() {
        val p = IntArray(26); p[13] = 2; p[24] = 2; p[1] = -2
        val s = board(p); val dice = Dice(3, 5)
        val legal = MoveGenerator.legalMoves(s, dice)
        assertTrue(legal.isNotEmpty())
        val best = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = Int.MAX_VALUE)
        val bestScore = Evaluator.evaluate(MoveGenerator.apply(s, best), s.toMove, Weights.FULL)
        for (m in legal) {
            val score = Evaluator.evaluate(MoveGenerator.apply(s, m), s.toMove, Weights.FULL)
            assertTrue(score <= bestScore + 1e-9, "found a better move than bestMove returned")
        }
    }

    @Test fun `depth-0 prefers hitting an opponent blot`() {
        val p = IntArray(26); p[8] = 1; p[5] = -1; p[13] = 2
        val s = board(p); val dice = Dice(3, 4)
        val legal = MoveGenerator.legalMoves(s, dice)
        val best = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = Int.MAX_VALUE)
        assertTrue(best.subMoves.any { it.isHit }, "AI should choose the hitting turn")
    }

    @Test fun `tie-break is deterministic`() {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val s = board(p); val dice = Dice(2, 2)
        val legal = MoveGenerator.legalMoves(s, dice)
        val a = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = 8)
        val b = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = 8)
        assertEquals(a, b)
    }
}
