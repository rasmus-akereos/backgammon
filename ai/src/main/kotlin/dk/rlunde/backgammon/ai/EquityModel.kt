package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/**
 * Assembles the full outcome distribution for [perspective] from the static eval score: per-phase
 * win probability (§4.3) split by the gammon/backgammon conditional rates of each prospective loser
 * (§4.4–§4.5). Allocation-light; safe to call per move on the UI path.
 */
internal object EquityModel {
    fun distribution(state: BoardState, perspective: Player, weights: Weights): OutcomeDistribution {
        val eval = Evaluator.evaluate(state, perspective, weights)
        val phase = GamePhases.of(state)
        val winProb = WinProbability.fromEquity(eval, phase)
        val loseProb = 1.0 - winProb

        // If WE win, the opponent loses; if WE lose, we lose.
        val winRates = GammonModel.loseRates(gammonFeaturesOf(state, loser = perspective.opponent))
        val loseRates = GammonModel.loseRates(gammonFeaturesOf(state, loser = perspective))

        return OutcomeDistribution(
            winSingle = winProb * (1.0 - winRates.gammon),
            winGammon = winProb * (winRates.gammon - winRates.backgammon),
            winBackgammon = winProb * winRates.backgammon,
            loseSingle = loseProb * (1.0 - loseRates.gammon),
            loseGammon = loseProb * (loseRates.gammon - loseRates.backgammon),
            loseBackgammon = loseProb * loseRates.backgammon,
        )
    }
}
