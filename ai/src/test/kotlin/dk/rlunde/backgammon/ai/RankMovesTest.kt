package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankMovesTest {
    private val s = startingPosition()
    private val dice = Dice(3, 1)
    private val legal = MoveGenerator.legalMoves(s, dice)

    private fun rank(depth: Int) =
        Expectimax.rankMoves(s, dice, legal, Weights.FULL, depth, NodeBudget(Int.MAX_VALUE))

    @Test fun `ranking contains every legal play exactly once`() {
        val r = rank(0)
        assertEquals(legal.size, r.size)
        assertEquals(legal.toSet(), r.map { it.move }.toSet())
    }

    @Test fun `ranking is sorted best-first and human-perspective`() {
        val r = rank(1)
        for (i in 1 until r.size) assertTrue(r[i - 1].score >= r[i].score, "not sorted at $i")
        assertTrue(r.first().score >= r.last().score)
    }

    @Test fun `top of ranking equals bestMove at equal depth with full root set`() {
        val depth = 0
        val top = rank(depth).first().move
        val best = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth, topK = Int.MAX_VALUE,
            budget = NodeBudget(Int.MAX_VALUE))
        assertEquals(best, top)
    }

    @Test fun `large move set is scored at uniform depth deterministically`() {
        val d = Dice(6, 6)
        val lg = MoveGenerator.legalMoves(s, d)
        val r1 = Expectimax.rankMoves(s, d, lg, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE))
        val r2 = Expectimax.rankMoves(s, d, lg, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE))
        assertEquals(r1.map { it.move to it.score }, r2.map { it.move to it.score })
    }
}
