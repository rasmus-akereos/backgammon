package dk.rlunde.backgammon.ai

/**
 * Full 6-way outcome distribution from one side's perspective; components are probabilities that
 * sum to 1 (within epsilon). [cubelessEquity] is money equity in points, ∈ [−3, +3]. See spec §4.2.
 */
data class OutcomeDistribution(
    val winSingle: Double,
    val winGammon: Double,
    val winBackgammon: Double,
    val loseSingle: Double,
    val loseGammon: Double,
    val loseBackgammon: Double,
) {
    val winProb: Double get() = winSingle + winGammon + winBackgammon
    val loseProb: Double get() = loseSingle + loseGammon + loseBackgammon

    val cubelessEquity: Double get() =
        (winSingle + 2 * winGammon + 3 * winBackgammon) -
        (loseSingle + 2 * loseGammon + 3 * loseBackgammon)
}
