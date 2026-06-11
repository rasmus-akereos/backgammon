package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move

/**
 * Chooses a full turn. Pure + synchronous; the caller (the VM) runs it off the UI thread via
 * withContext(Dispatchers.Default). Perspective is [state].toMove (the AI's own side).
 * @param legal MUST be MoveGenerator.legalMoves(state, dice) for the same state/dice, non-empty.
 */
interface AiPlayer {
    fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move
}
