package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Scoring
import kotlin.math.abs

/**
 * Grades [playedMove] against all [legal] plays from [state] (spec Phase 5). Pure, deterministic,
 * no RNG. The reference is a fixed 2-ply (depth 1) search with Weights.FULL and no noise.
 */
object MoveAnalyzer {
    private const val ANALYSIS_DEPTH = 1
    private val REFERENCE_WEIGHTS = Weights.FULL

    // PROVISIONAL band thresholds in eval-score points (tuning follow-up, spec §3.3/§6).
    private const val GOOD_MAX = 0.5
    private const val INACCURACY_MAX = 2.0
    private const val MISTAKE_MAX = 5.0

    // A score within this magnitude of WIN_CONSTANT is a forced win/loss line — suppress numbers (§3.7).
    private const val WIN_BAND = Expectimax.WIN_CONSTANT / 2.0

    fun analyze(state: BoardState, dice: Dice, playedMove: Move, legal: List<Move>): MoveAnalysis {
        require(legal.isNotEmpty()) { "analyze called with no legal moves" }
        val budget = NodeBudget(Int.MAX_VALUE)
        val ranking = Expectimax.rankMoves(state, dice, legal, REFERENCE_WEIGHTS, ANALYSIS_DEPTH, budget)

        val playedBoard = MoveGenerator.apply(state, playedMove)
        val playedIdx = ranking.indexOfFirst { MoveGenerator.apply(state, it.move) == playedBoard }
        require(playedIdx >= 0) { "playedMove is not among legal moves" }

        val best = ranking.first()
        val played = ranking[playedIdx]
        val evalLoss = (best.score - played.score).coerceAtLeast(0.0)

        val strictlyBetter = ranking.count { it.score > played.score }
        val playedRank = strictlyBetter + 1
        val tiedForBest = playedRank == 1 && ranking.count { it.score >= best.score } > 1
        val forced = legal.size == 1

        val terminal = Scoring.isGameOver(playedBoard)
        val extreme = abs(best.score) >= WIN_BAND || abs(played.score) >= WIN_BAND

        val suppressed = terminal || extreme
        val featureDeltas: List<FeatureDelta> = if (suppressed) emptyList() else {
            val human = state.toMove
            val bestTerms = Evaluator.breakdown(MoveGenerator.apply(state, best.move), human, REFERENCE_WEIGHTS)
                .associate { it.feature to it.value }
            val playedTerms = Evaluator.breakdown(playedBoard, human, REFERENCE_WEIGHTS)
                .associate { it.feature to it.value }
            (bestTerms.keys union playedTerms.keys)
                .map { f -> FeatureDelta(f, bestTerms[f] ?: 0.0, playedTerms[f] ?: 0.0) }
                .sortedByDescending { abs(it.delta) }
        }
        val winProbDrop: Double? = if (suppressed) null
            else WinProbability.fromEquity(best.score) - WinProbability.fromEquity(played.score)

        return MoveAnalysis(
            band = band(playedRank, evalLoss),
            playedRank = playedRank,
            tiedForBest = tiedForBest,
            totalCandidates = legal.size,
            evalLoss = evalLoss,
            winProbDrop = winProbDrop,
            terminal = terminal,
            best = best,
            played = played,
            featureDeltas = featureDeltas,
            forced = forced,
        )
    }

    private fun band(playedRank: Int, evalLoss: Double): Band = when {
        playedRank == 1 -> Band.BEST
        evalLoss <= GOOD_MAX -> Band.GOOD
        evalLoss <= INACCURACY_MAX -> Band.INACCURACY
        evalLoss <= MISTAKE_MAX -> Band.MISTAKE
        else -> Band.BLUNDER
    }
}
