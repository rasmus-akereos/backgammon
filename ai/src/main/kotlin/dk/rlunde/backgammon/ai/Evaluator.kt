package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/**
 * Weighted linear position evaluator; positive = good for [perspective]. Callers evaluate the
 * POST-move state (toMove already flipped) with [perspective] = the side that just moved (spec §4.4).
 * In a no-contact race the eval collapses to ≈ pip differential. See spec §4.2.
 */
internal object Evaluator {

    fun evaluate(state: BoardState, perspective: Player, weights: Weights): Double {
        val race = weights.pip * Features.pipDifferential(state, perspective) +
                   weights.off * Features.offDifferential(state, perspective)
        if (Features.noContact(state)) return race

        val o = perspective.opponent
        val costWeighted = weights != Weights.SIMPLIFIED
        return race +
            weights.bar * Features.barTerm(state, perspective) +
            weights.backChecker * (Features.backCheckers(state, o) - Features.backCheckers(state, perspective)) +
            weights.homePoint * (Features.homePointsMade(state, perspective) - Features.homePointsMade(state, o)) +
            weights.fivePoint * (Features.keyPointsMade(state, perspective) - Features.keyPointsMade(state, o)) +
            weights.prime * (Features.primeLength(state, perspective) - Features.primeLength(state, o)) +
            weights.anchor * (Features.anchorsMade(state, perspective) - Features.anchorsMade(state, o)) +
            weights.advancedAnchor * (Features.advancedAnchorsMade(state, perspective) - Features.advancedAnchorsMade(state, o)) +
            weights.blot * (Features.blotPenalty(state, o, costWeighted) - Features.blotPenalty(state, perspective, costWeighted))
    }
}
