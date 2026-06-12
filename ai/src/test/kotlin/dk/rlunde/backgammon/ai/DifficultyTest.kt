package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DifficultyTest {
    @Test fun `four tiers with expected depths`() {
        assertEquals(0, Difficulty.BEGINNER.searchDepth)
        assertEquals(0, Difficulty.INTERMEDIATE.searchDepth)
        assertEquals(1, Difficulty.ADVANCED.searchDepth)
        assertEquals(2, Difficulty.EXPERT.searchDepth)
    }

    @Test fun `intermediate is unchanged greedy 1-ply`() {
        val ai = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(1))
        val s = startingPosition(); val dice = Dice(3, 1)
        val legal = MoveGenerator.legalMoves(s, dice)
        val expected = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = Int.MAX_VALUE)
        repeat(10) { assertEquals(expected, ai.chooseMove(s, dice, legal)) }
    }

    @Test fun `advanced returns a legal move on the opening`() {
        val ai = HeuristicAiPlayer(Difficulty.ADVANCED)
        val s = startingPosition(); val dice = Dice(3, 1)
        val legal = MoveGenerator.legalMoves(s, dice)
        assertTrue(ai.chooseMove(s, dice, legal) in legal)
    }
}
