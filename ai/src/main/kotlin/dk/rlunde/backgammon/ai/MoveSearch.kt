package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator

/**
 * 1-ply move selection. The roll is already known, so this is a greedy argmax over the post-move
 * static eval (NOT expectimax — no chance node at depth 1). Deterministic tie-break: the first
 * maximum encountered. The genuine chance-node expectimax (≥2-ply) arrives in Phase 3. Spec §4.4.
 */
internal object MoveSearch {
    fun bestMove(state: BoardState, dice: Dice, legal: List<Move>, weights: Weights): Move {
        require(legal.isNotEmpty()) { "bestMove called with no legal moves" }
        val mover = state.toMove
        var best = legal.first()
        var bestScore = Evaluator.evaluate(MoveGenerator.apply(state, best), mover, weights)
        for (i in 1 until legal.size) {
            val m = legal[i]
            val score = Evaluator.evaluate(MoveGenerator.apply(state, m), mover, weights)
            if (score > bestScore) { bestScore = score; best = m }
        }
        return best
    }
}
