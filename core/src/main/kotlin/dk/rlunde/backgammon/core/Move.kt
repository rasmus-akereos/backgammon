package dk.rlunde.backgammon.core

/**
 * A single checker movement using one die.
 * [from]/[to] use board indices 1..24, plus sentinels:
 *   off  -> 0 (WHITE) / 25 (BLACK);  bar -> from = 25 (WHITE) / 0 (BLACK).
 * [isHit] is true when [to] held exactly one opponent checker (a blot).
 */
data class SubMove(val from: Int, val to: Int, val die: Int, val isHit: Boolean)

/** A complete legal turn: an ordered list of sub-moves. */
data class Move(val subMoves: List<SubMove>) {
    /** Sum of die FACE values played — the metric for the max-pips rule. */
    val pipsUsed: Int get() = subMoves.sumOf { it.die }
}
