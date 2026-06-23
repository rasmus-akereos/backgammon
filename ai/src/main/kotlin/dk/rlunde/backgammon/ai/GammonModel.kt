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
 * Coefficients are self-play-calibrated (slice 6c-1): features are standardized via [scaledRow] and
 * the gammon/bg logistics are fit on decisive-window positions (≥1 borne off, or a no-contact race),
 * which fixed the 6a degeneracy (unscaled `pip` saturating the logistic + noisy per-ply labels).
 */
internal object GammonModel {
    // {intercept, wBorneOff, wPip, wBackContact} on SCALED features (see scaledRow). Fitted offline
    // by EquityCalibrationTest (2026-06-23, 400 ADVANCED/FULL games; 6915 decisive rows, realized
    // gammon rate 0.190). wPip > 0: a further-behind loser is more likely gammoned.
    internal val GAMMON_COEFFS = doubleArrayOf(-2.2821797555444863, -2.7527809877061107, 3.215603025289811, 0.9395204497565748)
    internal val BG_COEFFS = doubleArrayOf(-3.915480448580417, -0.8965014282277425, -0.4140400108034241, 0.2304070516235051)

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
