package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator
import kotlin.random.Random

/**
 * The Phase-2 AI. With probability [Difficulty.noise] it plays a *plausible* weak move (a uniform
 * pick among the top-3 moves by the simplified eval — human-fallible, not absurd); otherwise it
 * plays the 1-ply best. [rng] is injected for deterministic tests.
 */
class HeuristicAiPlayer(
    private val difficulty: Difficulty,
    private val rng: Random = Random.Default,
) : AiPlayer {

    override fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move {
        require(legal.isNotEmpty()) { "chooseMove called with no legal moves" }
        return if (difficulty.noise > 0.0 && rng.nextDouble() < difficulty.noise)
            sampleWeak(state, dice, legal)
        else
            MoveSearch.bestMove(state, dice, legal, difficulty.weights)
    }

    private fun sampleWeak(state: BoardState, dice: Dice, legal: List<Move>): Move {
        val topK = legal.sortedByDescending {
            Evaluator.evaluate(MoveGenerator.apply(state, it), state.toMove, Weights.SIMPLIFIED)
        }.take(3)
        return topK[rng.nextInt(topK.size)]
    }
}
