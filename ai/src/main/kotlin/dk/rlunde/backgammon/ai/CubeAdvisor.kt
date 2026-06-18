package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/** Public facade: calibrated single-win probability for [perspective] from the static eval (6a §4.3). */
object CubeAdvisor {
    fun winProb(state: BoardState, perspective: Player): Double =
        WinProbability.fromEquity(Evaluator.evaluate(state, perspective, Weights.FULL), GamePhases.of(state))
}
