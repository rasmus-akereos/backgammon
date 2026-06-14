package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.SubMove
import dk.rlunde.backgammon.ui.board.BoardTarget

enum class Phase { NEED_ROLL, MOVING, COMMITTABLE, GAME_OVER }

/** The turn a human just committed, with its pre-move context, for post-move analysis. */
data class CommittedTurn(
    val preBoard: BoardState,
    val dice: Dice,
    val move: Move,
)

/** One-shot UI events (consumed once). */
sealed interface UiEvent {
    /** No legal moves this roll; the turn auto-passes after the player acknowledges. */
    data object NoLegalMoves : UiEvent
}

/** Immutable snapshot the board renders. [board] is the partial mid-turn board (mover not flipped). */
data class GameUiState(
    val board: BoardState,
    val toMove: Player,
    val dice: Dice?,
    val remainingDice: List<Int>,
    val phase: Phase,
    val selectedOrigin: BoardTarget?,
    val destinations: Set<BoardTarget>,
    /** Sub-moves staged so far this turn, in play order (for the move tracker). */
    val stagedMoves: List<SubMove>,
    val whitePip: Int,
    val blackPip: Int,
    val winner: Player?,
    val winValue: Int,
    /** Set by the VM, not the pure controller: true while the AI is choosing its move. */
    val aiThinking: Boolean = false,
    /** Set by the VM: the colour the computer plays (null = hot-seat). */
    val aiSide: Player? = null,
)
