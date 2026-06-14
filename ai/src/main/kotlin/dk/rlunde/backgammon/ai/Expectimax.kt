package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Scoring

/**
 * Depth-parameterised negamax-expectimax. value(state, depth) returns the eval score from the
 * perspective of state.toMove; parents negate on recursion (apply() flips the mover). depth counts
 * chance nodes below the root decision (GNU/XG "n-ply": depth 0 = 1-ply, depth 1 = 2-ply, depth 2 = 3-ply).
 * Replaces the Phase-2 MoveSearch (depth 0 == greedy 1-ply argmax).
 */
internal object Expectimax {

    /** Tunable. Far above any heuristic eval magnitude so a forced win/loss dominates. */
    internal const val WIN_CONSTANT = 1_000_000.0

    /** Tunable cap on candidate static-evaluations per bestMove call (Expert wall-clock budget). */
    internal const val MAX_EVALS = 200_000

    /** The 21 distinct rolls with probabilities: 15 non-doubles at 2/36, 6 doubles at 1/36. */
    internal val DISTINCT_ROLLS: List<Pair<Dice, Double>> = buildList {
        for (a in 1..6) for (b in a..6) {
            val w = if (a == b) 1.0 / 36.0 else 2.0 / 36.0
            add(Dice(a, b) to w)
        }
    }

    /**
     * Best full turn for [state].toMove given the known [dice]. [legal] is the canonical non-empty
     * MoveGenerator.legalMoves(state, dice). Pre: [state] is NOT game-over.
     */
    fun bestMove(
        state: BoardState, dice: Dice, legal: List<Move>,
        weights: Weights, depth: Int, topK: Int,
        budget: NodeBudget = NodeBudget(MAX_EVALS),
    ): Move {
        require(legal.isNotEmpty()) { "bestMove called with no legal moves" }
        budget.spend(legal.size)
        // The root's `legal` is the caller's known-roll set; deeper nodes regenerate their own.
        return legal.prunedTo(topK, state, weights)
            .maxByOrNull { m -> -value(MoveGenerator.apply(state, m), depth, weights, topK, budget) }!!
    }

    private fun value(
        state: BoardState, depth: Int, weights: Weights, topK: Int, budget: NodeBudget,
    ): Double {
        if (Scoring.isGameOver(state)) return -terminalEquity(state)
        if (depth == 0 || budget.exhausted) return Evaluator.evaluate(state, state.toMove, weights)
        var ev = 0.0
        for ((roll, w) in DISTINCT_ROLLS) {
            if (budget.exhausted) return Evaluator.evaluate(state, state.toMove, weights)
            val rollLegal = MoveGenerator.legalMoves(state, roll)
            val best = if (rollLegal.isEmpty()) {
                -value(MoveGenerator.pass(state), depth - 1, weights, topK, budget)
            } else {
                budget.spend(rollLegal.size)
                rollLegal.prunedTo(topK, state, weights)
                    .maxOf { m -> -value(MoveGenerator.apply(state, m), depth - 1, weights, topK, budget) }
            }
            ev += w * best
        }
        return ev
    }

    /** Winner just moved, so winnerAndValue's winner == state.toMove.opponent; we read only the 1/2/3 multiplier. */
    private fun terminalEquity(state: BoardState): Double =
        WIN_CONSTANT * Scoring.winnerAndValue(state)!!.second

    /** Rank by the cheap 1-ply static eval (from the perspective of [from].toMove) and keep the top [topK].
     *  sortedByDescending is stable, so the first-max tie-break matches the Phase-2 MoveSearch. */
    private fun List<Move>.prunedTo(topK: Int, from: BoardState, weights: Weights): List<Move> =
        sortedByDescending { Evaluator.evaluate(MoveGenerator.apply(from, it), from.toMove, weights) }
            .take(topK)

    /**
     * Score EVERY [legal] play for analysis (no root pruning, so playedRank is exact), sorted
     * best-first. score is human-perspective: -value(apply(state, m), depth). Pass a generous
     * [budget] (analysis is off-main-thread, one-shot) so every candidate is scored at the same
     * depth — never mix depth-1 with a depth-0 fallback. Pre: [state] is NOT game-over.
     */
    fun rankMoves(
        state: BoardState, dice: Dice, legal: List<Move>,
        weights: Weights, depth: Int, budget: NodeBudget,
    ): List<AnalyzedPlay> {
        require(legal.isNotEmpty()) { "rankMoves called with no legal moves" }
        return legal
            .map { m -> AnalyzedPlay(m, -value(MoveGenerator.apply(state, m), depth, weights, Int.MAX_VALUE, budget)) }
            .sortedByDescending { it.score }
    }

    /** Test seam: full expectimax node value with no pruning and an effectively unlimited budget. */
    internal fun expectedValueForTest(state: BoardState, depth: Int, weights: Weights): Double =
        value(state, depth, weights, Int.MAX_VALUE, NodeBudget(Int.MAX_VALUE))
}
