package dk.rlunde.backgammon.core

/** Stateless rules engine: legal-move generation and state transitions. Thread-safe by statelessness. */
object MoveGenerator {

    // ---- sentinels & per-player geometry -------------------------------------------------
    private fun offSentinel(player: Player) = if (player == Player.WHITE) 0 else 25
    private fun barFrom(player: Player) = if (player == Player.WHITE) 25 else 0
    private fun entryPoint(player: Player, die: Int) = if (player == Player.WHITE) 25 - die else die
    private fun normalTo(player: Player, from: Int, die: Int) =
        if (player == Player.WHITE) from - die else from + die
    private fun homeRange(player: Player) = if (player == Player.WHITE) 1..6 else 19..24
    private fun distToOff(player: Player, point: Int) = if (player == Player.WHITE) point else 25 - point

    // ---- state transitions ----------------------------------------------------------------

    /** Flip the side to move; used to advance the turn on a forced pass (empty legal-move set). */
    fun pass(state: BoardState): BoardState = state.copy(toMove = state.toMove.opponent)

    /** Apply a complete legal turn, returning a NEW state. Input is never mutated or aliased. */
    fun apply(state: BoardState, move: Move): BoardState {
        var current = state
        for (sm in move.subMoves) current = applySubMove(current, sm)
        val flipped = current.copy(toMove = current.toMove.opponent)
        check(conservesCheckers(state, flipped)) { "checker count invariant violated by $move" }
        return flipped
    }

    /** Apply ONE sub-move for state.toMove. Allocates a fresh points array (copy-on-write). */
    private fun applySubMove(state: BoardState, sm: SubMove): BoardState {
        val mover = state.toMove
        val points = state.points.copyOf()
        val bar = state.bar.toMutableMap()
        val off = state.off.toMutableMap()

        // remove the moving checker from its origin
        if (sm.from == barFrom(mover) && sm.from !in 1..24) {
            bar[mover] = bar.getValue(mover) - 1
        } else {
            points[sm.from] -= mover.sign
        }

        // place it at its destination
        if (sm.to == offSentinel(mover) && sm.to !in 1..24) {
            off[mover] = off.getValue(mover) + 1
        } else {
            if (sm.isHit) {
                points[sm.to] = 0
                bar[mover.opponent] = bar.getValue(mover.opponent) + 1
            }
            points[sm.to] += mover.sign
        }

        return BoardState(points, bar, off, mover) // toMove unchanged here; apply() flips once at end
    }

    private fun totalCheckers(state: BoardState, player: Player): Int {
        val onBoard = (1..24).sumOf { state.count(player, it) }
        return onBoard + state.barCount(player) + state.offCount(player)
    }

    private fun conservesCheckers(before: BoardState, after: BoardState): Boolean =
        Player.entries.all { player -> totalCheckers(before, player) == totalCheckers(after, player) }
}
