package dk.rlunde.backgammon.ai

/** Provisional cube thresholds on the gammonless single-win prob (spec §3). Refined in 6c. */
object CubePolicy {
    const val DOUBLE_MIN = 0.70  // offer once win-prob reaches the market window
    const val TAKE_MIN = 0.21    // receiver takes if its own win-prob >= ~21% (cubeful live-cube take point)
    fun shouldDouble(winProb: Double): Boolean = winProb >= DOUBLE_MIN
    fun shouldTake(receiverWinProb: Double): Boolean = receiverWinProb >= TAKE_MIN
}
