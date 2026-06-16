package dk.rlunde.backgammon.ai

import kotlin.math.exp

/**
 * Per-phase logistic squash of an eval score into a single-win probability in [0,1], measured from the
 * side whose eval is taken, symmetric about 0.5 (intercept-free). [K] is fitted OFFLINE by
 * EquityCalibrationTest (spec §5) and committed below. See spec §4.3.
 */
internal object WinProbability {
    // Fitted offline by EquityCalibrationTest (2026-06-16, 400 ADVANCED/FULL self-play games).
    // log-loss vs the K=0.1 baseline: CONTACT 0.628 vs 0.902, RACE 0.192 vs 0.224.
    internal val K: Map<GamePhase, Double> = mapOf(
        GamePhase.CONTACT to 0.0378,
        GamePhase.RACE to 0.0555,
    )

    fun fromEquity(equity: Double, phase: GamePhase): Double =
        1.0 / (1.0 + exp(-K.getValue(phase) * equity))
}
