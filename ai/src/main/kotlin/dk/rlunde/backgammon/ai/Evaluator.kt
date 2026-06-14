package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/** A feature name (1:1 and in-order with Weights fields) used by [Evaluator.breakdown]. */
enum class Feature { PIP, OFF, BLOT, HOME_POINT, KEY_POINT, PRIME, ANCHOR, ADVANCED_ANCHOR, BAR, BACK_CHECKER }

/** One weighted feature contribution to the eval score (weight already applied). */
data class FeatureContribution(val feature: Feature, val value: Double)

/**
 * Weighted linear position evaluator; positive = good for [perspective]. Callers evaluate the
 * POST-move state (toMove already flipped) with [perspective] = the side that just moved (spec §4.4).
 * In a no-contact race the eval collapses to ≈ pip differential. See spec §4.2.
 */
internal object Evaluator {

    fun evaluate(state: BoardState, perspective: Player, weights: Weights): Double =
        terms(state, perspective, weights).sumOf { it.value }

    /** Per-feature attribution; sums to [evaluate]. No-contact returns only PIP/OFF. */
    fun breakdown(state: BoardState, perspective: Player, weights: Weights): List<FeatureContribution> =
        terms(state, perspective, weights)

    /** Single source of truth: one contribution per Weights field, in field-declaration order. */
    private fun terms(state: BoardState, perspective: Player, weights: Weights): List<FeatureContribution> {
        val pip = weights.pip * Features.pipDifferential(state, perspective)
        val off = weights.off * Features.offDifferential(state, perspective)
        if (Features.noContact(state)) return listOf(
            FeatureContribution(Feature.PIP, pip),
            FeatureContribution(Feature.OFF, off),
        )
        val o = perspective.opponent
        val costWeighted = weights != Weights.SIMPLIFIED
        return listOf(
            FeatureContribution(Feature.PIP, pip),
            FeatureContribution(Feature.OFF, off),
            FeatureContribution(Feature.BLOT, weights.blot *
                (Features.blotPenalty(state, o, costWeighted) - Features.blotPenalty(state, perspective, costWeighted))),
            FeatureContribution(Feature.HOME_POINT, weights.homePoint *
                (Features.homePointsMade(state, perspective) - Features.homePointsMade(state, o))),
            FeatureContribution(Feature.KEY_POINT, weights.fivePoint *
                (Features.keyPointsMade(state, perspective) - Features.keyPointsMade(state, o))),
            FeatureContribution(Feature.PRIME, weights.prime *
                (Features.primeLength(state, perspective) - Features.primeLength(state, o))),
            FeatureContribution(Feature.ANCHOR, weights.anchor *
                (Features.anchorsMade(state, perspective) - Features.anchorsMade(state, o))),
            FeatureContribution(Feature.ADVANCED_ANCHOR, weights.advancedAnchor *
                (Features.advancedAnchorsMade(state, perspective) - Features.advancedAnchorsMade(state, o))),
            FeatureContribution(Feature.BAR, weights.bar * Features.barTerm(state, perspective)),
            FeatureContribution(Feature.BACK_CHECKER, weights.backChecker *
                (Features.backCheckers(state, o) - Features.backCheckers(state, perspective))),
        )
    }
}
