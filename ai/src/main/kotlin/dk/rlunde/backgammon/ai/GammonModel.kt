package dk.rlunde.backgammon.ai

import kotlin.math.exp
import kotlin.math.min

/** Inputs to the gammon model, describing the side that is assumed to LOSE. */
internal data class GammonFeatures(val borneOff: Int, val pip: Int, val backContact: Int)

/** Conditional rates given that side loses: P(gammoned), P(backgammoned). */
internal data class GammonRates(val gammon: Double, val backgammon: Double)

/**
 * Feature-based logistic for gammon/backgammon rates, conditioned on a side losing (spec §4.4).
 * A borne-off checker makes a gammon impossible — hard-gated to 0 rather than trusted to the logistic.
 *
 * Coefficients are deliberately HAND-SET (directionally correct: more back-contact → more bg; a
 * borne-off checker → no gammon). The 2026-06-16 automated fit was degenerate — `pip` is unscaled
 * (~0–167) so its fitted weight saturated the logistic to ≈0 everywhere, and per-ply labels carry
 * weak gammon signal. Rigorous gammon-rate calibration (feature scaling + decisive-position sampling)
 * is deferred; the design (spec §4) only requires trustworthy gammon awareness by slice 6c. Win-prob
 * IS self-play-calibrated (see WinProbability.K).
 */
internal object GammonModel {
    // {intercept, wBorneOff, wPip, wBackContact} on SCALED features (see scaledRow). PROVISIONAL —
    // replaced by the offline fit in a later 6c-1 task.
    internal var GAMMON_COEFFS = doubleArrayOf(-1.5, 0.0, 1.2, 0.6)
    internal var BG_COEFFS = doubleArrayOf(-3.0, 0.0, 1.0, 1.5)

    /** Features normalized to ~[0,1] with fixed divisors. Shared by the runtime model and the
     *  calibration harness so they can never drift. */
    internal fun scaledRow(f: GammonFeatures): DoubleArray =
        doubleArrayOf(f.borneOff / 15.0, f.pip / 167.0, f.backContact / 15.0)

    private fun logistic(c: DoubleArray, f: GammonFeatures): Double {
        val s = scaledRow(f)
        return 1.0 / (1.0 + exp(-(c[0] + c[1] * s[0] + c[2] * s[1] + c[3] * s[2])))
    }

    fun loseRates(f: GammonFeatures): GammonRates {
        if (f.borneOff > 0) return GammonRates(0.0, 0.0) // gammon impossible once a checker is off
        val g = logistic(GAMMON_COEFFS, f).coerceIn(0.0, 1.0)
        val bg = logistic(BG_COEFFS, f).coerceIn(0.0, 1.0)
        return GammonRates(g, min(bg, g)) // backgammon is a stricter gammon
    }
}
