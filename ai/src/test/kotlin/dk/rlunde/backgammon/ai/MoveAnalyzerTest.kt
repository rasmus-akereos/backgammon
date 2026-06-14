package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.SubMove
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoveAnalyzerTest {
    private val s = startingPosition()
    private val dice = Dice(3, 1)
    private val legal = MoveGenerator.legalMoves(s, dice)

    private fun ranking() =
        Expectimax.rankMoves(s, dice, legal, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE))
    private fun rankedBest() = ranking().first().move
    private fun rankedWorst() = ranking().last().move

    @Test fun `best move is BEST rank 1 zero loss`() {
        val a = MoveAnalyzer.analyze(s, dice, rankedBest(), legal)
        assertEquals(Band.BEST, a.band)
        assertEquals(1, a.playedRank)
        assertEquals(0.0, a.evalLoss, 1e-9)
        assertEquals(legal.size, a.totalCandidates)
        assertTrue(!a.forced)
    }

    @Test fun `worst move loses equity and is worse than BEST`() {
        val a = MoveAnalyzer.analyze(s, dice, rankedWorst(), legal)
        assertTrue(a.evalLoss > 0.0)
        assertTrue(a.playedRank > 1)
        assertTrue(a.band != Band.BEST)
        assertTrue(a.featureDeltas.isNotEmpty())
        val mags = a.featureDeltas.map { kotlin.math.abs(it.delta) }
        assertEquals(mags.sortedDescending(), mags)
    }

    @Test fun `played move matched by resulting board despite sub-move order`() {
        val best = rankedBest()
        val reversed = Move(best.subMoves.reversed())
        val a = MoveAnalyzer.analyze(s, dice, reversed, legal)
        assertEquals(1, a.playedRank)
    }

    @Test fun `move not in legal fails fast`() {
        val bogus = Move(listOf(SubMove(from = 24, to = 23, die = 1, isHit = false)))
        assertFailsWith<IllegalArgumentException> { MoveAnalyzer.analyze(s, dice, bogus, legal) }
    }

    @Test fun `single legal play is forced`() {
        val one = listOf(rankedBest())
        val a = MoveAnalyzer.analyze(s, dice, one.first(), one)
        assertTrue(a.forced)
        assertEquals(Band.BEST, a.band)
        assertEquals(1, a.totalCandidates)
    }
}
