package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Move

/** A scored full-turn play. [score] is the human-perspective search value (spec §3.5). */
data class AnalyzedPlay(val move: Move, val score: Double)

/** Quality band (chess-style by product choice). */
enum class Band { BEST, GOOD, INACCURACY, MISTAKE, BLUNDER }

/** Best-vs-played static contribution for one feature. */
data class FeatureDelta(val feature: Feature, val best: Double, val played: Double) {
    val delta: Double get() = best - played
}

/** Full analysis of one human play. winProbDrop is null and featureDeltas empty for terminal or forced-win/loss plays (§3.7). */
data class MoveAnalysis(
    val band: Band,
    val playedRank: Int,
    val tiedForBest: Boolean,
    val totalCandidates: Int,
    val evalLoss: Double,
    val winProbDrop: Double?,
    /** Calibrated outcome distribution of the played position (human perspective); null when suppressed. */
    val positionEquity: OutcomeDistribution?,
    val terminal: Boolean,
    val best: AnalyzedPlay,
    val played: AnalyzedPlay,
    val featureDeltas: List<FeatureDelta>,
    val forced: Boolean,
)
