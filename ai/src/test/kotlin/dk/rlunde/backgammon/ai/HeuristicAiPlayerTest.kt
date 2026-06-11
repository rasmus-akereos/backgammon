package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeuristicAiPlayerTest {
    @Test fun `intermediate never takes the weak branch`() {
        val ai = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(1))
        val s = startingPosition(); val dice = Dice(3, 1)
        val legal = MoveGenerator.legalMoves(s, dice)
        val best = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        repeat(20) { assertEquals(best, ai.chooseMove(s, dice, legal)) }
    }

    @Test fun `beginner weak branch only ever returns a top-K move`() {
        val s = startingPosition(); val dice = Dice(6, 5)
        val legal = MoveGenerator.legalMoves(s, dice)
        val topK = legal.sortedByDescending {
            Evaluator.evaluate(MoveGenerator.apply(s, it), s.toMove, Weights.SIMPLIFIED)
        }.take(3).toSet()
        for (seed in 0L until 50L) {
            val pick = HeuristicAiPlayer(Difficulty.BEGINNER, Random(seed)).chooseMove(s, dice, legal)
            assertTrue(pick in topK, "Beginner picked outside top-3 (seed $seed)")
        }
    }

    @Test fun `intermediate beats a uniform-random player by a wide margin`() {
        val seeds = (1L..40L).toList()
        var aiWins = 0
        for (seed in seeds) { if (playGame(seed)) aiWins++ }
        assertTrue(aiWins >= 32, "Intermediate won only $aiWins/${seeds.size} vs random")
    }

    private fun playGame(seed: Long): Boolean {
        val rng = Random(seed)
        val roller = SeededDiceRoller(seed)
        var state = startingPosition()
        val ai = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(seed xor 0x5DEECE66DL))
        var guard = 0
        while (!Scoring.isGameOver(state) && guard++ < 1000) {
            val dice = roller.roll()
            val legal = MoveGenerator.legalMoves(state, dice)
            state = if (legal.isEmpty()) {
                MoveGenerator.pass(state)
            } else {
                val move = if (state.toMove == Player.WHITE) ai.chooseMove(state, dice, legal)
                           else legal[rng.nextInt(legal.size)]
                MoveGenerator.apply(state, move)
            }
        }
        return Scoring.winnerAndValue(state)?.first == Player.WHITE
    }
}
