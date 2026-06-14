package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/**
 * Feature names in Weights field-declaration order (the 1:1 binding `breakdown` relies on; note the
 * `fivePoint` weight maps to KEY_POINT, which scores the 5- and bar-point). Public because it is
 * exposed on MoveAnalysis.featureDeltas (Phase 5) and consumed by the :app trainer UI.
 */
enum class Feature { PIP, OFF, BLOT, HOME_POINT, KEY_POINT, PRIME, ANCHOR, ADVANCED_ANCHOR, BAR, BACK_CHECKER }

/** One weighted feature contribution to the eval score (weight already applied). Public: see [Feature]. */
data class FeatureContribution(val feature: Feature, val value: Double)

/**
 * Weighted linear position evaluator; positive = good for [perspective]. Callers evaluate the
 * POST-move state (toMove already flipped) with [perspective] = the side that just moved (spec §4.4).
 * In a no-contact race the eval collapses to ≈ pip differential. See spec §4.2.
 */
internal object Evaluator {

    /** Allocation-free on the search hot path: the inline visitor's lambda is inlined away. */
    fun evaluate(state: BoardState, perspective: Player, weights: Weights): Double {
        var sum = 0.0
        forEachTerm(state, perspective, weights) { _, v -> sum += v }
        return sum
    }

    /** Per-feature attribution; sums to [evaluate]. No-contact returns only PIP/OFF. */
    fun breakdown(state: BoardState, perspective: Player, weights: Weights): List<FeatureContribution> =
        buildList { forEachTerm(state, perspective, weights) { f, v -> add(FeatureContribution(f, v)) } }

    /** Single source of truth: emits one term per Weights field, in field-declaration order. */
    private inline fun forEachTerm(
        state: BoardState, perspective: Player, weights: Weights, emit: (Feature, Double) -> Unit,
    ) {
        emit(Feature.PIP, weights.pip * Features.pipDifferential(state, perspective))
        emit(Feature.OFF, weights.off * Features.offDifferential(state, perspective))
        if (Features.noContact(state)) return
        val o = perspective.opponent
        val costWeighted = weights != Weights.SIMPLIFIED
        emit(Feature.BLOT, weights.blot *
            (Features.blotPenalty(state, o, costWeighted) - Features.blotPenalty(state, perspective, costWeighted)))
        emit(Feature.HOME_POINT, weights.homePoint *
            (Features.homePointsMade(state, perspective) - Features.homePointsMade(state, o)))
        emit(Feature.KEY_POINT, weights.fivePoint *
            (Features.keyPointsMade(state, perspective) - Features.keyPointsMade(state, o)))
        emit(Feature.PRIME, weights.prime *
            (Features.primeLength(state, perspective) - Features.primeLength(state, o)))
        emit(Feature.ANCHOR, weights.anchor *
            (Features.anchorsMade(state, perspective) - Features.anchorsMade(state, o)))
        emit(Feature.ADVANCED_ANCHOR, weights.advancedAnchor *
            (Features.advancedAnchorsMade(state, perspective) - Features.advancedAnchorsMade(state, o)))
        emit(Feature.BAR, weights.bar * Features.barTerm(state, perspective))
        emit(Feature.BACK_CHECKER, weights.backChecker *
            (Features.backCheckers(state, o) - Features.backCheckers(state, perspective)))
    }
}
