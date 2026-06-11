package dk.rlunde.backgammon.ai

/** Hand-tuned linear weights. Phase 2: reasonable constants; precise tuning is Phase 3. */
internal data class Weights(
    val pip: Double,
    val off: Double,
    val blot: Double,
    val homePoint: Double,
    val fivePoint: Double,     // extra for the 5-point / bar-point
    val prime: Double,
    val anchor: Double,
    val advancedAnchor: Double, // extra for the golden/bar-point anchor
    val bar: Double,
    val backChecker: Double,
) {
    companion object {
        val FULL = Weights(
            pip = 1.0, off = 12.0, blot = 1.0, homePoint = 4.0, fivePoint = 3.0,
            prime = 4.0, anchor = 3.0, advancedAnchor = 4.0, bar = 8.0, backChecker = 2.0,
        )
        val SIMPLIFIED = Weights(
            pip = 1.0, off = 12.0, blot = 1.0, homePoint = 0.0, fivePoint = 0.0,
            prime = 0.0, anchor = 0.0, advancedAnchor = 0.0, bar = 8.0, backChecker = 0.0,
        )
    }
}
