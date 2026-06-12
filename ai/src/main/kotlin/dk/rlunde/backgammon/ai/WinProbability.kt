package dk.rlunde.backgammon.ai

import kotlin.math.exp

/**
 * Logistic squash of an eval score into a single-win probability in [0,1]. Doubling-cube groundwork
 * (Phase 6 / coach Phase 5 consume it). K is PROVISIONAL and NOT self-play-calibrated yet — calibration
 * is deferred to the phase that first consumes win-prob (against a named reference weight set, with
 * per-phase buckets). Models single-win only; no gammon rates. See spec §4.7.
 */
internal object WinProbability {
    const val K: Double = 0.1
    /** [equity] is the evaluator's eval score, measured from the side whose win-prob we want. */
    fun fromEquity(equity: Double): Double = 1.0 / (1.0 + exp(-K * equity))
}
